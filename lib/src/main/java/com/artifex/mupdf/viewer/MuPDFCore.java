package com.artifex.mupdf.viewer;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.DisplayList;
import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Link;
import com.artifex.mupdf.fitz.Location;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Outline;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Quad;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.RectI;
import com.artifex.mupdf.fitz.SeekableInputStream;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;

public class MuPDFCore
{
	private final String APP = "MuPDF";

	private final int MAXIMUM_OUTLINE_ITEMS = 1000;
	private final int MAXIMUM_OUTLINE_DEPTH = 4;

	private int resolution;
	private Document doc;
	private Outline[] outline;
	private int pageCount = -1;
	private boolean reflowable = false;
	private int currentPage;
	private Page page;
	private float pageWidth;
	private float pageHeight;
	private DisplayList displayList;

	/* Default to "A Format" pocket book size. */
	private int layoutW = 312;
	private int layoutH = 504;
	private int layoutEM = 10;

	private boolean outlineTruncated;

	/* M11：页 → 章 映射缓存（chapterOfPageCount 为生成时的页数，用于失效判定） */
	private int[] chapterOfPage;
	private int chapterOfPageCount = -1;

	private MuPDFCore(Document doc) {
		this.doc = doc;
		// 加密文档（如加密 epub）认证前 layout/countPages 可能失败：延迟到认证后，
		// 失败仅记 pageCount=0（引擎据此走 needsPassword 路径，而非误判损坏）
		try {
			doc.layout(layoutW, layoutH, layoutEM);
			pageCount = doc.countPages();
		} catch (Exception ex) {
			pageCount = 0;
			// 标记未排版，强制下次 layout() 执行重排（加密文档认证后补排路径）
			layoutW = -1;
			layoutH = -1;
		}
		try {
			reflowable = doc.isReflowable();
		} catch (Exception ex) {
			reflowable = false;
		}
		resolution = 160;
		currentPage = -1;
	}

	public MuPDFCore(byte buffer[], String magic) {
		this(Document.openDocument(buffer, magic));
	}

	public MuPDFCore(SeekableInputStream stm, String magic) {
		this(Document.openDocument(stm, magic));
	}

	public String getTitle() {
		return doc.getMetaData(Document.META_INFO_TITLE);
	}

	public int countPages() {
		return pageCount;
	}

	public boolean isReflowable() {
		return reflowable;
	}

	/**
	 * M9: 页码 -> 跨重排稳定的 bookmark（fitz makeBookmark(locationFromPageNumber(page))）。
	 * 供流式文档（epub）以 bookmark 十进制串作为进度锚点。失败返回 Long.MIN_VALUE（与合法
	 * bookmark 0 区分，避免首章首页被误判失败）。
	 */
	public synchronized long pageToBookmark(int pageNum) {
		if (doc == null)
			return Long.MIN_VALUE;
		try {
			return doc.makeBookmark(doc.locationFromPageNumber(pageNum));
		} catch (Exception ex) {
			return Long.MIN_VALUE;
		}
	}

	/**
	 * M9: bookmark -> 当前排版下的页码。失败（非法/失效 anchor，或 doc==null）返回 -2。
	 * 通过「bookmark→page→bookmark」往返一致性校验区分「真实第 0 页」与「失效锚点」，
	 * 失效锚点返回 -2 让调用方回落到 payload 内 page，避免静默跳回首页。
	 */
	public synchronized int bookmarkToPage(long mark) {
		if (doc == null)
			return -2;
		try {
			int page = doc.pageNumberFromLocation(doc.findBookmark(mark));
			long back = doc.makeBookmark(doc.locationFromPageNumber(page));
			if (back != mark)
				return -2;
			return page;
		} catch (Exception ex) {
			return -2;
		}
	}

	/**
	 * M11: 页码 -> 当前排版下的 Location 分量 {chapter, pageInChapter}。
	 * 真机 S0-2 实测：Location 分量**跨 doc 会话可精确还原**（bookmark long 不行），
	 * 故进度/书签锚点以此为准。失败返回 null。
	 */
	public synchronized int[] pageToLocation(int pageNum) {
		if (doc == null)
			return null;
		try {
			Location loc = doc.locationFromPageNumber(pageNum);
			return new int[] { loc.chapter, loc.page };
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * M11: Location 分量 -> 当前排版页码；doc 为空/越界/异常返回 -2。
	 * 注意：章内页码超出该章页数时 MuPDF 会**静默落到相邻章**（S0-2 C 组实测），
	 * 调用方须用 {@link #pageToLocation(int)} 回读校验结果是否仍为同一 Location。
	 */
	public synchronized int pageFromLocation(int chapter, int pageInChapter) {
		if (doc == null)
			return -2;
		try {
			int page = doc.pageNumberFromLocation(new Location(chapter, pageInChapter));
			if (page < 0 || page >= doc.countPages())
				return -2;
			return page;
		} catch (Exception ex) {
			return -2;
		}
	}

	/**
	 * M11: 指定章在当前排版下的 {起始页, 页数}；章不存在或异常返回 null。
	 * 整册扫描（页数量级 10^2~10^3），仅在抓取/还原锚点时调用（S0-2：Location 无章页数 API）。
	 */
	public synchronized int[] chapterRange(int chapter) {
		if (chapter < 0)
			return null;
		int[] map = chapterOfPageMap();
		if (map == null)
			return null;
		int first = -1;
		int last = -1;
		for (int p = 0; p < map.length; p++) {
			if (map[p] != chapter)
				continue;
			if (first < 0)
				first = p;
			last = p;
		}
		return first < 0 ? null : new int[] { first, last - first + 1 };
	}

	/**
	 * M11：页 → 章 映射缓存（一次整册扫描，供 {@link #chapterRange(int)} 复用）。
	 * 键为页数：layout() 主动置空，页数变化（认证后补排等）亦自动重扫。
	 */
	private int[] chapterOfPageMap() {
		if (doc == null)
			return null;
		try {
			int total = doc.countPages();
			if (chapterOfPage != null && chapterOfPageCount == total)
				return chapterOfPage;
			int[] map = new int[total];
			for (int p = 0; p < total; p++)
				map[p] = doc.locationFromPageNumber(p).chapter;
			chapterOfPage = map;
			chapterOfPageCount = total;
			return map;
		} catch (Exception ex) {
			chapterOfPage = null;
			chapterOfPageCount = -1;
			return null;
		}
	}

	public synchronized int layout(int oldPage, int w, int h, int em) {
		if (doc == null)
			return oldPage;
		if (w != layoutW || h != layoutH || em != layoutEM) {
			layoutW = w;
			layoutH = h;
			layoutEM = em;
			try {
				// M11：旧页 remap 改走 Location 分量（bookmark long 跨会话失效，S0-2 B 组实测），
				// 同章内精确还原；章页数变化导致越界时按该章起始页兜底，避免返回越界旧页。
				int[] loc = (oldPage >= 0) ? pageToLocation(oldPage) : null;
				doc.layout(layoutW, layoutH, layoutEM);
				currentPage = -1;
				pageCount = doc.countPages();
				chapterOfPage = null;
				chapterOfPageCount = -1;
				outline = null;
				try {
					outline = doc.loadOutline();
				} catch (Exception ex) {
					/* ignore error */
				}
				if (loc == null)
					return 0;
				int page = pageFromLocation(loc[0], loc[1]);
				if (page >= 0)
					return page;
				int[] range = chapterRange(loc[0]);
				return range != null ? range[0] : 0;
			} catch (Exception ex) {
				return (oldPage >= 0) ? Math.min(oldPage, Math.max(pageCount - 1, 0)) : 0;
			}
		}
		return oldPage;
	}

	private synchronized void gotoPage(int pageNum) {
		/* TODO: page cache */
		if (pageNum > pageCount-1)
			pageNum = pageCount-1;
		else if (pageNum < 0)
			pageNum = 0;
		if (pageNum != currentPage) {
			if (page != null)
				page.destroy();
			page = null;
			if (displayList != null)
				displayList.destroy();
			displayList = null;
			page = null;
			pageWidth = 0;
			pageHeight = 0;
			currentPage = -1;

			if (doc != null) {
				page = doc.loadPage(pageNum);
				Rect b = page.getBounds();
				pageWidth = b.x1 - b.x0;
				pageHeight = b.y1 - b.y0;
			}

			currentPage = pageNum;
		}
	}

	public synchronized PointF getPageSize(int pageNum) {
		gotoPage(pageNum);
		return new PointF(pageWidth, pageHeight);
	}

	public synchronized void onDestroy() {
		if (displayList != null)
			displayList.destroy();
		displayList = null;
		if (page != null)
			page.destroy();
		page = null;
		if (doc != null)
			doc.destroy();
		doc = null;
	}

	public synchronized void drawPage(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		gotoPage(pageNum);

		if (displayList == null && page != null)
			try {
				displayList = page.toDisplayList();
			} catch (Exception ex) {
				displayList = null;
			}

		if (displayList == null || page == null)
			return;

		float zoom = resolution / 72;
		Matrix ctm = new Matrix(zoom, zoom);
		RectI bbox = new RectI(page.getBounds().transform(ctm));
		float xscale = (float)pageW / (float)(bbox.x1-bbox.x0);
		float yscale = (float)pageH / (float)(bbox.y1-bbox.y0);
		ctm.scale(xscale, yscale);

		AndroidDrawDevice dev = new AndroidDrawDevice(bm, patchX, patchY);
		try {
			displayList.run(dev, ctm, cookie);
			dev.close();
		} finally {
			dev.destroy();
		}
	}

	public synchronized void updatePage(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		drawPage(bm, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, cookie);
	}

	public synchronized Link[] getPageLinks(int pageNum) {
		gotoPage(pageNum);
		return page != null ? page.getLinks() : null;
	}

	public synchronized int resolveLink(Link link) {
		return doc.pageNumberFromLocation(doc.resolveLink(link));
	}

	public synchronized Quad[][] searchPage(int pageNum, String text) {
		gotoPage(pageNum);
		return page.search(text);
	}

	public synchronized boolean hasOutline() {
		if (outline == null) {
			try {
				outline = doc.loadOutline();
			} catch (Exception ex) {
				/* ignore error */
			}
		}
		return outline != null;
	}

	private void flattenOutlineNodes(ArrayList<OutlineActivity.Item> result, Outline list[], String indent, int depth) {
		for (Outline node : list) {
			if (node.title != null) {
				int page = doc.pageNumberFromLocation(doc.resolveLink(node));
				if (result.size() >= MAXIMUM_OUTLINE_ITEMS)
					outlineTruncated = true;
				else
					result.add(new OutlineActivity.Item(indent + node.title, page));
			}
			if (node.down != null)
			{
				if (depth >= MAXIMUM_OUTLINE_DEPTH || result.size() >= MAXIMUM_OUTLINE_ITEMS)
					outlineTruncated = true;
				else
					flattenOutlineNodes(result, node.down, indent + "    ", depth + 1);
			}
		}
	}

	public synchronized ArrayList<OutlineActivity.Item> getOutline() {
		outlineTruncated = false;
		ArrayList<OutlineActivity.Item> result = new ArrayList<OutlineActivity.Item>();
		flattenOutlineNodes(result, outline, "", 0);
		return result;
	}

	public synchronized boolean wasOutlineTruncated() {
		return outlineTruncated;
	}

	public synchronized boolean needsPassword() {
		return doc.needsPassword();
	}

	public synchronized boolean authenticatePassword(String password) {
		boolean authenticated = doc.authenticatePassword(password);
		pageCount = doc.countPages();
		reflowable = doc.isReflowable();
		return authenticated;
	}
}

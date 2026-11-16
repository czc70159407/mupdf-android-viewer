package com.artifex.mupdf.viewer;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.DisplayList;
import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Font;
import com.artifex.mupdf.fitz.Image;
import com.artifex.mupdf.fitz.Link;
import com.artifex.mupdf.fitz.Location;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Outline;
import com.artifex.mupdf.fitz.PDFAnnotation;
import com.artifex.mupdf.fitz.PDFPage;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Point;
import com.artifex.mupdf.fitz.Quad;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.RectI;
import com.artifex.mupdf.fitz.SeekableInputStream;
import com.artifex.mupdf.fitz.StructuredText;
import com.artifex.mupdf.fitz.StructuredTextWalker;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

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
				int oldChapterPages = -1;
				if (loc != null) {
					int[] before = chapterRange(loc[0]);
					oldChapterPages = (before != null) ? before[1] : -1;
				}
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
				// 定位信息不可得时**停在钳制后的旧页**，不要跳回首页（重排失败不等于用户回到开头）
				if (loc == null)
					return clampPage(oldPage);
				int[] range = chapterRange(loc[0]);
				if (range == null)
					return clampPage(oldPage);
				int page = pageFromLocation(loc[0], loc[1]);
				int[] back = (page >= 0) ? pageToLocation(page) : null;
				boolean samePagination = back != null && back[0] == loc[0] && back[1] == loc[1]
						&& range[1] == oldChapterPages;
				if (samePagination)
					return page;
				// 章页数已变（重排）：pageInChapter 是相对值，直接还原会落在不同内容上，
				// 必须按章内比例折算（真机：横屏 21 页/章 ↔ 竖屏 10 页/章）
				if (oldChapterPages <= 0)
					return range[0];
				double ratio = (loc[1] + 0.5) / oldChapterPages;
				int offset = (int) (ratio * range[1]);
				if (offset < 0)
					offset = 0;
				if (offset > range[1] - 1)
					offset = range[1] - 1;
				return range[0] + offset;
			} catch (Exception ex) {
				return clampPage(oldPage);
			}
		}
		return oldPage;
	}

	/** 页码钳到当前排版范围（负值归 0；pageCount 未就绪时返回 0）。 */
	private int clampPage(int page) {
		if (page <= 0 || pageCount <= 0)
			return 0;
		return Math.min(page, pageCount - 1);
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

	/** M12：文本选择结果（页面坐标 pt 的 quads + 选中文本）。 */
	public static class TextSelection {
		public final Quad[] quads;
		public final String text;

		TextSelection(Quad[] quads, String text) {
			this.quads = quads;
			this.text = text;
		}
	}

	/**
	 * M12：两点选择文本（页面坐标 pt，吸附到词边界）。
	 *
	 * 注意：fitz 1.28.2 的 `StructuredText.search(String)` 真机 JNI 崩溃
	 * （`jobject invalid local reference`，M12 S0 探针已验证），故只用
	 * `snapSelection`/`copy` 两点选择，不用 search。无文本或异常返回 null。
	 */
	public synchronized TextSelection selectText(int pageNum, float x0, float y0, float x1, float y1) {
		gotoPage(pageNum);
		if (page == null)
			return null;
		StructuredText st = null;
		try {
			st = page.toStructuredText("preserve-whitespace");
			if (st == null)
				return null;
			Quad[] quads = st.highlight(new Point(x0, y0), new Point(x1, y1));
			if (quads == null || quads.length == 0) {
				// 单点长按（起止点相同）：highlight 为空，回退吸附到词边界取单个 quad
				Quad q = st.snapSelection(new Point(x0, y0), new Point(x1, y1),
						StructuredText.SELECT_WORDS);
				if (q != null)
					quads = new Quad[] { q };
			}
			if (quads == null || quads.length == 0)
				return null;
			String text = st.copy(new Point(x0, y0), new Point(x1, y1));
			if (text == null || text.trim().isEmpty()) {
				// 零位移长按：highlight 为空时 quads 来自 snapSelection，而 copy 的零长度区间为空，
				// 改用词 quad 端点重取文本（否则 draft.text 为空：朗读误报「未找到语音包」、复制空串）
				text = copyByQuads(st, quads);
			}
			return new TextSelection(quads, text);
		} catch (Exception ex) {
			return null;
		} finally {
			if (st != null)
				st.destroy();
		}
	}

	/**
	 * 零长度选区回退取文本：按 quad 端点取阅读顺序区间（水平文字即首字符左上 → 末字符右下）；
	 * 端点吸附不到字符时退化为 quad 中心 → 右下角。
	 */
	private static String copyByQuads(StructuredText st, Quad[] quads) {
		StringBuilder sb = new StringBuilder();
		for (Quad q : quads) {
			float left = Math.min(Math.min(q.ul_x, q.ur_x), Math.min(q.ll_x, q.lr_x));
			float top = Math.min(Math.min(q.ul_y, q.ur_y), Math.min(q.ll_y, q.lr_y));
			float right = Math.max(Math.max(q.ul_x, q.ur_x), Math.max(q.ll_x, q.lr_x));
			float bottom = Math.max(Math.max(q.ul_y, q.ur_y), Math.max(q.ll_y, q.lr_y));
			String piece = st.copy(new Point(left, top), new Point(right, bottom));
			if (piece == null || piece.isEmpty()) {
				piece = st.copy(new Point((left + right) / 2f, (top + bottom) / 2f),
						new Point(right, bottom));
			}
			if (piece != null)
				sb.append(piece);
		}
		return sb.toString();
	}

	/** 页文本结构缓存：字符 Quad + unicode（拖柄高频重算复用，避免每次 MOVE 全页 walk） */
	private TextStructure textStructure;
	private int textStructurePage = -1;

	/** 页文本结构：walk 一次结果（字符按阅读顺序） */
	private static final class TextStructure {
		final ArrayList<Quad> quads = new ArrayList<>();
		final ArrayList<Integer> codes = new ArrayList<>();
	}

	/**
	 * M14 方案 B：字符级精确选择——复用缓存文本结构，按点匹配起止字符，取连续区间。
	 *
	 * 相比 {@link #selectText}（矩形 highlight，会因手指微抖跨行选中相邻行行末），
	 * 本方法按字符阅读顺序精确截取，天然平滑跨行、不跳行。
	 *
	 * 注：fitz 1.28.2 无 highlightSelection，故用 walk(StructuredTextWalker) 的 onChar 逐字构建；
	 * 结构按页缓存（[getTextStructure]），拖柄高频 MOVE 只做内存索引查找，不再重复 walk。
	 */
	public synchronized TextSelection selectTextByChars(int pageNum, float x0, float y0, float x1, float y1) {
		gotoPage(pageNum);
		if (page == null)
			return null;
		TextStructure ts = getTextStructure(pageNum);
		if (ts == null || ts.quads.isEmpty())
			return null;
		int i0 = indexOfPoint(ts.quads, x0, y0);
		int i1 = indexOfPoint(ts.quads, x1, y1);
		return slice(ts, Math.min(i0, i1), Math.max(i0, i1));
	}

	/**
	 * M14：零位移长按取词——按页字符表做词边界扩展：英文/数字按连续词字符扩展，
	 * 中文等非 ASCII 取单字。
	 *
	 * 不用 `StructuredText.snapSelection(SELECT_WORDS)`：其对无空格的 CJK 会把整行
	 * 判定为一个词，中英混排行长按即选中整行、朗读整行。
	 */
	public synchronized TextSelection selectWordAt(int pageNum, float x, float y) {
		gotoPage(pageNum);
		if (page == null)
			return null;
		TextStructure ts = getTextStructure(pageNum);
		if (ts == null || ts.quads.isEmpty())
			return null;
		int i = nearestNonSpace(ts.codes, indexOfPoint(ts.quads, x, y));
		int lo = i;
		int hi = i;
		if (isWordCode(ts.codes.get(i))) {
			while (lo > 0 && isWordCode(ts.codes.get(lo - 1)))
				lo--;
			while (hi < ts.codes.size() - 1 && isWordCode(ts.codes.get(hi + 1)))
				hi++;
		}
		return slice(ts, lo, hi);
	}

	/** 词字符判定：ASCII 字母/数字与 _ ' @ -（CJK、空白、标点不扩展 → 中文按单字） */
	private static boolean isWordCode(int c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '_' || c == '\'' || c == '@' || c == '-';
	}

	/** 从 index 起向两侧找最近的非空白字符（长按落在空白时不至于选中单个空格）；无则原样返回 */
	private static int nearestNonSpace(List<Integer> codes, int index) {
		if (index < 0 || index >= codes.size())
			return index;
		int left = index;
		int right = index;
		while (left >= 0 || right < codes.size()) {
			if (left >= 0 && !Character.isWhitespace(codes.get(left).intValue()))
				return left;
			if (right < codes.size() && !Character.isWhitespace(codes.get(right).intValue()))
				return right;
			left--;
			right++;
		}
		return index;
	}

	/** 字符区间 → TextSelection（quads + 文本） */
	private static TextSelection slice(TextStructure ts, int lo, int hi) {
		Quad[] sel = new Quad[hi - lo + 1];
		StringBuilder sb = new StringBuilder();
		for (int i = lo; i <= hi; i++) {
			sel[i - lo] = ts.quads.get(i);
			sb.appendCodePoint(ts.codes.get(i).intValue());
		}
		return new TextSelection(sel, sb.toString());
	}

	/** 获取页文本结构（按页缓存：同页直接返回，翻页重建） */
	private synchronized TextStructure getTextStructure(int pageNum) {
		if (textStructure != null && textStructurePage == pageNum && !textStructure.quads.isEmpty())
			return textStructure;
		StructuredText st = null;
		try {
			st = page.toStructuredText("preserve-whitespace");
			if (st == null)
				return null;
			final TextStructure ts = new TextStructure();
			st.walk(new StructuredTextWalker() {
				@Override
				public void onImageBlock(Rect bbox, Matrix transform, Image image) {
				}

				@Override
				public void beginTextBlock(Rect bbox, int index) {
				}

				@Override
				public void endTextBlock() {
				}

				@Override
				public void beginLine(Rect bbox, int wmode, Point dir) {
				}

				@Override
				public void endLine() {
				}

				@Override
				public void onChar(int c, Point origin, Font font, float size, Quad quad,
						int flags, int argb, int alpha) {
					ts.quads.add(quad);
					ts.codes.add(c);
				}

				@Override
				public void beginStruct(String standard, String raw, int index) {
				}

				@Override
				public void endStruct() {
				}

				@Override
				public void onVector(Rect bbox, StructuredTextWalker.VectorInfo info, int argb) {
				}
			});
			textStructure = ts;
			textStructurePage = pageNum;
			return ts;
		} catch (Exception ex) {
			textStructure = null;
			textStructurePage = -1;
			return null;
		} finally {
			if (st != null)
				st.destroy();
		}
	}

	/** 点 → 字符索引：优先命中的字符 quad，否则距字符中心最近 */
	private static int indexOfPoint(List<Quad> quads, float x, float y) {
		for (int i = 0; i < quads.size(); i++) {
			if (quads.get(i).contains(x, y))
				return i;
		}
		int best = 0;
		float bestD = Float.MAX_VALUE;
		for (int i = 0; i < quads.size(); i++) {
			Quad q = quads.get(i);
			float cx = (q.ul_x + q.lr_x) / 2f;
			float cy = (q.ul_y + q.lr_y) / 2f;
			float d = (cx - x) * (cx - x) + (cy - y) * (cy - y);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	/**
	 * M12：清空某页所有高亮 annotation（删除后重建用）。返回是否有变更。
	 */
	public synchronized boolean clearHighlights(int pageNum) {
		gotoPage(pageNum);
		if (!(page instanceof PDFPage))
			return false;
		try {
			PDFPage pdfPage = (PDFPage) page;
			PDFAnnotation[] anns = pdfPage.getAnnotations();
			boolean changed = false;
			for (PDFAnnotation ann : anns) {
				if (ann.getType() == PDFAnnotation.TYPE_HIGHLIGHT) {
					pdfPage.deleteAnnotation(ann);
					changed = true;
				}
			}
			if (changed && displayList != null) {
				displayList.destroy();
				displayList = null;
			}
			return changed;
		} catch (Exception ex) {
			return false;
		}
	}

	/**
	 * M12：创建原生高亮 annotation（仅 PDF）。成功返回 true，并销毁当前页 displayList
	 * 使下次 drawPage 重建（含新 annotation）。
	 */
	public synchronized boolean addHighlight(int pageNum, Quad[] quads, float[] color) {
		gotoPage(pageNum);
		if (!(page instanceof PDFPage) || quads == null || quads.length == 0)
			return false;
		try {
			PDFPage pdfPage = (PDFPage) page;
			PDFAnnotation ann = pdfPage.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT);
			if (ann == null)
				return false;
			ann.setQuadPoints(quads);
			ann.setColor(color);
			boolean ok = ann.update();
			if (ok) {
				if (displayList != null) {
					displayList.destroy();
					displayList = null;
				}
			}
			return ok;
		} catch (Exception ex) {
			return false;
		}
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

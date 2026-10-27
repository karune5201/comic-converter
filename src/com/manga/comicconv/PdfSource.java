package com.manga.comicconv;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;

/**
 * PDF 输入。
 *
 * 策略（按优先级）：
 *  1. 页面只有一张整页图且是 DCTDecode → 取出原始 JPEG 字节（字节级无损）
 *  2. FlateDecode 的 RGB/灰度图 → 解出原始样本重编码成 PNG（像素无损）
 *  3. 其它（JPX/CCITT/JBIG2、多图叠加、矢量内容、解析失败）
 *     → 用系统 PdfRenderer 栅格化（有损，会在 UI 上明确提示）
 *
 * 自己写了个够用的 PDF 解析器：支持经典 xref 表、xref 流、对象流、
 * Flate 预测器。目标是扫描版漫画 PDF，不是通用 PDF 渲染。
 */
public class PdfSource implements ComicSource {

    private final File file;
    private final int rasterDpi;

    private RandomAccessFile raf;
    private Buf buf;                 // mmap 视图，不占堆
    private Doc doc;

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;

    /** 每页的提取方案 */
    private final List<PagePlan> plans = new ArrayList<>();
    private boolean anyRasterized;
    private final StringBuilder lossyNotes = new StringBuilder();
    private boolean closed;

    public PdfSource(File file, int rasterDpi) {
        this.file = file;
        this.rasterDpi = rasterDpi > 0 ? rasterDpi : 300;
    }

    private static final class PagePlan {
        int pageIndex;
        /** 图像对象号；<0 表示不是单图页 */
        int imgObj = -1;
        /** 多条拼片（原始字节 + 位置）；非空表示这页由几条图拼成 */
        List<Strip> strips;
        String why = "";
    }

    // ============================ 打开 ============================

    @Override
    public void open() throws Exception {
        if (!Io.isPdf(Io.head(file, 8))) {
            throw new IOException("这个文件不是 PDF：" + file.getName());
        }
        long len = file.length();
        if (len > Integer.MAX_VALUE - 64) {
            throw new IOException("PDF 超过 2GB，处理不了：" + file.getName());
        }
        // 用 mmap：300MB 的 PDF 不能整个读进堆里，Android 堆给不了那么多
        raf = new RandomAccessFile(file, "r");
        try {
            buf = new Buf(raf.getChannel().map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, len));
        } catch (Throwable t) {
            // 32 位系统虚拟地址空间有限，超大 PDF 可能映射不出来。
            // 这里不能让整个转换失败，退成逐页栅格化还能出结果。
            buf = null;
            doc = null;
            lossyNote("文件太大，这机器一次装不下（" + t.getMessage()
                    + "），改成一页页截图，画质有损");
        }

        if (buf != null) {
            try {
                doc = new Doc(buf);
                doc.parse();
            } catch (Exception e) {
                doc = null;
                lossyNote("PDF 结构没读明白（" + e.getMessage() + "），整本改成一页页截图");
            }
        }

        int pageCount;
        if (doc != null && doc.encrypted) {
            lossyNote("这个 PDF 加了密或者有权限限制，取不出里面的图，只能一页页截图");
        }

        if (doc != null && !doc.pages.isEmpty()) {
            pageCount = doc.pages.size();
            for (int i = 0; i < pageCount; i++) {
                PagePlan p = new PagePlan();
                p.pageIndex = i;
                if (doc.encrypted) {
                    p.why = "PDF 加密了";
                    plans.add(p);
                    continue;
                }
                try {
                    int obj = doc.singleFullPageImage(doc.pages.get(i));
                    if (obj > 0) {
                        p.imgObj = obj;
                    } else {
                        // 试试多条拼片（扫描版常把一页切成几条）
                        List<Strip> st = doc.pageStrips(doc.pages.get(i));
                        if (st != null && st.size() > 1) {
                            p.strips = st;
                        } else {
                            p.why = "这页不是一张整图";
                        }
                    }
                } catch (Exception e) {
                    p.why = "找不到图：" + e.getMessage();
                }
                plans.add(p);
            }
        } else {
            // 结构解析不了，靠 PdfRenderer 报页数
            openRenderer();
            pageCount = renderer.getPageCount();
            for (int i = 0; i < pageCount; i++) {
                PagePlan p = new PagePlan();
                p.pageIndex = i;
                p.why = "结构没读出来";
                plans.add(p);
            }
        }
        if (pageCount == 0) throw new IOException("这个 PDF 里没页面：" + file.getName());
    }

    private void openRenderer() throws IOException {
        if (renderer != null) return;
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pfd);
    }

    private void lossyNote(String s) {
        if (lossyNotes.length() > 0) lossyNotes.append('\n');
        lossyNotes.append(s);
    }

    // ============================ 取页 ============================

    @Override
    public int pageCount() { return plans.size(); }

    @Override
    public String pageName(int i) { return String.format("page%04d", i + 1); }

    @Override
    public List<Strip> readStrips(int i) {
        PagePlan p = plans.get(i);
        return p.strips != null && p.strips.size() > 1 ? p.strips : null;
    }

    @Override
    public byte[] readPage(int i) throws Exception {
        PagePlan p = plans.get(i);
        if (p.strips != null && !p.strips.isEmpty()) {
            // 单图调用方：给第一条，真正的多条走 readStrips
            return p.strips.get(0).data;
        }
        if (p.imgObj > 0 && doc != null) {
            try {
                byte[] got = doc.extractImage(p.imgObj);
                if (got != null && got.length > 0) return got;
            } catch (Exception e) {
                p.why = "提取失败：" + e.getMessage();
            }
        }
        // 栅格化回退
        return rasterize(i, p.why);
    }

    private byte[] rasterize(int i, String why) throws IOException {
        openRenderer();
        if (!anyRasterized) {
            anyRasterized = true;
            lossyNote("有些页取不出原图（" + why + "），按 " + rasterDpi
                    + " dpi 截了图，这部分画质有损");
        }
        PdfRenderer.Page pg = renderer.openPage(i);
        Bitmap bm = null;
        try {
            // PDF 页尺寸单位是 pt（1/72 inch）
            int w = Math.max(1, (int) Math.round(pg.getWidth() / 72.0 * rasterDpi));
            int h = Math.max(1, (int) Math.round(pg.getHeight() / 72.0 * rasterDpi));
            // 防止超大页爆内存
            long px = (long) w * h;
            long maxPx = 40L * 1000 * 1000;
            if (px > maxPx) {
                double s = Math.sqrt((double) maxPx / px);
                w = Math.max(1, (int) (w * s));
                h = Math.max(1, (int) (h * s));
            }
            bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bm.eraseColor(0xffffffff);
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return Img.pngLossless(bm);
        } finally {
            Img.recycle(bm);
            try { pg.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public String title() { return ComicSource.stripExt(file.getName()); }

    @Override
    public boolean lossless() { return !anyRasterized; }

    @Override
    public String lossyReason() {
        return lossyNotes.length() == 0 ? null : lossyNotes.toString();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (renderer != null) try { renderer.close(); } catch (Exception ignore) {}
        if (pfd != null) try { pfd.close(); } catch (Exception ignore) {}
        if (raf != null) try { raf.close(); } catch (IOException ignore) {}
        buf = null;
        doc = null;
    }

    // ==================================================================
    //                        够用的 PDF 解析器
    // ==================================================================

    /** PDF 对象：用 Object 表示 —— Map/List/Ref/String/byte[]/Double/Boolean/Name */
    static final class Ref {
        final int num;
        Ref(int num) { this.num = num; }
        @Override public boolean equals(Object o) { return o instanceof Ref && ((Ref) o).num == num; }
        @Override public int hashCode() { return num; }
    }

    static final class Name {
        final String v;
        Name(String v) { this.v = v; }
        @Override public String toString() { return "/" + v; }
        @Override public boolean equals(Object o) { return o instanceof Name && ((Name) o).v.equals(v); }
        @Override public int hashCode() { return v.hashCode(); }
    }

    static final class PStream {
        Map<String, Object> dict;
        int dataStart;
        int dataLen;
    }

    static final class Doc {
        final Buf d;
        /** 对象号 -> 文件偏移 */
        final Map<Integer, Integer> xref = new HashMap<>();
        /** 对象号 -> [对象流所在对象号, 在流里的序号] */
        final Map<Integer, int[]> compressed = new HashMap<>();
        /** 已解析对象缓存 */
        final Map<Integer, Object> cache = new HashMap<>();
        /** 对象流解包缓存 */
        final Map<Integer, Map<Integer, Object>> objStmCache = new HashMap<>();
        Map<String, Object> trailer = new HashMap<>();
        final List<Map<String, Object>> pages = new ArrayList<>();
        /** 有 /Encrypt：流和字符串都是密文，提取不了原图 */
        boolean encrypted;

        Doc(Buf d) { this.d = d; }

        void parse() throws IOException {
            int sx = findStartXref();
            Set<Integer> seen = new HashSet<>();
            int pos = sx;
            while (pos > 0 && pos < d.len() && seen.add(pos)) {
                Integer prev = readXrefSection(pos);
                if (prev == null) break;
                pos = prev;
            }
            if (xref.isEmpty() && compressed.isEmpty()) {
                scanAllObjects();   // xref 坏了就全文扫 "N 0 obj"
            }
            encrypted = trailer.containsKey("Encrypt");
            buildPages();
            if (pages.isEmpty()) {
                scanAllObjects();
                buildPages();
            }
        }

        int findStartXref() throws IOException {
            int from = Math.max(0, d.len() - 2048);
            String tail = d.str(from, d.len() - from);
            int i = tail.lastIndexOf("startxref");
            if (i < 0) throw new IOException("找不到 startxref 标记");
            Lexer lx = new Lexer(d, from + i + 9);
            Object v = lx.readToken();
            if (!(v instanceof Double)) throw new IOException("startxref 不是数字");
            return (int) ((Double) v).doubleValue();
        }

        /** 读一段 xref（表或流），返回 /Prev，没有则 null */
        Integer readXrefSection(int pos) {
            try {
                Lexer lx = new Lexer(d, pos);
                lx.skipWs();
                if (lx.peekKeyword("xref")) {
                    lx.pos += 4;
                    // 经典 xref 表
                    while (true) {
                        lx.skipWs();
                        if (lx.peekKeyword("trailer")) {
                            lx.pos += 7;
                            Object t = lx.readObject(this);
                            if (t instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> tm = (Map<String, Object>) t;
                                mergeTrailer(tm);
                                // 混合文件：/XRefStm 指向 xref 流
                                Object xs = tm.get("XRefStm");
                                if (xs instanceof Double) {
                                    readXrefSection((int) ((Double) xs).doubleValue());
                                }
                                Object prev = tm.get("Prev");
                                return prev instanceof Double
                                        ? (int) ((Double) prev).doubleValue() : null;
                            }
                            return null;
                        }
                        Object a = lx.readToken(), b = lx.readToken();
                        if (!(a instanceof Double) || !(b instanceof Double)) return null;
                        int start = (int) ((Double) a).doubleValue();
                        int count = (int) ((Double) b).doubleValue();
                        for (int k = 0; k < count; k++) {
                            lx.skipWs();
                            Object o1 = lx.readToken(), o2 = lx.readToken();
                            lx.skipWs();
                            int type = lx.pos < d.len() ? d.at(lx.pos) : 'f';
                            lx.pos++;
                            if (type == 'n' && o1 instanceof Double) {
                                int num = start + k;
                                if (!xref.containsKey(num) && !compressed.containsKey(num)) {
                                    xref.put(num, (int) ((Double) o1).doubleValue());
                                }
                            }
                        }
                    }
                } else {
                    // xref 流：pos 指向 "N 0 obj"
                    Object o = parseObjectAt(pos, null);
                    if (!(o instanceof PStream)) return null;
                    PStream st = (PStream) o;
                    mergeTrailer(st.dict);
                    byte[] data = decodeStream(st);
                    Object wo = resolve(st.dict.get("W"));
                    if (!(wo instanceof List)) return null;
                    @SuppressWarnings("unchecked")
                    List<Object> wl = (List<Object>) wo;
                    int[] w = new int[wl.size()];
                    for (int i = 0; i < w.length; i++) w[i] = num(wl.get(i), 0);

                    List<int[]> ranges = new ArrayList<>();
                    Object idx = resolve(st.dict.get("Index"));
                    if (idx instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> il = (List<Object>) idx;
                        for (int i = 0; i + 1 < il.size(); i += 2) {
                            ranges.add(new int[]{num(il.get(i), 0), num(il.get(i + 1), 0)});
                        }
                    } else {
                        ranges.add(new int[]{0, num(resolve(st.dict.get("Size")), 0)});
                    }
                    int rowLen = 0;
                    for (int x : w) rowLen += x;
                    if (rowLen <= 0) return null;
                    int p = 0;
                    for (int[] r : ranges) {
                        for (int k = 0; k < r[1] && p + rowLen <= data.length; k++, p += rowLen) {
                            int q = p;
                            long f1 = w[0] == 0 ? 1 : readBE(data, q, w[0]); q += w[0];
                            long f2 = w.length > 1 ? readBE(data, q, w[1]) : 0; q += w.length > 1 ? w[1] : 0;
                            long f3 = w.length > 2 ? readBE(data, q, w[2]) : 0;
                            int objNum = r[0] + k;
                            if (xref.containsKey(objNum) || compressed.containsKey(objNum)) continue;
                            if (f1 == 1) {
                                xref.put(objNum, (int) f2);
                            } else if (f1 == 2) {
                                compressed.put(objNum, new int[]{(int) f2, (int) f3});
                            }
                        }
                    }
                    Object prev = st.dict.get("Prev");
                    return prev instanceof Double ? (int) ((Double) prev).doubleValue() : null;
                }
            } catch (Exception e) {
                return null;
            }
        }

        void mergeTrailer(Map<String, Object> t) {
            for (Map.Entry<String, Object> e : t.entrySet()) {
                if (!trailer.containsKey(e.getKey())) trailer.put(e.getKey(), e.getValue());
            }
        }

        /** xref 不可用时的兜底：全文正则扫对象定义 */
        void scanAllObjects() {
            String s = d.asLatin1();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)\\s+(\\d+)\\s+obj\\b").matcher(s);
            while (m.find()) {
                int num = Integer.parseInt(m.group(1));
                xref.put(num, m.start());   // 后面的覆盖前面的（增量更新语义）
            }
            if (trailer.get("Root") == null) {
                // 找 /Type /Catalog
                java.util.regex.Matcher c = java.util.regex.Pattern
                        .compile("/Type\\s*/Catalog").matcher(s);
                if (c.find()) {
                    int back = s.lastIndexOf(" obj", c.start());
                    if (back > 0) {
                        java.util.regex.Matcher om = java.util.regex.Pattern
                                .compile("(\\d+)\\s+\\d+\\s+obj\\s*$")
                                .matcher(s.substring(Math.max(0, back - 24), back + 4));
                        if (om.find()) trailer.put("Root", new Ref(Integer.parseInt(om.group(1))));
                    }
                }
            }
        }

        static long readBE(byte[] b, int off, int n) {
            long v = 0;
            for (int i = 0; i < n && off + i < b.length; i++) {
                v = (v << 8) | (b[off + i] & 0xff);
            }
            return v;
        }

        static int num(Object o, int def) {
            if (o instanceof Double) return (int) ((Double) o).doubleValue();
            return def;
        }

        Object resolve(Object o) {
            int guard = 0;
            while (o instanceof Ref && guard++ < 64) o = getObject(((Ref) o).num);
            return o;
        }

        Object getObject(int num) {
            if (cache.containsKey(num)) return cache.get(num);
            cache.put(num, null);   // 防环
            Object r = null;
            try {
                Integer off = xref.get(num);
                if (off != null && off >= 0 && off < d.len()) {
                    r = parseObjectAt(off, num);
                } else {
                    int[] c = compressed.get(num);
                    if (c != null) {
                        Map<Integer, Object> m = loadObjStm(c[0]);
                        if (m != null) r = m.get(num);
                    }
                }
            } catch (Exception ignore) {
            }
            cache.put(num, r);
            return r;
        }

        /** 解析 "N 0 obj ... endobj"；expectNum 非空时校验对象号 */
        Object parseObjectAt(int off, Integer expectNum) throws IOException {
            Lexer lx = new Lexer(d, off);
            lx.skipWs();
            Object a = lx.readToken();
            Object b = lx.readToken();
            lx.skipWs();
            if (!lx.peekKeyword("obj")) {
                throw new IOException("偏移 " + off + " 处不是对象定义");
            }
            lx.pos += 3;
            if (expectNum != null && a instanceof Double
                    && (int) ((Double) a).doubleValue() != expectNum) {
                throw new IOException("对象号不符：期望 " + expectNum + " 实得 " + a);
            }
            Object val = lx.readObject(this);
            lx.skipWs();
            if (lx.peekKeyword("stream")) {
                lx.pos += 6;
                // stream 后跟 CRLF 或 LF
                if (lx.pos < d.len() && d.at(lx.pos) == '\r') lx.pos++;
                if (lx.pos < d.len() && d.at(lx.pos) == '\n') lx.pos++;
                PStream st = new PStream();
                @SuppressWarnings("unchecked")
                Map<String, Object> dict = val instanceof Map
                        ? (Map<String, Object>) val : new HashMap<String, Object>();
                st.dict = dict;
                st.dataStart = lx.pos;
                int declared = num(resolve(dict.get("Length")), -1);
                if (declared >= 0 && lx.pos + declared <= d.len()
                        && looksLikeEndstream(lx.pos + declared)) {
                    st.dataLen = declared;
                } else {
                    int e = indexOf(d, "endstream", lx.pos);
                    if (e < 0) throw new IOException("stream 没有对应的 endstream");
                    int end = e;
                    // 去掉 endstream 前的换行
                    if (end > lx.pos && d.at(end - 1) == '\n') end--;
                    if (end > lx.pos && d.at(end - 1) == '\r') end--;
                    st.dataLen = end - lx.pos;
                }
                return st;
            }
            return val;
        }

        boolean looksLikeEndstream(int p) {
            for (int i = p; i < Math.min(d.len(), p + 4); i++) {
                if (d.at(i) == 'e') return matches(d, i, "endstream");
                if (d.at(i) != '\r' && d.at(i) != '\n' && d.at(i) != ' ' && d.at(i) != '\t') return false;
            }
            return false;
        }

        Map<Integer, Object> loadObjStm(int stmNum) {
            if (objStmCache.containsKey(stmNum)) return objStmCache.get(stmNum);
            Map<Integer, Object> res = new HashMap<>();
            objStmCache.put(stmNum, res);
            try {
                Object o = resolve(new Ref(stmNum));
                if (!(o instanceof PStream)) return res;
                PStream st = (PStream) o;
                byte[] data = decodeStream(st);
                int n = num(resolve(st.dict.get("N")), 0);
                int first = num(resolve(st.dict.get("First")), 0);
                Lexer hx = new Lexer(data, 0);
                int[][] pairs = new int[n][2];
                for (int i = 0; i < n; i++) {
                    Object a = hx.readToken(), b = hx.readToken();
                    pairs[i][0] = num(a, -1);
                    pairs[i][1] = num(b, -1);
                }
                for (int i = 0; i < n; i++) {
                    if (pairs[i][0] < 0 || pairs[i][1] < 0) continue;
                    Lexer ox = new Lexer(data, first + pairs[i][1]);
                    res.put(pairs[i][0], ox.readObject(this));
                }
            } catch (Exception ignore) {
            }
            return res;
        }

        // ---------------- 页树 ----------------

        void buildPages() {
            pages.clear();
            Object root = resolve(trailer.get("Root"));
            Map<String, Object> pagesNode = null;
            if (root instanceof Map) {
                Object pn = resolve(((Map<?, ?>) root).get("Pages"));
                if (pn instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) pn;
                    pagesNode = m;
                }
            }
            if (pagesNode != null) {
                walk(pagesNode, new HashMap<String, Object>(), new HashSet<Object>(), 0);
            }
            if (pages.isEmpty()) {
                // 兜底：扫所有 /Type /Page 对象
                List<Integer> nums = new ArrayList<>(xref.keySet());
                java.util.Collections.sort(nums);
                for (int n : nums) {
                    Object o = resolve(new Ref(n));
                    if (o instanceof Map) {
                        Object t = ((Map<?, ?>) o).get("Type");
                        if (t instanceof Name && "Page".equals(((Name) t).v)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> pm = (Map<String, Object>) o;
                            pages.add(pm);
                        }
                    }
                }
            }
        }

        private static final String[] INHERITABLE = {"Resources", "MediaBox", "CropBox", "Rotate"};

        void walk(Map<String, Object> node, Map<String, Object> inherited,
                  Set<Object> seen, int depth) {
            if (depth > 64 || pages.size() > 20000) return;
            Object t = node.get("Type");
            String type = t instanceof Name ? ((Name) t).v : null;
            Map<String, Object> inh = new HashMap<>(inherited);
            for (String k : INHERITABLE) {
                if (node.containsKey(k)) inh.put(k, node.get(k));
            }
            Object kidsO = resolve(node.get("Kids"));
            if ("Pages".equals(type) || (type == null && kidsO instanceof List)) {
                if (!(kidsO instanceof List)) return;
                for (Object k : (List<?>) kidsO) {
                    if (k instanceof Ref && !seen.add(((Ref) k).num)) continue;
                    Object kid = resolve(k);
                    if (kid instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> km = (Map<String, Object>) kid;
                        walk(km, inh, seen, depth + 1);
                    }
                }
            } else {
                Map<String, Object> page = new HashMap<>(node);
                for (Map.Entry<String, Object> e : inh.entrySet()) {
                    if (!page.containsKey(e.getKey())) page.put(e.getKey(), e.getValue());
                }
                pages.add(page);
            }
        }

        /**
         * 判断页面是不是"单张整页图"，是则返回图像对象号。
         * 条件：Resources/XObject 里恰好一个 Subtype=Image，且没有第二张图。
         */
        int singleFullPageImage(Map<String, Object> page) {
            Object res = resolve(page.get("Resources"));
            if (!(res instanceof Map)) return -1;
            Object xo = resolve(((Map<?, ?>) res).get("XObject"));
            if (!(xo instanceof Map)) return -1;
            int found = -1;
            int count = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) xo).entrySet()) {
                Object v = e.getValue();
                int objNum = v instanceof Ref ? ((Ref) v).num : -1;
                Object s = resolve(v);
                if (!(s instanceof PStream)) continue;
                Object st = ((PStream) s).dict.get("Subtype");
                if (st instanceof Name && "Image".equals(((Name) st).v)) {
                    count++;
                    if (objNum > 0) found = objNum;
                }
            }
            return count == 1 ? found : -1;
        }

        /**
         * 解析页面内容流，找出每个 Do 调用把图像摆在了哪儿。
         * 只处理轴对齐变换（扫描版拼条都是轴对齐的），有旋转/斜切就放弃。
         * 返回按"从上到下"排好序的拼片；拿不准就返回 null 交给栅格化。
         */
        List<Strip> pageStrips(Map<String, Object> page) {
            Object res = resolve(page.get("Resources"));
            if (!(res instanceof Map)) return null;
            Object xoO = resolve(((Map<?, ?>) res).get("XObject"));
            if (!(xoO instanceof Map)) return null;
            Map<?, ?> xo = (Map<?, ?>) xoO;

            float[] box = rect(resolve(page.get("MediaBox")));
            if (box == null) return null;
            float pw = box[2] - box[0], ph = box[3] - box[1];
            if (pw <= 0 || ph <= 0) return null;

            byte[] content = pageContent(page);
            if (content == null || content.length == 0) return null;

            List<Strip> out = new ArrayList<>();
            double[] ctm = {1, 0, 0, 1, 0, 0};
            List<double[]> stack = new ArrayList<>();
            List<Object> operands = new ArrayList<>();
            Lexer lx = new Lexer(content, 0);

            while (lx.pos < content.length) {
                int before = lx.pos;
                Object o = lx.readObject(this);
                if (lx.pos == before) break;
                if (o instanceof String) {
                    String op = (String) o;
                    if (op.equals("q")) {
                        stack.add(ctm.clone());
                    } else if (op.equals("Q")) {
                        if (!stack.isEmpty()) ctm = stack.remove(stack.size() - 1);
                    } else if (op.equals("cm") && operands.size() >= 6) {
                        int n = operands.size();
                        double[] m = new double[6];
                        for (int k = 0; k < 6; k++) {
                            Object v = operands.get(n - 6 + k);
                            m[k] = v instanceof Double ? (Double) v : 0;
                        }
                        ctm = mul(m, ctm);
                    } else if (op.equals("Do") && !operands.isEmpty()) {
                        Object nm = operands.get(operands.size() - 1);
                        if (nm instanceof Name) {
                            Object st = resolve(xo.get(((Name) nm).v));
                            if (st instanceof PStream) {
                                PStream ps = (PStream) st;
                                Object sub = resolve(ps.dict.get("Subtype"));
                                if (sub instanceof Name && "Image".equals(((Name) sub).v)) {
                                    // 有旋转/斜切就整页放弃
                                    if (Math.abs(ctm[1]) > 0.01 || Math.abs(ctm[2]) > 0.01) {
                                        return null;
                                    }
                                    if (ps.dict.containsKey("SMask")
                                            || ps.dict.containsKey("Mask")) return null;
                                    List<String> fl = filterChain(ps.dict);
                                    if (fl.size() != 1 || !"DCTDecode".equals(fl.get(0))) {
                                        return null;
                                    }
                                    byte[] jpg = d.slice(ps.dataStart, ps.dataLen);
                                    ImageInfo ii = ImageInfo.parse(jpg);
                                    if (!ii.isJpeg() || ii.width <= 0
                                            || ii.jpegComponents == 4) return null;
                                    double x0 = ctm[4], y0 = ctm[5];
                                    double w = ctm[0], h = ctm[3];
                                    if (w < 0) { x0 += w; w = -w; }
                                    if (h < 0) { y0 += h; h = -h; }
                                    if (w <= 0 || h <= 0) return null;
                                    out.add(new Strip(jpg,
                                            (float) ((x0 - box[0]) / pw),
                                            (float) ((y0 - box[1]) / ph),
                                            (float) (w / pw),
                                            (float) (h / ph)));
                                    if (out.size() > 32) return null;
                                }
                            }
                        }
                    }
                    operands.clear();
                } else {
                    operands.add(o);
                    if (operands.size() > 32) operands.remove(0);
                }
            }

            if (out.size() < 2) return null;
            // 按 y 从高到低排（页面从上到下）
            java.util.Collections.sort(out, new java.util.Comparator<Strip>() {
                @Override public int compare(Strip a, Strip b) {
                    return Float.compare(b.y + b.h, a.y + a.h);
                }
            });
            // 覆盖率检查：盖不住大半页说明还有别的内容，交给栅格化更稳
            float area = 0;
            for (Strip s : out) area += s.w * s.h;
            if (area < 0.5f) return null;
            return out;
        }

        byte[] pageContent(Map<String, Object> page) {
            try {
                Object c = resolve(page.get("Contents"));
                if (c instanceof PStream) return decodeStream((PStream) c);
                if (c instanceof List) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    for (Object x : (List<?>) c) {
                        Object s = resolve(x);
                        if (s instanceof PStream) {
                            o.write(decodeStream((PStream) s));
                            o.write('\n');
                        }
                    }
                    return o.toByteArray();
                }
            } catch (Exception ignore) {
            }
            return null;
        }

        static float[] rect(Object o) {
            if (!(o instanceof List)) return null;
            List<?> l = (List<?>) o;
            if (l.size() < 4) return null;
            float[] r = new float[4];
            for (int i = 0; i < 4; i++) {
                Object v = l.get(i);
                if (!(v instanceof Double)) return null;
                r[i] = (float) ((Double) v).doubleValue();
            }
            if (r[0] > r[2]) { float t = r[0]; r[0] = r[2]; r[2] = t; }
            if (r[1] > r[3]) { float t = r[1]; r[1] = r[3]; r[3] = t; }
            return r;
        }

        /** 矩阵乘：cm 是左乘当前 CTM */
        static double[] mul(double[] m, double[] n) {
            return new double[]{
                    m[0] * n[0] + m[1] * n[2],
                    m[0] * n[1] + m[1] * n[3],
                    m[2] * n[0] + m[3] * n[2],
                    m[2] * n[1] + m[3] * n[3],
                    m[4] * n[0] + m[5] * n[2] + n[4],
                    m[4] * n[1] + m[5] * n[3] + n[5],
            };
        }

        // ---------------- 图像提取 ----------------

        /** 返回可直接当页面用的图片字节（JPEG 原字节 或 重编码 PNG）；失败返回 null */
        byte[] extractImage(int objNum) throws IOException {
            Object o = resolve(new Ref(objNum));
            if (!(o instanceof PStream)) return null;
            PStream st = (PStream) o;
            List<String> filters = filterChain(st.dict);
            // 带软掩码/掩码的图不走原样提取（会丢透明信息），交给栅格化
            if (st.dict.containsKey("SMask") || st.dict.containsKey("Mask")) return null;

            String last = filters.isEmpty() ? null : filters.get(filters.size() - 1);

            if ("DCTDecode".equals(last) && filters.size() == 1) {
                // 原始 JPEG，直接取字节
                byte[] jpg = d.slice(st.dataStart, st.dataLen);
                ImageInfo ii = ImageInfo.parse(jpg);
                if (ii.isJpeg() && ii.width > 0) {
                    // CMYK JPEG 交给栅格化，省得颜色翻转处理不对
                    if (ii.jpegComponents == 4) return null;
                    return jpg;
                }
                return null;
            }

            if ("FlateDecode".equals(last) || "LZWDecode".equals(last)) {
                int w = num(resolve(st.dict.get("Width")), 0);
                int h = num(resolve(st.dict.get("Height")), 0);
                int bpc = num(resolve(st.dict.get("BitsPerComponent")), 8);
                if (w <= 0 || h <= 0) return null;
                if (bpc != 8) return null;        // 1/2/4/16 位交给栅格化
                int comps = colorComponents(st.dict);
                if (comps != 1 && comps != 3) return null;
                byte[] raw = decodeStream(st);
                long need = (long) w * h * comps;
                if (raw.length < need) return null;
                return Img.encodePng(raw, w, h, comps, 8);
            }
            return null;   // JPX / CCITT / JBIG2 等
        }

        int colorComponents(Map<String, Object> dict) {
            Object cs = resolve(dict.get("ColorSpace"));
            if (cs instanceof Name) {
                String n = ((Name) cs).v;
                if (n.equals("DeviceGray") || n.equals("CalGray") || n.equals("G")) return 1;
                if (n.equals("DeviceRGB") || n.equals("CalRGB") || n.equals("RGB")) return 3;
                if (n.equals("DeviceCMYK") || n.equals("CMYK")) return 4;
                return -1;
            }
            if (cs instanceof List) {
                List<?> l = (List<?>) cs;
                if (l.isEmpty()) return -1;
                Object f = resolve(l.get(0));
                if (f instanceof Name) {
                    String n = ((Name) f).v;
                    if (n.equals("ICCBased") && l.size() > 1) {
                        Object s = resolve(l.get(1));
                        if (s instanceof PStream) {
                            return num(resolve(((PStream) s).dict.get("N")), -1);
                        }
                    }
                    if (n.equals("CalGray")) return 1;
                    if (n.equals("CalRGB") || n.equals("Lab")) return 3;
                    if (n.equals("Indexed") || n.equals("I")) return -1;
                    if (n.equals("DeviceN") && l.size() > 1) {
                        Object names = resolve(l.get(1));
                        return names instanceof List ? ((List<?>) names).size() : -1;
                    }
                }
            }
            return -1;
        }

        List<String> filterChain(Map<String, Object> dict) {
            List<String> out = new ArrayList<>();
            Object f = resolve(dict.get("Filter"));
            if (f == null) f = resolve(dict.get("F"));
            if (f instanceof Name) out.add(((Name) f).v);
            else if (f instanceof List) {
                for (Object x : (List<?>) f) {
                    Object r = resolve(x);
                    if (r instanceof Name) out.add(((Name) r).v);
                }
            }
            return out;
        }

        /** 解码流（只处理 Flate + 预测器；其它过滤器原样返回） */
        byte[] decodeStream(PStream st) throws IOException {
            byte[] data = d.slice(st.dataStart, st.dataLen);
            List<String> fs = filterChain(st.dict);
            for (String f : fs) {
                if ("FlateDecode".equals(f) || "Fl".equals(f)) {
                    data = inflate(data);
                } else if ("ASCIIHexDecode".equals(f) || "AHx".equals(f)) {
                    data = asciiHex(data);
                } else {
                    // 其它过滤器不处理，留给调用方判断
                    break;
                }
            }
            Object dp = resolve(st.dict.get("DecodeParms"));
            if (dp == null) dp = resolve(st.dict.get("DP"));
            if (dp instanceof List && !((List<?>) dp).isEmpty()) {
                dp = resolve(((List<?>) dp).get(0));
            }
            if (dp instanceof Map) {
                Map<?, ?> p = (Map<?, ?>) dp;
                int pred = num(resolve(p.get("Predictor")), 1);
                if (pred > 1) {
                    int colors = num(resolve(p.get("Colors")), 1);
                    int bpc = num(resolve(p.get("BitsPerComponent")), 8);
                    int columns = num(resolve(p.get("Columns")), 1);
                    data = unpredict(data, pred, colors, bpc, columns);
                }
            }
            return data;
        }

        static byte[] inflate(byte[] in) throws IOException {
            // 先按 zlib 试，失败再按 raw deflate 试
            for (int attempt = 0; attempt < 2; attempt++) {
                Inflater inf = new Inflater(attempt == 1);
                try {
                    inf.setInput(in);
                    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.length * 4));
                    byte[] b = new byte[1 << 16];
                    while (!inf.finished()) {
                        int n;
                        try {
                            n = inf.inflate(b);
                        } catch (java.util.zip.DataFormatException e) {
                            break;
                        }
                        if (n > 0) out.write(b, 0, n);
                        else if (inf.needsInput() || inf.needsDictionary()) break;
                    }
                    if (out.size() > 0) return out.toByteArray();
                } finally {
                    inf.end();
                }
            }
            throw new IOException("Flate 解压失败");
        }

        static byte[] asciiHex(byte[] in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length / 2 + 1);
            int hi = -1;
            for (byte b : in) {
                int c = b & 0xff;
                if (c == '>') break;
                int v = Character.digit(c, 16);
                if (v < 0) continue;
                if (hi < 0) hi = v;
                else { out.write((hi << 4) | v); hi = -1; }
            }
            if (hi >= 0) out.write(hi << 4);
            return out.toByteArray();
        }

        /** 还原 PNG 预测器（10..15）和 TIFF 预测器（2） */
        static byte[] unpredict(byte[] data, int pred, int colors, int bpc, int columns) {
            int bpp = Math.max(1, colors * bpc / 8);
            int rowLen = (columns * colors * bpc + 7) / 8;
            if (pred == 2) {
                if (bpc != 8) return data;
                int rows = data.length / rowLen;
                for (int r = 0; r < rows; r++) {
                    int off = r * rowLen;
                    for (int i = bpp; i < rowLen; i++) {
                        data[off + i] = (byte) (data[off + i] + data[off + i - bpp]);
                    }
                }
                return data;
            }
            // PNG 预测器：每行开头多一个过滤器类型字节
            int stride = rowLen + 1;
            int rows = data.length / stride;
            byte[] out = new byte[rows * rowLen];
            byte[] prev = new byte[rowLen];
            for (int r = 0; r < rows; r++) {
                int ft = data[r * stride] & 0xff;
                int src = r * stride + 1;
                int dst = r * rowLen;
                for (int i = 0; i < rowLen; i++) {
                    int rawv = data[src + i] & 0xff;
                    int left = i >= bpp ? (out[dst + i - bpp] & 0xff) : 0;
                    int up = prev[i] & 0xff;
                    int ul = i >= bpp ? (prev[i - bpp] & 0xff) : 0;
                    int v;
                    switch (ft) {
                        case 0: v = rawv; break;
                        case 1: v = rawv + left; break;
                        case 2: v = rawv + up; break;
                        case 3: v = rawv + ((left + up) >> 1); break;
                        case 4: {
                            int pa = Math.abs(up - ul), pb = Math.abs(left - ul);
                            int pc = Math.abs(left + up - 2 * ul);
                            int pr = (pa <= pb && pa <= pc) ? left : (pb <= pc ? up : ul);
                            v = rawv + pr;
                            break;
                        }
                        default: v = rawv;
                    }
                    out[dst + i] = (byte) v;
                }
                System.arraycopy(out, dst, prev, 0, rowLen);
            }
            return out;
        }
    }

    // ---------------- 词法分析 ----------------

    static final class Lexer {
        final Buf d;
        int pos;

        Lexer(Buf d, int pos) { this.d = d; this.pos = pos; }
        Lexer(byte[] b, int pos) { this.d = new Buf(b); this.pos = pos; }

        static boolean isWs(int c) {
            return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == 0 || c == 12;
        }

        static boolean isDelim(int c) {
            return c == '(' || c == ')' || c == '<' || c == '>' || c == '['
                    || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
        }

        void skipWs() {
            while (pos < d.len()) {
                int c = d.at(pos) & 0xff;
                if (isWs(c)) { pos++; continue; }
                if (c == '%') {   // 注释
                    while (pos < d.len() && d.at(pos) != '\n' && d.at(pos) != '\r') pos++;
                    continue;
                }
                break;
            }
        }

        boolean peekKeyword(String kw) {
            return matches(d, pos, kw);
        }

        /** 读一个数字/关键字 token；数字返回 Double，其它返回 String */
        Object readToken() {
            skipWs();
            if (pos >= d.len()) return null;
            int start = pos;
            int c = d.at(pos) & 0xff;
            if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                pos++;
                while (pos < d.len()) {
                    int x = d.at(pos) & 0xff;
                    if ((x >= '0' && x <= '9') || x == '.' || x == '-' || x == '+'
                            || x == 'e' || x == 'E') pos++;
                    else break;
                }
                try {
                    return Double.valueOf(d.str(start, pos - start));
                } catch (NumberFormatException e) {
                    return Double.valueOf(0);
                }
            }
            while (pos < d.len()) {
                int x = d.at(pos) & 0xff;
                if (isWs(x) || isDelim(x)) break;
                pos++;
            }
            if (pos == start) { pos++; return String.valueOf((char) c); }
            return d.str(start, pos - start);
        }

        /** 读一个完整 PDF 对象 */
        Object readObject(Doc doc) {
            skipWs();
            if (pos >= d.len()) return null;
            int c = d.at(pos) & 0xff;

            if (c == '/') {
                pos++;
                int s = pos;
                StringBuilder b = new StringBuilder();
                while (pos < d.len()) {
                    int x = d.at(pos) & 0xff;
                    if (isWs(x) || isDelim(x)) break;
                    if (x == '#' && pos + 2 < d.len()) {
                        int h1 = Character.digit(d.at(pos + 1) & 0xff, 16);
                        int h2 = Character.digit(d.at(pos + 2) & 0xff, 16);
                        if (h1 >= 0 && h2 >= 0) {
                            b.append((char) ((h1 << 4) | h2));
                            pos += 3;
                            continue;
                        }
                    }
                    b.append((char) x);
                    pos++;
                }
                if (b.length() == 0 && pos > s) {
                    b.append(d.str(s, pos - s));
                }
                return new Name(b.toString());
            }

            if (c == '[') {
                pos++;
                List<Object> list = new ArrayList<>();
                while (true) {
                    skipWs();
                    if (pos >= d.len()) break;
                    if ((d.at(pos) & 0xff) == ']') { pos++; break; }
                    Object o = readObject(doc);
                    if (o == NOTHING) break;
                    list.add(o);
                }
                return list;
            }

            if (c == '<' && pos + 1 < d.len() && (d.at(pos + 1) & 0xff) == '<') {
                pos += 2;
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    skipWs();
                    if (pos >= d.len()) break;
                    if ((d.at(pos) & 0xff) == '>' ) {
                        pos++;
                        if (pos < d.len() && (d.at(pos) & 0xff) == '>') pos++;
                        break;
                    }
                    Object k = readObject(doc);
                    if (!(k instanceof Name)) {
                        if (k == NOTHING) break;
                        continue;
                    }
                    Object v = readObject(doc);
                    if (v == NOTHING) break;
                    map.put(((Name) k).v, v);
                }
                return map;
            }

            if (c == '<') {   // 十六进制字符串
                pos++;
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                int hi = -1;
                while (pos < d.len()) {
                    int x = d.at(pos++) & 0xff;
                    if (x == '>') break;
                    int v = Character.digit(x, 16);
                    if (v < 0) continue;
                    if (hi < 0) hi = v;
                    else { o.write((hi << 4) | v); hi = -1; }
                }
                if (hi >= 0) o.write(hi << 4);
                return o.toByteArray();
            }

            if (c == '(') {   // 字面字符串
                pos++;
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                int depth = 1;
                while (pos < d.len()) {
                    int x = d.at(pos++) & 0xff;
                    if (x == '\\') {
                        if (pos >= d.len()) break;
                        int e = d.at(pos++) & 0xff;
                        switch (e) {
                            case 'n': o.write('\n'); break;
                            case 'r': o.write('\r'); break;
                            case 't': o.write('\t'); break;
                            case 'b': o.write('\b'); break;
                            case 'f': o.write('\f'); break;
                            case '\r':
                                if (pos < d.len() && d.at(pos) == '\n') pos++;
                                break;
                            case '\n': break;
                            default:
                                if (e >= '0' && e <= '7') {
                                    int v = e - '0';
                                    for (int k = 0; k < 2 && pos < d.len(); k++) {
                                        int y = d.at(pos) & 0xff;
                                        if (y < '0' || y > '7') break;
                                        v = v * 8 + (y - '0');
                                        pos++;
                                    }
                                    o.write(v & 0xff);
                                } else {
                                    o.write(e);
                                }
                        }
                        continue;
                    }
                    if (x == '(') depth++;
                    if (x == ')') { depth--; if (depth == 0) break; }
                    o.write(x);
                }
                return o.toByteArray();
            }

            if (c == ']' || c == '>' || c == '}' || c == ')') { pos++; return NOTHING; }

            // 数字 / 引用 / 关键字
            int save = pos;
            Object t = readToken();
            if (t instanceof Double) {
                int save2 = pos;
                Object t2 = readToken();
                if (t2 instanceof Double) {
                    skipWs();
                    if (pos < d.len() && (d.at(pos) & 0xff) == 'R'
                            && (pos + 1 >= d.len() || isWs(d.at(pos + 1) & 0xff)
                            || isDelim(d.at(pos + 1) & 0xff))) {
                        pos++;
                        return new Ref((int) ((Double) t).doubleValue());
                    }
                }
                pos = save2;
                return t;
            }
            if ("true".equals(t)) return Boolean.TRUE;
            if ("false".equals(t)) return Boolean.FALSE;
            if ("null".equals(t)) return null;
            if (t == null) { pos = save + 1; return NOTHING; }
            return t;
        }
    }


    // ---------------- mmap 字节视图 ----------------

    /** 统一的只读字节访问，底层可以是 mmap 或普通数组。 */
    static final class Buf {
        private final java.nio.ByteBuffer mb;
        private final byte[] arr;
        private final int length;

        Buf(java.nio.ByteBuffer mb) { this.mb = mb; this.arr = null; this.length = mb.capacity(); }

        Buf(byte[] a) { this.mb = null; this.arr = a; this.length = a.length; }

        int len() { return length; }

        byte at(int i) {
            if (i < 0 || i >= length) return 0;
            return arr != null ? arr[i] : mb.get(i);
        }

        byte[] slice(int off, int n) {
            if (off < 0) { n += off; off = 0; }
            if (n < 0) n = 0;
            if (off + n > length) n = Math.max(0, length - off);
            byte[] out = new byte[n];
            if (arr != null) {
                System.arraycopy(arr, off, out, 0, n);
            } else {
                java.nio.ByteBuffer dup = mb.duplicate();
                dup.position(off);
                dup.get(out, 0, n);
            }
            return out;
        }

        /** 取一段当 latin-1 字符串（字节与字符一一对应，适合结构解析） */
        String str(int off, int n) {
            return new String(slice(off, n), StandardCharsets.ISO_8859_1);
        }

        /** 整个内容当字符串 —— 只在 xref 彻底坏掉的兜底路径用 */
        String asLatin1() {
            return str(0, length);
        }
    }

    static final Object NOTHING = new Object();

    static boolean matches(Buf d, int pos, String s) {
        if (pos < 0 || pos + s.length() > d.len()) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((d.at(pos + i) & 0xff) != s.charAt(i)) return false;
        }
        return true;
    }

    static int indexOf(Buf d, String s, int from) {
        byte first = (byte) s.charAt(0);
        for (int i = Math.max(0, from); i <= d.len() - s.length(); i++) {
            if (d.at(i) == first && matches(d, i, s)) return i;
        }
        return -1;
    }
}

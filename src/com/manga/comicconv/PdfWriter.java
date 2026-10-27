package com.manga.comicconv;

import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * PDF 输出：优先把输入图片的原始字节直接塞进 PDF 流。
 * - 基线 JPEG  -> /DCTDecode 原字节直嵌（字节级无损）
 * - 普通 PNG   -> IDAT 原 zlib 流直嵌 + Predictor 15（字节级无损）
 * - 调色板 PNG -> IDAT 直嵌 + /Indexed 色彩空间（字节级无损）
 * - 其它格式   -> 解码成原始样本 + FlateDecode（像素无损）
 *
 * 全程流式写文件，不把整本书缓冲在内存里。
 */
public class PdfWriter implements ComicWriter {

    public static class Options {
        /** 0 = 1 像素当 1 pt；>0 = 按该 dpi 折算页面物理尺寸 */
        public int dpi = 0;
        /** 允许有损回退（目前仅影响解码失败时的处理策略） */
        public boolean allowLossy = false;
    }

    private final File outFile;
    private final Options opt;
    private final Report rep = new Report();

    private Counting out;
    private Meta meta;
    private boolean closed;
    private boolean finished;

    /** 对象号 -> 文件偏移；下标即对象号，0 号位占位不用 */
    private final ArrayList<Long> offsets = new ArrayList<>();
    /** 已写出的页对象号，用于最后拼 Pages 的 /Kids */
    private final ArrayList<Integer> pageObjs = new ArrayList<>();

    private static final int OBJ_CATALOG = 1;
    private static final int OBJ_PAGES = 2;
    private static final int OBJ_INFO = 3;
    /** 页相关对象从 4 号开始分配 */
    private int nextObj = 4;

    public PdfWriter(File outFile, Options opt) {
        this.outFile = outFile;
        this.opt = opt != null ? opt : new Options();
    }

    // ---------------- 计数输出流：记录绝对偏移 ----------------
    private static final class Counting extends OutputStream {
        private final OutputStream o;
        long pos;

        Counting(OutputStream o) { this.o = o; }

        @Override public void write(int b) throws java.io.IOException { o.write(b); pos++; }

        @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
            o.write(b, off, len); pos += len;
        }

        @Override public void flush() throws java.io.IOException { o.flush(); }

        @Override public void close() throws java.io.IOException { o.close(); }
    }

    private void raw(String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** 记录某对象号的起始偏移 */
    private void mark(int objNum) {
        while (offsets.size() <= objNum) offsets.add(null);
        offsets.set(objNum, out.pos);
    }

    private int alloc() { return nextObj++; }

    /** 写一个带流的对象 */
    private void writeStreamObj(int num, String dictBody, byte[] data) throws Exception {
        mark(num);
        raw(num + " 0 obj\n<< " + dictBody + " /Length " + data.length + " >>\nstream\n");
        out.write(data, 0, data.length);
        raw("\nendstream\nendobj\n");
    }

    /** 写一个纯字典对象 */
    private void writeDictObj(int num, String dictBody) throws Exception {
        mark(num);
        raw(num + " 0 obj\n<< " + dictBody + " >>\nendobj\n");
    }

    // ---------------- 生命周期 ----------------
    @Override
    public void begin(Meta meta) throws Exception {
        this.meta = meta != null ? meta : new Meta();
        out = new Counting(new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16));
        // 头：版本 + 二进制标识注释
        raw("%PDF-1.7\n");
        out.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3});
        raw("\n");
    }

    @Override
    public void addPage(Page page) throws Exception {
        if (page == null || page.data == null || page.data.length == 0) {
            rep.failedPages++;
            rep.note("第 " + (page == null ? -1 : page.index + 1) + " 页是空的，跳过");
            return;
        }
        ImageInfo ii = page.info;
        if (ii == null || ii.width <= 0 || ii.height <= 0) {
            rep.failedPages++;
            rep.note("第 " + (page.index + 1) + " 页（" + page.name + "）：看不出图的大小，跳过");
            return;
        }

        int imgObj = alloc();
        String csAndFilter;
        byte[] streamData;
        int bpc;
        int extraObj = 0;      // SMask 或调色板对象
        String smaskRef = "";

        if (ii.isJpeg() && !ii.jpegProgressive && ii.jpegComponents > 0) {
            // ---- 基线 JPEG：原字节直嵌 ----
            String cs = ii.jpegComponents == 1 ? "/DeviceGray"
                    : ii.jpegComponents == 4 ? "/DeviceCMYK" : "/DeviceRGB";
            String decode = "";
            if (ii.jpegComponents == 4 && hasAdobeApp14(page.data)) {
                // Adobe 写的 CMYK JPEG 是反相存的
                decode = " /Decode [1 0 1 0 1 0 1 0]";
            }
            csAndFilter = "/ColorSpace " + cs + decode + " /Filter /DCTDecode";
            streamData = page.data;
            bpc = 8;
            rep.exactPages++;

        } else if (ii.isPng() && ii.pngDirectEmbeddable()) {
            // ---- 灰度/真彩 PNG：IDAT 原 zlib 流直嵌 ----
            byte[] idat = ImageInfo.pngIdat(page.data);
            if (idat.length == 0) { fail(page, "这张 PNG 里没有图像数据"); return; }
            int colors = ii.pngColorType == 2 ? 3 : 1;
            String cs = ii.pngColorType == 2 ? "/DeviceRGB" : "/DeviceGray";
            csAndFilter = "/ColorSpace " + cs + " /Filter /FlateDecode"
                    + " /DecodeParms << /Predictor 15 /Colors " + colors
                    + " /BitsPerComponent " + ii.pngBitDepth
                    + " /Columns " + ii.width + " >>";
            streamData = idat;
            bpc = ii.pngBitDepth;
            rep.exactPages++;

        } else if (ii.isPng() && ii.pngColorType == 3 && ii.pngInterlace == 0 && !ii.pngHasTrns) {
            // ---- 调色板 PNG：IDAT 直嵌 + /Indexed ----
            byte[] idat = ImageInfo.pngIdat(page.data);
            int plteOff = ImageInfo.findPngChunk(page.data, "PLTE");
            if (idat.length == 0 || plteOff < 0) { fail(page, "这张索引色 PNG 缺调色板"); return; }
            int plteLen = ImageInfo.pngChunkLen(page.data, plteOff);
            int entries = plteLen / 3;
            if (entries <= 0) { fail(page, "这张图的调色板是空的"); return; }
            StringBuilder hex = new StringBuilder(plteLen * 2 + 2);
            hex.append('<');
            for (int i = 0; i < plteLen; i++) {
                hex.append(String.format("%02X", page.data[plteOff + i] & 0xff));
            }
            hex.append('>');
            csAndFilter = "/ColorSpace [/Indexed /DeviceRGB " + (entries - 1) + " " + hex + "]"
                    + " /Filter /FlateDecode"
                    + " /DecodeParms << /Predictor 15 /Colors 1"
                    + " /BitsPerComponent " + ii.pngBitDepth
                    + " /Columns " + ii.width + " >>";
            streamData = idat;
            bpc = ii.pngBitDepth;
            rep.exactPages++;

        } else {
            // ---- 其它：解码后以原始样本重编码（像素无损） ----
            Bitmap bm = Img.decode(page.data);
            if (bm == null) {
                fail(page, "这种图读不出来（" + ii.ext + "）");
                return;
            }
            try {
                boolean alpha = Img.hasRealAlpha(bm);
                boolean gray = !alpha && Img.isGrayscale(bm);
                byte[] samples = gray ? Img.grayRaw(bm) : Img.rgbRaw(bm);
                streamData = Img.deflate(samples);
                csAndFilter = "/ColorSpace " + (gray ? "/DeviceGray" : "/DeviceRGB")
                        + " /Filter /FlateDecode";
                bpc = 8;
                // 尺寸以实际解码结果为准
                ii.width = bm.getWidth();
                ii.height = bm.getHeight();
                if (alpha) {
                    extraObj = alloc();
                    byte[] a = Img.deflate(Img.alphaRaw(bm));
                    writeStreamObj(extraObj,
                            "/Type /XObject /Subtype /Image"
                                    + " /Width " + bm.getWidth() + " /Height " + bm.getHeight()
                                    + " /ColorSpace /DeviceGray /BitsPerComponent 8"
                                    + " /Filter /FlateDecode", a);
                    smaskRef = " /SMask " + extraObj + " 0 R";
                }
                rep.recodedPages++;
                String why = ii.isJpeg() && ii.jpegProgressive ? "渐进式 JPEG，放进 PDF 不一定兼容"
                        : ii.isPng() ? "PNG 带透明通道或隔行扫描，没法原样放进去"
                        : "PDF 放不了 " + ii.ext + " 这种图";
                rep.note("第 " + (page.index + 1) + " 页：" + why +"，重新打包了一遍，画质不变");
            } finally {
                Img.recycle(bm);
            }
        }

        // 图像对象
        writeStreamObj(imgObj,
                "/Type /XObject /Subtype /Image"
                        + " /Width " + ii.width + " /Height " + ii.height
                        + " /BitsPerComponent " + bpc
                        + " " + csAndFilter + smaskRef, streamData);

        // 页面尺寸（pt）
        double w, h;
        if (opt.dpi > 0) {
            w = ii.width * 72.0 / opt.dpi;
            h = ii.height * 72.0 / opt.dpi;
        } else {
            w = ii.width;
            h = ii.height;
        }
        String ws = fmt(w), hs = fmt(h);

        // 内容流
        int contentObj = alloc();
        byte[] content = ("q " + ws + " 0 0 " + hs + " 0 0 cm /Im0 Do Q\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        writeStreamObj(contentObj, "", content);

        // 页对象
        int pageObj = alloc();
        writeDictObj(pageObj,
                "/Type /Page /Parent " + OBJ_PAGES + " 0 R"
                        + " /MediaBox [0 0 " + ws + " " + hs + "]"
                        + " /Resources << /XObject << /Im0 " + imgObj + " 0 R >> >>"
                        + " /Contents " + contentObj + " 0 R");
        pageObjs.add(pageObj);
    }

    /**
     * 写一个由多条原始图片拼成的页面：几条 JPEG 原字节各自摆在原位置。
     * 这条路径是字节级无损的 —— 不解码、不重编码、不重新拼图。
     */
    public void addStripPage(int pageIndex, java.util.List<Strip> strips) throws Exception {
        if (strips == null || strips.isEmpty()) return;

        // 页面尺寸：按像素密度最高的那条反推，保证最清晰的一条是 1:1
        double bestW = 0, bestH = 0;
        for (Strip s : strips) {
            if (s.info == null || s.info.width <= 0 || s.w <= 0 || s.h <= 0) continue;
            bestW = Math.max(bestW, s.info.width / s.w);
            bestH = Math.max(bestH, s.info.height / s.h);
        }
        if (bestW <= 0 || bestH <= 0) {
            rep.failedPages++;
            rep.note("第 " + (pageIndex + 1) + " 页：算不出这页图的大小，跳过");
            return;
        }
        double pw = bestW, ph = bestH;
        if (opt.dpi > 0) {
            pw = pw * 72.0 / opt.dpi;
            ph = ph * 72.0 / opt.dpi;
        }

        StringBuilder xobjs = new StringBuilder();
        StringBuilder content = new StringBuilder();
        int n = 0;
        for (Strip s : strips) {
            ImageInfo ii = s.info;
            if (ii == null || !ii.isJpeg() || ii.jpegProgressive || ii.width <= 0) {
                rep.note("第 " + (pageIndex + 1) + " 页有一块图不是常见的 JPEG，跳过这块");
                continue;
            }
            int imgObj = alloc();
            String cs = ii.jpegComponents == 1 ? "/DeviceGray"
                    : ii.jpegComponents == 4 ? "/DeviceCMYK" : "/DeviceRGB";
            writeStreamObj(imgObj,
                    "/Type /XObject /Subtype /Image"
                            + " /Width " + ii.width + " /Height " + ii.height
                            + " /BitsPerComponent 8 /ColorSpace " + cs
                            + " /Filter /DCTDecode", s.data);
            String nm = "Im" + n;
            xobjs.append('/').append(nm).append(' ').append(imgObj).append(" 0 R ");
            double x = s.x * pw, y = s.y * ph, w = s.w * pw, h = s.h * ph;
            content.append("q ").append(fmt(w)).append(" 0 0 ").append(fmt(h))
                   .append(' ').append(fmt(x)).append(' ').append(fmt(y))
                   .append(" cm /").append(nm).append(" Do Q\n");
            n++;
        }
        if (n == 0) {
            rep.failedPages++;
            return;
        }
        rep.exactPages++;

        int contentObj = alloc();
        writeStreamObj(contentObj, "",
                content.toString().getBytes(StandardCharsets.ISO_8859_1));
        int pageObj = alloc();
        writeDictObj(pageObj,
                "/Type /Page /Parent " + OBJ_PAGES + " 0 R"
                        + " /MediaBox [0 0 " + fmt(pw) + " " + fmt(ph) + "]"
                        + " /Resources << /XObject << " + xobjs + ">> >>"
                        + " /Contents " + contentObj + " 0 R");
        pageObjs.add(pageObj);
    }

    /** 跳过一页并记账 */
    private void fail(Page page, String why) {
        rep.failedPages++;
        rep.note("第 " + (page.index + 1) + " 页（" + page.name + "）：" + why + "，跳过");
    }

    @Override
    public void finish() throws Exception {
        if (finished) return;
        finished = true;

        // Pages
        StringBuilder kids = new StringBuilder("[");
        for (int i = 0; i < pageObjs.size(); i++) {
            if (i > 0) kids.append(' ');
            kids.append(pageObjs.get(i)).append(" 0 R");
        }
        kids.append(']');
        writeDictObj(OBJ_PAGES, "/Type /Pages /Count " + pageObjs.size() + " /Kids " + kids);

        // Info
        String now = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date());
        writeDictObj(OBJ_INFO,
                "/Title " + pdfText(meta.title)
                        + " /Author " + pdfText(meta.author == null ? "" : meta.author)
                        + " /Producer " + pdfText("ComicConv")
                        + " /Creator " + pdfText("ComicConv")
                        + " /CreationDate (D:" + now + "+00'00')"
                        + " /ModDate (D:" + now + "+00'00')");

        // Catalog
        writeDictObj(OBJ_CATALOG,
                "/Type /Catalog /Pages " + OBJ_PAGES + " 0 R"
                        + " /ViewerPreferences << /FitWindow true >>");

        // xref
        int maxObj = offsets.size() - 1;
        long xrefPos = out.pos;
        raw("xref\n0 " + (maxObj + 1) + "\n");
        raw("0000000000 65535 f \n");
        for (int i = 1; i <= maxObj; i++) {
            Long off = offsets.get(i);
            if (off == null) {
                // 未使用的对象号（理论上不该有），写成空闲项
                raw("0000000000 65535 f \n");
            } else {
                raw(String.format("%010d 00000 n \n", off));
            }
        }
        String id = String.format("%016X", System.currentTimeMillis() * 1000003L);
        raw("trailer\n<< /Size " + (maxObj + 1)
                + " /Root " + OBJ_CATALOG + " 0 R"
                + " /Info " + OBJ_INFO + " 0 R"
                + " /ID [<" + id + "> <" + id + ">] >>\n");
        raw("startxref\n" + xrefPos + "\n%%EOF\n");
        out.flush();
    }

    @Override
    public Report report() { return rep; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (out != null) {
            try { out.close(); } catch (Exception ignore) {}
        }
    }

    // ---------------- 工具 ----------------

    /** 去掉多余小数位，PDF 里不要科学计数法 */
    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.4f", v);
    }

    /** PDF 文本串：中文必须 UTF-16BE + BOM 的十六进制形式 */
    private static String pdfText(String s) {
        if (s == null) s = "";
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < 32 || s.charAt(i) > 126) { ascii = false; break; }
        }
        if (ascii) {
            StringBuilder b = new StringBuilder("(");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(' || c == ')' || c == '\\') b.append('\\');
                b.append(c);
            }
            return b.append(')').toString();
        }
        byte[] u = s.getBytes(StandardCharsets.UTF_16BE);
        StringBuilder b = new StringBuilder("<FEFF");
        for (byte x : u) b.append(String.format("%02X", x & 0xff));
        return b.append('>').toString();
    }

    /** 扫 APP14 段看是不是 Adobe 写的 CMYK JPEG */
    private static boolean hasAdobeApp14(byte[] d) {
        int p = 2;
        while (p + 4 < d.length) {
            if ((d[p] & 0xff) != 0xFF) { p++; continue; }
            int m = d[p + 1] & 0xff;
            if (m == 0xFF) { p++; continue; }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD9)) { p += 2; continue; }
            if (m == 0xDA) break; // 到扫描数据了
            int len = ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
            if (len < 2) break;
            if (m == 0xEE && p + 4 + 5 <= d.length) {
                if (d[p + 4] == 'A' && d[p + 5] == 'd' && d[p + 6] == 'o'
                        && d[p + 7] == 'b' && d[p + 8] == 'e') return true;
            }
            p += 2 + len;
        }
        return false;
    }

    /** 给调用方探测用：这个输出格式理论上能否字节无损 */
    public static boolean canBeByteExact(ImageInfo ii) {
        if (ii == null) return false;
        if (ii.isJpeg() && !ii.jpegProgressive) return true;
        if (ii.isPng() && ii.pngDirectEmbeddable()) return true;
        return ii.isPng() && ii.pngColorType == 3 && ii.pngInterlace == 0 && !ii.pngHasTrns;
    }
}

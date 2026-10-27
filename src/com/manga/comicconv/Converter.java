package com.manga.comicconv;

import java.io.File;
import java.util.List;

/** 把一本书从输入格式转成输出格式。所有无损策略的调度都在这里。 */
public final class Converter {

    public static final int OUT_PDF = 0;
    public static final int OUT_EPUB = 1;
    public static final int OUT_CBZ = 2;
    public static final int OUT_MOBI = 3;
    public static final int OUT_IMAGES = 4;

    public static String extOf(int outFormat) {
        switch (outFormat) {
            case OUT_PDF: return ".pdf";
            case OUT_EPUB: return ".epub";
            case OUT_CBZ: return ".cbz";
            case OUT_MOBI: return ".mobi";
            case OUT_IMAGES: return "";
            default: return ".bin";
        }
    }

    public static String nameOf(int outFormat) {
        switch (outFormat) {
            case OUT_PDF: return "PDF";
            case OUT_EPUB: return "EPUB";
            case OUT_CBZ: return "CBZ";
            case OUT_MOBI: return "MOBI";
            case OUT_IMAGES: return "图片";
            default: return "?";
        }
    }

    public static class Options {
        public int outFormat = OUT_PDF;
        public boolean rtl = true;
        public boolean overwrite;
        /** MOBI 兼容模式：超限图片降采样（有损） */
        public boolean kindleSafe;
        /** PDF 输入栅格化回退的 dpi */
        public int pdfRasterDpi = 300;
        public String author = "";
        public String language = "zh";
    }

    /** 单本转换结果 */
    public static class Result {
        public boolean ok;
        public File outFile;
        public int pages;
        public String message = "";
        public boolean byteExact;
        public boolean pixelExact;
        public String detail = "";
    }

    public interface Progress {
        /** 返回 false 表示请求取消 */
        boolean onPage(int done, int total);
    }

    private Converter() {}

    public static Result convert(Scanner.Item item, File outDir, Options opt,
                                 File tmpDir, Progress progress) {
        Result r = new Result();
        ComicSource src = null;
        ComicWriter dst = null;
        try {
            src = Scanner.openSource(item, opt.pdfRasterDpi);
            src.open();
            int n = src.pageCount();
            r.pages = n;

            String title = src.title();
            File out = new File(outDir, safeName(title) + extOf(opt.outFormat));
            if (out.exists() && !opt.overwrite) {
                out = uniquify(out);
            } else if (out.exists() && opt.overwrite && opt.outFormat == OUT_IMAGES) {
                deleteTree(out);
            }
            r.outFile = out;

            PdfWriter pdfW = null;
            switch (opt.outFormat) {
                case OUT_PDF: {
                    PdfWriter.Options o = new PdfWriter.Options();
                    pdfW = new PdfWriter(out, o);
                    dst = pdfW;
                    break;
                }
                case OUT_EPUB: {
                    EpubWriter.Options o = new EpubWriter.Options();
                    o.rtl = opt.rtl;
                    dst = new EpubWriter(out, o);
                    break;
                }
                case OUT_CBZ: {
                    dst = new CbzWriter(out, new CbzWriter.Options());
                    break;
                }
                case OUT_IMAGES: {
                    dst = new FolderWriter(out);
                    break;
                }
                default: {
                    MobiWriter.Options o = new MobiWriter.Options();
                    o.kindleSafeImages = opt.kindleSafe;
                    dst = new MobiWriter(out, tmpDir, o);
                    break;
                }
            }

            ComicWriter.Meta meta = new ComicWriter.Meta();
            meta.title = title;
            meta.author = opt.author;
            meta.language = opt.language;
            meta.pageCount = n;
            meta.outPath = out.getAbsolutePath();
            dst.begin(meta);

            int emitted = 0;
            for (int i = 0; i < n; i++) {
                if (progress != null && !progress.onPage(i, n)) {
                    r.message = "已取消";
                    safeClose(dst, src);
                    deleteQuietly(out);
                    return r;
                }
                List<Strip> strips = null;
                try {
                    strips = src.readStrips(i);
                } catch (Exception ignore) {
                }

                if (strips != null && strips.size() > 1) {
                    if (pdfW != null) {
                        // PDF 输出：几条原始 JPEG 按原位置摆同一页，字节级无损
                        pdfW.addStripPage(i, strips);
                        emitted++;
                    } else {
                        // 其它格式：按原字节拆成连续几页，同样不重编码
                        for (Strip s : strips) {
                            Page p = new Page(emitted, String.format("p%04d_%d", i + 1, emitted), s.data);
                            p.info = s.info;
                            dst.addPage(p);
                            emitted++;
                        }
                        dst.report().note("第 " + (i + 1)
                                + " 页本来是 " + strips.size()
                                + " 张图拼的，已经拆成 " + strips.size() + " 页，图没动过");
                    }
                    continue;
                }

                byte[] data = src.readPage(i);
                Page p = new Page(emitted, src.pageName(i), data);
                dst.addPage(p);
                emitted++;
            }
            dst.finish();

            ComicWriter.Report rep = dst.report();
            r.ok = true;
            r.byteExact = rep.byteExact() && src.lossless();
            r.pixelExact = rep.pixelExact() && src.lossless();
            StringBuilder d = new StringBuilder(rep.summary());
            if (!src.lossless() && src.lossyReason() != null) {
                d.append('\n').append(src.lossyReason());
            }
            if (rep.notes.length() > 0) d.append('\n').append(rep.notes);
            r.detail = d.toString();
            r.message = emitted + " 页 → " + out.getName();
            if (progress != null) progress.onPage(n, n);
        } catch (Throwable e) {
            r.ok = false;
            String m = e.getMessage();
            r.message = m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
            if (e instanceof OutOfMemoryError) {
                r.message = "内存不够，这本书有几页图太大";
            }
            if (r.outFile != null) deleteQuietly(r.outFile);
        } finally {
            safeClose(dst, src);
        }
        return r;
    }

    private static void safeClose(ComicWriter dst, ComicSource src) {
        if (dst != null) try { dst.close(); } catch (Exception ignore) {}
        if (src != null) try { src.close(); } catch (Exception ignore) {}
    }

    private static void deleteQuietly(File f) {
        if (f == null || !f.exists()) return;
        try {
            if (f.isDirectory()) deleteTree(f);
            else f.delete();
        } catch (Exception ignore) {}
    }

    /** 递归删目录（只用于清理本次自己刚建的输出） */
    static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) deleteTree(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    /** 去掉文件名里不能用的字符 */
    public static String safeName(String s) {
        if (s == null || s.trim().isEmpty()) return "comic";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20) {
                b.append('_');
            } else {
                b.append(c);
            }
        }
        String r = b.toString().trim();
        // 文件名长度留余量（有些文件系统按字节算 255）
        byte[] bytes = r.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 180) {
            int end = r.length();
            while (end > 1 && r.substring(0, end)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 180) {
                end--;
            }
            r = r.substring(0, end);
        }
        return r.isEmpty() ? "comic" : r;
    }

    private static File uniquify(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }
}

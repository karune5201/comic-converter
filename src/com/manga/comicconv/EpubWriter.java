package com.manga.comicconv;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * EPUB 3 fixed-layout 输出（漫画标准做法）。
 * 图片以 STORED 原样写入，字节级无损；每页一个按像素尺寸定死的 XHTML。
 */
public class EpubWriter implements ComicWriter {

    public static class Options {
        /** 固定版式（漫画必须开） */
        public boolean fixedLayout = true;
        /** 右起翻页（日漫） */
        public boolean rtl = true;
        /** 图片用 STORED 原样存 */
        public boolean storeImages = true;
    }

    private final File outFile;
    private final Options opt;
    private final Report rep = new Report();

    private ZipOutputStream zos;
    private Meta meta;
    private boolean closed, finished;
    private final String uuid = UUID.randomUUID().toString();

    /** 每页记录：图片条目名、宽、高、mime */
    private static final class Item {
        String img; int w, h; String mime;
    }

    private final ArrayList<Item> items = new ArrayList<>();

    public EpubWriter(File outFile, Options opt) {
        this.outFile = outFile;
        this.opt = opt != null ? opt : new Options();
    }

    @Override
    public void begin(Meta meta) throws Exception {
        this.meta = meta != null ? meta : new Meta();
        zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16));

        // mimetype 必须是第一个条目、STORED、无 extra field
        byte[] mt = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        ZipEntry e = new ZipEntry("mimetype");
        e.setMethod(ZipEntry.STORED);
        e.setSize(mt.length);
        e.setCompressedSize(mt.length);
        CRC32 c = new CRC32();
        c.update(mt, 0, mt.length);
        e.setCrc(c.getValue());
        e.setTime(0L);
        zos.putNextEntry(e);
        zos.write(mt);
        zos.closeEntry();

        writeText("META-INF/container.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<container version=\"1.0\""
                        + " xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                        + "  <rootfiles>\n"
                        + "    <rootfile full-path=\"OEBPS/content.opf\""
                        + " media-type=\"application/oebps-package+xml\"/>\n"
                        + "  </rootfiles>\n</container>\n");
    }

    @Override
    public void addPage(Page page) throws Exception {
        if (page == null || page.data == null || page.data.length == 0) {
            rep.failedPages++;
            rep.note("第 " + (page == null ? -1 : page.index + 1) + " 页是空的，跳过");
            return;
        }
        Item it = new Item();
        it.img = page.outName();
        it.w = page.info != null ? page.info.width : 0;
        it.h = page.info != null ? page.info.height : 0;
        it.mime = page.info != null && page.info.mime != null
                ? page.info.mime : mimeOf(it.img);
        if (it.w <= 0 || it.h <= 0) {
            it.w = 1200; it.h = 1800;
            rep.note("第 " + (page.index + 1) + " 页：看不出尺寸，先按 1200x1800 排");
        }

        // 图片：STORED 原样写
        byte[] d = page.data;
        ZipEntry e = new ZipEntry("OEBPS/images/" + it.img);
        if (opt.storeImages) {
            CRC32 c = new CRC32();
            c.update(d, 0, d.length);
            e.setMethod(ZipEntry.STORED);
            e.setSize(d.length);
            e.setCompressedSize(d.length);
            e.setCrc(c.getValue());
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        e.setTime(System.currentTimeMillis());
        zos.putNextEntry(e);
        zos.write(d);
        zos.closeEntry();

        // 该页的 XHTML
        int n = items.size() + 1;
        String base = String.format("p%04d", n);
        String xhtml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\""
                + " xmlns:epub=\"http://www.idpf.org/2007/ops\">\n"
                + "<head><title>" + n + "</title>\n"
                + "<meta name=\"viewport\" content=\"width=" + it.w
                + ", height=" + it.h + "\"/>\n"
                + "<style>html,body{margin:0;padding:0;height:100%;width:100%}"
                + "div{margin:0;padding:0;height:100%;width:100%}"
                + "img{display:block;width:100%;height:100%;object-fit:contain}</style>\n"
                + "</head>\n<body><div><img src=\"../images/" + it.img
                + "\" alt=\"" + n + "\"/></div></body>\n</html>\n";
        writeText("OEBPS/text/" + base + ".xhtml", xhtml);

        items.add(it);
        rep.exactPages++;
    }

    @Override
    public void finish() throws Exception {
        if (finished) return;
        finished = true;
        writeText("OEBPS/content.opf", buildOpf());
        writeText("OEBPS/nav.xhtml", buildNav());
        writeText("OEBPS/toc.ncx", buildNcx());
        zos.finish();
        zos.flush();
    }

    private String buildOpf() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder b = new StringBuilder(1024 + items.size() * 160);
        b.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        b.append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"")
         .append(" unique-identifier=\"pub-id\"")
         .append(" prefix=\"rendition: http://www.idpf.org/vocab/rendition/#\">\n");
        b.append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");
        b.append("    <dc:identifier id=\"pub-id\">urn:uuid:").append(uuid)
         .append("</dc:identifier>\n");
        b.append("    <dc:title>").append(CbzWriter.xmlEsc(meta.title)).append("</dc:title>\n");
        b.append("    <dc:language>").append(CbzWriter.xmlEsc(meta.language))
         .append("</dc:language>\n");
        if (meta.author != null && !meta.author.isEmpty()) {
            b.append("    <dc:creator>").append(CbzWriter.xmlEsc(meta.author))
             .append("</dc:creator>\n");
        }
        b.append("    <meta property=\"dcterms:modified\">").append(f.format(new Date()))
         .append("</meta>\n");
        if (opt.fixedLayout) {
            b.append("    <meta property=\"rendition:layout\">pre-paginated</meta>\n");
            b.append("    <meta property=\"rendition:orientation\">auto</meta>\n");
            b.append("    <meta property=\"rendition:spread\">landscape</meta>\n");
        }
        if (!items.isEmpty()) {
            b.append("    <meta name=\"cover\" content=\"img0001\"/>\n");
        }
        b.append("  </metadata>\n  <manifest>\n");
        b.append("    <item id=\"nav\" href=\"nav.xhtml\"")
         .append(" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        b.append("    <item id=\"ncx\" href=\"toc.ncx\"")
         .append(" media-type=\"application/x-dtbncx+xml\"/>\n");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String id = String.format("%04d", i + 1);
            b.append("    <item id=\"img").append(id).append("\" href=\"images/")
             .append(CbzWriter.xmlEsc(it.img)).append("\" media-type=\"")
             .append(CbzWriter.xmlEsc(it.mime)).append('"');
            if (i == 0) b.append(" properties=\"cover-image\"");
            b.append("/>\n");
            b.append("    <item id=\"pg").append(id).append("\" href=\"text/p")
             .append(id).append(".xhtml\" media-type=\"application/xhtml+xml\"/>\n");
        }
        b.append("  </manifest>\n  <spine toc=\"ncx\"");
        if (opt.rtl) b.append(" page-progression-direction=\"rtl\"");
        b.append(">\n");
        for (int i = 0; i < items.size(); i++) {
            b.append("    <itemref idref=\"pg").append(String.format("%04d", i + 1))
             .append("\" properties=\"rendition:layout-pre-paginated\"/>\n");
        }
        b.append("  </spine>\n</package>\n");
        return b.toString();
    }

    /** 页数多时抽稀目录，否则 nav 会巨大 */
    private int navStep() {
        return items.size() > 200 ? 10 : 1;
    }

    private String buildNav() {
        int step = navStep();
        StringBuilder b = new StringBuilder(512 + items.size() * 48);
        b.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n");
        b.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"")
         .append(" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n");
        b.append("<head><title>").append(CbzWriter.xmlEsc(meta.title))
         .append("</title></head>\n<body>\n");
        b.append("<nav epub:type=\"toc\" id=\"toc\"><h1>目录</h1><ol>\n");
        for (int i = 0; i < items.size(); i += step) {
            b.append("  <li><a href=\"text/p").append(String.format("%04d", i + 1))
             .append(".xhtml\">第 ").append(i + 1).append(" 页</a></li>\n");
        }
        b.append("</ol></nav>\n");
        b.append("<nav epub:type=\"landmarks\" hidden=\"hidden\"><ol>")
         .append("<li><a epub:type=\"bodymatter\" href=\"text/p0001.xhtml\">开始</a></li>")
         .append("</ol></nav>\n");
        b.append("</body>\n</html>\n");
        if (step > 1) rep.note("页数 " + items.size() + " 页有点多，目录改成每 " + step + " 页列一条");
        return b.toString();
    }

    private String buildNcx() {
        int step = navStep();
        StringBuilder b = new StringBuilder(512 + items.size() * 64);
        b.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        b.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n");
        b.append("  <head><meta name=\"dtb:uid\" content=\"urn:uuid:").append(uuid)
         .append("\"/><meta name=\"dtb:depth\" content=\"1\"/></head>\n");
        b.append("  <docTitle><text>").append(CbzWriter.xmlEsc(meta.title))
         .append("</text></docTitle>\n  <navMap>\n");
        int order = 1;
        for (int i = 0; i < items.size(); i += step) {
            b.append("    <navPoint id=\"np").append(order).append("\" playOrder=\"")
             .append(order).append("\"><navLabel><text>第 ").append(i + 1)
             .append(" 页</text></navLabel><content src=\"text/p")
             .append(String.format("%04d", i + 1)).append(".xhtml\"/></navPoint>\n");
            order++;
        }
        b.append("  </navMap>\n</ncx>\n");
        return b.toString();
    }

    private void writeText(String name, String content) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        e.setTime(System.currentTimeMillis());
        zos.putNextEntry(e);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".heic") || n.endsWith(".heif")) return "image/heif";
        if (n.endsWith(".avif")) return "image/avif";
        return "image/jpeg";
    }

    @Override
    public Report report() { return rep; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (zos != null) {
            try { zos.close(); } catch (Exception ignore) {}
        }
    }
}

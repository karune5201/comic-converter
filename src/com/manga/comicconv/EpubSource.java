package com.manga.comicconv;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB 输入。
 * 优先按 OPF spine 的阅读顺序取图（漫画 EPUB 每页一个 xhtml 包一张图），
 * 解析不出来就退回按条目名自然排序。
 */
public class EpubSource implements ComicSource {

    private final File file;
    private ZipFile zf;
    private final List<String> names = new ArrayList<>();
    private String bookTitle;
    private boolean closed;

    public EpubSource(File file) {
        this.file = file;
    }

    @Override
    public void open() throws Exception {
        zf = new ZipFile(file, StandardCharsets.UTF_8);

        // 所有图片条目，留着兜底
        List<String> allImages = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            if (ComicSource.isImageName(e.getName())) allImages.add(e.getName());
        }

        try {
            parseSpine(allImages);
        } catch (Exception ignore) {
            // 结构解析失败就走兜底
        }

        if (names.isEmpty()) {
            names.addAll(allImages);
            Collections.sort(names, ComicSource.NATURAL);
        }
        if (names.isEmpty()) {
            throw new IOException("EPUB 里没找到图：" + file.getName());
        }
    }

    /** 读 container.xml -> OPF -> spine -> 每个 xhtml 里的 img src */
    private void parseSpine(List<String> allImages) throws Exception {
        String container = readText("META-INF/container.xml");
        if (container == null) return;
        String opfPath = attr(container, "rootfile", "full-path");
        if (opfPath == null) return;
        String opf = readText(opfPath);
        if (opf == null) return;

        String opfDir = dirOf(opfPath);
        bookTitle = tagText(opf, "dc:title");
        if (bookTitle == null) bookTitle = tagText(opf, "title");

        // manifest: id -> href
        java.util.Map<String, String> manifest = new java.util.HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<item\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(opf);
        while (m.find()) {
            String tag = m.group();
            String id = attrOf(tag, "id");
            String href = attrOf(tag, "href");
            if (id != null && href != null) manifest.put(id, href);
        }

        // spine 顺序
        List<String> spine = new ArrayList<>();
        java.util.regex.Matcher sm = java.util.regex.Pattern
                .compile("<itemref\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(opf);
        while (sm.find()) {
            String idref = attrOf(sm.group(), "idref");
            if (idref != null && manifest.containsKey(idref)) spine.add(manifest.get(idref));
        }
        if (spine.isEmpty()) return;

        Set<String> ordered = new LinkedHashSet<>();
        for (String href : spine) {
            String path = resolvePath(opfDir, href);
            if (ComicSource.isImageName(path)) {
                // spine 里直接是图（少见但合法）
                String hit = findEntry(path, allImages);
                if (hit != null) ordered.add(hit);
                continue;
            }
            String html = readText(path);
            if (html == null) continue;
            String htmlDir = dirOf(path);
            // 抓 <img src> 和 <image xlink:href>
            java.util.regex.Matcher im = java.util.regex.Pattern
                    .compile("(?:<img\\b[^>]*?\\bsrc|<image\\b[^>]*?href)\\s*=\\s*[\"']([^\"']+)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(html);
            while (im.find()) {
                String src = resolvePath(htmlDir, im.group(1));
                String hit = findEntry(src, allImages);
                if (hit != null) ordered.add(hit);
            }
        }
        names.addAll(ordered);
    }

    /** zip 条目名可能带不同前缀，做一次宽松匹配 */
    private String findEntry(String path, List<String> all) {
        if (zf.getEntry(path) != null) return path;
        String tail = path.startsWith("/") ? path.substring(1) : path;
        if (zf.getEntry(tail) != null) return tail;
        String base = tail.substring(tail.lastIndexOf('/') + 1);
        for (String n : all) {
            if (n.equals(tail) || n.endsWith("/" + tail)) return n;
        }
        for (String n : all) {
            if (n.endsWith("/" + base) || n.equals(base)) return n;
        }
        return null;
    }

    private static String dirOf(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i + 1);
    }

    /** 处理 ../ 和 ./ */
    private static String resolvePath(String dir, String href) {
        if (href.startsWith("/")) href = href.substring(1);
        int q = href.indexOf('#');
        if (q >= 0) href = href.substring(0, q);
        href = urlDecode(href);
        String full = href.startsWith("..") || !href.contains(":") ? dir + href : href;
        List<String> parts = new ArrayList<>();
        for (String seg : full.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        // 不用 String.join（API 26），手拼以兼容低版本
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String urlDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String attr(String xml, String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + "\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        if (m.find()) return attrOf(m.group(), name);
        return null;
    }

    private static String attrOf(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + name + "\\s*=\\s*[\"']([^\"']*)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String tagText(String xml, String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + java.util.regex.Pattern.quote(tag)
                                + "\\b[^>]*>(.*?)</" + java.util.regex.Pattern.quote(tag) + ">",
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(xml);
        if (!m.find()) return null;
        String t = m.group(1).trim();
        return t.isEmpty() ? null : unescape(t);
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    private String readText(String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) {
            String tail = name.startsWith("/") ? name.substring(1) : name;
            e = zf.getEntry(tail);
        }
        if (e == null) return null;
        InputStream in = null;
        try {
            in = zf.getInputStream(e);
            return new String(Io.readAll(in, e.getSize()), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignore) {}
        }
    }

    @Override
    public int pageCount() { return names.size(); }

    @Override
    public String pageName(int i) { return names.get(i); }

    @Override
    public byte[] readPage(int i) throws Exception {
        ZipEntry e = zf.getEntry(names.get(i));
        if (e == null) throw new IOException("找不到这个文件：" + names.get(i));
        InputStream in = zf.getInputStream(e);
        try {
            return Io.readAll(in, e.getSize());
        } finally {
            in.close();
        }
    }

    @Override
    public String title() {
        if (bookTitle != null && !bookTitle.isEmpty()) return bookTitle;
        return ComicSource.stripExt(file.getName());
    }

    @Override
    public boolean lossless() { return true; }

    @Override
    public String lossyReason() { return null; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (zf != null) try { zf.close(); } catch (IOException ignore) {}
    }
}

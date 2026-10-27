package com.manga.comicconv;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CBZ 输出：原始字节原样打包，字节级无损。
 * 图片本身已是压缩格式，默认用 STORED（不再 deflate），速度快且无收益损失。
 */
public class CbzWriter implements ComicWriter {

    public static class Options {
        /** true = STORED 不压缩 */
        public boolean store = true;
        /** 写 ComicInfo.xml 元数据 */
        public boolean comicInfo = true;
    }

    private final File outFile;
    private final Options opt;
    private final Report rep = new Report();

    private ZipOutputStream zos;
    private Meta meta;
    private boolean closed, finished;

    /** 收集每页宽高，finish 时写进 ComicInfo.xml */
    private final ArrayList<int[]> dims = new ArrayList<>();

    public CbzWriter(File outFile, Options opt) {
        this.outFile = outFile;
        this.opt = opt != null ? opt : new Options();
    }

    @Override
    public void begin(Meta meta) throws Exception {
        this.meta = meta != null ? meta : new Meta();
        zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16));
        zos.setComment("ComicConv");
        if (!opt.store) {
            zos.setLevel(1);   // 图片再压也几乎没收益，用最低级别
        }
    }

    @Override
    public void addPage(Page page) throws Exception {
        if (page == null || page.data == null || page.data.length == 0) {
            rep.failedPages++;
            rep.note("第 " + (page == null ? -1 : page.index + 1) + " 页是空的，跳过");
            return;
        }
        ZipEntry e = new ZipEntry(page.outName());
        if (opt.store) {
            // STORED 必须自己算 CRC 和长度
            CRC32 crc = new CRC32();
            crc.update(page.data, 0, page.data.length);
            e.setMethod(ZipEntry.STORED);
            e.setSize(page.data.length);
            e.setCompressedSize(page.data.length);
            e.setCrc(crc.getValue());
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        e.setTime(System.currentTimeMillis());
        zos.putNextEntry(e);
        zos.write(page.data);
        zos.closeEntry();

        int w = page.info != null ? page.info.width : 0;
        int h = page.info != null ? page.info.height : 0;
        dims.add(new int[]{w, h});
        rep.exactPages++;
    }

    @Override
    public void finish() throws Exception {
        if (finished) return;
        finished = true;
        if (opt.comicInfo) {
            byte[] xml = buildComicInfo();
            ZipEntry e = new ZipEntry("ComicInfo.xml");
            e.setMethod(ZipEntry.DEFLATED);
            e.setTime(System.currentTimeMillis());
            zos.putNextEntry(e);
            zos.write(xml);
            zos.closeEntry();
        }
        zos.finish();
        zos.flush();
    }

    private byte[] buildComicInfo() {
        StringBuilder b = new StringBuilder(256 + dims.size() * 64);
        b.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        b.append("<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"")
         .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        b.append("  <Title>").append(xmlEsc(meta.title)).append("</Title>\n");
        if (meta.author != null && !meta.author.isEmpty()) {
            b.append("  <Writer>").append(xmlEsc(meta.author)).append("</Writer>\n");
        }
        b.append("  <PageCount>").append(dims.size()).append("</PageCount>\n");
        b.append("  <LanguageISO>").append(xmlEsc(meta.language)).append("</LanguageISO>\n");
        b.append("  <Manga>YesAndRightToLeft</Manga>\n");
        b.append("  <Pages>\n");
        for (int i = 0; i < dims.size(); i++) {
            int[] d = dims.get(i);
            b.append("    <Page Image=\"").append(i).append('"');
            if (d[0] > 0) b.append(" ImageWidth=\"").append(d[0]).append('"');
            if (d[1] > 0) b.append(" ImageHeight=\"").append(d[1]).append('"');
            if (i == 0) b.append(" Type=\"FrontCover\"");
            b.append("/>\n");
        }
        b.append("  </Pages>\n</ComicInfo>\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String xmlEsc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    // 过滤 XML 1.0 不允许的控制字符
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') b.append(' ');
                    else b.append(c);
            }
        }
        return b.toString();
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

package com.manga.comicconv;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ZIP / CBZ 输入。
 * 重点处理中文条目名：很多老压缩包用 GBK 或 Shift_JIS 存名字且没打 UTF-8 标志位，
 * 这里会用三种编码各试一遍，挑乱码最少的那个。
 */
public class ZipSource implements ComicSource {

    private final File file;
    private ZipFile zf;
    private final List<String> names = new ArrayList<>();
    private boolean closed;

    public ZipSource(File file) {
        this.file = file;
    }

    @Override
    public void open() throws Exception {
        Charset[] candidates = {
                StandardCharsets.UTF_8,
                charsetOrNull("GBK"),
                charsetOrNull("Shift_JIS"),
                charsetOrNull("Big5"),
        };

        Charset best = null;
        int bestScore = Integer.MAX_VALUE;
        List<String> bestNames = null;

        for (Charset cs : candidates) {
            if (cs == null) continue;
            ZipFile probe = null;
            try {
                probe = new ZipFile(file, cs);
                List<String> got = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = probe.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    if (!ComicSource.isImageName(e.getName())) continue;
                    got.add(e.getName());
                }
                if (got.isEmpty()) continue;
                int score = Io.garbageScore(got);
                if (score < bestScore) {
                    bestScore = score;
                    best = cs;
                    bestNames = got;
                }
                if (score == 0) break;   // 完全干净，不用再试了
            } catch (Exception ignore) {
                // 这个编码打不开就换下一个
            } finally {
                if (probe != null) try { probe.close(); } catch (IOException ignore) {}
            }
        }

        if (best == null || bestNames == null || bestNames.isEmpty()) {
            throw new IOException("压缩包里没找到图：" + file.getName());
        }

        zf = new ZipFile(file, best);
        names.clear();
        names.addAll(bestNames);
        Collections.sort(names, ComicSource.NATURAL);
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
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
    public String title() { return ComicSource.stripExt(file.getName()); }

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

package com.manga.comicconv;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TAR / CBT 输入（含 .tar.gz / .tgz / .tar.bz2 / .tar.xz）。
 * tar 是纯顺序流，没有中央目录，所以：
 *  - open() 先扫一遍建索引
 *  - readPage 顺序读时沿用同一个流（常规转换就是顺序的）
 *  - 乱序读时重开流从头扫
 */
public class TarSource implements ComicSource {

    private final File file;
    private final List<String> names = new ArrayList<>();
    /** 条目在 tar 里的原始出现顺序，用于顺序读 */
    private final List<String> rawOrder = new ArrayList<>();

    private TarArchiveInputStream cur;
    /** cur 当前已经消费到 rawOrder 的哪个下标（-1 = 还没开始） */
    private int curPos = -1;
    private boolean closed;

    public TarSource(File file) {
        this.file = file;
    }

    private InputStream openRaw() throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16);
        byte[] h = Io.head(file, 8);
        try {
            if (Io.isGzip(h)) return new GzipCompressorInputStream(in, true);
            if (Io.isBzip2(h)) return new BZip2CompressorInputStream(in, true);
            if (Io.isXz(h)) return new XZCompressorInputStream(in, true);
            return in;
        } catch (NoClassDefFoundError e) {
            try { in.close(); } catch (IOException ignore) {}
            throw new IOException("这个压缩包用了我没打包进去的解码库（" + e.getMessage() + "）");
        } catch (IOException e) {
            try { in.close(); } catch (IOException ignore) {}
            throw e;
        }
    }

    private TarArchiveInputStream openTar() throws IOException {
        // tar 里的名字编码不统一，UTF-8 是最常见的；解不出来的字符会变成替换字符，
        // 但 tar 的 CBT 基本都是工具生成的 ASCII 名，影响很小。
        return new TarArchiveInputStream(openRaw(), "UTF-8");
    }

    @Override
    public void open() throws Exception {
        TarArchiveInputStream tin = openTar();
        try {
            TarArchiveEntry e;
            while ((e = tin.getNextTarEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n == null) continue;
                n = n.replace('\\', '/');
                if (!ComicSource.isImageName(n)) continue;
                rawOrder.add(n);
                names.add(n);
            }
        } finally {
            try { tin.close(); } catch (IOException ignore) {}
        }
        if (names.isEmpty()) {
            throw new IOException("压缩包里没找到图：" + file.getName());
        }
        Collections.sort(names, ComicSource.NATURAL);
    }

    @Override
    public int pageCount() { return names.size(); }

    @Override
    public String pageName(int i) { return names.get(i); }

    @Override
    public byte[] readPage(int i) throws Exception {
        String want = names.get(i);
        int target = rawOrder.indexOf(want);
        if (target < 0) throw new IOException("找不到这个文件：" + want);

        // 需要回退就重开流
        if (cur == null || curPos >= target) {
            closeCur();
            cur = openTar();
            curPos = -1;
        }
        TarArchiveEntry e;
        while ((e = cur.getNextTarEntry()) != null) {
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (n == null) continue;
            n = n.replace('\\', '/');
            if (!ComicSource.isImageName(n)) continue;
            curPos++;
            if (curPos == target) {
                return Io.readAll(cur, e.getSize());
            }
        }
        throw new IOException("读到结尾也没找到文件：" + want);
    }

    private void closeCur() {
        if (cur != null) {
            try { cur.close(); } catch (IOException ignore) {}
            cur = null;
        }
    }

    @Override
    public String title() {
        String n = file.getName();
        // 去掉 .tar.gz 这种双扩展名
        String low = n.toLowerCase();
        if (low.endsWith(".tar.gz") || low.endsWith(".tar.xz") || low.endsWith(".tar.bz2")) {
            return n.substring(0, n.length() - 7);
        }
        if (low.endsWith(".tgz")) return n.substring(0, n.length() - 4);
        return ComicSource.stripExt(n);
    }

    @Override
    public boolean lossless() { return true; }

    @Override
    public String lossyReason() { return null; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeCur();
    }
}

package com.manga.comicconv;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 7z / CB7 输入，基于 commons-compress 1.11。
 *
 * 这里刻意用 1.11 而不是新版：新版的 SevenZFile / SevenZArchiveEntry 引用了
 * java.util.stream 和 java.nio.file，那是 Android 8.0（API 26）才有的类，
 * 在 5.0~7.1 的机器上会直接 NoClassDefFoundError。1.11 是纯 Java 6 实现，
 * 全库不碰这些类，所以 7z 在低版本系统上也能用。
 *
 * 代价是 1.11 的 SevenZFile 没有 getInputStream(entry)，只能顺序 read()。
 * 好在我们本来就是从第 0 页往后按顺序读，所以用一个游标顺序推进即可；
 * 真要往回跳就重新打开一趟。
 */
public class SevenZSource implements ComicSource {

    private final File file;
    private SevenZFile sz;
    private final List<String> names = new ArrayList<>();
    /** 每张图在压缩包里的绝对条目序号，用于顺序定位 */
    private final List<Integer> ordinals = new ArrayList<>();
    private final List<Long> sizes = new ArrayList<>();
    /** 当前已定位到的绝对条目序号，-1 表示还没开始 */
    private int cursor = -1;
    private boolean closed;

    public SevenZSource(File file) {
        this.file = file;
    }

    @Override
    public void open() throws Exception {
        if (!Io.is7z(Io.head(file, 8))) {
            throw new IOException("这个文件不是 7z：" + file.getName());
        }
        try {
            sz = new SevenZFile(file);
        } catch (NoClassDefFoundError e) {
            throw new IOException("这个 7z 用了我没打包进去的压缩算法（" + e.getMessage()
                    + "）。先解压，或者转成 ZIP/CBZ：" + file.getName());
        } catch (Exception e) {
            throw new IOException("7z 打不开，可能加了密码或者文件坏了：" + file.getName(), e);
        }

        // 1.11 没有 getEntries()，只能顺序枚举
        int ord = 0;
        SevenZArchiveEntry e;
        while ((e = sz.getNextEntry()) != null) {
            if (!e.isDirectory()) {
                String n = e.getName();
                if (n != null) {
                    n = n.replace('\\', '/');
                    if (ComicSource.isImageName(n)) {
                        names.add(n);
                        ordinals.add(ord);
                        sizes.add(e.getSize());
                    }
                }
            }
            ord++;
        }
        if (names.isEmpty()) {
            throw new IOException("压缩包里没找到图：" + file.getName());
        }

        // 枚举把流读到末尾了，读正文要重开
        reopen();
    }

    private void reopen() throws IOException {
        if (sz != null) try { sz.close(); } catch (IOException ignore) {}
        try {
            sz = new SevenZFile(file);
        } catch (NoClassDefFoundError err) {
            throw new IOException("这个 7z 的压缩算法支持不了：" + err.getMessage());
        } catch (Exception ex) {
            throw new IOException("再打开一次也失败了：" + file.getName(), ex);
        }
        cursor = -1;
    }

    /** 把流定位到指定绝对条目，使其内容可读 */
    private void position(int target) throws IOException {
        if (cursor > target) reopen();   // 需要往回跳，重新开一趟
        while (cursor < target) {
            SevenZArchiveEntry e = sz.getNextEntry();
            if (e == null) throw new IOException("7z 里面的结构乱掉了");
            cursor++;
        }
    }

    @Override
    public int pageCount() { return names.size(); }

    @Override
    public String pageName(int i) { return names.get(i); }

    @Override
    public byte[] readPage(int i) throws Exception {
        long size = sizes.get(i);
        if (size <= 0 || size > Integer.MAX_VALUE - 8) {
            throw new IOException("第 " + (i + 1) + " 页尺寸不对（" + size + " 字节）");
        }
        byte[] out = new byte[(int) size];
        try {
            position(ordinals.get(i));
            int off = 0;
            while (off < out.length) {
                int n = sz.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off != out.length) {
                throw new IOException("第 " + (i + 1) + " 页只读到 " + off + "/" + out.length + " 字节");
            }
            return out;
        } catch (NoClassDefFoundError err) {
            throw new IOException("第 " + (i + 1) + " 页用了不支持的压缩算法（"
                    + err.getMessage() + "）");
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
        if (sz != null) try { sz.close(); } catch (IOException ignore) {}
    }
}

package com.manga.comicconv;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAR / CBR 输入，基于 junrar。
 * junrar 只支持 RAR4；RAR5 会在 open() 时给出明确提示而不是静默失败。
 */
public class RarSource implements ComicSource {

    private final File file;
    private Archive archive;
    private final List<String> names = new ArrayList<>();
    private final Map<String, FileHeader> headers = new HashMap<>();
    private boolean closed;

    public RarSource(File file) {
        this.file = file;
    }

    @Override
    public void open() throws Exception {
        byte[] h = Io.head(file, 16);
        if (Io.isRar5(h)) {
            throw new IOException("这是 RAR5，我只认 RAR4。\n"
                    + "先用别的工具解压，或者转成 ZIP/CBZ 再转：" + file.getName());
        }
        if (!Io.isRar4(h)) {
            throw new IOException("这个文件不是 RAR：" + file.getName());
        }

        try {
            archive = new Archive(file);
        } catch (Exception e) {
            throw new IOException("RAR 打不开，可能坏了或者加了密码：" + file.getName(), e);
        }

        try {
            if (archive.isEncrypted()) {
                throw new IOException("这个 RAR 有密码，转不了：" + file.getName());
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception ignore) {
            // isEncrypted 在某些包上会抛，忽略继续试
        }

        for (FileHeader fh : archive.getFileHeaders()) {
            if (fh.isDirectory()) continue;
            // junrar 1.0.1 没有 getFileName()，Unicode 名在 getFileNameW()
            String n = fh.getFileNameW();
            if (n == null || n.isEmpty()) n = fh.getFileNameString();
            if (n == null) continue;
            n = n.replace('\\', '/');
            if (!ComicSource.isImageName(n)) continue;
            names.add(n);
            headers.put(n, fh);
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
        String n = names.get(i);
        FileHeader fh = headers.get(n);
        if (fh == null) throw new IOException("找不到这个文件：" + n);
        InputStream in = null;
        try {
            in = archive.getInputStream(fh);
            return Io.readAll(in, fh.getFullUnpackSize());
        } catch (Exception e) {
            throw new IOException("解压失败：" + n, e);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignore) {}
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
        if (archive != null) try { archive.close(); } catch (IOException ignore) {}
    }
}

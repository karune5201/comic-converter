package com.manga.comicconv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 输出成图片文件夹：每页一个文件，原始字节原样写出。
 * 这是最直白的无损形式 —— 没有任何容器包装，拿去给别的工具用也方便。
 */
public class FolderWriter implements ComicWriter {

    private final File outDir;
    private final Report rep = new Report();
    private boolean closed, finished;
    private int written;

    public FolderWriter(File outDir) {
        this.outDir = outDir;
    }

    @Override
    public void begin(Meta meta) throws Exception {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("输出目录建不了：" + outDir.getAbsolutePath());
        }
        if (!outDir.isDirectory()) {
            throw new IOException("这个输出路径不是文件夹：" + outDir.getAbsolutePath());
        }
    }

    @Override
    public void addPage(Page page) throws Exception {
        if (page == null || page.data == null || page.data.length == 0) {
            rep.failedPages++;
            rep.note("第 " + (page == null ? -1 : page.index + 1) + " 页是空的，跳过");
            return;
        }
        File f = new File(outDir, page.outName());
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(page.data);
        } finally {
            o.close();
        }
        written++;
        rep.exactPages++;
    }

    @Override
    public void finish() {
        if (finished) return;
        finished = true;
    }

    public int writtenCount() { return written; }

    @Override
    public Report report() { return rep; }

    @Override
    public void close() {
        closed = true;
    }
}

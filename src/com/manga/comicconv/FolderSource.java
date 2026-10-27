package com.manga.comicconv;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 目录里的图片当成一本书。只扫一层，子目录由上层 Scanner 当作另一本处理。 */
public class FolderSource implements ComicSource {

    private final File dir;
    private final List<String> names = new ArrayList<>();
    private boolean closed;

    public FolderSource(File dir) {
        this.dir = dir;
    }

    @Override
    public void open() throws Exception {
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && ComicSource.isImageName(f.getName())) {
                    names.add(f.getName());
                }
            }
        }
        if (names.isEmpty()) {
            throw new IOException("这个文件夹里没找到图：" + dir.getName());
        }
        Collections.sort(names, ComicSource.NATURAL);
    }

    @Override
    public int pageCount() { return names.size(); }

    @Override
    public String pageName(int i) { return names.get(i); }

    @Override
    public byte[] readPage(int i) throws Exception {
        File f = new File(dir, names.get(i));
        InputStream in = new FileInputStream(f);
        try {
            return Io.readAll(in, f.length());
        } finally {
            in.close();
        }
    }

    @Override
    public String title() { return dir.getName(); }

    @Override
    public boolean lossless() { return true; }

    @Override
    public String lossyReason() { return null; }

    @Override
    public void close() { closed = true; }
}

package com.manga.comicconv;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 扫目录，找出里面有哪些"书"（压缩包 / PDF / EPUB / 图片目录）。 */
public final class Scanner {

    /** 一本书 */
    public static class Item {
        public File file;
        /** 目录型（里面直接放图片） */
        public boolean isFolder;
        public String kind = "";
        public long size;
        /** UI 上是否勾选 */
        public boolean selected = true;

        public String name() {
            return file.getName();
        }

        public String sizeText() {
            if (isFolder) return "目录";
            double mb = size / 1024.0 / 1024.0;
            if (mb >= 1024) return String.format("%.1f GB", mb / 1024);
            if (mb >= 1) return String.format("%.0f MB", mb);
            return String.format("%.0f KB", size / 1024.0);
        }
    }

    private Scanner() {}

    /** 按扩展名判断能不能当输入 */
    public static String kindOf(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".cbz") || n.endsWith(".zip")) return "CBZ/ZIP";
        if (n.endsWith(".cbr") || n.endsWith(".rar")) return "CBR/RAR";
        if (n.endsWith(".cb7") || n.endsWith(".7z")) return "CB7/7z";
        if (n.endsWith(".cbt") || n.endsWith(".tar") || n.endsWith(".tar.gz")
                || n.endsWith(".tgz") || n.endsWith(".tar.bz2") || n.endsWith(".tar.xz")) {
            return "CBT/TAR";
        }
        if (n.endsWith(".pdf")) return "PDF";
        if (n.endsWith(".epub")) return "EPUB";
        if (n.endsWith(".mobi") || n.endsWith(".azw3") || n.endsWith(".azw")
                || n.endsWith(".prc")) {
            return "MOBI/AZW3";
        }
        return null;
    }

    /**
     * 扫目录。
     * recursive 为 true 时递归子目录；子目录里直接放图片的会被当成一本书。
     */
    public static List<Item> scan(File dir, boolean recursive) {
        List<Item> out = new ArrayList<>();
        scanInto(dir, recursive, out, 0);
        Collections.sort(out, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return ComicSource.Comparator.natural(a.file.getPath(), b.file.getPath());
            }
        });
        return out;
    }

    private static void scanInto(File dir, boolean recursive, List<Item> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 12 || out.size() > 5000) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;

        boolean hasLooseImages = false;
        for (File f : fs) {
            if (f.isFile() && ComicSource.isImageName(f.getName())) {
                hasLooseImages = true;
                break;
            }
        }
        if (hasLooseImages) {
            Item it = new Item();
            it.file = dir;
            it.isFolder = true;
            it.kind = "图片目录";
            out.add(it);
        }

        for (File f : fs) {
            if (f.isFile()) {
                String k = kindOf(f);
                if (k != null) {
                    Item it = new Item();
                    it.file = f;
                    it.kind = k;
                    it.size = f.length();
                    out.add(it);
                }
            } else if (f.isDirectory() && recursive) {
                scanInto(f, true, out, depth + 1);
            }
        }
    }

    /** 按文件内容/扩展名建对应的输入实现 */
    public static ComicSource openSource(Item item, int pdfRasterDpi) {
        if (item.isFolder) return new FolderSource(item.file);
        File f = item.file;
        byte[] h = Io.head(f, 16);
        // 魔数优先：扩展名写错的文件也能认出来
        if (Io.isZip(h)) {
            String n = f.getName().toLowerCase();
            return n.endsWith(".epub") ? new EpubSource(f) : new ZipSource(f);
        }
        if (Io.isRar4(h) || Io.isRar5(h)) return new RarSource(f);
        if (Io.is7z(h)) return new SevenZSource(f);
        if (Io.isPdf(h)) return new PdfSource(f, pdfRasterDpi);
        if (Io.isMobi(h)) return new MobiSource(f);
        if (Io.isGzip(h) || Io.isBzip2(h) || Io.isXz(h)) return new TarSource(f);
        // 魔数认不出来，按扩展名猜
        String n = f.getName().toLowerCase();
        if (n.endsWith(".cbt") || n.endsWith(".tar")) return new TarSource(f);
        if (n.endsWith(".epub")) return new EpubSource(f);
        if (n.endsWith(".mobi") || n.endsWith(".azw3") || n.endsWith(".azw")
                || n.endsWith(".prc")) {
            return new MobiSource(f);
        }
        return new ZipSource(f);
    }
}

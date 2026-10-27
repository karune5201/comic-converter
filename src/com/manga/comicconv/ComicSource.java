package com.manga.comicconv;

import java.io.Closeable;
import java.util.List;

/**
 * 输入端统一接口。实现负责按页顺序给出原始字节。
 * 约定：pageCount() 在 open() 后可用；readPage(i) 返回输入文件里的原始字节，不重编码。
 */
public interface ComicSource extends Closeable {
    /** 打开并建立页索引 */
    void open() throws Exception;

    /** 页数 */
    int pageCount();

    /** 第 i 页的条目名（用于命名/排序回溯） */
    String pageName(int i);

    /** 读第 i 页原始字节 */
    byte[] readPage(int i) throws Exception;

    /**
     * 第 i 页是不是由多条原始图片拼成的（目前只有部分扫描版 PDF 会这样）。
     * 返回 null 表示就是普通单图页，走 readPage 即可。
     * 返回非空时，调用方应当按输出格式决定是摆在同一页还是拆成连续几页。
     */
    default java.util.List<Strip> readStrips(int i) throws Exception {
        return null;
    }

    /** 书名（不含扩展名），用于输出文件命名 */
    String title();

    /**
     * 这个来源是否能保证字节级无损。
     * PDF 栅格化回退之类的有损路径要返回 false，UI 会提示。
     */
    boolean lossless();

    /** 有损/降级原因，lossless() 为 false 时给出可读说明 */
    String lossyReason();

    /** 页名自然排序用的比较器（数字按数值比，不按字典序） */
    Comparator NATURAL = new Comparator();

    class Comparator implements java.util.Comparator<String> {
        @Override
        public int compare(String a, String b) {
            return natural(a, b);
        }

        public static int natural(String a, String b) {
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i), cb = b.charAt(j);
                boolean da = ca >= '0' && ca <= '9';
                boolean db = cb >= '0' && cb <= '9';
                if (da && db) {
                    int si = i, sj = j;
                    while (i < a.length() && a.charAt(i) >= '0' && a.charAt(i) <= '9') i++;
                    while (j < b.length() && b.charAt(j) >= '0' && b.charAt(j) <= '9') j++;
                    String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                    String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                    if (na.length() != nb.length()) return na.length() - nb.length();
                    int c = na.compareTo(nb);
                    if (c != 0) return c;
                } else {
                    char la = Character.toLowerCase(ca), lb = Character.toLowerCase(cb);
                    if (la != lb) return la - lb;
                    i++; j++;
                }
            }
            return (a.length() - i) - (b.length() - j);
        }
    }

    /** 判断条目名是否是支持的图片 */
    static boolean isImageName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        String base = slash >= 0 ? n.substring(slash + 1) : n;
        // 跳过 macOS / Windows 垃圾条目
        if (base.startsWith("._") || base.equals(".ds_store") || base.equals("thumbs.db")) return false;
        if (n.contains("__macosx/")) return false;
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".jpe")
                || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".webp")
                || n.endsWith(".bmp") || n.endsWith(".heic") || n.endsWith(".heif")
                || n.endsWith(".avif") || n.endsWith(".jfif") || n.endsWith(".jxl");
    }

    /** 去掉扩展名 */
    static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        int s = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (d > s) return name.substring(s + 1, d);
        return s >= 0 ? name.substring(s + 1) : name;
    }

    List<String> EMPTY = java.util.Collections.emptyList();
}

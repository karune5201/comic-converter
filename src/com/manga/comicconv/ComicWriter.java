package com.manga.comicconv;

import java.io.Closeable;

/**
 * 输出端统一接口。实现负责把 Page 的原始字节写进目标容器。
 * 约定：优先原字节直嵌；做不到的才重编码，并通过 Report 上报。
 */
public interface ComicWriter extends Closeable {
    /** 开始写一本书 */
    void begin(Meta meta) throws Exception;

    /** 写一页。pages 按 index 升序调用 */
    void addPage(Page page) throws Exception;

    /** 收尾（写索引 / trailer / OPF 等） */
    void finish() throws Exception;

    /** 本次输出的无损情况统计 */
    Report report();

    /** 书的元信息 */
    class Meta {
        public String title = "Untitled";
        public String author = "";
        public String language = "zh";
        public int pageCount;
        /** 输出路径，仅用于日志 */
        public String outPath = "";
    }

    /** 每页的处理结果统计 */
    class Report {
        /** 原字节直嵌的页数 */
        public int exactPages;
        /** 重编码（无损重编码，像素一致）的页数 */
        public int recodedPages;
        /** 有损处理的页数（降质/缩放） */
        public int lossyPages;
        /** 失败跳过的页数 */
        public int failedPages;
        /** 可读说明，逐条追加 */
        public final StringBuilder notes = new StringBuilder();
        private int noteCount;
        /** 同类说明最多留这么多条，避免一本几百页刷爆日志 */
        private static final int MAX_NOTES = 8;

        public void note(String s) {
            noteCount++;
            if (noteCount > MAX_NOTES) {
                if (noteCount == MAX_NOTES + 1) {
                    notes.append('\n').append("（后面还有类似的，就不重复了）");
                }
                return;
            }
            if (notes.length() > 0) notes.append('\n');
            notes.append(s);
        }

        /** 不计入折叠上限的总结性说明 */
        public void noteAlways(String s) {
            if (notes.length() > 0) notes.append('\n');
            notes.append(s);
        }

        public boolean byteExact() {
            return lossyPages == 0 && recodedPages == 0 && failedPages == 0;
        }

        public boolean pixelExact() {
            return lossyPages == 0 && failedPages == 0;
        }

        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append("原封不动 ").append(exactPages).append(" 页");
            if (recodedPages > 0) b.append("，画质不变 ").append(recodedPages).append(" 页");
            if (lossyPages > 0) b.append("，有损 ").append(lossyPages).append(" 页");
            if (failedPages > 0) b.append("，失败 ").append(failedPages).append(" 页");
            return b.toString();
        }
    }
}

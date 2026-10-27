package com.manga.comicconv;

/**
 * 一页里的一条"拼片"。
 *
 * 有些扫描版 PDF 把一整页切成几条横条分别存成 JPEG，再拼在同一页上。
 * 为了保住原始字节，我们不去解码重拼，而是：
 *  - 输出 PDF 时：把几条原始 JPEG 按原位置摆在同一页上（字节级无损）
 *  - 输出 CBZ/EPUB/MOBI 时：按原字节拆成连续几页（字节级无损）
 *
 * x/y/w/h 是归一化页面坐标（0..1），y 轴自底向上（沿用 PDF 习惯）。
 */
public class Strip {
    public byte[] data;
    public ImageInfo info;
    public float x, y, w, h;

    public Strip(byte[] data, float x, float y, float w, float h) {
        this.data = data;
        this.info = ImageInfo.parse(data);
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }
}

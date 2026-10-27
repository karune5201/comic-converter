package com.manga.comicconv;

/**
 * 一页漫画的原始字节 + 解析出的基本信息。
 * data 永远是输入文件里的原始字节，不做任何重编码。
 */
public class Page {
    /** 页在书里的序号，从 0 开始 */
    public int index;
    /** 原始条目名（压缩包内路径 / 文件名），用于排序和输出命名 */
    public String name;
    /** 原始字节，未做任何处理 */
    public byte[] data;
    /** 图像信息（宽高、类型），由 ImageInfo.parse 填充 */
    public ImageInfo info;

    public Page(int index, String name, byte[] data) {
        this.index = index;
        this.name = name;
        this.data = data;
        this.info = ImageInfo.parse(data);
    }

    /** 输出用的扩展名，带点，例如 ".jpg" */
    public String ext() {
        if (info != null && info.ext != null) return info.ext;
        int d = name.lastIndexOf('.');
        return d >= 0 ? name.substring(d).toLowerCase() : ".bin";
    }

    /** 输出用的规范文件名，例如 "p0001.jpg" */
    public String outName() {
        return String.format("p%04d%s", index + 1, ext());
    }
}

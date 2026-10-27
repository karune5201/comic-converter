package com.manga.comicconv;

import android.graphics.BitmapFactory;

/**
 * 纯字节头解析出图像宽高与类型，不解码像素（省内存）。
 * 解析不出来的才退回 BitmapFactory 的 bounds-only 解码。
 */
public class ImageInfo {
    public static final int T_UNKNOWN = 0;
    public static final int T_JPEG = 1;
    public static final int T_PNG = 2;
    public static final int T_GIF = 3;
    public static final int T_WEBP = 4;
    public static final int T_BMP = 5;
    public static final int T_HEIF = 6;

    public int type = T_UNKNOWN;
    public int width, height;
    public String ext = ".bin";
    public String mime = "application/octet-stream";

    /** JPEG: SOF 的分量数（1=灰度, 3=YCbCr, 4=CMYK） */
    public int jpegComponents;
    /** JPEG: 是否 progressive（SOF2） */
    public boolean jpegProgressive;

    /** PNG: IHDR 字段 */
    public int pngBitDepth, pngColorType, pngInterlace;
    /** PNG: 是否含 tRNS 块 */
    public boolean pngHasTrns;

    public boolean isJpeg() { return type == T_JPEG; }
    public boolean isPng() { return type == T_PNG; }

    /** PNG 是否能把 IDAT 原样塞进 PDF（无 alpha、非隔行、非调色板） */
    public boolean pngDirectEmbeddable() {
        if (type != T_PNG) return false;
        if (pngInterlace != 0) return false;
        if (pngHasTrns) return false;
        // 0=灰度 2=真彩 3=调色板 4=灰度+alpha 6=真彩+alpha
        if (pngColorType == 4 || pngColorType == 6) return false;
        if (pngColorType == 3) return false;   // 调色板走单独分支，这里不算
        return pngBitDepth == 8 || pngBitDepth == 16 || pngBitDepth == 1
                || pngBitDepth == 2 || pngBitDepth == 4;
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static int be16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private static int le16(byte[] b, int o) {
        return ((b[o + 1] & 0xff) << 8) | (b[o] & 0xff);
    }

    private static int le32(byte[] b, int o) {
        return ((b[o + 3] & 0xff) << 24) | ((b[o + 2] & 0xff) << 16)
                | ((b[o + 1] & 0xff) << 8) | (b[o] & 0xff);
    }

    public static ImageInfo parse(byte[] d) {
        ImageInfo r = new ImageInfo();
        if (d == null || d.length < 12) return r;

        // ---- PNG ----
        if ((d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && (d[4] & 0xff) == 0x0d && (d[5] & 0xff) == 0x0a) {
            r.type = T_PNG; r.ext = ".png"; r.mime = "image/png";
            // IHDR 固定在 offset 8 之后
            if (d.length >= 33) {
                r.width = be32(d, 16);
                r.height = be32(d, 20);
                r.pngBitDepth = d[24] & 0xff;
                r.pngColorType = d[25] & 0xff;
                r.pngInterlace = d[28] & 0xff;
            }
            r.pngHasTrns = findPngChunk(d, "tRNS") >= 0;
            return r;
        }

        // ---- JPEG ----
        if ((d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) {
            r.type = T_JPEG; r.ext = ".jpg"; r.mime = "image/jpeg";
            parseJpeg(d, r);
            return r;
        }

        // ---- GIF ----
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            r.type = T_GIF; r.ext = ".gif"; r.mime = "image/gif";
            r.width = le16(d, 6);
            r.height = le16(d, 8);
            return r;
        }

        // ---- WebP (RIFF....WEBP) ----
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d.length > 16 && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            r.type = T_WEBP; r.ext = ".webp"; r.mime = "image/webp";
            parseWebp(d, r);
            return r;
        }

        // ---- BMP ----
        if (d[0] == 'B' && d[1] == 'M' && d.length >= 26) {
            r.type = T_BMP; r.ext = ".bmp"; r.mime = "image/bmp";
            r.width = le32(d, 18);
            r.height = Math.abs(le32(d, 22));
            return r;
        }

        // ---- HEIF / AVIF (ftyp box) ----
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            String brand = new String(d, 8, 4);
            if (brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1")
                    || brand.startsWith("avi") || brand.equals("av01")) {
                r.type = T_HEIF;
                r.ext = brand.startsWith("avi") || brand.equals("av01") ? ".avif" : ".heic";
                r.mime = r.ext.equals(".avif") ? "image/avif" : "image/heif";
                fillBoundsViaDecoder(d, r);
                return r;
            }
        }

        // 都不认，交给系统解码器试一次
        fillBoundsViaDecoder(d, r);
        return r;
    }

    private static void parseJpeg(byte[] d, ImageInfo r) {
        int p = 2;
        while (p + 3 < d.length) {
            if ((d[p] & 0xff) != 0xFF) { p++; continue; }
            int marker = d[p + 1] & 0xff;
            if (marker == 0xFF) { p++; continue; }
            // 无长度字段的标记
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) { p += 2; continue; }
            if (p + 4 > d.length) break;
            int len = be16(d, p + 2);
            if (len < 2) break;
            // SOF0..SOF15（跳过 DHT=C4, JPG=C8, DAC=CC）
            if (marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (p + 9 < d.length) {
                    r.height = be16(d, p + 5);
                    r.width = be16(d, p + 7);
                    r.jpegComponents = d[p + 9] & 0xff;
                    r.jpegProgressive = (marker == 0xC2 || marker == 0xC6 || marker == 0xCA
                            || marker == 0xCE);
                }
                return;
            }
            p += 2 + len;
        }
        if (r.width == 0) fillBoundsViaDecoder(d, r);
    }

    private static void parseWebp(byte[] d, ImageInfo r) {
        int p = 12;
        while (p + 8 <= d.length) {
            String fourcc = new String(d, p, 4);
            int size = le32(d, p + 4);
            int body = p + 8;
            if (fourcc.equals("VP8X") && body + 9 <= d.length) {
                r.width = 1 + (((d[body + 4] & 0xff)) | ((d[body + 5] & 0xff) << 8)
                        | ((d[body + 6] & 0xff) << 16));
                r.height = 1 + (((d[body + 7] & 0xff)) | ((d[body + 8] & 0xff) << 8)
                        | ((d[body + 9] & 0xff) << 16));
                return;
            } else if (fourcc.equals("VP8 ") && body + 10 <= d.length) {
                r.width = le16(d, body + 6) & 0x3fff;
                r.height = le16(d, body + 8) & 0x3fff;
                return;
            } else if (fourcc.equals("VP8L") && body + 5 <= d.length) {
                int bits = le32(d, body + 1);
                r.width = (bits & 0x3FFF) + 1;
                r.height = ((bits >> 14) & 0x3FFF) + 1;
                return;
            }
            p = body + size + (size & 1);
        }
        if (r.width == 0) fillBoundsViaDecoder(d, r);
    }

    private static void fillBoundsViaDecoder(byte[] d, ImageInfo r) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (o.outWidth > 0) {
                r.width = o.outWidth;
                r.height = o.outHeight;
                if (o.outMimeType != null) {
                    r.mime = o.outMimeType;
                    if (r.ext.equals(".bin")) {
                        if (o.outMimeType.contains("jpeg")) r.ext = ".jpg";
                        else if (o.outMimeType.contains("png")) r.ext = ".png";
                        else if (o.outMimeType.contains("webp")) r.ext = ".webp";
                        else if (o.outMimeType.contains("gif")) r.ext = ".gif";
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** 找 PNG 块，返回块数据起始 offset，没找到返回 -1 */
    public static int findPngChunk(byte[] d, String type) {
        int p = 8;
        byte[] t = type.getBytes();
        while (p + 8 <= d.length) {
            int len = be32(d, p);
            if (len < 0 || p + 12 + len > d.length) return -1;
            if (d[p + 4] == t[0] && d[p + 5] == t[1] && d[p + 6] == t[2] && d[p + 7] == t[3]) {
                return p + 8;
            }
            p += 12 + len;
        }
        return -1;
    }

    /** 取 PNG 块长度，配合 findPngChunk 用 */
    public static int pngChunkLen(byte[] d, int dataOffset) {
        return be32(d, dataOffset - 8);
    }

    /** 把所有 IDAT 块的数据拼起来（PNG 允许 IDAT 分多块） */
    public static byte[] pngIdat(byte[] d) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int p = 8;
        while (p + 8 <= d.length) {
            int len = be32(d, p);
            if (len < 0 || p + 12 + len > d.length) break;
            if (d[p + 4] == 'I' && d[p + 5] == 'D' && d[p + 6] == 'A' && d[p + 7] == 'T') {
                out.write(d, p + 8, len);
            }
            p += 12 + len;
        }
        return out.toByteArray();
    }
}

package com.manga.comicconv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;

/** 压缩与位图工具。所有重编码路径都走这里，方便统一统计无损性。 */
public final class Img {
    private Img() {}

    /** zlib 压缩（PDF FlateDecode / PNG IDAT 都用这个） */
    public static byte[] deflate(byte[] data, int level) {
        Deflater def = new Deflater(level);
        try {
            def.setInput(data);
            def.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
            byte[] buf = new byte[65536];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n > 0) out.write(buf, 0, n);
                else if (def.needsInput()) break;
            }
            return out.toByteArray();
        } finally {
            def.end();
        }
    }

    public static byte[] deflate(byte[] data) {
        return deflate(data, Deflater.BEST_COMPRESSION);
    }

    /** 解码成位图。失败返回 null。优先 ARGB_8888 保证取色稳定。 */
    public static Bitmap decode(byte[] data) {
        Bitmap bm = tryDecode(data, Bitmap.Config.ARGB_8888, 1);
        if (bm == null) {
            // 32 位设备堆只有 200~500MB，大图 ARGB_8888 会不够；
            // 退到 RGB_565 只占一半内存（仅在这条重编码路径上，不涉及无损直嵌）
            bm = tryDecode(data, Bitmap.Config.RGB_565, 1);
        }
        return bm;
    }

    private static Bitmap tryDecode(byte[] data, Bitmap.Config cfg, int sample) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = cfg;
            o.inSampleSize = sample;
            o.inMutable = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 缩放解码：maxEdge <= 0 表示不缩放。用于设备适配版（有损）。 */
    public static Bitmap decodeScaled(byte[] data, int maxEdge) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, b);
            int w = b.outWidth, h = b.outHeight;
            if (w <= 0 || h <= 0) return decode(data);
            int sample = 1;
            if (maxEdge > 0) {
                int longEdge = Math.max(w, h);
                while (longEdge / (sample * 2) >= maxEdge) sample *= 2;
            }
            Bitmap bm = tryDecode(data, Bitmap.Config.ARGB_8888, sample);
            if (bm == null) {
                // 低内存设备兜底
                bm = tryDecode(data, Bitmap.Config.RGB_565, sample);
            }
            if (bm == null || maxEdge <= 0) return bm;
            int longEdge = Math.max(bm.getWidth(), bm.getHeight());
            if (longEdge <= maxEdge) return bm;
            float s = (float) maxEdge / longEdge;
            int nw = Math.max(1, Math.round(bm.getWidth() * s));
            int nh = Math.max(1, Math.round(bm.getHeight() * s));
            Bitmap out = Bitmap.createScaledBitmap(bm, nw, nh, true);
            if (out != bm) bm.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 位图是否真的含半透明像素（有 alpha 通道不代表真用了） */
    public static boolean hasRealAlpha(Bitmap bm) {
        if (!bm.hasAlpha()) return false;
        int w = bm.getWidth(), h = bm.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bm.getPixels(row, 0, w, 0, y, w, 1);
            for (int i = 0; i < w; i++) {
                if ((row[i] >>> 24) != 0xff) return true;
            }
        }
        return false;
    }

    /** 取 RGB 原始样本，3 字节/像素，行优先。PDF DeviceRGB 直接用。 */
    public static byte[] rgbRaw(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        byte[] out = new byte[w * h * 3];
        int[] row = new int[w];
        int p = 0;
        for (int y = 0; y < h; y++) {
            bm.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                out[p++] = (byte) ((c >> 16) & 0xff);
                out[p++] = (byte) ((c >> 8) & 0xff);
                out[p++] = (byte) (c & 0xff);
            }
        }
        return out;
    }

    /** 取 alpha 通道，1 字节/像素。PDF SMask 用。 */
    public static byte[] alphaRaw(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        byte[] out = new byte[w * h];
        int[] row = new int[w];
        int p = 0;
        for (int y = 0; y < h; y++) {
            bm.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) out[p++] = (byte) (row[x] >>> 24);
        }
        return out;
    }

    /** 整图是否为灰度（R==G==B），灰度图走 DeviceGray 省 2/3 体积 */
    public static boolean isGrayscale(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bm.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                if (r != g || g != b) return false;
            }
        }
        return true;
    }

    /** 取灰度样本，1 字节/像素 */
    public static byte[] grayRaw(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        byte[] out = new byte[w * h];
        int[] row = new int[w];
        int p = 0;
        for (int y = 0; y < h; y++) {
            bm.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) out[p++] = (byte) ((row[x] >> 16) & 0xff);
        }
        return out;
    }

    /** 无损重编码成 PNG（像素一致，字节不同） */
    public static byte[] pngLossless(Bitmap bm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    /** 有损重编码成 JPEG，仅设备适配版用 */
    public static byte[] jpeg(Bitmap bm, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap src = bm;
        if (bm.hasAlpha()) {
            // JPEG 不支持 alpha，先铺白底
            Bitmap flat = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(flat);
            c.drawColor(0xffffffff);
            c.drawBitmap(bm, 0, 0, null);
            src = flat;
        }
        src.compress(Bitmap.CompressFormat.JPEG, quality, out);
        if (src != bm) src.recycle();
        return out.toByteArray();
    }

    /**
     * 由原始样本直接编码 PNG（不经 Bitmap，省一次解码和一份内存）。
     * comps: 1=灰度 3=RGB；bitDepth: 8 或 16。
     */
    public static byte[] encodePng(byte[] samples, int w, int h, int comps, int bitDepth) {
        int colorType = comps == 1 ? 0 : 2;
        int bytesPerPx = comps * (bitDepth / 8);
        int stride = w * bytesPerPx;
        // 加 PNG 行过滤器字节（0 = None），逐行拼
        byte[] raw = new byte[(stride + 1) * h];
        for (int y = 0; y < h; y++) {
            int src = y * stride;
            int dst = y * (stride + 1);
            raw[dst] = 0;
            int n = Math.min(stride, samples.length - src);
            if (n > 0) System.arraycopy(samples, src, raw, dst + 1, n);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 128);
        try {
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
            byte[] ihdr = new byte[13];
            putInt(ihdr, 0, w);
            putInt(ihdr, 4, h);
            ihdr[8] = (byte) bitDepth;
            ihdr[9] = (byte) colorType;
            ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
            writeChunk(out, "IHDR", ihdr);
            writeChunk(out, "IDAT", deflate(raw));
            writeChunk(out, "IEND", new byte[0]);
        } catch (java.io.IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data)
            throws java.io.IOException {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c);
    }

    /** 安全回收 */
    public static void recycle(Bitmap bm) {
        if (bm != null && !bm.isRecycled()) {
            try { bm.recycle(); } catch (Throwable ignore) {}
        }
    }
}

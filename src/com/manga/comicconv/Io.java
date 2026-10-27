package com.manga.comicconv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 小工具：流读取、魔数探测、文件名编码判别。 */
public final class Io {
    private Io() {}

    /** 把流读完。expected > 0 时用它预分配。 */
    public static byte[] readAll(InputStream in, long expected) throws IOException {
        int cap = expected > 0 && expected < Integer.MAX_VALUE - 8
                ? (int) expected : 65536;
        ByteArrayOutputStream out = new ByteArrayOutputStream(cap);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** 跳过 n 字节（InputStream.skip 不保证跳够） */
    public static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[1 << 16];
        while (left > 0) {
            long s = in.skip(left);
            if (s > 0) { left -= s; continue; }
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r <= 0) throw new IOException("数据提前断了");
            left -= r;
        }
    }

    /** 读文件头若干字节，用于魔数判断 */
    public static byte[] head(File f, int n) {
        byte[] b = new byte[n];
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int got = 0;
            while (got < n) {
                int r = in.read(b, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            if (got < n) {
                byte[] s = new byte[got];
                System.arraycopy(b, 0, s, 0, got);
                return s;
            }
            return b;
        } catch (IOException e) {
            return new byte[0];
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignore) {}
        }
    }

    public static boolean startsWith(byte[] d, int... bytes) {
        if (d.length < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) {
            if ((d[i] & 0xff) != (bytes[i] & 0xff)) return false;
        }
        return true;
    }

    /** RAR4 魔数 */
    public static boolean isRar4(byte[] h) {
        return startsWith(h, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00);
    }

    /** RAR5 魔数 */
    public static boolean isRar5(byte[] h) {
        return startsWith(h, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00);
    }

    public static boolean isZip(byte[] h) {
        return startsWith(h, 'P', 'K', 0x03, 0x04)
                || startsWith(h, 'P', 'K', 0x05, 0x06)
                || startsWith(h, 'P', 'K', 0x07, 0x08);
    }

    public static boolean is7z(byte[] h) {
        return startsWith(h, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C);
    }

    public static boolean isGzip(byte[] h) { return startsWith(h, 0x1F, 0x8B); }

    public static boolean isBzip2(byte[] h) { return startsWith(h, 'B', 'Z', 'h'); }

    public static boolean isXz(byte[] h) {
        return startsWith(h, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00);
    }

    public static boolean isPdf(byte[] h) { return startsWith(h, '%', 'P', 'D', 'F'); }

    /** MOBI / AZW3 / AZW / PRC：PalmDB 头偏移 60 处是 type+creator */
    public static boolean isMobi(byte[] h) {
        if (h.length < 68) return false;
        String tc = new String(h, 60, 8, java.nio.charset.StandardCharsets.US_ASCII);
        return tc.equals("BOOKMOBI") || tc.equals("TEXtREAd");
    }

    /**
     * 给一串文件名打分：替换字符和控制字符越多分越高。
     * 用来在 UTF-8 / GBK / Shift_JIS 之间挑最靠谱的那个。
     */
    public static int garbageScore(Iterable<String> names) {
        int score = 0;
        for (String n : names) {
            for (int i = 0; i < n.length(); i++) {
                char c = n.charAt(i);
                if (c == '\uFFFD') score += 10;
                else if (c < 0x20) score += 5;
                    // CJK 兼容区 / 私用区出现通常意味着解码错了
                else if (c >= 0xE000 && c <= 0xF8FF) score += 3;
            }
        }
        return score;
    }
}

package com.manga.comicconv;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MOBI / AZW3 / AZW / PRC 输入。
 *
 * 做法：直接扫 PalmDB 的记录表，把记录内容以图片魔数开头的那些挑出来，
 * 原字节就是页面图片 —— 不解码、不重编码。
 *
 * 几个坑都处理了：
 *  - 优先用 MOBI 头的 firstImageIndex..lastContentRecord 界定图片区；
 *    头不可靠（KF8/AZW3 常见）就退回全库扫魔数。
 *  - AZW3 常把 MOBI6 和 KF8 两份都塞在一个文件里，同一张图会出现两次 → 按内容哈希去重。
 *  - EXTH 里的封面缩略图（202）是封面的缩小重编码版，哈希不同但内容重复 → 按记录号排除。
 *  - DRM 加密的直接给可读提示，而不是吐一堆乱码。
 */
public class MobiSource implements ComicSource {

    private final File file;
    private RandomAccessFile raf;
    private boolean closed;

    /** 每张图在文件里的位置 [offset, length] */
    private final List<long[]> images = new ArrayList<>();
    private String bookTitle;

    public MobiSource(File file) {
        this.file = file;
    }

    @Override
    public void open() throws Exception {
        byte[] head = Io.head(file, 78);
        if (!Io.isMobi(head)) {
            throw new IOException("这个文件不是 MOBI/AZW3：" + file.getName());
        }
        raf = new RandomAccessFile(file, "r");
        long fileLen = raf.length();

        int numRecords = u16(head, 76);
        if (numRecords <= 1) throw new IOException("MOBI 的目录表是空的：" + file.getName());

        // 记录信息表：每条 8 字节，前 4 字节是该记录的绝对偏移
        byte[] table = new byte[8 * numRecords];
        raf.seek(78);
        raf.readFully(table);
        long[] offs = new long[numRecords + 1];
        for (int i = 0; i < numRecords; i++) {
            offs[i] = u32(table, 8 * i);
        }
        offs[numRecords] = fileLen;
        // 偏移必须单调，不然文件是坏的
        for (int i = 0; i < numRecords; i++) {
            if (offs[i] < 0 || offs[i] > fileLen || (i > 0 && offs[i] < offs[i - 1])) {
                throw new IOException("MOBI 里面的位置乱了，文件可能坏了：" + file.getName());
            }
        }

        // ---- 解析记录 0：PalmDOC 头 + MOBI 头 + EXTH ----
        int firstImage = -1, lastContent = -1, thumbRecord = -1, coverRecord = -1;
        int rec0Len = (int) Math.min(offs[1] - offs[0], 1 << 16);
        if (rec0Len > 16) {
            byte[] r0 = new byte[rec0Len];
            raf.seek(offs[0]);
            raf.readFully(r0);

            int encryption = u16(r0, 12);
            if (encryption != 0) {
                throw new IOException("这本 MOBI 有 DRM 加密，图取不出来：" + file.getName());
            }

            if (rec0Len > 16 + 24 && r0[16] == 'M' && r0[17] == 'O'
                    && r0[18] == 'B' && r0[19] == 'I') {
                int mobiLen = (int) u32(r0, 20);
                // firstImageIndex 在 MOBI 头相对 84 处
                if (mobiLen >= 88 && rec0Len >= 16 + 88) {
                    long v = u32(r0, 16 + 84);
                    if (v > 0 && v < numRecords) firstImage = (int) v;
                }
                if (mobiLen >= 172 && rec0Len >= 16 + 172) {
                    int v = u16(r0, 16 + 170);
                    if (v > 0 && v < numRecords) lastContent = v;
                }
                // 书名
                if (mobiLen >= 76 && rec0Len >= 16 + 76) {
                    int nameOff = (int) u32(r0, 16 + 60);
                    int nameLen = (int) u32(r0, 16 + 64);
                    if (nameOff > 0 && nameLen > 0 && nameLen < 4096
                            && nameOff + nameLen <= rec0Len) {
                        String t = new String(r0, nameOff, nameLen, StandardCharsets.UTF_8).trim();
                        if (!t.isEmpty()) bookTitle = t;
                    }
                }
                // EXTH：找封面(201)和缩略图(202)的相对序号
                int exthFlags = mobiLen >= 108 && rec0Len >= 16 + 108
                        ? (int) u32(r0, 16 + 104) : 0;
                if ((exthFlags & 0x40) != 0) {
                    int p = 16 + mobiLen;
                    if (p + 12 <= rec0Len && r0[p] == 'E' && r0[p + 1] == 'X'
                            && r0[p + 2] == 'T' && r0[p + 3] == 'H') {
                        int exthLen = (int) u32(r0, p + 4);
                        int count = (int) u32(r0, p + 8);
                        int q = p + 12;
                        for (int k = 0; k < count && k < 1024 && q + 8 <= rec0Len; k++) {
                            int type = (int) u32(r0, q);
                            int len = (int) u32(r0, q + 4);
                            if (len < 8 || q + len > rec0Len) break;
                            if ((type == 201 || type == 202) && len >= 12) {
                                long rel = u32(r0, q + 8);
                                if (rel != 0xFFFFFFFFL && rel < numRecords) {
                                    if (type == 201) coverRecord = (int) rel;
                                    else thumbRecord = (int) rel;
                                }
                            }
                            q += len;
                        }
                        if (exthLen <= 0) {
                            // 长度字段不可信，上面已经按条目长度走完了
                            exthLen = q - p;
                        }
                    }
                }
            }
        }

        // 缩略图的 EXTH 值是相对 firstImageIndex 的序号
        int thumbAbs = (thumbRecord >= 0 && firstImage > 0) ? firstImage + thumbRecord : -1;
        int coverAbs = (coverRecord >= 0 && firstImage > 0) ? firstImage + coverRecord : -1;

        // ---- 确定扫描范围 ----
        int from = firstImage > 0 ? firstImage : 1;
        int to = lastContent > 0 && lastContent >= from ? lastContent : numRecords - 1;

        Set<String> seen = new HashSet<>();
        collect(offs, from, to, thumbAbs, coverAbs, seen);

        // 头里的范围没取到东西（KF8/AZW3 常见），全库扫一遍
        if (images.isEmpty()) {
            seen.clear();
            collect(offs, 1, numRecords - 1, thumbAbs, coverAbs, seen);
        }

        if (images.isEmpty()) {
            throw new IOException("这里面没找到图，可能是本纯文字书：" + file.getName());
        }
    }

    /** 把 [from,to] 区间里内容是图片的记录收进来，按内容去重 */
    private void collect(long[] offs, int from, int to, int thumbAbs, int coverAbs,
                         Set<String> seen) throws IOException {
        byte[] probe = new byte[16];
        for (int i = Math.max(1, from); i <= to && i + 1 < offs.length; i++) {
            long start = offs[i];
            long len = offs[i + 1] - start;
            if (len < 16 || len > Integer.MAX_VALUE - 8) continue;
            // 缩略图跳过（它是封面的缩小重编码版，留着就是重复页）
            if (i == thumbAbs && thumbAbs != coverAbs) continue;

            raf.seek(start);
            raf.readFully(probe);
            if (!isImageMagic(probe)) continue;

            String key = fingerprint(start, (int) len);
            if (!seen.add(key)) continue;   // AZW3 双格式造成的重复
            images.add(new long[]{start, len});
        }
    }

    /** 指纹：长度 + 头尾各 2KB 的哈希。够区分重复图，又不用读几百 MB */
    private String fingerprint(long start, int len) throws IOException {
        int n = Math.min(2048, len);
        byte[] a = new byte[n];
        raf.seek(start);
        raf.readFully(a);
        byte[] b = new byte[n];
        raf.seek(start + len - n);
        raf.readFully(b);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            md.update((byte) (len >> 24));
            md.update((byte) (len >> 16));
            md.update((byte) (len >> 8));
            md.update((byte) len);
            md.update(a);
            md.update(b);
            StringBuilder sb = new StringBuilder(32);
            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            // 退化方案：长度 + 首尾字节
            return len + ":" + (a[0] & 0xff) + "," + (a[n - 1] & 0xff)
                    + "," + (b[0] & 0xff) + "," + (b[n - 1] & 0xff);
        }
    }

    private static boolean isImageMagic(byte[] d) {
        // JPEG
        if ((d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return true;
        // PNG
        if ((d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return true;
        // GIF
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return true;
        // BMP
        if (d[0] == 'B' && d[1] == 'M') return true;
        // WebP
        return d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    @Override
    public int pageCount() { return images.size(); }

    @Override
    public String pageName(int i) { return String.format("rec%04d", i + 1); }

    @Override
    public byte[] readPage(int i) throws Exception {
        long[] s = images.get(i);
        byte[] out = new byte[(int) s[1]];
        raf.seek(s[0]);
        raf.readFully(out);
        return out;
    }

    @Override
    public String title() {
        if (bookTitle != null && !bookTitle.isEmpty()) return bookTitle;
        return ComicSource.stripExt(file.getName());
    }

    @Override
    public boolean lossless() { return true; }

    @Override
    public String lossyReason() { return null; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (raf != null) try { raf.close(); } catch (IOException ignore) {}
    }
}

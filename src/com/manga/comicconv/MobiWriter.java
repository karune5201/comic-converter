package com.manga.comicconv;

import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Random;

/**
 * MOBI (MOBI6 / PalmDB) 输出，纯手写。
 *
 * 图片以原始字节进 image record（字节级无损）。MOBI 只认 JPEG/GIF/PNG/BMP，
 * 其它格式（WebP/HEIF/AVIF）会按像素无损转成 PNG。
 *
 * 内存策略：addPage 时把图片 spool 到临时文件，finish 时顺序组装，
 * 不把整本书留在内存里。
 */
public class MobiWriter implements ComicWriter {

    public static class Options {
        /** false = 严格无损；true = 超限图片降采样重编码成 JPEG（有损） */
        public boolean kindleSafeImages = false;
        /** MOBI6 单图安全上限 */
        public int maxImageBytes = 127 * 1024;
        /** kindleSafeImages 时的长边上限 */
        public int maxImageEdge = 1800;
        public int jpegQuality = 85;
    }

    private static final int RECORD_SIZE = 4096;

    private final File outFile;
    private final File tmpDir;
    private final Options opt;
    private final Report rep = new Report();

    private Meta meta;
    private boolean closed, finished;

    private File spoolFile;
    private OutputStream spool;
    private long spoolPos;
    /** 每页在 spool 文件里的 [offset, length] */
    private final ArrayList<long[]> imgSpans = new ArrayList<>();
    /** 超过安全上限的页号（1-based），用于提示 */
    private final ArrayList<Integer> oversize = new ArrayList<>();

    public MobiWriter(File outFile, File tmpDir, Options opt) {
        this.outFile = outFile;
        this.tmpDir = tmpDir;
        this.opt = opt != null ? opt : new Options();
    }

    @Override
    public void begin(Meta meta) throws Exception {
        this.meta = meta != null ? meta : new Meta();
        if (tmpDir != null && !tmpDir.exists()) tmpDir.mkdirs();
        spoolFile = new File(tmpDir, "mobi-spool-" + System.nanoTime() + ".bin");
        spool = new BufferedOutputStream(new FileOutputStream(spoolFile), 1 << 16);
        spoolPos = 0;
    }

    @Override
    public void addPage(Page page) throws Exception {
        if (page == null || page.data == null || page.data.length == 0) {
            rep.failedPages++;
            rep.note("第 " + (page == null ? -1 : page.index + 1) + " 页是空的，跳过");
            return;
        }
        byte[] data = page.data;
        ImageInfo ii = page.info;
        int pageNo = page.index + 1;

        boolean mobiNative = ii != null && (ii.type == ImageInfo.T_JPEG
                || ii.type == ImageInfo.T_PNG
                || ii.type == ImageInfo.T_GIF
                || ii.type == ImageInfo.T_BMP);

        if (!mobiNative) {
            // MOBI 不认这个容器格式 → 像素无损转 PNG
            Bitmap bm = Img.decode(data);
            if (bm == null) {
                rep.failedPages++;
                rep.note("第 " + pageNo + " 页的图读不出来（" + (ii != null ? ii.ext : "?") + "），跳过");
                return;
            }
            try {
                data = Img.pngLossless(bm);
            } finally {
                Img.recycle(bm);
            }
            rep.recodedPages++;
            rep.note("第 " + pageNo + " 页：MOBI 不认 "
                    + (ii != null ? ii.ext : "这个格式") + "，转成 PNG 了，画质不变");
        }

        if (opt.kindleSafeImages && data.length > opt.maxImageBytes) {
            Bitmap bm = Img.decodeScaled(data, opt.maxImageEdge);
            if (bm != null) {
                try {
                    byte[] small = Img.jpeg(bm, opt.jpegQuality);
                    if (small.length < data.length) {
                        data = small;
                        rep.lossyPages++;
                        rep.note("第 " + pageNo + " 页：超过 " + (opt.maxImageBytes / 1024)
                                + "KB，压缩了一下，画质有损");
                    }
                } finally {
                    Img.recycle(bm);
                }
            }
        } else if (data.length > opt.maxImageBytes) {
            oversize.add(pageNo);
        }

        if (mobiNative && data == page.data) rep.exactPages++;

        spool.write(data);
        imgSpans.add(new long[]{spoolPos, data.length});
        spoolPos += data.length;
    }

    @Override
    public void finish() throws Exception {
        if (finished) return;
        finished = true;
        spool.flush();
        spool.close();
        spool = null;

        int imgCount = imgSpans.size();
        if (!oversize.isEmpty()) {
            rep.noteAlways("有 " + oversize.size() + " 页超过了 MOBI 单张图 "
                    + (opt.maxImageBytes / 1024) + "KB 的上限（图还是原样没动）。"
                    + "老 Kindle 可能显示不出来，实在要能看就打开"
                    + "「Kindle 兼容模式」重新转一次。");
        }

        // ---- 正文 HTML ----
        byte[] html = buildHtml(imgCount);
        int textLength = html.length;
        int textRecCount = (textLength + RECORD_SIZE - 1) / RECORD_SIZE;
        if (textRecCount == 0) textRecCount = 1;

        // 记录编号规划：0=头，1..textRecCount=正文，之后是图片，再 FLIS/FCIS/EOF
        int firstImageIndex = 1 + textRecCount;
        int flisRec = firstImageIndex + imgCount;
        int fcisRec = flisRec + 1;
        int eofRec = fcisRec + 1;
        int totalRecords = eofRec + 1;

        // ---- 记录 0 ----
        byte[] rec0 = buildRecord0(textLength, textRecCount, firstImageIndex,
                imgCount, flisRec, fcisRec);

        // ---- 组装所有记录的长度表 ----
        ArrayList<Integer> lens = new ArrayList<>(totalRecords);
        lens.add(rec0.length);
        for (int i = 0; i < textRecCount; i++) {
            int off = i * RECORD_SIZE;
            lens.add(Math.min(RECORD_SIZE, textLength - off));
        }
        for (long[] s : imgSpans) lens.add((int) s[1]);
        byte[] flis = buildFlis();
        byte[] fcis = buildFcis(textLength);
        byte[] eof = new byte[]{(byte) 0xE9, (byte) 0x8E, 0x0D, 0x0A};
        lens.add(flis.length);
        lens.add(fcis.length);
        lens.add(eof.length);

        // ---- 偏移：78 字节头 + 8*N 记录表 + 2 字节填充 ----
        int headerBytes = 78 + 8 * totalRecords + 2;
        long[] offs = new long[totalRecords];
        long p = headerBytes;
        for (int i = 0; i < totalRecords; i++) {
            offs[i] = p;
            p += lens.get(i);
        }

        OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16);
        try {
            // PalmDB 头
            out.write(palmName(meta.title));                       // 32
            writeU16(out, 0);                                      // attributes
            writeU16(out, 0);                                      // version
            long palmTime = System.currentTimeMillis() / 1000L + 2082844800L;
            writeU32(out, palmTime);                               // ctime
            writeU32(out, palmTime);                               // mtime
            writeU32(out, 0);                                      // btime
            writeU32(out, 0);                                      // modnum
            writeU32(out, 0);                                      // appInfoID
            writeU32(out, 0);                                      // sortInfoID
            out.write("BOOK".getBytes(StandardCharsets.US_ASCII)); // type
            out.write("MOBI".getBytes(StandardCharsets.US_ASCII)); // creator
            writeU32(out, new Random().nextInt(0x7fffffff));        // uniqueIDseed
            writeU32(out, 0);                                      // nextRecordListID
            writeU16(out, totalRecords);                           // numRecords

            // 记录信息表
            for (int i = 0; i < totalRecords; i++) {
                writeU32(out, offs[i]);
                out.write(0);                                      // attributes
                int uid = 2 * i;
                out.write((uid >> 16) & 0xff);
                out.write((uid >> 8) & 0xff);
                out.write(uid & 0xff);
            }
            out.write(new byte[]{0, 0});                           // 2 字节填充

            // 记录 0
            out.write(rec0);
            // 正文记录
            for (int i = 0; i < textRecCount; i++) {
                int off = i * RECORD_SIZE;
                out.write(html, off, Math.min(RECORD_SIZE, textLength - off));
            }
            // 图片记录：从 spool 文件顺序搬
            RandomAccessFile raf = new RandomAccessFile(spoolFile, "r");
            try {
                byte[] buf = new byte[1 << 16];
                for (long[] s : imgSpans) {
                    raf.seek(s[0]);
                    long remain = s[1];
                    while (remain > 0) {
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, remain));
                        if (n <= 0) throw new IOException("临时文件读到一半断了");
                        out.write(buf, 0, n);
                        remain -= n;
                    }
                }
            } finally {
                try { raf.close(); } catch (Exception ignore) {}
            }
            out.write(flis);
            out.write(fcis);
            out.write(eof);
            out.flush();
        } finally {
            try { out.close(); } catch (Exception ignore) {}
            deleteSpool();
        }
    }

    /** 正文：每页一个居中 img，recindex 是相对 firstImageIndex 的 1-based 序号 */
    private byte[] buildHtml(int imgCount) {
        StringBuilder b = new StringBuilder(128 + imgCount * 72);
        b.append("<html><head></head><body>");
        for (int i = 1; i <= imgCount; i++) {
            b.append("<div align=\"center\"><img recindex=\"")
             .append(String.format("%05d", i))
             .append("\" /></div>");
            if (i < imgCount) b.append("<mbp:pagebreak/>");
        }
        b.append("</body></html>");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildRecord0(int textLength, int textRecCount, int firstImageIndex,
                                int imgCount, int flisRec, int fcisRec) throws IOException {
        byte[] titleBytes = meta.title.getBytes(StandardCharsets.UTF_8);
        int uniqueId = new Random().nextInt(0x7fffffff);

        // EXTH 先构造，才知道书名偏移
        byte[] exth = buildExth(imgCount);
        // PalmDOC 头 16 + MOBI 头 232 + EXTH + 书名
        int fullNameOffset = 16 + 232 + exth.length;

        ByteArrayOutputStream o = new ByteArrayOutputStream(fullNameOffset + titleBytes.length + 16);

        // ---- PalmDOC 头（16 字节）----
        writeU16(o, 1);              // compression: 1 = 不压缩（对文本也无损）
        writeU16(o, 0);              // unused
        writeU32(o, textLength);
        writeU16(o, textRecCount);
        writeU16(o, RECORD_SIZE);
        writeU16(o, 0);              // encryptionType
        writeU16(o, 0);              // unused

        // ---- MOBI 头（232 字节，从 identifier 起算）----
        ByteArrayOutputStream m = new ByteArrayOutputStream(232);
        m.write("MOBI".getBytes(StandardCharsets.US_ASCII));  // 0
        writeU32(m, 232);                                     // 4  headerLength
        writeU32(m, 2);                                       // 8  mobiType: 2 = book
        writeU32(m, 65001);                                   // 12 textEncoding: UTF-8
        writeU32(m, uniqueId);                                // 16
        writeU32(m, 6);                                       // 20 fileVersion
        for (int i = 0; i < 8; i++) writeU32(m, 0xFFFFFFFFL); // 24..55 索引字段全无
        writeU32(m, 1 + textRecCount);                        // 56 firstNonBookIndex
        writeU32(m, fullNameOffset);                          // 60 fullNameOffset
        writeU32(m, titleBytes.length);                       // 64 fullNameLength
        writeU32(m, localeCode(meta.language));               // 68 locale
        writeU32(m, 0);                                       // 72 inputLanguage
        writeU32(m, 0);                                       // 76 outputLanguage
        writeU32(m, 6);                                       // 80 minVersion
        writeU32(m, firstImageIndex);                         // 84 firstImageIndex
        writeU32(m, 0);                                       // 88 huffmanRecordOffset
        writeU32(m, 0);                                       // 92 huffmanRecordCount
        writeU32(m, 0);                                       // 96 huffmanTableOffset
        writeU32(m, 0);                                       // 100 huffmanTableLength
        writeU32(m, 0x40);                                    // 104 exthFlags: 有 EXTH
        for (int i = 0; i < 32; i++) m.write(0);              // 108..139 保留
        writeU32(m, 0xFFFFFFFFL);                             // 140 unknown
        writeU32(m, 0);                                       // 144 drmOffset
        writeU32(m, 0);                                       // 148 drmCount
        writeU32(m, 0);                                       // 152 drmSize
        writeU32(m, 0);                                       // 156 drmFlags
        for (int i = 0; i < 8; i++) m.write(0);               // 160..167 保留
        writeU16(m, 1);                                       // 168 firstContentRecord
        writeU16(m, firstImageIndex + imgCount - 1);          // 170 lastContentRecord
        writeU32(m, 1);                                       // 172 unknown
        writeU32(m, fcisRec);                                 // 176 fcisRecord
        writeU32(m, 1);                                       // 180 fcisCount
        writeU32(m, flisRec);                                 // 184 flisRecord
        writeU32(m, 1);                                       // 188 flisCount
        writeU32(m, 0);                                       // 192
        writeU32(m, 0);                                       // 196
        writeU32(m, 0xFFFFFFFFL);                             // 200
        writeU32(m, 0);                                       // 204
        writeU32(m, 0xFFFFFFFFL);                             // 208
        writeU32(m, 0xFFFFFFFFL);                             // 212
        writeU32(m, 0);                                       // 216 extraRecordDataFlags
        writeU32(m, 0xFFFFFFFFL);                             // 220 indxRecordOffset
        writeU32(m, 0);                                       // 224
        writeU32(m, 0);                                       // 228
        byte[] mobi = m.toByteArray();
        if (mobi.length != 232) {
            throw new IOException("MOBI 头长度不对：" + mobi.length + "，应为 232");
        }
        o.write(mobi);

        // ---- EXTH ----
        o.write(exth);
        // ---- 书名 ----
        o.write(titleBytes);
        o.write(0);
        o.write(0);
        // 4 字节对齐
        while (o.size() % 4 != 0) o.write(0);
        return o.toByteArray();
    }

    private byte[] buildExth(int imgCount) throws IOException {
        ArrayList<byte[]> recs = new ArrayList<>();
        recs.add(exthStr(100, meta.author == null || meta.author.isEmpty()
                ? "ComicConv" : meta.author));           // author
        recs.add(exthStr(503, meta.title));              // updated title
        recs.add(exthStr(108, "ComicConv"));             // contributor
        recs.add(exthStr(104, ""));                      // isbn 占位（空）
        recs.add(exthStr(105, "Comic"));                 // subject
        if (imgCount > 0) {
            recs.add(exthU32(201, 0));                   // coverOffset: 第一张图
            recs.add(exthU32(202, 0));                   // thumbOffset
        }
        recs.add(exthU32(125, imgCount));                // 图片数
        recs.add(exthU32(131, 0));                       // 未使用

        int body = 0;
        for (byte[] r : recs) body += r.length;
        int headerLen = 12 + body;
        int pad = (4 - (headerLen % 4)) % 4;

        ByteArrayOutputStream o = new ByteArrayOutputStream(headerLen + pad);
        o.write("EXTH".getBytes(StandardCharsets.US_ASCII));
        writeU32(o, headerLen + pad);
        writeU32(o, recs.size());
        for (byte[] r : recs) o.write(r);
        for (int i = 0; i < pad; i++) o.write(0);
        return o.toByteArray();
    }

    private static byte[] exthStr(int type, String val) throws IOException {
        byte[] v = val.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream(8 + v.length);
        writeU32(o, type);
        writeU32(o, 8 + v.length);
        o.write(v);
        return o.toByteArray();
    }

    private static byte[] exthU32(int type, long val) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(12);
        writeU32(o, type);
        writeU32(o, 12);
        writeU32(o, val);
        return o.toByteArray();
    }

    private byte[] buildFlis() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(36);
        o.write("FLIS".getBytes(StandardCharsets.US_ASCII));
        writeU32(o, 8);
        writeU16(o, 65);
        writeU16(o, 0);
        writeU32(o, 0);
        writeU32(o, 0xFFFFFFFFL);
        writeU16(o, 1);
        writeU16(o, 3);
        writeU32(o, 3);
        writeU32(o, 1);
        writeU32(o, 0xFFFFFFFFL);
        return o.toByteArray();
    }

    private byte[] buildFcis(int textLength) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(44);
        o.write("FCIS".getBytes(StandardCharsets.US_ASCII));
        writeU32(o, 20);
        writeU32(o, 16);
        writeU32(o, 1);
        writeU32(o, 0);
        writeU32(o, textLength);
        writeU32(o, 0);
        writeU32(o, 32);
        writeU32(o, 8);
        writeU16(o, 1);
        writeU16(o, 1);
        writeU32(o, 0);
        return o.toByteArray();
    }

    /** PalmDB 名字字段：32 字节，ASCII 安全化，最后一字节必须是 0 */
    private static byte[] palmName(String title) {
        byte[] n = new byte[32];
        if (title == null) title = "Comic";
        int j = 0;
        for (int i = 0; i < title.length() && j < 31; i++) {
            char c = title.charAt(i);
            n[j++] = (byte) (c >= 32 && c < 127 ? c : '_');
        }
        return n;
    }

    private static int localeCode(String lang) {
        if (lang == null) return 9;
        String l = lang.toLowerCase();
        if (l.startsWith("zh")) return 4;
        if (l.startsWith("ja")) return 17;
        if (l.startsWith("ko")) return 18;
        return 9;   // en
    }

    private static void writeU16(OutputStream o, int v) throws IOException {
        o.write((v >> 8) & 0xff);
        o.write(v & 0xff);
    }

    private static void writeU32(OutputStream o, long v) throws IOException {
        o.write((int) ((v >> 24) & 0xff));
        o.write((int) ((v >> 16) & 0xff));
        o.write((int) ((v >> 8) & 0xff));
        o.write((int) (v & 0xff));
    }

    private void deleteSpool() {
        if (spoolFile != null && spoolFile.exists()) {
            try { spoolFile.delete(); } catch (Exception ignore) {}
        }
    }

    @Override
    public Report report() { return rep; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (spool != null) {
            try { spool.close(); } catch (Exception ignore) {}
            spool = null;
        }
        deleteSpool();
    }
}

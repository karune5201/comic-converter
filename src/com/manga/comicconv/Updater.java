package com.manga.comicconv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：查 GitHub 最新 Release、比对版本、下载 APK。
 *
 * 这里只负责「拿到新版本」和「把文件下下来」，真正安装交给系统
 * PackageInstaller（用户还得点一次确认），不做任何静默安装。
 */
public final class Updater {

    /** 仓库地址，改了仓库只用改这里 */
    private static final String API_LATEST =
            "https://api.github.com/repos/karune5201/comic-converter/releases/latest";
    private static final String UA = "ComicConv-Updater";

    /** 一个可用的新版本 */
    public static class Release {
        public String tag = "";        // v2.5
        public String version = "";    // 2.5（去掉 v）
        public String apkName = "";
        public String apkUrl = "";
        public long apkSize = 0;
        public String notes = "";
    }

    /** 下载进度回调，在后台线程调用 */
    public interface OnProgress {
        void onProgress(int percent);
    }

    private Updater() {}

    /**
     * 查询最新版本。网络或解析失败抛 IOException，由调用方转成界面提示。
     */
    public static Release checkLatest() throws IOException {
        String body = httpGet(API_LATEST);
        Release r = new Release();
        try {
            JSONObject o = new JSONObject(body);
            r.tag = o.optString("tag_name", "");
            r.version = stripV(r.tag);
            r.notes = o.optString("body", "");
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        r.apkName = name;
                        r.apkUrl = a.optString("browser_download_url", "");
                        r.apkSize = a.optLong("size", 0);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("更新信息解析失败", e);
        }
        if (r.tag.isEmpty() || r.apkUrl.isEmpty()) {
            throw new IOException("这个发布里没有 APK");
        }
        return r;
    }

    /** v2.5 / 2.5 -> 2.5 */
    static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        return t;
    }

    /**
     * 版本号比较：newer 严格大于 cur 返回 true。
     * 按点分段比数字，段数不同按 0 补齐，这样 2.10 > 2.9 也是对的。
     */
    public static boolean isNewer(String newer, String cur) {
        String[] a = stripV(newer).split("\\.");
        String[] b = stripV(cur).split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = parseIntSafe(i < a.length ? a[i] : "0");
            int y = parseIntSafe(i < b.length ? b[i] : "0");
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            // 只取前导数字，容忍「5-beta」这种后缀
            int end = 0;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 下载到 dest。已存在同大小的文件时直接复用，方便重试 */
    public static File download(Release r, File dest, OnProgress cb) throws IOException {
        if (dest.exists() && dest.length() == r.apkSize && r.apkSize > 0) {
            if (cb != null) cb.onProgress(100);
            return dest;
        }
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("建不了下载目录：" + dir);
        }

        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP " + code);
            }
            long total = r.apkSize > 0 ? r.apkSize : c.getContentLength();
            in = c.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0 && cb != null) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        cb.onProgress(pct);
                    }
                }
            }
            out.flush();
            if (cb != null) cb.onProgress(100);
            return dest;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (c != null) c.disconnect();
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            // GitHub API 要求带 UA，不带会 403
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("查询更新失败，HTTP " + code);
            }
            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            closeQuietly(in);
            if (c != null) c.disconnect();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Throwable ignore) {}
    }

    /** 供 FileInputStream 复用的工具：把文件整个拷进 PackageInstaller 的 Session 流 */
    public static void copyInto(File src, OutputStream os) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(src);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            closeQuietly(in);
        }
    }
}

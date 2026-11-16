package com.manga.comicconv;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.OutputStream;

/**
 * 把下载好的 APK 交给系统安装。
 *
 * 走 PackageInstaller 会话：系统会弹一次安装确认，用户点了才真正装。
 * 应用自己不碰「静默安装」这条路，也不需要 FileProvider。
 */
public final class Installer {

    /** 安装结果的广播 action（应用内部用） */
    public static final String ACTION_RESULT = "com.manga.comicconv.INSTALL_RESULT";

    private Installer() {}

    /** 是否已经给了「安装未知应用」权限（API 26+ 才有这个概念） */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return c.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    /** 跳到「安装未知应用」开关页，让用户给我们开权限 */
    public static void openUnknownSourcesSetting(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Throwable ignore) {}
        }
        try {
            Intent i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignore) {}
    }

    /**
     * 提交安装会话。结果通过 {@link #ACTION_RESULT} 广播回来：
     *   EXTRA_STATUS = PackageInstaller.STATUS_*
     * 注意 commit() 之后进程可能被系统杀掉做替换，别假设后面还能接着跑。
     */
    public static void install(Context c, File apk, int requestCode) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        p.setSize(apk.length());
        int id = pi.createSession(p);

        PackageInstaller.Session session = pi.openSession(id);
        try {
            OutputStream os = session.openWrite("base.apk", 0, apk.length());
            try {
                Updater.copyInto(apk, os);   // 把下载好的 APK 灌进会话
                session.fsync(os);
            } finally {
                try { os.close(); } catch (Throwable ignore) {}
            }
            Intent result = new Intent(ACTION_RESULT).setPackage(c.getPackageName());
            int flags = PendingIntentFlag(c);
            session.commit(android.app.PendingIntent
                    .getBroadcast(c, requestCode, result, flags)
                    .getIntentSender());
        } finally {
            try { session.close(); } catch (Throwable ignore) {}
        }
    }

    /** API 31+ 要求显式声明可变性，否则启动会抛异常 */
    private static int PendingIntentFlag(Context c) {
        int f = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) f |= android.app.PendingIntent.FLAG_MUTABLE;
        return f;
    }
}

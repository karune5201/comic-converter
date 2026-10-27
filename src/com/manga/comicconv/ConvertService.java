package com.manga.comicconv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务跑批量转换。
 * 整本漫画动辄几百 MB，转换要跑很久，放前台服务才不会被系统回收。
 */
public class ConvertService extends Service {

    public static final String CH_ID = "convert";
    public static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.manga.comicconv.START";
    public static final String ACTION_CANCEL = "com.manga.comicconv.CANCEL";

    /** 进度广播 */
    public static final String BC_PROGRESS = "com.manga.comicconv.PROGRESS";
    public static final String EX_BOOK_INDEX = "bookIndex";
    public static final String EX_BOOK_TOTAL = "bookTotal";
    public static final String EX_BOOK_NAME = "bookName";
    public static final String EX_PAGE = "page";
    public static final String EX_PAGE_TOTAL = "pageTotal";
    public static final String EX_LINE = "line";
    public static final String EX_DONE = "done";

    /** 任务参数由 Activity 直接塞进静态字段，省掉 Parcelable 的事 */
    public static volatile List<Scanner.Item> pending;
    public static volatile File outDir;
    public static volatile Converter.Options options;
    public static final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    public static volatile boolean running;

    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) {
            cancelFlag.set(true);
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        final List<Scanner.Item> items = pending != null
                ? new ArrayList<>(pending) : new ArrayList<Scanner.Item>();
        final File dir = outDir;
        final Converter.Options opt = options != null ? options : new Converter.Options();
        if (items.isEmpty() || dir == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        startForegroundCompat(buildNotif("准备中…", 0, items.size()));
        running = true;
        cancelFlag.set(false);

        worker = new Thread(new Runnable() {
            @Override public void run() {
                runBatch(items, dir, opt);
            }
        }, "comicconv-worker");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runBatch(List<Scanner.Item> items, File dir, Converter.Options opt) {
        File tmp = new File(getCacheDir(), "spool");
        if (!tmp.exists()) tmp.mkdirs();

        int okCount = 0, failCount = 0;
        int exact = 0, pixel = 0, lossy = 0;
        try {
            for (int b = 0; b < items.size(); b++) {
                if (cancelFlag.get()) break;
                final Scanner.Item it = items.get(b);
                final int bookIdx = b;
                final int bookTotal = items.size();

                notifyProgress(bookIdx, bookTotal, it.name(), 0, 0,
                        "开始转 " + it.name(), false);
                updateNotif("(" + (b + 1) + "/" + bookTotal + ") " + it.name(),
                        b, bookTotal);

                Converter.Result res = Converter.convert(it, dir, opt, tmp,
                        new Converter.Progress() {
                            long last;
                            @Override public boolean onPage(int done, int total) {
                                if (cancelFlag.get()) return false;
                                long now = System.currentTimeMillis();
                                if (now - last > 400 || done == total) {
                                    last = now;
                                    notifyProgress(bookIdx, bookTotal, it.name(),
                                            done, total, null, false);
                                }
                                return true;
                            }
                        });

                String line;
                if (res.ok) {
                    okCount++;
                    String tag = res.byteExact ? "原封不动"
                            : res.pixelExact ? "画质不变" : "有损";
                    if (res.byteExact) exact++;
                    else if (res.pixelExact) pixel++;
                    else lossy++;
                    line = "✓ " + it.name() + " → " + res.message + "  [" + tag + "]";
                    if (res.detail != null && !res.detail.isEmpty()) {
                        line += "\n    " + res.detail.replace("\n", "\n    ");
                    }
                } else {
                    failCount++;
                    line = "✗ " + it.name() + "：" + res.message;
                }
                notifyProgress(bookIdx, bookTotal, it.name(), 0, 0, line, false);
            }
        } catch (Throwable t) {
            notifyProgress(0, 0, "", 0, 0, "出错了：" + t, false);
        }

        String summary;
        if (cancelFlag.get()) {
            summary = "已取消：成功 " + okCount + " 本，失败 " + failCount + " 本";
        } else {
            summary = "全部完成，成功 " + okCount + " 本";
            if (failCount > 0) summary += "，失败 " + failCount + " 本";
            summary += "（原封不动 " + exact + "、画质不变 " + pixel
                    + "、有损 " + lossy + "）";
        }
        notifyProgress(0, 0, "", 0, 0, summary, true);
        finishNotif(summary);
        running = false;
        stopForegroundCompat();
        stopSelf();
    }

    private void notifyProgress(int bi, int bt, String name, int page, int pageTotal,
                                String line, boolean done) {
        Intent i = new Intent(BC_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra(EX_BOOK_INDEX, bi);
        i.putExtra(EX_BOOK_TOTAL, bt);
        i.putExtra(EX_BOOK_NAME, name);
        i.putExtra(EX_PAGE, page);
        i.putExtra(EX_PAGE_TOTAL, pageTotal);
        if (line != null) i.putExtra(EX_LINE, line);
        i.putExtra(EX_DONE, done);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH_ID, "转换进度",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("转换进度的通知");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text, int cur, int total) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle("正在转换漫画")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(pi);
        if (total > 0) b.setProgress(total, cur, false);

        Intent cancel = new Intent(this, ConvertService.class);
        cancel.setAction(ACTION_CANCEL);
        PendingIntent cpi = PendingIntent.getService(this, 1, cancel, flags);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "取消", cpi).build());
        return b.build();
    }

    private void updateNotif(String text, int cur, int total) {
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotif(text, cur, total));
    }

    private void finishNotif(String text) {
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        b.setContentTitle("转换完成")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, flags));
        nm.notify(NOTIF_ID + 1, b.build());
    }

    @SuppressWarnings("deprecation")
    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        cancelFlag.set(true);
        super.onDestroy();
    }
}

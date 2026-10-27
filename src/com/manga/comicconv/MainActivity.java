package com.manga.comicconv;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面。Material Design 3 + 响应式布局。
 *
 * 按 MD3 断点分两种排布：
 *  - compact（宽度 < 600dp，手机竖屏）：单列滚动 + 底部固定操作区
 *  - medium / expanded（平板、手机横屏）：左右双栏
 *      左栏 = 设置（来源 / 格式）+ 底部操作区
 *      右栏 = 书目列表 + 运行记录
 *
 * 清单里声明了 configChanges，旋转或分屏改尺寸时 Activity 不会重建，
 * 所以这里在 onConfigurationChanged 里主动重建界面，并把输入内容、书目勾选、
 * 日志、进度都搬过去，用户看不到状态丢失。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "comicconv";
    private static final int REQ_PERM = 100;
    /** 列表最多直接铺多少行，超出的走弹窗挑选 */
    private static final int MAX_INLINE_ROWS = 400;
    /** 内联日志保留的尾部行数 */
    private static final int LOG_TAIL_LINES = 30;

    private Md3 md3;
    private FrameLayout rootView;

    private EditText inPath, outPath, authorBox;
    private TextView statusText, logText, countText, formatHint;
    private Md3.LinearProgress pageBar;
    private Button scanBtn, convertBtn, cancelBtn, pickBtn;
    private LinearLayout listBox, permCard, kindleRow, rtlRow;
    private ScrollView pageScroll;

    private final List<TextView> chips = new ArrayList<>();
    private int outFormat = Converter.OUT_PDF;

    private final List<Scanner.Item> items = new ArrayList<>();
    /** 与 items 等长，null 表示该项没铺出来（超过上限） */
    private final List<CheckBox> itemBoxes = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();

    // 界面重建时用来搬运的状态
    private String stIn = "", stOut = "", stAuthor = "";
    private boolean stRec = true, stRtl = true, stOw = false, stKs = false;
    private boolean busy;

    private static final String[] FMT_NAMES = {"PDF", "EPUB", "CBZ", "MOBI", "图片"};
    private static final String[] FMT_HINTS = {
            "图直接放进去，不重新压一遍，画质一点不掉",
            "按页排好，图原样放。新 Kindle 也能直接看",
            "把原图打个包，最省事，体积跟原图差不多",
            "老格式了，一张图超过 127KB 就可能显示不出来，漫画页基本都超",
            "每页存成一张图，还是原来的文件，给别的软件用最方便",
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int bi = i.getIntExtra(ConvertService.EX_BOOK_INDEX, 0);
            int bt = i.getIntExtra(ConvertService.EX_BOOK_TOTAL, 0);
            String name = i.getStringExtra(ConvertService.EX_BOOK_NAME);
            int pg = i.getIntExtra(ConvertService.EX_PAGE, 0);
            int pt = i.getIntExtra(ConvertService.EX_PAGE_TOTAL, 0);
            String line = i.getStringExtra(ConvertService.EX_LINE);
            boolean done = i.getBooleanExtra(ConvertService.EX_DONE, false);

            if (pt > 0) {
                if (pageBar != null) pageBar.setFraction(pg / (float) pt);
                if (statusText != null) {
                    statusText.setText("(" + (bi + 1) + "/" + bt + ") " + shortName(name)
                            + "   " + pg + "/" + pt + " 页");
                }
            } else if (name != null && !name.isEmpty()) {
                if (statusText != null) {
                    statusText.setText("(" + (bi + 1) + "/" + bt + ") " + shortName(name));
                }
            }
            if (line != null) appendLog(line);
            if (done) {
                if (statusText != null) {
                    statusText.setText(line != null ? line : "完成了");
                    statusText.setTextColor(md3.primary);
                }
                setBusy(false);
                if (pageBar != null) pageBar.setFraction(1f);
            }
        }
    };

    private static String shortName(String s) {
        if (s == null) return "";
        return s.length() > 26 ? s.substring(0, 24) + "…" : s;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        md3 = new Md3(this);
        applySystemBars();
        loadPrefs();          // 先把状态读进来，buildUi 才能拿到正确的开关初值
        buildUi();
        selectFormat(outFormat);
        IntentFilter f = new IntentFilter(ConvertService.BC_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(receiver, f,
                    Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        } else {
            registerReceiver(receiver, f);   // 三参重载 API 26 才有
        }
        refreshPermState();
        setBusy(ConvertService.running);
    }

    private void applySystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(md3.surface);
            getWindow().setNavigationBarColor(md3.surface);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !md3.dark) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    // ================= 界面搭建 =================

    private void buildUi() {
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(md3.surface);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(md3.surface);
        main.addView(md3.topAppBar("漫画转换器", "原图直接打包，不重新压缩"));

        if (md3.isTwoPane()) {
            main.addView(buildTwoPane(), lpWeightV(1f));
        } else {
            main.addView(buildSingleColumn(), lpWeightV(1f));
            main.addView(buildActionArea(false));
        }
        rootView.addView(main);
        setContentView(rootView);
    }

    /** 竖向占满剩余高度 */
    private static LinearLayout.LayoutParams lpWeightV(float w) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, w);
    }

    /** 横向占满剩余宽度 */
    private static LinearLayout.LayoutParams lpWeightH(float w) {
        return new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** 手机竖屏：单列滚动 */
    private View buildSingleColumn() {
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, md3.dp(4), 0, md3.dp(12));

        content.addView(buildPermCard());
        content.addView(buildSourceCard());
        content.addView(buildFormatCard());
        content.addView(buildListCard());
        content.addView(buildLogCard());
        pageScroll.addView(content);
        return pageScroll;
    }

    /** 平板 / 横屏：左右双栏 */
    private View buildTwoPane() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // ---- 左栏：设置 + 操作区 ----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        int leftW = md3.isExpanded() ? md3.dp(400) : md3.dp(340);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                leftW, ViewGroup.LayoutParams.MATCH_PARENT));
        left.setBackgroundColor(md3.surfaceContainerLow);

        ScrollView ls = new ScrollView(this);
        ls.setFillViewport(true);
        LinearLayout lc = new LinearLayout(this);
        lc.setOrientation(LinearLayout.VERTICAL);
        lc.setPadding(0, md3.dp(4), 0, md3.dp(8));
        lc.addView(buildPermCard());
        lc.addView(buildSourceCard());
        lc.addView(buildFormatCard());
        ls.addView(lc);
        left.addView(ls, lpWeightV(1f));

        left.addView(buildActionArea(true));
        body.addView(left);

        // 双栏之间的分隔线
        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                md3.dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        sep.setBackgroundColor(md3.outlineVariant);
        body.addView(sep);

        // ---- 右栏：书目 + 记录 ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        LinearLayout rc = new LinearLayout(this);
        rc.setOrientation(LinearLayout.VERTICAL);
        int pad = md3.isExpanded() ? md3.dp(20) : md3.dp(8);
        rc.setPadding(pad, md3.dp(4), pad, md3.dp(16));
        rc.addView(buildListCard());
        rc.addView(buildLogCard());
        pageScroll.addView(rc);
        right.addView(pageScroll, lpWeightV(1f));
        body.addView(right);
        return body;
    }

    private View buildPermCard() {
        permCard = md3.card();
        permCard.setBackground(md3.stroked(md3.tertiaryContainer, md3.tertiary,
                md3.dp(1), md3.shapeMedium()));
        TextView hint = md3.bodySmall("得开「所有文件访问」权限，不然读不到漫画文件夹\n"
                + "Android 11 以后对压缩包这类文件管得比较严", md3.onTertiaryContainer);
        permCard.addView(hint);
        Button b = md3.filledButton("去开权限");
        b.setOnClickListener(v -> requestAllFiles());
        permCard.addView(marginTop(b, 12));
        return permCard;
    }

    private View buildSourceCard() {
        LinearLayout c = md3.card();
        c.addView(md3.cardHeader("选文件"));

        inPath = new EditText(this);
        c.addView(md3.textField("漫画目录", inPath));

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, md3.dp(12), 0, 0);
        scanBtn = md3.filledButton("扫描");
        scanBtn.setOnClickListener(v -> doScan());
        r.addView(scanBtn);
        c.addView(r);

        c.addView(md3.divider());

        outPath = new EditText(this);
        c.addView(md3.textField("输出目录", outPath));
        return c;
    }

    private View buildFormatCard() {
        LinearLayout c = md3.card();
        c.addView(md3.cardHeader("输出格式"));

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        // 重建界面时必须清空：否则旧控件留在列表里，selectFormat 会去改那些
        // 已经不在界面上的实例，真正显示的 chip 反而拿不到选中色。
        chips.clear();
        for (int i = 0; i < FMT_NAMES.length; i++) {
            final int idx = i;
            TextView chip = md3.filterChip(FMT_NAMES[i]);
            chip.setOnClickListener(v -> selectFormat(idx));
            chips.add(chip);
            chipRow.addView(chip);
        }
        c.addView(chipRow);

        formatHint = md3.bodySmall("", md3.onSurfaceVariant);
        formatHint.setPadding(md3.dp(3), md3.dp(10), 0, 0);
        c.addView(formatHint);

        c.addView(md3.divider());

        c.addView(md3.switchRow("包含子目录", "连里面的文件夹一起扫", stRec,
                v -> stRec = v));

        rtlRow = md3.switchRow("从右往左翻页", "日漫一般从右往左翻", stRtl,
                v -> stRtl = v);
        c.addView(rtlRow);

        c.addView(md3.switchRow("同名文件直接覆盖", "关掉就自动加序号", stOw,
                v -> stOw = v));

        kindleRow = new LinearLayout(this);
        kindleRow.setOrientation(LinearLayout.VERTICAL);
        kindleRow.addView(md3.switchRow("Kindle 兼容模式", "图太大的话压小一点，画质会降", stKs,
                v -> stKs = v));
        c.addView(kindleRow);

        c.addView(md3.divider());
        authorBox = new EditText(this);
        c.addView(md3.textField("作者，可以不填", authorBox));
        return c;
    }

    private View buildListCard() {
        LinearLayout c = md3.card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(md3.cardHeader("选要转的书"), lpWeightH(1f));
        pickBtn = md3.textButton("批量选择");
        pickBtn.setOnClickListener(v -> showPickDialog());
        head.addView(pickBtn);
        c.addView(head);

        countText = md3.bodySmall("还没扫描", md3.onSurfaceVariant);
        c.addView(countText);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, md3.dp(8), 0, md3.dp(4));
        Button all = md3.textButton("全选");
        all.setOnClickListener(v -> setAll(true));
        btns.addView(all);
        Button none = md3.textButton("取消全选");
        none.setOnClickListener(v -> setAll(false));
        btns.addView(none);
        c.addView(btns);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(listBox);
        return c;
    }

    private View buildLogCard() {
        LinearLayout c = md3.card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(md3.cardHeader("记录"), lpWeightH(1f));
        Button full = md3.textButton("查看全部");
        full.setOnClickListener(v -> showLogDialog());
        head.addView(full);
        c.addView(head);

        logText = md3.bodySmall("还没开始", md3.onSurfaceVariant);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11);
        logText.setBackground(md3.filled(md3.surfaceContainerHighest, md3.shapeSmall()));
        if (md3.isTwoPane()) logText.setMinHeight(md3.dp(96));
        int p = md3.dp(12);
        logText.setPadding(p, p, p, p);
        c.addView(logText);
        return c;
    }

    /** 操作区：状态 + 进度 + 按钮。手机在屏幕底部，平板在左栏底部。 */
    private View buildActionArea(boolean inPane) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setBackgroundColor(inPane ? md3.surfaceContainerHigh : md3.surfaceContainerLow);
        int px = md3.dp(16);
        f.setPadding(px, md3.dp(12), px, md3.dp(14));
        if (!inPane) f.setElevation(md3.dp(3));

        statusText = md3.bodySmall("准备好了", md3.onSurfaceVariant);
        f.addView(statusText);

        pageBar = md3.new LinearProgress();
        f.addView(marginTop(pageBar, 10));

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, md3.dp(12), 0, 0);
        convertBtn = md3.filledButton("开始转换");
        convertBtn.setOnClickListener(v -> doConvert());
        r.addView(convertBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cancelBtn = md3.outlinedButton("取消");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(md3.dp(10), 0, 0, 0);
        cancelBtn.setLayoutParams(clp);
        cancelBtn.setOnClickListener(v -> doCancel());
        r.addView(cancelBtn);
        f.addView(r);
        return f;
    }

    private <T extends View> T marginTop(T v, float d) {
        ViewGroup.LayoutParams old = v.getLayoutParams();
        LinearLayout.LayoutParams lp = old instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) old
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = md3.dp(d);
        v.setLayoutParams(lp);
        return v;
    }

    private void selectFormat(int idx) {
        outFormat = idx;
        for (int i = 0; i < chips.size(); i++) {
            md3.chipStyle(chips.get(i), i == idx);
        }
        if (formatHint != null) formatHint.setText(FMT_HINTS[idx]);
        if (kindleRow != null) {
            kindleRow.setVisibility(idx == Converter.OUT_MOBI ? View.VISIBLE : View.GONE);
        }
        if (rtlRow != null) {
            boolean on = idx == Converter.OUT_EPUB;
            rtlRow.setEnabled(on);
            rtlRow.setAlpha(on ? 1f : 0.4f);
        }
    }

    // ================= 旋转 / 分屏 =================

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        // 深浅色切换交给系统重建，这里只处理尺寸变化
        captureState();
        md3 = new Md3(this);
        applySystemBars();
        buildUi();
        applyState();
    }

    private void captureState() {
        if (inPath != null) stIn = inPath.getText().toString();
        if (outPath != null) stOut = outPath.getText().toString();
        if (authorBox != null) stAuthor = authorBox.getText().toString();
    }

    private void applyState() {
        inPath.setText(stIn);
        outPath.setText(stOut);
        authorBox.setText(stAuthor);
        selectFormat(outFormat);
        rebuildList();
        renderLogTail();
        setBusy(busy);
        updatePermCard();   // 重建会新建一张默认可见的卡片，这里必须重查一次
    }

    // ================= 权限 =================

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true;
            // 有些 ROM 上「所有文件访问」的开关不会反映到标准 API，
            // 只要能真正列出漫画目录，就当已授权，别再挡着用户。
            return canProbeStorage();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;   // 6.0 以下安装时就授权了
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        // Android 10 及以下还要写权限才能建输出文件
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 只刷新卡片显隐，不触发任何申请，可在重建/横竖屏后安全调用 */
    private void updatePermCard() {
        if (permCard != null) {
            permCard.setVisibility(hasAllFiles() ? View.GONE : View.VISIBLE);
        }
    }

    /** 真的去列一下目录，能读到就当权限可用 */
    private boolean canProbeStorage() {
        try {
            String p = stIn;
            File d = (p != null && !p.isEmpty()) ? new File(p) : null;
            if (d == null || !d.isDirectory()) {
                d = new File(Environment.getExternalStorageDirectory(), "漫画");
            }
            if (!d.isDirectory()) d = Environment.getExternalStorageDirectory();
            String[] list = d.list();
            return list != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshPermState() {
        updatePermCard();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
        }
    }

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    toast("打不开权限页面，去系统设置里手动开一下");
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // requestPermissions 是 API 23 才有的
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            toast("去系统设置里手动开一下存储权限");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermCard();
    }

    /** 从系统设置切回来时窗口重新获得焦点，立刻重查一次 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updatePermCard();
    }

    // ================= 扫描 =================

    private void doScan() {
        final String path = inPath.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            toast("先填输入目录");
            return;
        }
        final File dir = new File(path);
        if (!dir.isDirectory()) {
            toast("这个目录不存在，或者读不到");
            appendLog("✗ 读不到这个目录：" + path
                    + (hasAllFiles() ? "" : "（缺「所有文件访问」权限）"));
            return;
        }
        if (scanBtn != null) scanBtn.setEnabled(false);
        countText.setText("扫描中…");
        final boolean rec = stRec;
        new Thread(() -> {
            final List<Scanner.Item> found = Scanner.scan(dir, rec);
            runOnUiThread(() -> {
                items.clear();
                items.addAll(found);
                rebuildList();
                if (scanBtn != null) scanBtn.setEnabled(true);
                appendLog("扫描 " + path + " → " + found.size() + " 本");
            });
        }).start();
    }

    private void rebuildList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        itemBoxes.clear();

        int shown = Math.min(items.size(), MAX_INLINE_ROWS);
        for (int i = 0; i < items.size(); i++) {
            if (i >= shown) {
                itemBoxes.add(null);
                continue;
            }
            final Scanner.Item it = items.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setChecked(it.selected);
            cb.setOnCheckedChangeListener((bv, checked) -> {
                it.selected = checked;
                updateCount();
            });
            LinearLayout row = md3.listItem(cb, it.name(),
                    it.kind + " · " + it.sizeText(), i % 2 == 1);
            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, md3.dp(3), 0, md3.dp(3));
            row.setLayoutParams(lp);
            listBox.addView(row);
            itemBoxes.add(cb);
        }

        if (items.isEmpty()) {
            TextView t = md3.bodySmall("这里没找到能转换的文件", md3.onSurfaceVariant);
            t.setPadding(md3.dp(4), md3.dp(10), 0, md3.dp(6));
            listBox.addView(t);
        } else if (items.size() > shown) {
            TextView t = md3.bodySmall("还有 " + (items.size() - shown)
                    + " 本没显示出来，太多会卡。点右上角「批量选择」能选到所有书",
                    md3.error);
            t.setPadding(md3.dp(4), md3.dp(10), 0, 0);
            listBox.addView(t);
        }
        updateCount();
    }

    private void updateCount() {
        int sel = 0;
        for (Scanner.Item it : items) if (it.selected) sel++;
        if (countText != null) {
            countText.setText("找到 " + items.size() + " 本，已选 " + sel + " 本");
        }
    }

    private void setAll(boolean v) {
        for (Scanner.Item it : items) it.selected = v;
        for (CheckBox cb : itemBoxes) {
            if (cb != null) cb.setChecked(v);
        }
        updateCount();
    }

    /** ListView 用回收的适配器，几百本也不卡 */
    private final class PickAdapter extends BaseAdapter {
        private final List<Scanner.Item> data;

        PickAdapter(List<Scanner.Item> data) { this.data = data; }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Md3.ListRow r;
            if (convert != null && convert.getTag() instanceof Md3.ListRow) {
                r = (Md3.ListRow) convert.getTag();
            } else {
                r = md3.listRow();
                r.root.setTag(r);
            }
            final Scanner.Item it = data.get(pos);
            // 回收前必须先断开旧监听，否则 setChecked 会改到上一个条目的选中状态
            r.box.setOnCheckedChangeListener(null);
            md3.bindListRow(r, it.name() + "  [" + it.kind + "]",
                    it.sizeText(), it.selected, pos % 2 == 1);
            r.box.setOnCheckedChangeListener((bv, checked) -> it.selected = checked);
            r.root.setOnClickListener(v -> r.box.setChecked(!r.box.isChecked()));
            return r.root;
        }
    }

    /** 全量挑选弹窗，几百本也不卡 */
    private void showPickDialog() {
        if (items.isEmpty()) {
            toast("先扫描");
            return;
        }
        int sel = 0;
        for (Scanner.Item it : items) if (it.selected) sel++;

        TextView sub = md3.bodySmall("共 " + items.size() + " 本，已选 " + sel + " 本",
                md3.onSurfaceVariant);
        sub.setPadding(0, 0, 0, md3.dp(8));

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setBackgroundColor(md3.surfaceContainerHigh);
        lv.setAdapter(new PickAdapter(items));
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, md3.dp(400)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sub);
        box.addView(lv);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        md3.dialog("选要转的书", box, "确定",
                () -> rebuildList(), "取消", () -> rebuildList()).show();
    }

    private void showLogDialog() {
        ScrollView sv = new ScrollView(this);
        TextView t = md3.bodySmall(log.length() == 0 ? "（还没有记录）" : log.toString(),
                md3.onSurface);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setTextIsSelectable(true);
        int p = md3.dp(12);
        t.setPadding(p, p, p, p);
        t.setBackground(md3.filled(md3.surfaceContainerLowest, md3.shapeSmall()));
        sv.addView(t);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, md3.dp(380)));
        md3.dialog("记录", sv, "关闭", null, null, null).show();
    }

    // ================= 转换 =================

    private void doConvert() {
        if (ConvertService.running) {
            toast("已经在转了");
            return;
        }
        List<Scanner.Item> sel = new ArrayList<>();
        for (Scanner.Item it : items) if (it.selected) sel.add(it);
        if (sel.isEmpty()) {
            toast("一本都没选");
            return;
        }
        String op = outPath.getText().toString().trim();
        if (TextUtils.isEmpty(op)) {
            toast("先填输出目录");
            return;
        }
        File dir = new File(op);
        if (!dir.exists() && !dir.mkdirs()) {
            toast("输出目录创建不了");
            appendLog("✗ 输出目录建不了：" + op);
            return;
        }
        if (!dir.isDirectory() || !dir.canWrite()) {
            toast("输出目录写不进去");
            return;
        }

        Converter.Options opt = new Converter.Options();
        opt.outFormat = outFormat;
        opt.rtl = stRtl;
        opt.overwrite = stOw;
        opt.kindleSafe = stKs;
        opt.author = authorBox.getText().toString().trim();

        if (outFormat == Converter.OUT_MOBI && !opt.kindleSafe) {
            TextView msg = md3.bodyMedium("MOBI 这格式有点老，一张图超过 127KB 就容易出问题，"
                    + "而漫画页一般都有几百 KB 到 2MB。\n\n"
                    + "按现在的设置，图是原样放进去的，画质最好，"
                    + "但有些老 Kindle 可能打不开这些页。\n\n"
                    + "要是想让老机器一定打得开，勾上「Kindle 兼容模式」，"
                    + "图会被压小一点，画质有损失。\n\n"
                    + "另外亚马逊已经不推 MOBI 了，新 Kindle 都直接看 EPUB，建议还是选 EPUB。",
                    md3.onSurfaceVariant);
            msg.setLineSpacing(md3.dp(4), 1f);
            msg.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            md3.dialog("关于 MOBI", msg,
                    "还是继续", () -> startConvert(sel, dir, opt),
                    "换成 EPUB", () -> selectFormat(Converter.OUT_EPUB)).show();
            return;
        }
        startConvert(sel, dir, opt);
    }

    private void startConvert(List<Scanner.Item> sel, File dir, Converter.Options opt) {
        savePrefs();
        log.setLength(0);
        appendLog("转成 " + Converter.nameOf(opt.outFormat)
                + "，放到 " + dir.getAbsolutePath());
        appendLog("一共 " + sel.size() + " 本，开始");

        ConvertService.pending = sel;
        ConvertService.outDir = dir;
        ConvertService.options = opt;
        Intent i = new Intent(this, ConvertService.class);
        i.setAction(ConvertService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        setBusy(true);
        if (statusText != null) statusText.setTextColor(md3.onSurfaceVariant);
        if (pageBar != null) pageBar.setFraction(0f);
    }

    private void doCancel() {
        Intent i = new Intent(this, ConvertService.class);
        i.setAction(ConvertService.ACTION_CANCEL);
        startService(i);
        ConvertService.cancelFlag.set(true);
        appendLog("已经取消了，等手上这页写完");
        if (cancelBtn != null) cancelBtn.setEnabled(false);
    }

    private void setBusy(boolean b) {
        busy = b;
        if (convertBtn != null) {
            convertBtn.setEnabled(!b);
            convertBtn.setAlpha(b ? 0.5f : 1f);
        }
        if (cancelBtn != null) {
            cancelBtn.setEnabled(b);
            cancelBtn.setAlpha(b ? 1f : 0.45f);
        }
        if (scanBtn != null) scanBtn.setEnabled(!b);
    }

    // ================= 杂项 =================

    private void appendLog(String s) {
        log.append(s).append('\n');
        if (log.length() > 200000) log.delete(0, log.length() - 150000);
        renderLogTail();
    }

    private void renderLogTail() {
        if (logText == null) return;
        String[] lines = log.toString().split("\n");
        int from = Math.max(0, lines.length - LOG_TAIL_LINES);
        StringBuilder tail = new StringBuilder();
        if (from > 0) {
            tail.append("…（前面还有 ").append(from).append(" 行，点「查看全部」看）\n");
        }
        for (int i = from; i < lines.length; i++) tail.append(lines[i]).append('\n');
        logText.setText(tail.toString().trim());
    }

    private void toast(String s) {
        if (rootView != null) md3.snackbar(rootView, s);
    }

    /** 只读偏好到状态字段，界面由 buildUi 按这些值初始化 */
    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        File ext = Environment.getExternalStorageDirectory();
        String defIn = new File(ext, "漫画").isDirectory()
                ? new File(ext, "漫画").getAbsolutePath()
                : ext.getAbsolutePath();
        stIn = p.getString("in", defIn);
        stOut = p.getString("out", new File(ext, "Download/ComicConv").getAbsolutePath());
        stAuthor = p.getString("author", "");
        outFormat = p.getInt("fmt", 0);
        if (outFormat < 0 || outFormat >= FMT_NAMES.length) outFormat = 0;
        stRec = p.getBoolean("rec", true);
        stRtl = p.getBoolean("rtl", true);
        stOw = p.getBoolean("ow", false);
        stKs = p.getBoolean("ks", false);
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("in", inPath.getText().toString())
                .putString("out", outPath.getText().toString())
                .putString("author", authorBox.getText().toString())
                .putInt("fmt", outFormat)
                .putBoolean("rec", stRec)
                .putBoolean("rtl", stRtl)
                .putBoolean("ow", stOw)
                .putBoolean("ks", stKs)
                .apply();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignore) {}
        super.onDestroy();
    }
}

package com.manga.comicconv;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material Design 3 设计系统（手写实现）。
 *
 * 为什么不用 com.google.android.material：那套库强依赖 AndroidX，而这个项目是
 * javac + d8 + aapt 手工构建的，没有 Gradle，AAR 里的 res 也没法用 aapt2 合并
 * （aapt2 只有 x86_64 版本，这台 aarch64 机器跑不了）。
 *
 * 所以这里按 MD3 规范把需要的东西画出来：
 *  - 颜色：完整的 MD3 颜色角色（primary / container / surface 系列 / outline …），
 *    跟随系统深浅色
 *  - 字体：MD3 type scale（display / headline / title / body / label）
 *  - 形状：MD3 shape scale（4 / 8 / 12 / 16 / 28dp）
 *  - 组件：top app bar、filled/outlined/text button、card、filter chip、
 *    outlined text field（带浮动标签）、switch、list item、linear progress、snackbar
 *
 * 所有尺寸都按 dp 算，平板上会自动放大间距和触控区。
 */
public final class Md3 {

    // ==================== 颜色角色 ====================
    public final int primary, onPrimary, primaryContainer, onPrimaryContainer;
    public final int secondary, onSecondary, secondaryContainer, onSecondaryContainer;
    public final int tertiary, onTertiary, tertiaryContainer, onTertiaryContainer;
    public final int error, onError, errorContainer, onErrorContainer;
    public final int surface, onSurface, surfaceVariant, onSurfaceVariant;
    public final int surfaceContainerLowest, surfaceContainerLow, surfaceContainer;
    public final int surfaceContainerHigh, surfaceContainerHighest;
    public final int outline, outlineVariant;
    public final int inverseSurface, inverseOnSurface;
    public final boolean dark;

    private final Context ctx;

    public Md3(Context c) {
        this.ctx = c;
        int night = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        dark = night == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            primary = 0xFFBBC3FF; onPrimary = 0xFF1A2A7A;
            primaryContainer = 0xFF31419B; onPrimaryContainer = 0xFFDEE0FF;
            secondary = 0xFFC5C4DD; onSecondary = 0xFF2E2F42;
            secondaryContainer = 0xFF444559; onSecondaryContainer = 0xFFE1E0F9;
            tertiary = 0xFFF0BF48; onTertiary = 0xFF402D00;
            tertiaryContainer = 0xFF5C4200; onTertiaryContainer = 0xFFFFDF9E;
            error = 0xFFFFB4AB; onError = 0xFF690005;
            errorContainer = 0xFF93000A; onErrorContainer = 0xFFFFDAD6;
            surface = 0xFF131318; onSurface = 0xFFE5E1E6;
            surfaceVariant = 0xFF46464F; onSurfaceVariant = 0xFFC7C5D0;
            surfaceContainerLowest = 0xFF0E0E13;
            surfaceContainerLow = 0xFF1B1B21;
            surfaceContainer = 0xFF1F1F25;
            surfaceContainerHigh = 0xFF2A2A30;
            surfaceContainerHighest = 0xFF35353B;
            outline = 0xFF91909A; outlineVariant = 0xFF46464F;
            inverseSurface = 0xFFE5E1E6; inverseOnSurface = 0xFF303034;
        } else {
            primary = 0xFF4A5BB5; onPrimary = 0xFFFFFFFF;
            primaryContainer = 0xFFDEE0FF; onPrimaryContainer = 0xFF00105C;
            secondary = 0xFF5C5D72; onSecondary = 0xFFFFFFFF;
            secondaryContainer = 0xFFE1E0F9; onSecondaryContainer = 0xFF191A2C;
            tertiary = 0xFF7A5900; onTertiary = 0xFFFFFFFF;
            tertiaryContainer = 0xFFFFDF9E; onTertiaryContainer = 0xFF261A00;
            error = 0xFFBA1A1A; onError = 0xFFFFFFFF;
            errorContainer = 0xFFFFDAD6; onErrorContainer = 0xFF410002;
            surface = 0xFFFBF8FD; onSurface = 0xFF1B1B1F;
            surfaceVariant = 0xFFE3E1EC; onSurfaceVariant = 0xFF46464F;
            surfaceContainerLowest = 0xFFFFFFFF;
            surfaceContainerLow = 0xFFF5F2FA;
            surfaceContainer = 0xFFEFECF4;
            surfaceContainerHigh = 0xFFE9E6EE;
            surfaceContainerHighest = 0xFFE3E0E8;
            outline = 0xFF777680; outlineVariant = 0xFFC7C5D0;
            inverseSurface = 0xFF303034; inverseOnSurface = 0xFFF3EFF4;
        }
    }

    // ==================== 尺寸 / 形状 ====================

    public int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    /** MD3 shape scale */
    public int shapeExtraSmall() { return dp(4); }
    public int shapeSmall() { return dp(8); }
    public int shapeMedium() { return dp(12); }
    public int shapeLarge() { return dp(16); }
    public int shapeExtraLarge() { return dp(28); }

    public GradientDrawable filled(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    public GradientDrawable stroked(int fill, int strokeColor, int strokeDp, int radius) {
        GradientDrawable g = filled(fill, radius);
        g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    public RippleDrawable ripple(int bgColor, int radius, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                filled(bgColor, radius), null);
    }

    /** 屏幕宽度（dp），用于判断平板/手机 */
    public int screenWidthDp() {
        return ctx.getResources().getConfiguration().screenWidthDp;
    }

    /** MD3 断点：compact < 600 <= medium < 840 <= expanded */
    public boolean isCompact() { return screenWidthDp() < 600; }
    public boolean isExpanded() { return screenWidthDp() >= 840; }

    public int screenHeightDp() {
        return ctx.getResources().getConfiguration().screenHeightDp;
    }

    /**
     * 是否用双栏。
     *
     * 光看宽度不够：手机横屏也有 800dp 宽，但只有 400dp 高，硬塞双栏会挤成一团。
     * 所以要求宽度 >= 600dp 且高度 >= 480dp —— 平板竖屏、平板横屏都满足，
     * 手机竖屏和横屏都还是单列。
     */
    public boolean isTwoPane() {
        return screenWidthDp() >= 600 && screenHeightDp() >= 480;
    }

    // ==================== 字体阶梯 ====================

    public TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public TextView titleLarge(String s, int color) { return text(s, 22, color, false); }
    public TextView titleMedium(String s, int color) { return text(s, 16, color, true); }
    public TextView titleSmall(String s, int color) { return text(s, 14, color, true); }
    public TextView bodyLarge(String s, int color) { return text(s, 16, color, false); }
    public TextView bodyMedium(String s, int color) { return text(s, 14, color, false); }
    public TextView bodySmall(String s, int color) { return text(s, 12, color, false); }

    public TextView labelLarge(String s, int color) {
        TextView t = text(s, 14, color, true);
        t.setLetterSpacing(0.008f);
        return t;
    }

    public TextView labelMedium(String s, int color) {
        TextView t = text(s, 12, color, true);
        t.setLetterSpacing(0.04f);
        return t;
    }

    public TextView labelSmall(String s, int color) {
        TextView t = text(s, 11, color, true);
        t.setLetterSpacing(0.05f);
        return t;
    }

    // ==================== 组件 ====================

    /** MD3 small top app bar */
    public LinearLayout topAppBar(String title, String subtitle) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(surface);
        boolean wide = !isCompact();
        int px = dp(wide ? 24 : 16);
        bar.setPadding(px, dp(wide ? 14 : 12), px, dp(wide ? 14 : 12));

        TextView t = titleLarge(title, onSurface);
        if (wide) t.setTextSize(24);
        bar.addView(t);

        if (subtitle != null) {
            TextView s = bodySmall(subtitle, onSurfaceVariant);
            s.setPadding(0, dp(2), 0, 0);
            bar.addView(s);
        }
        return bar;
    }

    /** MD3 filled button（胶囊形） */
    public Button filledButton(String label) {
        Button b = baseButton(label, onPrimary, 14, true);
        b.setBackground(ripple(primary, dp(20), 0x33FFFFFF));
        return b;
    }

    /** MD3 outlined button */
    public Button outlinedButton(String label) {
        Button b = baseButton(label, primary, 14, true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1F4A5BB5),
                stroked(Color.TRANSPARENT, outline, 1, dp(20)), null));
        return b;
    }

    /** MD3 text button */
    public Button textButton(String label) {
        Button b = baseButton(label, primary, 14, true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1F4A5BB5),
                filled(Color.TRANSPARENT, dp(20)), null));
        return b;
    }

    /** MD3 tonal button（次要操作） */
    public Button tonalButton(String label) {
        Button b = baseButton(label, onSecondaryContainer, 14, true);
        b.setBackground(ripple(secondaryContainer, dp(20), 0x334A5BB5));
        return b;
    }

    private Button baseButton(String label, int textColor, float sp, boolean bold) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(sp);
        b.setTextColor(textColor);
        if (bold) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.008f);
        b.setPadding(dp(24), dp(10), dp(24), dp(10));
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setElevation(0);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /** MD3 filled card */
    public LinearLayout card() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(filled(surfaceContainerLow, shapeMedium()));
        int p = dp(16);
        l.setPadding(p, dp(14), p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(6), dp(16), dp(6));
        l.setLayoutParams(lp);
        return l;
    }

    /** MD3 outlined card */
    public LinearLayout outlinedCard() {
        LinearLayout l = card();
        l.setBackground(stroked(surface, outlineVariant, 1, shapeMedium()));
        return l;
    }

    /** 卡片小标题（MD3 titleSmall + 前导圆点） */
    public LinearLayout cardHeader(String title) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(10));

        View dot = new View(ctx);
        dot.setBackground(filled(primary, dp(4)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.setMargins(0, 0, dp(8), 0);
        dot.setLayoutParams(dlp);
        row.addView(dot);

        row.addView(titleSmall(title, onSurfaceVariant));
        return row;
    }

    /** MD3 filter chip */
    public TextView filterChip(String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.008f);
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(36), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        chipStyle(t, false);
        return t;
    }

    public void chipStyle(TextView chip, boolean selected) {
        if (selected) {
            // 用实心主色而不是 secondaryContainer：后者的浅紫压在卡片底色上
            // 几乎没有对比，看起来就像「颜色没了」。
            chip.setBackground(filled(primary, shapeSmall()));
            chip.setTextColor(onPrimary);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            chip.setBackground(stroked(Color.TRANSPARENT, outline, 1, shapeSmall()));
            chip.setTextColor(onSurfaceVariant);
            chip.setTypeface(Typeface.DEFAULT);
        }
    }

    /**
     * MD3 outlined text field。
     *
     * 标签放在框内左上角、输入文字上方，不用负 margin。之前把标签往上顶
     * 到边框外，但 FrameLayout 默认 clipChildren，标签上半截会被裁掉，
     * 显示成「字被遮挡」。现在改成框内分层：上面标签、下面输入文字，
     * 各自留足空间，任何父容器下都不会被裁。
     */
    public FrameLayout textField(String label, EditText input) {
        FrameLayout box = new FrameLayout(ctx);
        box.setBackground(stroked(surfaceContainerLowest, outline, 1, shapeExtraSmall()));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        box.setLayoutParams(blp);

        input.setBackground(null);
        input.setTextSize(15);
        input.setTextColor(onSurface);
        input.setHintTextColor(onSurfaceVariant);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(25), dp(16), dp(5));
        box.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView lab = text(label, 11, onSurfaceVariant, false);
        lab.setLetterSpacing(0.02f);
        lab.setSingleLine(true);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(16);
        llp.topMargin = dp(6);
        box.addView(lab, llp);
        return box;
    }

    /** MD3 switch 的开关回调 */
    public interface OnToggle {
        void onToggle(boolean checked);
    }

    /** MD3 switch 行：标题 + 说明 + 右侧开关（内部新建开关） */
    public LinearLayout switchRow(String title, String supporting, boolean checked,
                                  OnToggle cb) {
        return switchRow(title, supporting, new Switch(ctx, checked), cb);
    }

    /** MD3 switch 行：外部传入开关，便于后续改启用状态 */
    public LinearLayout switchRow(String title, String supporting, Switch sw,
                                  OnToggle cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setMinimumHeight(dp(48));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(bodyMedium(title, onSurface));
        if (supporting != null) {
            TextView s = bodySmall(supporting, onSurfaceVariant);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(52), dp(32));
        slp.setMargins(dp(12), 0, 0, 0);
        sw.setLayoutParams(slp);
        sw.setOnClickListener(v -> {
            sw.toggle();
            if (cb != null) cb.onToggle(sw.isChecked());
        });
        row.addView(sw);
        row.setOnClickListener(v -> {
            sw.toggle();
            if (cb != null) cb.onToggle(sw.isChecked());
        });
        return row;
    }

    /** MD3 开关本体 */
    public static final class Switch extends FrameLayout {
        private final View thumb;
        private final GradientDrawable track = new GradientDrawable();
        private boolean checked;
        private final int onColor, offTrack, offThumb, thumbOn;
        private final int w, h, thumbSize, pad;

        Switch(Context c, boolean checked) {
            super(c);
            this.checked = checked;
            Md3 m = new Md3(c);
            onColor = m.primary;
            offTrack = m.surfaceContainerHighest;
            offThumb = m.outline;
            thumbOn = m.onPrimary;
            w = m.dp(52); h = m.dp(32);
            thumbSize = m.dp(24); pad = m.dp(4);

            track.setShape(GradientDrawable.RECTANGLE);
            track.setCornerRadius(h / 2f);
            setBackground(track);

            thumb = new View(c);
            GradientDrawable td = new GradientDrawable();
            td.setShape(GradientDrawable.OVAL);
            td.setColor(offThumb);
            thumb.setBackground(td);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(thumbSize, thumbSize);
            lp.topMargin = (h - thumbSize) / 2;
            lp.leftMargin = pad;
            addView(thumb, lp);
            apply(false);
        }

        public boolean isChecked() { return checked; }

        void toggle() {
            checked = !checked;
            apply(true);
        }

        private void apply(boolean animate) {
            track.setColor(checked ? onColor : offTrack);
            GradientDrawable td = new GradientDrawable();
            td.setShape(GradientDrawable.OVAL);
            td.setColor(checked ? thumbOn : offThumb);
            thumb.setBackground(td);
            float target = checked ? (w - thumbSize - pad) : pad;
            if (animate) {
                thumb.animate().translationX(target - pad).setDuration(140).start();
            } else {
                thumb.setTranslationX(target - pad);
            }
        }
    }

    /** MD3 list item：复选框 + 主标题 + 副标题 */
    public LinearLayout listItem(android.widget.CheckBox box, String title, String supporting,
                                 boolean alt) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(56));
        row.setBackground(filled(alt ? surfaceContainer : surfaceContainerLow,
                shapeSmall()));

        box.setButtonTintList(ColorStateList.valueOf(primary));
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(box);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.setPadding(dp(4), 0, 0, 0);

        TextView t = bodyMedium(title, onSurface);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(t);

        TextView s = bodySmall(supporting, onSurfaceVariant);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);

        row.addView(texts);
        return row;
    }

    public View divider() {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(10), 0, dp(10));
        v.setLayoutParams(lp);
        v.setBackgroundColor(outlineVariant);
        return v;
    }

    /** MD3 linear progress indicator（圆头） */
    public final class LinearProgress extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float frac;

        public LinearProgress() {
            super(Md3.this.ctx);
            setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        }

        public void setFraction(float f) {
            frac = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            p.setColor(surfaceVariant);
            cv.drawRoundRect(0, 0, w, h, r, r, p);
            if (frac > 0f) {
                float iw = Math.max(h, w * frac);
                p.setColor(primary);
                cv.drawRoundRect(0, 0, iw, h, r, r, p);
            }
        }
    }

    /** MD3 snackbar，挂在 FrameLayout 根上，2.6 秒后自动消失 */
    public void snackbar(ViewGroup root, String msg) {
        if (!(root instanceof FrameLayout)) return;
        final TextView t = bodyMedium(msg, inverseOnSurface);
        t.setBackground(filled(inverseSurface, shapeExtraSmall()));
        int px = dp(16);
        t.setPadding(px, dp(14), px, dp(14));
        t.setElevation(dp(6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(88);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        t.setLayoutParams(lp);
        t.setAlpha(0f);
        ((FrameLayout) root).addView(t);
        t.animate().alpha(1f).setDuration(150).start();
        t.postDelayed(() -> t.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> ((FrameLayout) root).removeView(t)).start(), 2600);
    }

    // ==================== 弹窗 ====================

    /**
     * MD3 弹窗。
     *
     * 不用系统 AlertDialog：Activity 主题固定是 DeviceDefault.Light，
     * 而这里的色板跟随系统深浅色，深色模式下会变成「黑色界面 + 白色弹窗」。
     * 所以自己画：surfaceContainerHigh 底、28dp 圆角、24dp 内边距、右侧文字按钮。
     */
    public Dialog dialog(String title, View content, String posLabel, Runnable onPos,
                         String negLabel, Runnable onNeg) {
        final Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        root.setPadding(p, p, p, dp(12));
        root.setBackground(filled(surfaceContainerHigh, shapeExtraLarge()));

        if (title != null && !title.isEmpty()) {
            root.addView(text(title, 20, onSurface, false));
        }

        if (content != null) {
            // 保留内容自带的尺寸（比如日志框固定 360dp 高），只补上间距
            LinearLayout.LayoutParams clp = content.getLayoutParams()
                    instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) content.getLayoutParams()
                    : new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(16);
            root.addView(content, clp);
        }

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        btns.setLayoutParams(blp);

        if (negLabel != null) {
            Button b = textButton(negLabel);
            b.setOnClickListener(v -> {
                d.dismiss();
                if (onNeg != null) onNeg.run();
            });
            btns.addView(b);
        }
        if (posLabel != null) {
            Button b = textButton(posLabel);
            b.setOnClickListener(v -> {
                d.dismiss();
                if (onPos != null) onPos.run();
            });
            btns.addView(b);
        }
        root.addView(btns);

        d.setContentView(root);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // MD3 弹窗宽度：手机上留 24dp 边距，平板上最宽 560dp
            int maxW = ctx.getResources().getDisplayMetrics().widthPixels - dp(48);
            w.setLayout(Math.min(dp(560), maxW), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    /** 可复用的列表行（供 ListView 回收） */
    public static final class ListRow {
        public LinearLayout root;
        public android.widget.CheckBox box;
        public TextView title, sub;
    }

    public ListRow listRow() {
        ListRow r = new ListRow();
        r.root = new LinearLayout(ctx);
        r.root.setOrientation(LinearLayout.HORIZONTAL);
        r.root.setGravity(Gravity.CENTER_VERTICAL);
        r.root.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.root.setMinimumHeight(dp(56));

        r.box = new android.widget.CheckBox(ctx);
        r.box.setButtonTintList(ColorStateList.valueOf(primary));
        r.root.addView(r.box);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.setPadding(dp(4), 0, 0, 0);

        r.title = bodyMedium("", onSurface);
        r.title.setMaxLines(2);
        r.title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(r.title);

        r.sub = bodySmall("", onSurfaceVariant);
        r.sub.setPadding(0, dp(2), 0, 0);
        texts.addView(r.sub);

        r.root.addView(texts);
        return r;
    }

    public void bindListRow(ListRow r, String title, String sub, boolean checked, boolean alt) {
        r.title.setText(title);
        r.sub.setText(sub);
        r.box.setChecked(checked);
        r.root.setBackground(filled(alt ? surfaceContainer : surfaceContainerLow,
                shapeSmall()));
    }
}

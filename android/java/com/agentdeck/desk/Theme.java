package com.agentdeck.desk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 两个页面共用的配色、字体、尺寸与格式化工具。 */
final class Theme {
    static final int BG = 0xFF07090F;
    static final int SURFACE = 0xFF0E131C;
    static final int SURFACE_HI = 0xFF151C28;
    static final int STROKE = 0xFF1C2532;
    static final int TEXT = 0xFFE6EDF3;
    static final int MUTED = 0xFF8B949E;
    static final int FAINT = 0xFF4A5566;
    static final int GREEN = 0xFF3FB950;
    static final int RED = 0xFFF85149;
    static final int BRAND_A = 0xFF6EE7B7;
    static final int BRAND_B = 0xFF8AB4F8;
    static final int BRAND_C = 0xFFA78BFA;

    static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);

    private Theme() {}

    static float density(Context c) { return c.getResources().getDisplayMetrics().density; }

    static int dp(Context c, float v) { return Math.round(v * density(c)); }

    static int alpha(int color, float a) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (color & 0xFFFFFF);
    }

    /** 线性混色，t=0 取 a，t=1 取 b。 */
    static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    /** 面板背景：左上角带一点强调色的渐变 + 细描边。 */
    static GradientDrawable panel(Context c, int tint, float tintAmount, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{mix(SURFACE, tint, tintAmount), SURFACE});
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, 1)), STROKE);
        return g;
    }

    static GradientDrawable pill(Context c, int color, float fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(alpha(color, fill));
        g.setCornerRadius(dp(c, 99));
        return g;
    }

    static TextView text(Context c, int color, float sp, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setIncludeFontPadding(false);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    static int color(String c, int fallback) {
        if (c != null && c.startsWith("#") && c.length() == 7) {
            try { return Color.parseColor(c); } catch (IllegalArgumentException ignored) {}
        }
        return fallback;
    }

    static String fmt(long n) {
        if (n >= 1_000_000_000) return String.format(Locale.US, "%.2fB", n / 1e9);
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    /** 紧凑格式：三位有效数字以内，308M / 72.1M / 2.8M。 */
    static String fmtShort(long n) {
        String[] u = {"", "K", "M", "B"};
        double v = n;
        int i = 0;
        while (v >= 1000 && i < u.length - 1) { v /= 1000; i++; }
        if (i == 0) return String.valueOf(n);
        return (v >= 100 ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v)) + u[i];
    }

    static String money(double v) { return String.format(Locale.US, "$%.2f", v); }

    static String ago(long ts) {
        long min = Math.max(0, System.currentTimeMillis() - ts) / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hr = min / 60;
        if (hr < 24) return hr + " 小时前";
        long d = hr / 24;
        if (d < 30) return d + " 天前";
        return new SimpleDateFormat("M/d", Locale.getDefault()).format(new Date(ts));
    }
}

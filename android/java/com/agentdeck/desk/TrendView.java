package com.agentdeck.desk;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Token 随时间走势图 —— 渐变柱状图。
 * 数据来自服务端 /api/agents/<id> 的 series.hourly / series.daily。
 * 柱子从左到右依次升起；手指按住/拖动可查看单根柱的数值。
 */
public class TrendView extends View {
    private long[] data;
    private String[] labels;
    private int accent = Theme.BRAND_A;
    private String title = "";
    private long max = 1, sum = 0;
    private float progress = 0f;
    private int selected = -1;
    private ValueAnimator anim;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    // 布局缓存（onDraw 计算，触摸命中复用）
    private float plotLeft, plotRight, slot;

    public TrendView(Context c) {
        super(c);
        float d = Theme.density(c);
        labelPaint.setColor(Theme.MUTED);
        labelPaint.setTextSize(9.5f * d);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setColor(Theme.TEXT);
        titlePaint.setTextSize(13 * d);
        titlePaint.setTypeface(Theme.BOLD);
        valuePaint.setTextSize(13 * d);
        valuePaint.setTypeface(Theme.BOLD);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
        gridPaint.setColor(Theme.STROKE);
        gridPaint.setStrokeWidth(Math.max(1, d));
        gridPaint.setPathEffect(new DashPathEffect(new float[]{3 * d, 4 * d}, 0));
        tipPaint.setTextSize(11 * d);
        tipPaint.setTypeface(Theme.BOLD);
        tipPaint.setTextAlign(Paint.Align.CENTER);
    }

    private String[] tips;

    /** 触摸提示用的完整时间文本（轴标签是稀疏的，提示需要每根柱都有）。 */
    public void setTips(String[] tips) { this.tips = tips; }

    public void setData(long[] data, String[] labels, int accent, String title) {
        this.data = data;
        this.labels = labels;
        this.accent = accent;
        this.title = title;
        this.max = 1;
        this.sum = 0;
        if (data != null) for (long v : data) { if (v > max) max = v; sum += v; }
        bar.setShader(null);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1200);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.end();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (data == null || data.length == 0 || slot <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                int i = (int) ((e.getX() - plotLeft) / slot);
                i = Math.max(0, Math.min(data.length - 1, i));
                if (i != selected) { selected = i; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                selected = -1;
                invalidate();
                return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Context c = getContext();
        float rad = Theme.dp(c, 16);

        // 面板背景
        bg.setShader(new LinearGradient(0, 0, w, h, Theme.mix(Theme.SURFACE, accent, 0.07f), Theme.SURFACE, Shader.TileMode.CLAMP));
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, rad, rad, bg);
        bg.setShader(null);
        bg.setStyle(Paint.Style.STROKE);
        bg.setStrokeWidth(Math.max(1, Theme.dp(c, 1)));
        bg.setColor(Theme.STROKE);
        r.inset(0.5f, 0.5f);
        canvas.drawRoundRect(r, rad, rad, bg);
        bg.setStyle(Paint.Style.FILL);

        float pad = Theme.dp(c, 16);
        float top = Theme.dp(c, 44);
        float bottom = h - Theme.dp(c, 22);
        plotLeft = pad;
        plotRight = w - pad;
        float plotH = bottom - top;

        canvas.drawText(title, pad, Theme.dp(c, 26), titlePaint);
        valuePaint.setColor(accent);
        canvas.drawText("Σ " + Theme.fmt(sum), w - pad, Theme.dp(c, 26), valuePaint);

        if (data == null || data.length == 0 || sum == 0) {
            labelPaint.setTextSize(12 * Theme.density(c));
            canvas.drawText(data == null ? "加载中…" : "该时段暂无用量", w / 2f, top + plotH / 2f, labelPaint);
            labelPaint.setTextSize(9.5f * Theme.density(c));
            return;
        }

        for (int g = 0; g <= 2; g++) {
            float gy = top + plotH * (g / 2f);
            canvas.drawLine(plotLeft, gy, plotRight, gy, gridPaint);
        }

        int n = data.length;
        slot = (plotRight - plotLeft) / n;
        float barW = Math.max(Theme.dp(c, 2), slot * 0.62f);
        if (bar.getShader() == null)
            bar.setShader(new LinearGradient(0, top, 0, bottom, accent, Theme.alpha(accent, 0.35f), Shader.TileMode.CLAMP));

        // 每根柱有自己的启动时刻，形成从左往右的波浪
        float spread = 0.45f;
        for (int i = 0; i < n; i++) {
            if (data[i] <= 0) continue;
            float local = (progress - spread * i / n) / (1 - spread);
            local = Math.max(0, Math.min(1, local));
            float eased = 1 - (1 - local) * (1 - local) * (1 - local);
            float hh = Math.max(Theme.dp(c, 2), (data[i] / (float) max) * plotH) * eased;
            if (hh <= 0) continue;
            float cx = plotLeft + slot * i + slot / 2f;
            bar.setAlpha(selected < 0 || selected == i ? 255 : 90);
            canvas.drawRoundRect(cx - barW / 2f, bottom - hh, cx + barW / 2f, bottom, barW / 2f, barW / 2f, bar);
        }
        bar.setAlpha(255);

        // X 轴标签：只取非空标签，且保证彼此不重叠
        float lastX = -1e9f;
        float minGap = Theme.dp(c, 30);
        for (int i = 0; i < n; i++) {
            if (labels == null || i >= labels.length || labels[i] == null || labels[i].isEmpty()) continue;
            float cx = plotLeft + slot * i + slot / 2f;
            if (cx - lastX < minGap || cx > plotRight - Theme.dp(c, 10)) continue;
            canvas.drawText(labels[i], cx, h - Theme.dp(c, 7), labelPaint);
            lastX = cx;
        }

        if (selected >= 0) drawTip(canvas, c, selected, bottom, plotH, top);
    }

    private void drawTip(Canvas canvas, Context c, int i, float bottom, float plotH, float top) {
        float cx = plotLeft + slot * i + slot / 2f;
        String lb = tips != null && i < tips.length ? tips[i]
                : labels != null && i < labels.length ? labels[i] : "";
        String s = Theme.fmt(data[i]) + (lb.isEmpty() ? "" : "  ·  " + lb);
        float tw = tipPaint.measureText(s) + Theme.dp(c, 16);
        float th = Theme.dp(c, 22);
        float barTop = bottom - (data[i] / (float) max) * plotH;
        float ty = Math.max(top - th + Theme.dp(c, 4), barTop - th - Theme.dp(c, 6));
        float tx = Math.max(plotLeft, Math.min(plotRight - tw, cx - tw / 2f));
        r.set(tx, ty, tx + tw, ty + th);
        bg.setColor(Theme.SURFACE_HI);
        canvas.drawRoundRect(r, th / 2f, th / 2f, bg);
        bg.setStyle(Paint.Style.STROKE);
        bg.setColor(Theme.alpha(accent, 0.6f));
        canvas.drawRoundRect(r, th / 2f, th / 2f, bg);
        bg.setStyle(Paint.Style.FILL);
        tipPaint.setColor(Theme.TEXT);
        canvas.drawText(s, r.centerX(), r.centerY() + tipPaint.getTextSize() * 0.36f, tipPaint);
    }
}

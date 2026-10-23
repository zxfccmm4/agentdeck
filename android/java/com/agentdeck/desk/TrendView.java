package com.agentdeck.desk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Token 随时间走势图 —— 柱状图。
 * 数据来自服务端 /api/agents/<id> 的 series.hourly / series.daily。
 */
public class TrendView extends View {
    private long[] data;
    private String[] labels;
    private int accent = Color.parseColor("#6EE7B7");
    private String title = "";
    private long max = 1;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int bgColor = Color.parseColor("#151a23");

    public TrendView(Context c) {
        super(c);
        labelPaint.setColor(Color.parseColor("#8b949e"));
        labelPaint.setTextSize(10 * getResources().getDisplayMetrics().density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setColor(Color.parseColor("#e6edf3"));
        titlePaint.setTextSize(13 * getResources().getDisplayMetrics().density);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        gridPaint.setColor(Color.parseColor("#222b38"));
        gridPaint.setStrokeWidth(1);
    }

    public void setData(long[] data, String[] labels, int accent, String title) {
        this.data = data;
        this.labels = labels;
        this.accent = accent;
        this.title = title;
        this.max = 1;
        if (data != null) for (long v : data) if (v > max) max = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 背景
        int r = dp(12);
        barPaint.setColor(bgColor);
        barPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(0, 0, w, h, r, r, barPaint);

        int pad = dp(14);
        int top = dp(28); // 标题区
        int bottom = h - dp(6);
        int left = pad;
        int right = w - pad;
        int plotH = bottom - top;

        // 标题
        titlePaint.setColor(Color.parseColor("#e6edf3"));
        canvas.drawText(title, pad, dp(20), titlePaint);

        if (data == null || data.length == 0) {
            labelPaint.setTextSize(12 * getResources().getDisplayMetrics().density);
            labelPaint.setColor(Color.parseColor("#8b949e"));
            canvas.drawText("暂无数据", w / 2f, top + plotH / 2f, labelPaint);
            return;
        }

        // 网格线（2 条水平）
        gridPaint.setColor(Color.parseColor("#222b38"));
        for (int g = 0; g <= 2; g++) {
            float gy = top + plotH * (1 - g / 2f);
            // 左侧留出刻度
            canvas.drawLine(left + dp(28), gy, right, gy, gridPaint);
        }

        float slot = (right - dp(28) - left) / (float) data.length;
        float barW = Math.max(2f, slot * 0.62f);

        barPaint.setColor(accent);
        barPaint.setStyle(Paint.Style.FILL);

        // 柱状
        for (int i = 0; i < data.length; i++) {
            float hh = (data[i] == 0) ? 0 : (data[i] / (float) max) * plotH;
            float cx = left + dp(28) + slot * i + slot / 2f;
            if (hh <= 0) continue;
            canvas.drawRoundRect(cx - barW / 2f, top + plotH - hh, cx + barW / 2f, top + plotH, barW / 2f, barW / 2f, barPaint);
        }

        // X 轴标签（稀疏显示）
        labelPaint.setColor(Color.parseColor("#8b949e"));
        labelPaint.setTextSize(9 * getResources().getDisplayMetrics().density);
        int labelStep = Math.max(1, (int) Math.ceil(data.length / 8));
        int lblCount = 0;
        for (int i = 0; i < data.length; i += labelStep) {
            if (labels != null && i < labels.length && labels[i] != null && labels[i].length() > 0) {
                float cx = left + dp(28) + slot * i + slot / 2f;
                // 最后一个标签不要贴右缘
                if (i + labelStep >= data.length && cx > right - dp(34)) continue;
                canvas.drawText(labels[i], cx, bottom - 1, labelPaint);
                lblCount++;
            }
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
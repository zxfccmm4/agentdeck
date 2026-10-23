package com.agentdeck.desk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;

/**
 * 顶栏时钟：大号 HH:MM，变化的数字从上方滑入、旧数字向下滑出；
 * 右侧一圈秒进度环（渐变 + 头部亮点）中间显示秒数；冒号随秒闪烁。
 * 平时由 Ambient 以 10fps 刷新（秒环、冒号）；数字滑动的 420ms 内自己按屏幕帧率重绘，保证顺滑。
 */
final class ClockView extends View {
    private static final long SLIDE_MS = 420;

    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint colon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Matrix matrix = new Matrix();
    private SweepGradient sweep;

    // 4 位数字：H H M M；prev 为上一个值，changedAt 为切换时刻
    private final char[] cur = {'0', '0', '0', '0'};
    private final char[] prev = {'0', '0', '0', '0'};
    private final long[] changedAt = new long[4];
    private boolean inited;

    ClockView(Context c) {
        super(c);
        digit.setColor(Theme.TEXT);
        digit.setTypeface(Theme.LIGHT);
        digit.setTextAlign(Paint.Align.CENTER);
        digit.setFontFeatureSettings("tnum");
        colon.setColor(Theme.BRAND_A);
        colon.setTypeface(Theme.LIGHT);
        colon.setTextAlign(Paint.Align.CENTER);
        ringBg.setStyle(Paint.Style.STROKE);
        ringBg.setColor(Theme.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        secText.setColor(Theme.MUTED);
        secText.setTypeface(Theme.MEDIUM);
        secText.setTextAlign(Paint.Align.CENTER);
        secText.setFontFeatureSettings("tnum");
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); Ambient.add(this); }
    @Override protected void onDetachedFromWindow() { Ambient.remove(this); super.onDetachedFromWindow(); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int h = MeasureSpec.getSize(hSpec);
        digit.setTextSize(h * 0.86f);
        float dw = digit.measureText("0");
        int w = (int) (dw * 4 + dw * 0.55f + h * 0.18f + h * 0.92f);
        setMeasuredDimension(resolveSize(w, wSpec), h);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        sweep = null;
    }

    private void update(long now) {
        Calendar c = Calendar.getInstance();
        int hh = c.get(Calendar.HOUR_OF_DAY), mm = c.get(Calendar.MINUTE);
        char[] next = {(char) ('0' + hh / 10), (char) ('0' + hh % 10), (char) ('0' + mm / 10), (char) ('0' + mm % 10)};
        for (int i = 0; i < 4; i++) {
            if (!inited) { cur[i] = prev[i] = next[i]; continue; }
            if (next[i] != cur[i]) { prev[i] = cur[i]; cur[i] = next[i]; changedAt[i] = now; }
        }
        inited = true;
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        update(now);
        float h = getHeight();
        digit.setTextSize(h * 0.86f);
        colon.setTextSize(h * 0.80f);
        float dw = digit.measureText("0");
        float base = h * 0.80f;

        float x = dw / 2f;
        canvas.save();
        canvas.clipRect(0, 0, dw * 4 + dw * 0.55f, h);
        for (int i = 0; i < 4; i++) {
            if (i == 2) {
                // 冒号：每秒前半亮、后半暗
                long ms = System.currentTimeMillis() % 1000;
                colon.setAlpha(ms < 500 ? 255 : 70);
                canvas.drawText(":", x - dw / 2f + dw * 0.275f, base - h * 0.06f, colon);
                x += dw * 0.55f;
            }
            float p = changedAt[i] == 0 ? 1f : Math.min(1f, (now - changedAt[i]) / (float) SLIDE_MS);
            float e = 1 - (1 - p) * (1 - p) * (1 - p);
            if (p < 1f) {
                digit.setAlpha((int) (255 * (1 - e)));
                canvas.drawText(String.valueOf(prev[i]), x, base + h * 0.6f * e, digit);
                digit.setAlpha((int) (255 * e));
                canvas.drawText(String.valueOf(cur[i]), x, base - h * 0.6f * (1 - e), digit);
            } else {
                digit.setAlpha(255);
                canvas.drawText(String.valueOf(cur[i]), x, base, digit);
            }
            x += dw;
        }
        canvas.restore();
        boolean sliding = false;
        for (long c : changedAt) if (c != 0 && now - c < SLIDE_MS) sliding = true;
        if (sliding) postInvalidateOnAnimation();

        // 秒进度环
        float size = h * 0.92f;
        float cx = getWidth() - size / 2f, cy = h / 2f;
        float sw = Math.max(2, size * 0.09f);
        arc.set(cx - size / 2f + sw, cy - size / 2f + sw, cx + size / 2f - sw, cy + size / 2f - sw);
        ringBg.setStrokeWidth(sw);
        canvas.drawOval(arc, ringBg);

        long wall = System.currentTimeMillis();
        float sec = (wall / 1000) % 60 + (wall % 1000) / 1000f;
        float sweepDeg = sec / 60f * 360f;
        if (sweep == null) sweep = new SweepGradient(cx, cy,
                new int[]{Theme.alpha(Theme.BRAND_A, 0.1f), Theme.BRAND_A, Theme.BRAND_B}, new float[]{0f, 0.7f, 1f});
        // 渐变尾部始终跟着头部走
        matrix.setRotate(-90f + sweepDeg - 360f, cx, cy);
        sweep.setLocalMatrix(matrix);
        ring.setShader(sweep);
        ring.setStrokeWidth(sw);
        if (sweepDeg > 0.5f) canvas.drawArc(arc, -90f, sweepDeg, false, ring);

        double rad = Math.toRadians(-90f + sweepDeg);
        float hx = cx + (float) Math.cos(rad) * arc.width() / 2f;
        float hy = cy + (float) Math.sin(rad) * arc.height() / 2f;
        head.setColor(Theme.alpha(Theme.BRAND_B, 0.35f));
        canvas.drawCircle(hx, hy, sw * 1.3f, head);
        head.setColor(0xFFFFFFFF);
        canvas.drawCircle(hx, hy, sw * 0.55f, head);

        secText.setTextSize(size * 0.34f);
        secText.setColor(Theme.TEXT);
        canvas.drawText(String.format(java.util.Locale.US, "%02d", (int) sec), cx, cy + secText.getTextSize() * 0.36f, secText);
    }
}

package com.agentdeck.desk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * AgentDeck 标志：渐变描边圆角方块 + 三根上升的用量柱 + 右上角在线点。
 * 与 res/drawable/ic_launcher_fg.xml 同一造型；animated 时描边流转、柱子起伏。
 */
final class LogoView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final long t0 = SystemClock.uptimeMillis();
    private boolean animated = true;
    private SweepGradient sweep;

    private static final float[] BARS = {0.30f, 0.46f, 0.62f};

    LogoView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
    }

    void setAnimated(boolean on) { animated = on; sync(); invalidate(); }

    private void sync() {
        if (animated && isAttachedToWindow()) Ambient.add(this); else Ambient.remove(this);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); sync(); }
    @Override protected void onDetachedFromWindow() { Ambient.remove(this); super.onDetachedFromWindow(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = Math.min(w, h);
        float ox = (w - s) / 2f, oy = (h - s) / 2f;
        sweep = new SweepGradient(w / 2f, h / 2f,
                new int[]{Theme.BRAND_A, Theme.BRAND_B, Theme.BRAND_C, Theme.BRAND_B, Theme.BRAND_A}, null);
        bar.setShader(new LinearGradient(0, oy + s * 0.78f, 0, oy + s * 0.22f,
                Theme.BRAND_A, Theme.BRAND_B, Shader.TileMode.CLAMP));
        ring.setShader(sweep);
        rect.set(ox, oy, ox + s, oy + s);
    }

    @Override protected void onDraw(Canvas canvas) {
        float s = rect.width();
        if (s <= 0) return;
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        float sw = s * 0.075f;
        float inset = sw / 2f + s * 0.08f;
        float l = rect.left + inset, tp = rect.top + inset, r = rect.right - inset, b = rect.bottom - inset;
        float radius = (r - l) * 0.27f;

        fill.setColor(Theme.SURFACE);
        canvas.drawRoundRect(l, tp, r, b, radius, radius, fill);
        if (animated) {
            matrix.setRotate((t * 70f) % 360f, rect.centerX(), rect.centerY());
            sweep.setLocalMatrix(matrix);
        }
        ring.setStrokeWidth(sw);
        canvas.drawRoundRect(l, tp, r, b, radius, radius, ring);

        // 三根柱：位于方块 25% / 50% / 75%，底部对齐
        float side = r - l;
        float bw = side * 0.13f;
        float base = tp + side * 0.78f;
        for (int i = 0; i < 3; i++) {
            float k = animated ? 0.78f + 0.22f * (float) Math.sin(t * 2.6f + i * 1.25f) : 1f;
            float hgt = side * BARS[i] * k;
            float cx = l + side * (0.25f + 0.25f * i);
            canvas.drawRoundRect(cx - bw / 2f, base - hgt, cx + bw / 2f, base, bw / 2f, bw / 2f, bar);
        }

        // 右上角在线点
        float dx = r - side * 0.02f, dy = tp + side * 0.02f, dr = s * 0.085f;
        if (animated) {
            float p = (t % 1.8f) / 1.8f;
            fill.setColor(Theme.alpha(Theme.GREEN, 0.45f * (1 - p)));
            canvas.drawCircle(dx, dy, dr * (1 + 1.1f * p), fill);
        }
        fill.setColor(Theme.BG);
        canvas.drawCircle(dx, dy, dr * 1.35f, fill);
        fill.setColor(Theme.GREEN);
        canvas.drawCircle(dx, dy, dr, fill);

    }
}

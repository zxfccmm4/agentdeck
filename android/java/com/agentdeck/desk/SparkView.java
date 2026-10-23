package com.agentdeck.desk;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 卡片内的迷你走势线：首次从左向右展开，之后新旧数据平滑形变。
 * axis 打开时底部画「-12h / -6h / 现在」刻度，并在峰值处标注数值。
 */
final class SparkView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fillPath = new Path();
    private float[] from = new float[0], to = new float[0];
    private float morph = 1f, reveal = 0f;
    private int color = Theme.BRAND_A;
    private ValueAnimator anim;
    private boolean axis;
    private long[] raw = new long[0];
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peak = new Paint(Paint.ANTI_ALIAS_FLAG);

    void setAxis(boolean on) { axis = on; invalidate(); }

    SparkView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Theme.dp(c, 1.6f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        base.setStyle(Paint.Style.STROKE);
        base.setStrokeWidth(Theme.dp(c, 1));
        base.setColor(Theme.STROKE);
        base.setPathEffect(new DashPathEffect(new float[]{Theme.dp(c, 3), Theme.dp(c, 3)}, 0));
        tick.setColor(Theme.FAINT);
        tick.setTextSize(Theme.dp(c, 9));
        peak.setTextSize(Theme.dp(c, 10));
        peak.setTypeface(Theme.BOLD);
        peak.setTextAlign(Paint.Align.CENTER);
    }

    void setData(long[] data, int color) {
        raw = data == null ? new long[0] : data;
        boolean colorChanged = this.color != color;
        this.color = color;
        long max = 0;
        int n = data == null ? 0 : data.length;
        for (int i = 0; i < n; i++) max = Math.max(max, data[i]);
        float[] next = new float[n];
        for (int i = 0; i < n; i++) next[i] = max > 0 ? data[i] / (float) max : 0f;
        if (colorChanged) updateShader();
        if (sameAs(next)) { if (colorChanged) invalidate(); return; }

        float[] cur = current();
        from = cur.length == n ? cur : new float[n];
        to = next;
        final boolean first = reveal < 1f;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(first ? 1100 : 700);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            if (first) { reveal = v; morph = 1f; } else morph = v;
            invalidate();
        });
        anim.start();
    }

    private boolean sameAs(float[] next) {
        if (next.length != to.length) return false;
        for (int i = 0; i < next.length; i++) if (Math.abs(next[i] - to[i]) > 1e-4) return false;
        return true;
    }

    private float[] current() {
        float[] c = new float[to.length];
        for (int i = 0; i < c.length; i++) c[i] = (i < from.length ? from[i] : 0) + (to[i] - (i < from.length ? from[i] : 0)) * morph;
        return c;
    }

    private void updateShader() {
        int h = getHeight();
        if (h <= 0) return;
        area.setShader(new LinearGradient(0, 0, 0, h - axisH(), Theme.alpha(color, 0.32f), Theme.alpha(color, 0f), Shader.TileMode.CLAMP));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { updateShader(); }

    private float axisH() { return axis ? Theme.dp(getContext(), 14) : 0; }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.end();
        super.onDetachedFromWindow();
    }

    private void drawAxis(Canvas canvas, int w, int h, float pad) {
        float y = h - Theme.dp(getContext(), 2);
        tick.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("-12h", pad, y, tick);
        tick.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("-6h", w / 2f, y, tick);
        tick.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("现在", w - pad, y, tick);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int fullH = getHeight();
        float pad = Theme.dp(getContext(), 5); // 留出末端圆点半径，避免被裁切
        if (axis) drawAxis(canvas, w, fullH, pad);
        int h = (int) (fullH - axisH());
        float top = axis ? Theme.dp(getContext(), 16) : 0; // 峰值标签留白
        float[] v = current();
        boolean empty = true;
        for (float x : v) if (x > 0) { empty = false; break; }
        if (v.length < 2 || empty) {
            canvas.drawLine(0, h - pad, w, h - pad, base);
            return;
        }
        float step = (w - pad * 2) / (v.length - 1);
        path.reset();
        float span = h - pad * 2 - top;
        float px = pad, py = h - pad - v[0] * span;
        int peakI = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[peakI]) peakI = i;
        float peakX = px, peakY = py;
        path.moveTo(px, py);
        for (int i = 1; i < v.length; i++) {
            float x = pad + step * i, y = h - pad - v[i] * span;
            if (i == peakI) { peakX = x; peakY = y; }
            float mx = (px + x) / 2f;
            path.cubicTo(mx, py, mx, y, x, y);
            px = x; py = y;
        }
        fillPath.set(path);
        fillPath.lineTo(px, h);
        fillPath.lineTo(pad, h);
        fillPath.close();

        canvas.save();
        canvas.clipRect(0, 0, w * reveal, h);
        canvas.drawPath(fillPath, area);
        line.setColor(color);
        canvas.drawPath(path, line);
        canvas.restore();

        if (axis && reveal >= 1f && peakI < raw.length && raw[peakI] > 0 && peakI != v.length - 1) {
            peak.setColor(Theme.alpha(color, 0.9f));
            float lx = Math.max(Theme.dp(getContext(), 16), Math.min(w - Theme.dp(getContext(), 16), peakX));
            canvas.drawText(Theme.fmt(raw[peakI]), lx, peakY - Theme.dp(getContext(), 7), peak);
            dot.setColor(Theme.alpha(color, 0.8f));
            canvas.drawCircle(peakX, peakY, Theme.dp(getContext(), 2.2f), dot);
        }
        if (reveal >= 1f) {
            dot.setColor(Theme.alpha(color, 0.3f));
            canvas.drawCircle(px, py, Theme.dp(getContext(), 4.5f), dot);
            dot.setColor(color);
            canvas.drawCircle(px, py, Theme.dp(getContext(), 2.2f), dot);
        }
    }
}

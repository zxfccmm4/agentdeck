package com.agentdeck.desk;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 各 agent token 占比环形图；分段比例变化时平滑过渡，中心显示一组主副文字。 */
final class DonutView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float[] from = new float[0], to = new float[0];
    private int[] colors = new int[0];
    private float t = 1f, intro = 0f;
    private String centerBig = "", centerSmall = "";
    private ValueAnimator anim;

    DonutView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        big.setColor(Theme.TEXT);
        big.setTypeface(Theme.BOLD);
        big.setTextAlign(Paint.Align.CENTER);
        small.setColor(Theme.MUTED);
        small.setTextAlign(Paint.Align.CENTER);
    }

    void setCenter(String b, String s) {
        if (b.equals(centerBig) && s.equals(centerSmall)) return;
        centerBig = b; centerSmall = s;
        invalidate();
    }

    void setSegments(long[] values, int[] cols) {
        double total = 0;
        for (long v : values) total += Math.max(0, v);
        float[] next = new float[values.length];
        for (int i = 0; i < values.length; i++) next[i] = total > 0 ? (float) (Math.max(0, values[i]) / total) : 0f;
        boolean same = next.length == to.length;
        for (int i = 0; same && i < next.length; i++) same = Math.abs(next[i] - to[i]) < 1e-4 && cols[i] == colors[i];
        if (same) return;

        float[] cur = current();
        from = cur.length == next.length ? cur : new float[next.length];
        to = next;
        colors = cols;
        final boolean first = intro < 1f;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(first ? 1300 : 800);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            if (first) { intro = v; t = 1f; } else t = v;
            invalidate();
        });
        anim.start();
    }

    private float[] current() {
        float[] c = new float[to.length];
        for (int i = 0; i < c.length; i++) {
            float f = i < from.length ? from[i] : 0;
            c[i] = f + (to[i] - f) * t;
        }
        return c;
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.end();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight());
        float sw = s * 0.12f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        rect.set(cx - s / 2f + sw / 2f, cy - s / 2f + sw / 2f, cx + s / 2f - sw / 2f, cy + s / 2f - sw / 2f);
        ring.setStrokeWidth(sw);
        ring.setColor(Theme.STROKE);
        canvas.drawArc(rect, 0, 360, false, ring);

        float[] v = current();
        int nonZero = 0;
        for (float x : v) if (x > 0.002f) nonZero++;
        float gap = nonZero > 1 ? 3f : 0f;
        float start = -90f + (1 - intro) * -90f;
        for (int i = 0; i < v.length; i++) {
            float sweep = v[i] * 360f * intro;
            if (sweep <= gap) { start += sweep; continue; }
            ring.setColor(colors[i]);
            canvas.drawArc(rect, start + gap / 2f, sweep - gap, false, ring);
            start += sweep;
        }

        // 文字缩放到环内径以内
        float inner = s - sw * 2.6f;
        big.setTextSize(s * 0.2f);
        float bw = big.measureText(centerBig);
        if (bw > inner) big.setTextSize(big.getTextSize() * inner / bw);
        small.setTextSize(Math.max(Theme.dp(getContext(), 9), s * 0.09f));
        float sw2 = small.measureText(centerSmall);
        if (sw2 > inner) small.setTextSize(small.getTextSize() * inner / sw2);
        canvas.drawText(centerBig, cx, cy + big.getTextSize() * 0.2f, big);
        canvas.drawText(centerSmall, cx, cy + big.getTextSize() * 0.2f + small.getTextSize() * 1.4f, small);
    }
}

package com.agentdeck.desk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** 状态点；运行中时向外扩散呼吸光环（按时间取相位，由 Ambient 统一刷新）。 */
final class PulseDot extends View {
    private static final long PERIOD_MS = 1600;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = Theme.MUTED;
    private boolean pulsing;

    PulseDot(Context c) { super(c); }

    void setState(int color, boolean pulsing) {
        if (this.color == color && this.pulsing == pulsing) return;
        this.color = color;
        this.pulsing = pulsing;
        sync();
        invalidate();
    }

    private void sync() {
        if (pulsing && isAttachedToWindow()) Ambient.add(this); else Ambient.remove(this);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); sync(); }
    @Override protected void onDetachedFromWindow() { Ambient.remove(this); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float max = Math.min(cx, cy);
        float r = max * 0.46f;
        if (pulsing) {
            float p = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
            float e = 1 - (1 - p) * (1 - p); // ease-out
            paint.setColor(Theme.alpha(color, 0.55f * (1 - e)));
            canvas.drawCircle(cx, cy, r + (max - r) * e, paint);
        }
        paint.setColor(Theme.alpha(color, 0.25f));
        canvas.drawCircle(cx, cy, r * 1.45f, paint);
        paint.setColor(color);
        canvas.drawCircle(cx, cy, r, paint);
    }
}

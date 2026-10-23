package com.agentdeck.desk;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** token 构成分段条：缓存读 / 输入 / 缓存写 / 输出，比例变化时平滑过渡。 */
final class MixBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float[] from = new float[4], to = new float[4];
    private float t = 1f;
    private int[] colors = new int[4];
    private ValueAnimator anim;
    private long tail;

    /** 末尾追加一段不着色的空槽（按同一单位计），用于进度条。 */
    void setTail(long tail) { if (this.tail != tail) { this.tail = tail; invalidate(); } }

    MixBar(Context c) { super(c); }

    /** 依次传入 缓存读、输入、缓存写、输出。 */
    void setParts(long cacheR, long in, long cacheW, long out, int accent) {
        colors = new int[]{
                Theme.alpha(accent, 0.35f), accent,
                Theme.mix(accent, 0xFFFFFFFF, 0.35f), Theme.mix(accent, 0xFFFFFFFF, 0.75f)};
        double sum = Math.max(1, cacheR + in + cacheW + out + tail);
        float[] next = {(float) (cacheR / sum), (float) (in / sum), (float) (cacheW / sum), (float) (out / sum)};
        boolean same = true;
        for (int i = 0; i < 4; i++) same &= Math.abs(next[i] - to[i]) < 1e-3;
        if (same) { invalidate(); return; }
        from = current();
        to = next;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private float[] current() {
        float[] c = new float[4];
        for (int i = 0; i < 4; i++) c[i] = from[i] + (to[i] - from[i]) * t;
        return c;
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.end();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        paint.setColor(Theme.STROKE);
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, rad, rad, paint);
        float[] v = current();
        float gap = Theme.dp(getContext(), 2);
        float x = 0;
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        // 先算可见段，过窄的段补到条高；补出来的宽度从最宽的段里扣
        float[] segs = new float[4];
        int widest = 0;
        float borrowed = 0;
        for (int i = 0; i < 4; i++) {
            segs[i] = v[i] * w;
            if (segs[i] > segs[widest]) widest = i;
            if (segs[i] >= 0.5f && segs[i] < h) { borrowed += h - segs[i]; segs[i] = h; }
        }
        segs[widest] = Math.max(h, segs[widest] - borrowed);
        for (int i = 0; i < 4; i++) {
            float seg = segs[i];
            if (seg < 0.5f) continue;
            paint.setColor(colors[i]);
            boolean last = x + seg >= w - 1;
            r.set(x, 0, Math.min(w, x + seg - (last || seg <= h ? 0 : gap)), h);
            canvas.drawRoundRect(r, rad, rad, paint);
            x += seg + (seg <= h && !last ? gap : 0);
        }
        canvas.restore();
    }
}

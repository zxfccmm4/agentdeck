package com.agentdeck.desk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 全屏氛围背景：几团柔光 + 稀疏点阵。
 * 只在尺寸或配色变化时渲染一次到位图；持续的全屏重绘会让上层所有面板跟着重画，
 * 在旧手机上 RenderThread 会吃掉大半个核。入场时整体淡入。
 */
final class GlowBackground extends View {
    private static final float[][] SPOTS = {{0.12f, 0.18f}, {0.82f, 0.30f}, {0.45f, 0.95f}};
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] colors = {Theme.BRAND_A, Theme.BRAND_B, Theme.BRAND_C};
    private Bitmap cache;

    GlowBackground(Context c) {
        super(c);
        dots.setColor(Theme.alpha(Theme.TEXT, 0.045f));
        dots.setStrokeCap(Paint.Cap.ROUND);
        dots.setStrokeWidth(Theme.dp(c, 1.6f));
        setAlpha(0f);
        animate().alpha(1f).setDuration(900).start();
    }

    void setColors(int... c) {
        colors = c;
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { rebuild(w, h); }

    private void rebuild(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (cache != null) cache.recycle();
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(cache);
        c.drawColor(Theme.BG);
        float r = Math.max(w, h) * 0.55f;
        for (int i = 0; i < colors.length; i++) {
            float[] p = SPOTS[i % SPOTS.length];
            glow.setShader(new RadialGradient(w * p[0], h * p[1], r,
                    Theme.alpha(colors[i], 0.13f), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, glow);
        }
        glow.setShader(null);
        float step = Theme.dp(getContext(), 22);
        for (float y = step / 2; y < h; y += step)
            for (float x = step / 2; x < w; x += step) c.drawPoint(x, y, dots);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (cache != null) canvas.drawBitmap(cache, 0, 0, null);
        else canvas.drawColor(Theme.BG);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (cache != null) { cache.recycle(); cache = null; }
    }
}

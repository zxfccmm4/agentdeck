package com.agentdeck.desk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * agent 品牌图标（来自 LobeHub Icons，res/drawable-nodpi/agent_*.png）放在圆角底板上，
 * 右下角叠加状态点。live 时 logo 呼吸浮动、外圈渐变描边旋转、状态点扩散；
 * dim 时去色半透明（用于空闲 dock）。所有持续动画由 Ambient 统一刷新。
 * 没有对应图标的 agent 显示首字母。
 */
final class AgentIcon extends View {
    private static final long PERIOD_MS = 1600;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tile = new RectF();
    private final Rect dst = new Rect();
    private Bitmap logo;
    private String initial = "";
    private int accent = Theme.MUTED;
    private int statusColor = 0;
    private boolean pulsing, live, dim;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private SweepGradient sweep;
    private final long t0 = SystemClock.uptimeMillis();

    AgentIcon(Context c) {
        super(c);
        letter.setTypeface(Theme.BOLD);
        letter.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
    }

    void setLive(boolean on) {
        if (live == on) return;
        live = on;
        sync();
        invalidate();
    }

    void setDim(boolean on) {
        if (dim == on) return;
        dim = on;
        if (on) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0.15f);
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
        } else paint.setColorFilter(null);
        invalidate();
    }

    void setAgent(String id, String name, int accent) {
        if (this.accent != accent) sweep = null;
        this.accent = accent;
        logo = load(getContext(), id);
        initial = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        invalidate();
    }

    /** statusColor 为 0 时不画状态点。 */
    void setStatus(int statusColor, boolean pulsing) {
        if (this.statusColor == statusColor && this.pulsing == pulsing) return;
        this.statusColor = statusColor;
        this.pulsing = pulsing;
        sync();
        invalidate();
    }

    private void sync() {
        if ((pulsing || live) && isAttachedToWindow()) Ambient.add(this); else Ambient.remove(this);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); sync(); }
    @Override protected void onDetachedFromWindow() { Ambient.remove(this); super.onDetachedFromWindow(); }

    private static Bitmap load(Context c, String id) {
        if (id == null) return null;
        if (CACHE.containsKey(id)) return CACHE.get(id);
        int res = resFor(id);
        Bitmap b = res == 0 ? null : BitmapFactory.decodeResource(c.getResources(), res);
        CACHE.put(id, b);
        return b;
    }

    private static int resFor(String id) {
        switch (id) {
            case "claude": return R.drawable.agent_claude;
            case "codex": return R.drawable.agent_codex;
            case "pi": return R.drawable.agent_pi;
            case "opencode": return R.drawable.agent_opencode;
            case "dsh": return R.drawable.agent_dsh;
            case "workbuddy": return R.drawable.agent_workbuddy;
            case "kiro": return R.drawable.agent_kiro;
            case "antigravity": return R.drawable.agent_antigravity;
            case "gemini": return R.drawable.agent_gemini;
            case "codebuddy": return R.drawable.agent_codebuddy;
            case "copilot": return R.drawable.agent_copilot;
            case "cursor": return R.drawable.agent_cursor;
            default: return 0;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        // 底板占 86%，给右下角状态点留出溢出空间
        float ts = s * 0.86f;
        tile.set(0, 0, ts, ts);
        float r = ts * 0.28f;
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        paint.setAlpha(255);
        // live：底板颜色随呼吸明暗变化
        float breath = live ? 0.5f + 0.5f * (float) Math.sin(t * 2.2f) : 0f;
        paint.setColor(dim ? Theme.SURFACE_HI
                : Theme.mix(Theme.SURFACE_HI, accent, live ? 0.18f + 0.22f * breath : 0.16f));
        canvas.drawRoundRect(tile, r, r, paint);
        float sw = live ? Math.max(3, ts * 0.075f) : Math.max(1, Theme.dp(getContext(), 1));
        ring.setStrokeWidth(sw);
        if (live) {
            // 彗星状高亮：一段亮白尾迹沿边框旋转，其余部分淡
            if (sweep == null) sweep = new SweepGradient(ts / 2f, ts / 2f,
                    new int[]{Theme.alpha(accent, 0.12f), Theme.alpha(accent, 0.12f), accent,
                            Theme.mix(accent, 0xFFFFFFFF, 0.65f), Theme.alpha(accent, 0.12f)},
                    new float[]{0f, 0.55f, 0.86f, 0.97f, 1f});
            matrix.setRotate((t * 140f) % 360f, ts / 2f, ts / 2f);
            sweep.setLocalMatrix(matrix);
            ring.setShader(sweep);
        } else {
            ring.setShader(null);
            ring.setColor(Theme.alpha(dim ? Theme.MUTED : accent, dim ? 0.2f : 0.35f));
        }
        float in = sw / 2f;
        tile.inset(in, in);
        canvas.drawRoundRect(tile, r - in, r - in, ring);
        tile.inset(-in, -in);

        if (logo != null) {
            // live：缓慢呼吸 + 轻微上下浮动
            float k = live ? 0.94f + 0.12f * breath : 1f;
            float bob = live ? ts * 0.045f * (float) Math.sin(t * 1.6f + 1f) : 0f;
            int is = Math.round(ts * 0.70f * k);
            int ox = Math.round((ts - is) / 2f);
            int oy = Math.round((ts - is) / 2f + bob);
            dst.set(ox, oy, ox + is, oy + is);
            if (dim) paint.setAlpha(150);
            canvas.drawBitmap(logo, null, dst, paint);
            paint.setAlpha(255);
        } else {
            letter.setColor(dim ? Theme.MUTED : accent);
            letter.setTextSize(ts * 0.5f);
            canvas.drawText(initial, ts / 2f, ts / 2f + letter.getTextSize() * 0.36f, letter);
        }

        if (statusColor == 0) return;
        float cx = s * 0.78f, cy = s * 0.78f, dr = s * 0.11f;
        if (pulsing) {
            // 光圈最大半径不能超出 View 边界，否则会被裁成方块
            float maxR = s - cx - 1;
            float p = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
            float e = 1 - (1 - p) * (1 - p);
            paint.setColor(Theme.alpha(statusColor, 0.5f * (1 - e)));
            canvas.drawCircle(cx, cy, dr * 1.4f + (maxR - dr * 1.4f) * e, paint);
        }
        paint.setColor(Theme.BG);
        canvas.drawCircle(cx, cy, dr * 1.4f, paint);
        paint.setColor(statusColor);
        canvas.drawCircle(cx, cy, dr, paint);
    }
}

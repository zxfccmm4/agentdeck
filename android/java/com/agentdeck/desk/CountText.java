package com.agentdeck.desk;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/** 数值变化时滚动过渡的文本，等宽数字避免跳动。 */
final class CountText extends TextView {
    interface Fmt { String f(double v); }

    private Fmt fmt = v -> Theme.fmt((long) v);
    private double shown = Double.NaN;
    private double target = Double.NaN;
    private ValueAnimator anim;

    CountText(Context c) {
        super(c);
        setFontFeatureSettings("tnum");
        setSingleLine(true);
        setIncludeFontPadding(false);
    }

    void setFmt(Fmt f) { fmt = f; }

    void setValue(double v) {
        if (v == target) return;
        target = v;
        final double from = Double.isNaN(shown) ? 0 : shown;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Double.isNaN(shown) ? 1300 : 800);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> {
            shown = from + (v - from) * (float) a.getAnimatedValue();
            setText(fmt.f(shown));
        });
        anim.start();
    }

    void setPlaceholder(String s) {
        if (anim != null) anim.cancel();
        shown = Double.NaN;
        target = Double.NaN;
        setText(s);
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.end();
        super.onDetachedFromWindow();
    }
}

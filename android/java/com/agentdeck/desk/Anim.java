package com.agentdeck.desk;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** 通用入场 / 按压动效。 */
final class Anim {
    private Anim() {}

    static void enter(View v, long delay, float dy) {
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(460)
                .setInterpolator(new DecelerateInterpolator(2.2f)).start();
    }

    /** 按下缩小、松开回弹；返回 false 让点击事件照常分发。 */
    @SuppressLint("ClickableViewAccessibility")
    static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.955f).scaleY(0.955f).setDuration(110)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(320)
                            .setInterpolator(new OvershootInterpolator(2.4f)).start();
                    break;
            }
            return false;
        });
    }
}

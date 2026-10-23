package com.agentdeck.desk;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 常驻动效（logo、呼吸点）的共享节拍器。
 * 各 View 自己 postInvalidate 时相位错开，每个都会触发一次整窗重绘；
 * 统一在一个节拍里刷新，帧数就只取决于这里的频率。
 */
final class Ambient {
    private static final long FRAME_MS = 100; // ~10fps，旧手机常亮屏下 CPU 可接受
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<View> VIEWS = new ArrayList<>();
    private static boolean running;

    private Ambient() {}

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            for (int i = 0; i < VIEWS.size(); i++) VIEWS.get(i).invalidate();
            if (VIEWS.isEmpty()) running = false;
            else H.postDelayed(this, FRAME_MS);
        }
    };

    static void add(View v) {
        if (!VIEWS.contains(v)) VIEWS.add(v);
        if (!running) { running = true; H.post(TICK); }
    }

    static void remove(View v) { VIEWS.remove(v); }
}

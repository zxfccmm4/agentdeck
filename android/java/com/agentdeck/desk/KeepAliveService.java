package com.agentdeck.desk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 前台保活服务：让桌面屏 App 长时间运行不被系统后台回收，
 * 并借助 wake lock 让屏幕保持常亮。
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "agentdeck_keepalive";
    private static final int NOTIF_ID = 1977;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // 仅用 PARTIAL_WAKE_LOCK 保进程；屏幕常亮由 Activity 的 KEEP_SCREEN_ON 负责
        try {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "AgentDeck::KeepAlive");
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "AgentDeck 桌面屏", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("AgentDeck 桌面屏")
                .setContentText("正在展示 agent 用量")
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被杀后自动重启
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
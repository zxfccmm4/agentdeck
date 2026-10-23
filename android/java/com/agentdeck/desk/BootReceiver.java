package com.agentdeck.desk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启动：设备启动/解锁后自动拉起桌面屏。
 * 注意：部分 OEM（如小米、华为）默认会杀掉开机广播，需在设置里允许自启动。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i("AgentDeck", "BootReceiver: " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(i); } catch (Exception e) {
                Log.w("AgentDeck", "自启动失败: " + e);
            }
        }
    }
}
package com.jingxuan.netlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 开机/升级后，如果上次处于常驻状态，自动拉起保活服务并恢复断网 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(KeeperService.PREFS, Context.MODE_PRIVATE);
        boolean blocked = prefs.getBoolean(KeeperService.KEY_BLOCKED, false);
        boolean resident = prefs.getBoolean(KeeperService.KEY_RESIDENT, false);
        if (!resident && !blocked) return;

        // 从 2.1.0 升级上来的用户只有 blocked，没有 resident
        if (blocked) prefs.edit().putBoolean(KeeperService.KEY_RESIDENT, true).apply();

        Intent svc = new Intent(context, KeeperService.class).setAction(KeeperService.ACTION_START);
        try {
            context.startForegroundService(svc);
        } catch (Exception e) {
            context.startService(svc);
        }
    }
}

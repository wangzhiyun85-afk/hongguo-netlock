package com.jingxuan.netlock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** 定时看门狗：常驻服务或 VPN 被系统杀掉后自动拉起，并续订下一次检查 */
public class WatchdogReceiver extends BroadcastReceiver {
    /** 2.4.0 起从 10 分钟缩短到 90 秒：进程被杀后最多一个半分钟就能自己拉回来 */
    static final long INTERVAL = 90 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(KeeperService.PREFS, Context.MODE_PRIVATE);
        boolean blocked = prefs.getBoolean(KeeperService.KEY_BLOCKED, false);
        boolean resident = prefs.getBoolean(KeeperService.KEY_RESIDENT, false);

        if (resident || blocked) {
            if (!KeeperService.isRunning()) {
                // 进程已经不在了：拉起常驻服务，它启动后会按 blocked 状态自动恢复断网
                Intent keeper = new Intent(context, KeeperService.class).setAction(KeeperService.ACTION_START);
                try {
                    context.startForegroundService(keeper);
                } catch (Exception e) {
                    context.startService(keeper);
                }
            } else if (blocked && !BlockService.isBlocking()) {
                // 常驻服务活着但 VPN 已被回收 / 没真正拦截，幂等拉起
                try {
                    context.startService(new Intent(context, BlockService.class));
                } catch (Exception ignored) {
                }
            }
        }
        schedule(context);
    }

    static void schedule(Context context) {
        schedule(context, INTERVAL);
    }

    /** 带延时的排程：划掉最近任务、VPN 被撤销后可以提前叫醒自己 */
    static void schedule(Context context, long delayMillis) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, WatchdogReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMillis, pi);
        } catch (Exception ignored) {
        }
    }
}

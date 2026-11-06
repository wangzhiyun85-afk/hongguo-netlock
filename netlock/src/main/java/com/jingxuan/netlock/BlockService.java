package com.jingxuan.netlock;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * 免 root 断网：本地 VPN 只接管红果（com.phoenix.read）的流量，收到即丢弃。
 *
 * 2.3.0 补齐两处「漏网」，这是之前断网不彻底、离线播放仍弹广告的根因：
 * 1) IPv6：2.2.0 只加了 IPv4 的 0.0.0.0/0 路由，手机有 IPv6 时红果可走 IPv6 绕过隧道；
 *    现在同时添加 IPv6 地址与 ::/0 路由。
 * 2) DNS：不指定隧道内 DNS 时，系统解析可能仍走物理网络（DNS 泄漏）；
 *    现在把 DNS 指向隧道内的黑洞地址，DNS 查询一并被丢弃。
 *
 * 常驻通知由 KeeperService 负责，本服务不显示通知，只负责建立/拆除隧道。
 * 2.3.1 起：联网状态不再保留「空隧道」——没有 VPN 就一定没有拦截，状态和实际完全一致。
 * （空隧道会抢走默认网络导致 DNS 失效，表现就是「点了恢复联网，红果还是上不了网」）
 */
public class BlockService extends VpnService {

    private static final String TAG = "NetLock";
    private static final String TARGET = "com.phoenix.read";

    /** 主动拆除隧道（恢复联网时用，避免 stopService 的异步延迟） */
    public static final String ACTION_TEARDOWN = "com.jingxuan.netlock.BLOCK_TEARDOWN";

    /** 隧道内地址：不对外通信，只作为黑洞 */
    private static final String IPV4_ADDR = "10.8.0.1";
    private static final String IPV4_DNS = "10.8.0.2";
    private static final String IPV6_ADDR = "fd00:8:8:8::1";
    private static final String IPV6_DNS = "fd00:8:8:8::2";

    /** 供看门狗判断「隧道是否真的在拦截」，避免通知栏显示已断网但实际没断 */
    private static volatile boolean blockingNow = false;

    private volatile boolean running = false;
    private volatile boolean blocking = false;
    private ParcelFileDescriptor tun;

    static boolean isBlocking() {
        return blockingNow;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        SharedPreferences prefs = getSharedPreferences(KeeperService.PREFS, MODE_PRIVATE);
        boolean wantBlock = prefs.getBoolean(KeeperService.KEY_BLOCKED, false);
        Log.i(TAG, "block onStartCommand action=" + action + " wantBlock=" + wantBlock + " running=" + running + " blocking=" + blocking);

        // 主动拆除，或状态已经是「联网」：立刻关掉隧道并退出，绝不保留空隧道
        if (ACTION_TEARDOWN.equals(action) || !wantBlock) {
            teardown();
            notifyState();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (running && blocking) return START_STICKY;

        teardown();

        ParcelFileDescriptor fd = establish(true, true);
        if (fd == null) {
            // 个别机型不支持 IPv6 地址，退回纯 IPv4（会牺牲 IPv6 的封锁，但比完全不通强）
            Log.w(TAG, "ipv6 establish failed, retry ipv4 only");
            fd = establish(true, false);
        }

        if (fd == null) {
            running = false;
            blocking = false;
            blockingNow = false;
            // 建立失败（例如 VPN 授权被撤销）：回写状态，避免通知栏显示「已断网」但实际没断
            prefs.edit().putBoolean(KeeperService.KEY_BLOCKED, false).apply();
            notifyState();
            try {
                startService(new Intent(this, KeeperService.class).setAction(KeeperService.ACTION_START));
            } catch (Exception ignored) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        tun = fd;
        blocking = true;
        running = true;
        blockingNow = true;
        startReader(fd);
        notifyState();
        WatchdogReceiver.schedule(this);
        return START_STICKY;
    }

    private void notifyState() {
        sendBroadcast(new Intent(KeeperService.ACTION_STATE).setPackage(getPackageName()));
    }

    private ParcelFileDescriptor establish(boolean block, boolean withIpv6) {
        try {
            Builder b = new Builder()
                    .setSession("红果断网")
                    .addAddress(IPV4_ADDR, 32);
            if (withIpv6) b.addAddress(IPV6_ADDR, 128);
            if (block) {
                b.addRoute("0.0.0.0", 0)
                        .addDnsServer(IPV4_DNS)
                        .addAllowedApplication(TARGET);
                if (withIpv6) {
                    b.addRoute("::", 0).addDnsServer(IPV6_DNS);
                }
            }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            ParcelFileDescriptor fd = b.establish();
            Log.i(TAG, "establish block=" + block + " ipv6=" + withIpv6 + " tun=" + (fd != null));
            return fd;
        } catch (Exception e) {
            Log.e(TAG, "establish failed block=" + block + " ipv6=" + withIpv6, e);
            return null;
        }
    }

    private void startReader(ParcelFileDescriptor fd) {
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[32767];
            try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                while (running && in.read(buf) >= 0) {
                    // 丢弃：红果的流量进不来也出不去
                }
            } catch (IOException ignored) {
            }
        }, "netlock-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** 关掉当前隧道；不修改 blocked 偏好，是否断网由调用方决定 */
    private void teardown() {
        running = false;
        blocking = false;
        blockingNow = false;
        ParcelFileDescriptor old = tun;
        tun = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    /**
     * 系统撤销 VPN（被其他 VPN 接管、或系统回收隧道）时回调。
     * 隧道已经失效，这里只做「承认状态 + 尽快重试」，避免通知栏继续显示已断网。
     */
    @Override
    public void onRevoke() {
        Log.w(TAG, "vpn revoked by system");
        teardown();
        notifyState();
        WatchdogReceiver.schedule(this, 5000);
        try {
            startService(new Intent(this, KeeperService.class).setAction(KeeperService.ACTION_START));
        } catch (Exception ignored) {
        }
        super.onRevoke();
    }
}

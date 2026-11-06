package com.jingxuan.netlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * 常驻保活服务：App 启动后进程一直处于前台服务状态，通知栏常驻一条可操作的卡片。
 * - 通知正文点击：打开极简状态页
 * - 通知按钮：断网 / 恢复联网（只切换 BlockService 的拦截模式，服务本身不退出）
 * - 通知按钮：系统联网控制（跳到系统自带的按应用断网页，是「不用常驻」的备选方案）
 * - 通知按钮：退出常驻（彻底结束，清除通知）
 *
 * 2.4.0 保活加固（针对「看着看着广告又出来」，即拦截隧道在播放中途消失）：
 * 1) 60 秒心跳：服务自己检查隧道是否还在，不在就立刻重建，不用等看门狗；
 * 2) 亮屏 / 解锁 / 网络切换（Wi‑Fi ↔ 数据）时立即自检并重建，这几种场景最容易掉隧道；
 * 3) 用户从最近任务划掉卡片（onTaskRemoved）后 3 秒安排一次自愈；
 * 4) 通知文案一律以「隧道是否真的在拦截」为准，隧道掉了会显示回联网状态。
 *
 * 2.6.0 起不再切换 activity-alias 桌面图标：切组件会把当前任务（含贴边小窗）关掉，
 * 从通知栏点开关就会让小窗消失、App 彻底退出；现在图标固定为 logo，状态改由界面背景图表示。
 */
public class KeeperService extends Service {

    private static final String TAG = "NetLock";

    public static final String ACTION_START = "com.jingxuan.netlock.KEEPER_START";
    public static final String ACTION_TOGGLE = "com.jingxuan.netlock.KEEPER_TOGGLE";
    public static final String ACTION_SET_BLOCK = "com.jingxuan.netlock.KEEPER_SET_BLOCK";
    public static final String ACTION_EXIT = "com.jingxuan.netlock.KEEPER_EXIT";
    public static final String ACTION_STATE = "com.jingxuan.netlock.KEEPER_STATE";
    /** 悬浮球点击：服务已经在跑，直接广播给它，避免从后台再去 startForegroundService */
    public static final String ACTION_BALL_TOGGLE = "com.jingxuan.netlock.KEEPER_BALL_TOGGLE";
    public static final String EXTRA_BLOCKED = "blocked";

    public static final String PREFS = "netlock";
    public static final String KEY_RESIDENT = "resident";
    public static final String KEY_BLOCKED = "blocked";

    private static final String CHANNEL = "netlock_keeper";
    private static final int NOTIFY_ID = 1;
    /** 心跳间隔：只看当前进程有没有掉隧道，开销很小 */
    private static final long HEARTBEAT_MS = 60 * 1000L;

    private static volatile boolean running = false;

    /** 上次同步到界面的「是否真的在拦截」，避免每分钟都重刷通知 */
    private Boolean lastUiBlocked = null;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (running) {
                ensureBlocking();
                syncUi(false);
                handler.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    /** 亮屏 / 解锁：用户回到手机时先自检一次，避免隧道早掉了却在播放时才发现 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "screen event " + intent.getAction());
            ensureBlocking();
            syncUi(true);
        }
    };

    /** BlockService 隧道状态变化时，通知栏、悬浮球跟着变 */
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_BALL_TOGGLE.equals(intent.getAction())) {
                // 悬浮球点击走本地广播，不重新启动服务，绕开后台启动前台服务的限制
                setBlocked(intent.getBooleanExtra(EXTRA_BLOCKED, false));
                return;
            }
            if (CustomImages.ACTION_CHANGED.equals(intent.getAction())) {
                // 主界面换过自定义头像：就地刷新悬浮球，不重建窗口
                if (ball != null) ball.reloadImage();
                return;
            }
            syncUi(false);
        }
    };

    private ConnectivityManager.NetworkCallback networkCallback;

    /** 常驻悬浮球：不在最近任务里，清理任务也划不掉，是主要的保活载体 */
    private FloatingBall ball;

    /** 悬浮球被系统摘掉后的补挂节流，避免与系统来回拉扯 */
    private int ballReaddCount;
    private long ballReaddWindow;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        ensureLauncherEntry();
        registerScreenReceiver();
        registerNetworkCallback();
        registerStateReceiver();
        ensureBall();
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    /**
     * 有悬浮窗权限就挂上悬浮球；窗口被系统摘掉、或权限是后来才给的，都会在这里补挂。
     * 悬浮球是 View，只能在主线程操作：网络回调等可能在别的线程进来，
     * 子线程碰 View 会抛 CalledFromWrongThreadException，直接把整个进程崩掉。
     */
    private void ensureBall() {
        if (!running) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::ensureBall);
            return;
        }
        if (!Settings.canDrawOverlays(this)) return;
        if (!prefs().getBoolean(KEY_RESIDENT, false)) return;
        if (ball == null || !ball.isShown()) {
            if (ball != null) ball.remove();
            ball = new FloatingBall(this, this::onBallLost);
            ball.show();
        }
        ball.setBlocked(prefs().getBoolean(KEY_BLOCKED, false) && BlockService.isBlocking());
    }

    /** 开关切换 / 系统清理悬浮窗后，窗口被摘掉时尽快挂回去（短时间内反复被摘就退避） */
    private void onBallLost() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now - ballReaddWindow > 60_000L) {
            ballReaddWindow = now;
            ballReaddCount = 0;
        }
        if (ballReaddCount >= 3) return;
        ballReaddCount++;
        handler.postDelayed(this::ensureBall, 800);
    }

    /**
     * 老版本会切换桌面别名。若升级前「已断网」别名是启用状态，系统会保留这个组件状态，
     * 导致升级后桌面入口（.Allowed）仍是被禁用、桌面看不到图标。
     * 这里只在启动时把它补回启用（从不反向禁用），之后图标永远固定为 logo。
     */
    private void ensureLauncherEntry() {
        PackageManager pm = getPackageManager();
        ComponentName entry = new ComponentName(this, "com.jingxuan.netlock.Allowed");
        try {
            if (pm.getComponentEnabledSetting(entry) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(entry, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (Exception e) {
            Log.w(TAG, "ensure launcher entry failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "keeper onStartCommand action=" + action);

        // 无论什么动作都先进入前台，避免 getForegroundService 启动后被判超时
        startForeground(NOTIFY_ID, notification());

        if (ACTION_EXIT.equals(action)) {
            prefs().edit().putBoolean(KEY_RESIDENT, false).putBoolean(KEY_BLOCKED, false).apply();
            stopService(new Intent(this, BlockService.class));
            if (ball != null) {
                ball.remove();
                ball = null;
            }
            lastUiBlocked = false;
            running = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        prefs().edit().putBoolean(KEY_RESIDENT, true).apply();
        ballReaddCount = 0;
        ensureBall();

        if (ACTION_TOGGLE.equals(action)) {
            setBlocked(!prefs().getBoolean(KEY_BLOCKED, false));
        } else if (ACTION_SET_BLOCK.equals(action)) {
            setBlocked(intent.getBooleanExtra(EXTRA_BLOCKED, false));
        } else {
            ensureBlocking();
            syncUi(true);
        }

        WatchdogReceiver.schedule(this);
        return START_STICKY;
    }

    private void setBlocked(boolean on) {
        if (on && VpnService.prepare(this) != null) {
            // 还没授权 VPN：不动状态，只把通知文案改成引导，并让界面回到真实状态
            prefs().edit().putBoolean(KEY_BLOCKED, false).apply();
            syncUi(true);
            notify(notification("需要授权 VPN 才能断网，点此打开「红果断网」"));
            return;
        }
        prefs().edit().putBoolean(KEY_BLOCKED, on).apply();
        if (on) {
            ensureBlocking();
        } else {
            // 先让服务自己拆隧道，再 stopService 兜底：避免「开关已关、隧道还在」的窗口
            try {
                startService(new Intent(this, BlockService.class).setAction(BlockService.ACTION_TEARDOWN));
            } catch (Exception ignored) {
            }
            stopService(new Intent(this, BlockService.class));
        }
        syncUi(true);
    }

    /** 已设置断网但隧道不在：立刻重建（幂等，重建失败时 BlockService 会回写真实状态） */
    private void ensureBlocking() {
        if (!prefs().getBoolean(KEY_BLOCKED, false)) return;
        if (BlockService.isBlocking()) return;
        Log.w(TAG, "tunnel missing, rebuilding");
        try {
            startService(new Intent(this, BlockService.class));
        } catch (Exception e) {
            Log.w(TAG, "rebuild tunnel failed", e);
        }
    }

    /**
     * 状态、通知两处对齐。
     *
     * @param force 为 true 时强制刷新（开关动作、亮屏自检后用）；false 时只有真的变了才刷，避免每分钟打扰
     */
    private void syncUi(boolean force) {
        // 界面、通知、悬浮球都只能在主线程动，别的线程进来先切回主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> syncUi(force));
            return;
        }
        // 每次状态变化都顺便确认悬浮球还在（有些 ROM 在 VPN 建立时会把悬浮窗摘掉）
        ensureBall();
        boolean blocked = prefs().getBoolean(KEY_BLOCKED, false) && BlockService.isBlocking();
        if (!force && lastUiBlocked != null && lastUiBlocked == blocked) return;
        lastUiBlocked = blocked;
        notify(notification());
        sendState();
        if (ball != null) ball.setBlocked(blocked);
    }

    private void notify(Notification n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_ID, n);
    }

    private void sendState() {
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
    }

    private Notification notification() {
        boolean blocked = prefs().getBoolean(KEY_BLOCKED, false) && BlockService.isBlocking();
        return notification(blocked ? "红果流量已拦截，点右侧可恢复联网" : "常驻保活中，点右侧可断网红果");
    }

    private Notification notification(String text) {
        boolean blocked = prefs().getBoolean(KEY_BLOCKED, false) && BlockService.isBlocking();
        ensureChannel();

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = PendingIntent.getForegroundService(this, 1,
                new Intent(this, KeeperService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent exit = PendingIntent.getService(this, 2,
                new Intent(this, KeeperService.class).setAction(ACTION_EXIT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setContentTitle(blocked ? "红果断网 · 已断网" : "红果断网 · 已联网")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_netlock)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(0, blocked ? "恢复联网" : "断网红果", toggle).build())
                .addAction(new Notification.Action.Builder(0, "退出常驻", exit).build());
        if (Build.VERSION.SDK_INT >= 31) {
            nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return nb.build();
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "常驻与开关", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_STATE);
        filter.addAction(ACTION_BALL_TOGGLE);
        filter.addAction(CustomImages.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    /** 切换 Wi‑Fi / 移动数据时 VPN 最容易静默失联，这里就地自检 */
    private void registerNetworkCallback() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                ensureBlocking();
                syncUi(false);
            }

            @Override
            public void onLost(Network network) {
                ensureBlocking();
                syncUi(false);
            }
        };
        try {
            // 必须指定主线程 Handler：网络回调默认跑在 ConnectivityThread 上，
            // 回调里会重挂悬浮球（View），在子线程做会直接把进程崩掉
            cm.registerDefaultNetworkCallback(networkCallback, handler);
        } catch (Exception e) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", e);
            networkCallback = null;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务划掉卡片：3 秒后自愈一次，尽量把常驻和断网拉回来
        Log.i(TAG, "onTaskRemoved, schedule heal in 3s");
        WatchdogReceiver.schedule(this, 3000);
        super.onTaskRemoved(rootIntent);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(heartbeat);
        if (ball != null) {
            ball.remove();
            ball = null;
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        if (networkCallback != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

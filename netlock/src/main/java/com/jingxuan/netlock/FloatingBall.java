package com.jingxuan.netlock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.net.VpnService;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * 常驻悬浮球（保活载体）：
 * - 不属于任何任务，清理「最近任务」划不掉，比贴边小窗更不容易被带走；
 * - 点一下：断网 / 恢复联网；长按：打开状态页；可拖动，位置会记住；
 * - 半透明小圆点，联网时绿色描边，已断网时红色描边。
 */
class FloatingBall {

    private static final String TAG = "NetLock";
    private static final String KEY_X = "ball_x";
    private static final String KEY_Y = "ball_y";

    private final Context context;
    private final WindowManager wm;
    private final ImageView view;
    private final WindowManager.LayoutParams lp;
    private final int size;
    private final int slop;
    private final Runnable onLost;

    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private long downTime;
    private boolean dragging;
    private boolean shown;
    private boolean attached;
    private boolean removing;
    private boolean blocked;

    /** 自定义头像合成出来的位图（没设置自定义图时为 null，直接用内置图） */
    private Bitmap image;

    FloatingBall(Context context, Runnable onLost) {
        this.context = context.getApplicationContext();
        this.onLost = onLost;
        wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        size = dp(40);
        slop = ViewConfiguration.get(this.context).getScaledTouchSlop();

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        Point screen = screenSize();
        lp.x = prefs().getInt(KEY_X, screen.x - size - dp(4));
        lp.y = prefs().getInt(KEY_Y, (int) (screen.y * 0.42f));

        view = new ImageView(this.context);
        view.setAlpha(0.5f);
        applyImage();
        // 被系统悄悄摘掉窗口（切 VPN、清理悬浮窗等）时通知服务重建
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                attached = true;
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                attached = false;
                if (!removing && onLost != null) onLost.run();
            }
        });
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        downX = lp.x;
                        downY = lp.y;
                        downTime = SystemClock.uptimeMillis();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            Point s = screenSize();
                            lp.x = clamp(downX + dx, 0, Math.max(0, s.x - size));
                            lp.y = clamp(downY + dy, 0, Math.max(0, s.y - size));
                            try {
                                wm.updateViewLayout(view, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            prefs().edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            if (SystemClock.uptimeMillis() - downTime >= 450) {
                                openPage();
                            } else {
                                toggle();
                            }
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    void show() {
        if (shown && attached) return;
        if (Build.VERSION.SDK_INT >= 26 && !Settings.canDrawOverlays(context)) return;
        if (attached) return;
        try {
            wm.addView(view, lp);
            shown = true;
            setBlocked(blocked);
        } catch (Exception e) {
            shown = false;
            Log.w(TAG, "add floating ball failed", e);
        }
    }

    boolean isShown() {
        return shown && attached;
    }

    void remove() {
        if (!shown) return;
        removing = true;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        } finally {
            removing = false;
        }
        shown = false;
        attached = false;
    }

    void setBlocked(boolean on) {
        blocked = on;
        applyImage();
    }

    /** 主界面换过自定义头像后调用，重新合成一次 */
    void reloadImage() {
        applyImage();
    }

    /** 有自定义头像就用「圆形头像 + 状态描边」，没有就用内置的绿/红图标 */
    private void applyImage() {
        Bitmap custom = CustomImages.ballBitmap(context, size, blocked);
        Bitmap old = image;
        image = custom;
        if (custom != null) {
            view.setImageBitmap(custom);
        } else {
            view.setImageResource(blocked ? R.drawable.ball_off : R.drawable.ball_on);
        }
        if (old != null && old != custom) old.recycle();
    }

    private void toggle() {
        boolean isBlocked = prefs().getBoolean(KeeperService.KEY_BLOCKED, false);
        // 状态是断网但隧道没起来：当成重试，而不是误关
        boolean target = isBlocked && !BlockService.isBlocking() ? true : !isBlocked;
        if (target && VpnService.prepare(context) != null) {
            // 还没授权 VPN，只能回到页面上弹系统授权框
            openPage();
            return;
        }
        // 服务就在跑，直接广播过去：不重启服务，也不受「后台启动前台服务」限制
        if (KeeperService.isRunning()) {
            context.sendBroadcast(new Intent(KeeperService.ACTION_BALL_TOGGLE)
                    .setPackage(context.getPackageName())
                    .putExtra(KeeperService.EXTRA_BLOCKED, target));
            return;
        }
        Intent i = new Intent(context, KeeperService.class)
                .setAction(KeeperService.ACTION_SET_BLOCK)
                .putExtra(KeeperService.EXTRA_BLOCKED, target);
        try {
            context.startForegroundService(i);
        } catch (Exception e) {
            try {
                context.startService(i);
            } catch (Exception ignored) {
                Log.w(TAG, "toggle from ball failed", e);
            }
        }
    }

    private void openPage() {
        try {
            context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception e) {
            Log.w(TAG, "open page failed", e);
        }
    }

    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            try {
                android.graphics.Rect r = wm.getCurrentWindowMetrics().getBounds();
                return new Point(r.width(), r.height());
            } catch (Exception ignored) {
            }
        }
        Point p = new Point();
        if (wm != null) {
            //noinspection deprecation
            wm.getDefaultDisplay().getRealSize(p);
        }
        if (p.x == 0 || p.y == 0) {
            p.x = context.getResources().getDisplayMetrics().widthPixels;
            p.y = context.getResources().getDisplayMetrics().heightPixels;
        }
        return p;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(KeeperService.PREFS, Context.MODE_PRIVATE);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}

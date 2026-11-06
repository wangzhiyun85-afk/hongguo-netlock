package com.jingxuan.netlock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 极简状态页：整页就是一张背景图，点一下切换断网 / 联网。
 * 断网时切到 off 背景，联网时显示 on 背景，不切换桌面图标组件，
 * 因此从通知栏切换不会把贴边小窗（或本页面所在的最近任务）关掉。
 */
public class MainActivity extends Activity {

    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIFY = 2;
    private static final int REQ_PICK_BG_ON = 11;
    private static final int REQ_PICK_BG_OFF = 12;
    private static final int REQ_PICK_LOGO = 13;

    private ImageView background;
    private TextView status;

    /** 背景图用 RGB_565 解码，内存只有普通解码的一半左右 */
    private Bitmap bgOn;
    private Bitmap bgOff;

    /** 当前显示的背景是否是 off 图 */
    private boolean showingOff = false;

    /** 刚跳去授权「显示在其他应用上层」；回来时把悬浮球挂上 */
    private boolean overlayRequested = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        registerStateReceiver();
        requestNotificationPermission();
        startKeeper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (overlayRequested) {
            overlayRequested = false;
            // 授权回来后拉起服务，服务会在有权限时挂上悬浮球
            startKeeper();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        if (bgOn != null) bgOn.recycle();
        if (bgOff != null) bgOff.recycle();
        bgOn = null;
        bgOff = null;
        super.onDestroy();
    }

    private void startKeeper() {
        prefs().edit().putBoolean(KeeperService.KEY_RESIDENT, true).apply();
        Intent i = new Intent(this, KeeperService.class).setAction(KeeperService.ACTION_START);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            startService(i);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgOn = decodeBg(R.drawable.bg_on, CustomImages.BG_ON);
        background.setImageBitmap(bgOn);
        root.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = dp(30), padV = dp(16);
        bottom.setPadding(padH, padV, padH, dp(14));

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x59000000);
        pill.setCornerRadius(dp(26));
        bottom.setBackground(pill);

        status = new TextView(this);
        status.setTextSize(19);
        status.setTextColor(0xFFFFFFFF);
        status.setGravity(Gravity.CENTER);
        bottom.addView(status, wrap());

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setGravity(Gravity.CENTER);
        links.setPadding(0, dp(12), 0, 0);

        TextView exitLink = link("退出常驻");
        exitLink.setPadding(dp(22), 0, 0, 0);
        exitLink.setOnClickListener(v -> exitResident());
        links.addView(exitLink);

        TextView ballLink = link("悬浮球");
        ballLink.setPadding(dp(22), 0, 0, 0);
        ballLink.setOnClickListener(v -> openFloatingBall());
        links.addView(ballLink);

        TextView imageLink = link("图片");
        imageLink.setPadding(dp(22), 0, 0, 0);
        imageLink.setOnClickListener(v -> showImageMenu());
        links.addView(imageLink);

        bottom.addView(links, wrap());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(44);
        root.addView(bottom, lp);

        // 整页可点：点一下切换断网 / 恢复联网
        root.setOnClickListener(v -> onAction());
        setContentView(root);
    }

    private TextView link(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xAAFFFFFF);
        return tv;
    }

    private Bitmap decodeBg(int res, int kind) {
        // 优先用用户自己上传的图，没有才用内置图
        Bitmap custom = CustomImages.loadBackground(this, kind,
                getResources().getDisplayMetrics().widthPixels);
        if (custom != null) return custom;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inScaled = false;
        return BitmapFactory.decodeResource(getResources(), res, o);
    }

    /** 图片自定义入口：联网背景 / 断网背景 / 悬浮球头像 */
    private void showImageMenu() {
        String[] items = {"更换联网背景", "更换断网背景", "更换悬浮球头像", "恢复默认图片"};
        new AlertDialog.Builder(this)
                .setTitle("自定义图片")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) pickImage(REQ_PICK_BG_ON);
                    else if (which == 1) pickImage(REQ_PICK_BG_OFF);
                    else if (which == 2) pickImage(REQ_PICK_LOGO);
                    else resetImages();
                })
                .show();
    }

    /** 走系统文件选择器（SAF），不需要存储权限 */
    private void pickImage(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(i, request);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), request);
            } catch (Exception ignored) {
                Toast.makeText(this, "没找到可用的相册或文件管理器", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 解码 + 压缩较大，放到子线程做，避免大图卡住界面 */
    private void importImage(Uri uri, int kind) {
        int width = kind == CustomImages.LOGO
                ? dp(120)
                : getResources().getDisplayMetrics().widthPixels;
        Toast.makeText(this, "正在处理图片…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = CustomImages.importFrom(getApplicationContext(), uri, kind, width);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!ok) {
                    Toast.makeText(this, "这张图读不出来，换一张试试", Toast.LENGTH_SHORT).show();
                    return;
                }
                reloadImages();
                Toast.makeText(this, kind == CustomImages.BG_ON ? "联网背景已替换"
                        : kind == CustomImages.BG_OFF ? "断网背景已替换"
                        : "悬浮球头像已替换", Toast.LENGTH_SHORT).show();
            });
        }, "netlock-image").start();
    }

    private void resetImages() {
        CustomImages.clear(this, CustomImages.BG_ON);
        CustomImages.clear(this, CustomImages.BG_OFF);
        CustomImages.clear(this, CustomImages.LOGO);
        reloadImages();
        Toast.makeText(this, "已恢复默认图片", Toast.LENGTH_SHORT).show();
    }

    /** 换图后重建内存里的位图，并让常驻服务里的悬浮球同步换头像 */
    private void reloadImages() {
        if (bgOn != null) bgOn.recycle();
        if (bgOff != null) bgOff.recycle();
        bgOn = null;
        bgOff = null;
        bgOn = decodeBg(R.drawable.bg_on, CustomImages.BG_ON);
        boolean off = prefs().getBoolean(KeeperService.KEY_BLOCKED, false) && BlockService.isBlocking();
        showingOff = off;
        if (off) bgOff = decodeBg(R.drawable.bg_off, CustomImages.BG_OFF);
        background.setImageBitmap(off ? bgOff : bgOn);
        refresh();
        sendBroadcast(new Intent(CustomImages.ACTION_CHANGED).setPackage(getPackageName()));
    }

    /** 开启常驻悬浮球：没权限先去系统授权页，回来后在 onResume 里挂上 */
    private void openFloatingBall() {
        if (!Settings.canDrawOverlays(this)) {
            overlayRequested = true;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "允许「显示在其他应用上层」后，悬浮球会自动出现", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "请在系统设置里允许本 App 显示悬浮窗", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Toast.makeText(this, "悬浮球已开启：点一下切换，长按打开本页，可拖动", Toast.LENGTH_LONG).show();
        startKeeper();
    }

    private void refresh() {
        boolean blocked = prefs().getBoolean(KeeperService.KEY_BLOCKED, false);
        boolean resident = prefs().getBoolean(KeeperService.KEY_RESIDENT, false);
        // 以服务实际是否建起拦截隧道为准，而不是只看偏好值
        boolean reallyBlocking = BlockService.isBlocking();
        boolean off = blocked && reallyBlocking;

        if (off != showingOff) {
            showingOff = off;
            if (off && bgOff == null) bgOff = decodeBg(R.drawable.bg_off, CustomImages.BG_OFF);
            background.setImageBitmap(off ? bgOff : bgOn);
        }

        if (!resident) {
            status.setText("常驻已关闭");
        } else if (off) {
            status.setText("已断网");
        } else if (blocked) {
            status.setText("断网未生效 · 点一下重试");
        } else {
            status.setText("联网中");
        }
    }

    private void onAction() {
        boolean blocked = prefs().getBoolean(KeeperService.KEY_BLOCKED, false);
        if (blocked && !BlockService.isBlocking()) {
            // 状态是断网但隧道没起来：直接重试，而不是误把开关关掉
            applyBlock(true);
            return;
        }
        if (blocked) {
            applyBlock(false);
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
        } else {
            applyBlock(true);
        }
    }

    private void applyBlock(boolean on) {
        Intent i = new Intent(this, KeeperService.class)
                .setAction(KeeperService.ACTION_SET_BLOCK)
                .putExtra(KeeperService.EXTRA_BLOCKED, on);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            startService(i);
        }
        // 隧道建立/拆除有一小段延迟，多刷几次，让界面跟真实状态对齐
        status.postDelayed(this::refresh, 400);
        status.postDelayed(this::refresh, 1500);
    }

    private void exitResident() {
        startService(new Intent(this, KeeperService.class).setAction(KeeperService.ACTION_EXIT));
        Toast.makeText(this, "已退出常驻，再次打开本页会重新开启", Toast.LENGTH_SHORT).show();
        status.postDelayed(this::refresh, 200);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        int kind = requestCode == REQ_PICK_BG_ON ? CustomImages.BG_ON
                : requestCode == REQ_PICK_BG_OFF ? CustomImages.BG_OFF
                : requestCode == REQ_PICK_LOGO ? CustomImages.LOGO : 0;
        if (kind != 0) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importImage(data.getData(), kind);
            }
            return;
        }
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                status.post(() -> {
                    if (!isFinishing() && !isDestroyed()) applyBlock(true);
                });
            } else {
                Toast.makeText(this, "需要允许「红果断网」创建 VPN 连接", Toast.LENGTH_SHORT).show();
                refresh();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) {
            // 授权后再刷新一次前台服务通知
            startKeeper();
        }
    }

    @Override
    public void onBackPressed() {
        // 保留在最近任务里，卡片才能被系统“下拉锁定”
        moveTaskToBack(true);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(KeeperService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(KeeperService.PREFS, MODE_PRIVATE);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

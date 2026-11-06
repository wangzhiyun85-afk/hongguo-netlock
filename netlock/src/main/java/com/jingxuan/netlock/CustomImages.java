package com.jingxuan.netlock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 用户自选的背景图与悬浮球头像。
 *
 * 存在应用私有目录（files/custom），不申请存储权限、也不读取相册以外的位置；
 * 导入时按目标宽度降采样再保存，避免 12MP 大图把内存吃满。
 * 内置图仍然保留在 res 里，删除自定义图后立即回退到内置图。
 */
final class CustomImages {

    private static final String TAG = "NetLock";

    static final int BG_ON = 1;
    static final int BG_OFF = 2;
    static final int LOGO = 3;

    /** 图片被替换 / 恢复默认后通知常驻服务刷新悬浮球 */
    static final String ACTION_CHANGED = "com.jingxuan.netlock.IMAGES_CHANGED";

    private CustomImages() {
    }

    static File file(Context context, int kind) {
        String name;
        if (kind == BG_ON) {
            name = "bg_on.jpg";
        } else if (kind == BG_OFF) {
            name = "bg_off.jpg";
        } else {
            name = "logo.png";
        }
        File dir = new File(context.getFilesDir(), "custom");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "create custom image dir failed");
        }
        return new File(dir, name);
    }

    static boolean has(Context context, int kind) {
        return file(context, kind).isFile();
    }

    static boolean clear(Context context, int kind) {
        File f = file(context, kind);
        return !f.exists() || f.delete();
    }

    /** 从相册导入一张图，按 targetWidth 降采样后覆盖保存；背景存 JPEG，头像存 PNG 以保留透明 */
    static boolean importFrom(Context context, Uri uri, int kind, int targetWidth) {
        File target = file(context, kind);
        File tmp = new File(target.getPath() + ".tmp");
        Bitmap bitmap = null;
        try {
            bitmap = readSampled(context, uri, targetWidth, kind == LOGO);
            if (bitmap == null) return false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                Bitmap.CompressFormat format = kind == LOGO
                        ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
                if (!bitmap.compress(format, 90, out)) return false;
            }
            return tmp.renameTo(target) || (target.delete() && tmp.renameTo(target));
        } catch (Exception e) {
            Log.w(TAG, "import image failed kind=" + kind, e);
            return false;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (tmp.exists()) tmp.delete();
        }
    }

    /** 自定义背景；没有自定义图时返回 null（调用方回退到内置资源） */
    static Bitmap loadBackground(Context context, int kind, int targetWidth) {
        File f = file(context, kind);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inScaled = false;
            o.inSampleSize = sampleOf(f, targetWidth);
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            Log.w(TAG, "load custom background failed", e);
            return null;
        }
    }

    /**
     * 悬浮球头像：把自定义图裁成圆形，再套上与内置图标一致的绿/红描边。
     * 没有自定义图时返回 null（调用方回退到内置 ball_on / ball_off）。
     */
    static Bitmap ballBitmap(Context context, int sizePx, boolean blocked) {
        File f = file(context, LOGO);
        if (!f.isFile()) return null;
        Bitmap logo = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            o.inSampleSize = sampleOf(f, (int) (sizePx * 0.8f));
            logo = BitmapFactory.decodeFile(f.getPath(), o);
            if (logo == null) return null;

            Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            float cx = sizePx / 2f;
            float cy = sizePx / 2f;
            float ro = sizePx * 0.475f;
            float ri = sizePx * 0.435f;
            float rp = sizePx * 0.400f;

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(blocked ? 0xFFFF5C5C : 0xFF5AE090);
            canvas.drawCircle(cx, cy, ro, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            canvas.drawCircle(cx, cy, ri, paint);
            paint.setXfermode(null);

            Path clip = new Path();
            clip.addCircle(cx, cy, rp, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(logo, null,
                    new RectF(cx - rp, cy - rp, cx + rp, cy + rp),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            canvas.restore();
            return out;
        } catch (Exception e) {
            Log.w(TAG, "build custom ball bitmap failed", e);
            return null;
        } finally {
            if (logo != null) logo.recycle();
        }
    }

    private static Bitmap readSampled(Context context, Uri uri, int targetWidth, boolean keepAlpha) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= Math.max(1, targetWidth)) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = keepAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private static int sampleOf(File f, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= Math.max(1, targetWidth)) {
            sample *= 2;
        }
        return sample;
    }
}

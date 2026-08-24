package com.miaojizhang.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

/** Fixed orange notification styling used by the single-theme build. */
final class NotificationIconHelper {
    private NotificationIconHelper() {}

    static String readTheme(Context context) { return "orange"; }

    static int currentIconRes(Context context) { return R.drawable.ic_launcher_orange; }

    static int currentColor(Context context) { return Color.rgb(255, 106, 0); }

    static int buttonBackgroundResForTheme(String ignored) { return R.drawable.notification_add_button; }

    static Bitmap currentLargeIcon(Context context) {
        try {
            Drawable drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_orange);
            if (drawable == null) return null;
            int size = (int) (48 * context.getResources().getDisplayMetrics().density + 0.5f);
            if (size <= 0) size = 96;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}

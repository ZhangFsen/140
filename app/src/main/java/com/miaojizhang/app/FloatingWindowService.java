package com.miaojizhang.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RemoteViews;

public class FloatingWindowService extends Service {
    static final String ACTION_REFRESH_NOTIFICATION = "com.miaojizhang.app.REFRESH_FLOAT_NOTIFICATION";
    private static final String FLOAT_CHANNEL_ID = "miaojizhang_float";
    private static final int FLOAT_NOTIFICATION_ID = 9201;
    private static final float IDLE_ALPHA = 0.70f;

    private WindowManager windowManager;
    private View floatView;
    private View closeMenuView;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams closeMenuParams;
    private int startX;
    private int startY;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean longPressed;
    private int bubbleSize;
    private int hideOffset;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private Runnable hideMenuRunnable;
    private boolean receiverRegistered = false;

    private boolean isFloatEnabled() {
        return getSharedPreferences("miaojizhang_native", MODE_PRIVATE).getBoolean("float_enabled", false);
    }

    private boolean isQuickNotificationEnabled() {
        return getSharedPreferences("miaojizhang_native", MODE_PRIVATE).getBoolean("quick_notification_enabled", true);
    }

    private boolean canDrawOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private final Runnable dimRunnable = () -> {
        if (floatView != null && closeMenuView == null) floatView.setAlpha(IDLE_ALPHA);
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hideCloseMenuNow();
            handler.postDelayed(() -> {
                if (!isFloatEnabled() || !canDrawOverlay()) return;
                if (floatView == null) createFloatingButton();
                else restoreFloatAfterFullscreenCheck();
            }, 250);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (isFloatEnabled() && !canDrawOverlay()) {
            getSharedPreferences("miaojizhang_native", MODE_PRIVATE).edit().putBoolean("float_enabled", false).apply();
        }
        if (!isFloatEnabled() && !isQuickNotificationEnabled()) {
            stopSelf();
            return;
        }
        startAsForegroundService();
        syncFeatureViews();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isFloatEnabled() && !canDrawOverlay()) {
            getSharedPreferences("miaojizhang_native", MODE_PRIVATE).edit().putBoolean("float_enabled", false).apply();
        }
        if (!isFloatEnabled() && !isQuickNotificationEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForegroundService();
        syncFeatureViews();
        return START_STICKY;
    }

    private void syncFeatureViews() {
        if (isFloatEnabled() && canDrawOverlay()) {
            registerStateReceiver();
            if (floatView == null) createFloatingButton();
            else restoreFloatAfterFullscreenCheck();
        } else {
            removeFloatingButton();
        }
    }

    private void removeFloatingButton() {
        hideCloseMenuNow();
        if (windowManager != null && floatView != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
    }

    private void startAsForegroundService() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        FLOAT_CHANNEL_ID,
                        "快捷记账",
                        NotificationManager.IMPORTANCE_MIN
                );
                channel.setDescription("保持悬浮球或快捷记账通知稳定运行");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            Intent launchIntent = new Intent(this, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 9202, launchIntent, pendingFlags);

            Intent addIntent = new Intent(this, MainActivity.class);
            addIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            addIntent.putExtra("open_add", true);
            PendingIntent addPi = PendingIntent.getActivity(this, 9203, addIntent, pendingFlags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, FLOAT_CHANNEL_ID)
                    : new Notification.Builder(this);
            Bitmap largeIcon = NotificationIconHelper.currentLargeIcon(this);
            RemoteViews notificationView = new RemoteViews(getPackageName(), R.layout.notification_float);
            boolean floatEnabled = isFloatEnabled() && canDrawOverlay();
            String notificationTitle = floatEnabled ? "悬浮窗运行中" : "快捷记账通知";
            String notificationText = floatEnabled ? "保持悬浮球快速记账" : "随时快速记录一笔";
            notificationView.setTextViewText(R.id.notification_title, notificationTitle);
            notificationView.setTextViewText(R.id.notification_subtitle, notificationText);
            notificationView.setImageViewResource(R.id.notification_icon, NotificationIconHelper.currentIconRes(this));
            notificationView.setInt(R.id.notification_add, "setBackgroundResource", NotificationIconHelper.buttonBackgroundResForTheme(NotificationIconHelper.readTheme(this)));
            int quickVisibility = isQuickNotificationEnabled() ? View.VISIBLE : View.GONE;
            notificationView.setViewVisibility(R.id.notification_divider, quickVisibility);
            notificationView.setViewVisibility(R.id.notification_add, quickVisibility);
            notificationView.setOnClickPendingIntent(R.id.notification_content, pi);
            notificationView.setOnClickPendingIntent(R.id.notification_add, addPi);
            Notification.Builder notificationBuilder = builder
                    .setSmallIcon(NotificationIconHelper.currentIconRes(this))
                    .setContentTitle("秒记账" + notificationTitle)
                    .setContentText(notificationText)
                    .setContentIntent(pi)
                    .setCustomContentView(notificationView)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setWhen(0L)
                    .setColor(NotificationIconHelper.currentColor(this));
            if (largeIcon != null) notificationBuilder.setLargeIcon(largeIcon);
            Notification notification = notificationBuilder.build();
            startForeground(FLOAT_NOTIFICATION_ID, notification);
        } catch (Exception ignored) {
            // 某些系统会限制前台服务通知；失败时仍继续尝试显示悬浮窗，避免直接闪退。
        }
    }

    private void registerStateReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(stateReceiver, filter);
            receiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private void createFloatingButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null || floatView != null) return;

        float density = getResources().getDisplayMetrics().density;
        bubbleSize = (int) (56 * density);
        hideOffset = (int) (36 * density);

        TextView bubble = new TextView(this);
        bubble.setText("+");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(30);
        bubble.setGravity(Gravity.CENTER);
        bubble.setIncludeFontPadding(false);
        bubble.setBackground(makeBubbleBackground());
        bubble.setElevation(7 * density);
        bubble.setAlpha(IDLE_ALPHA);
        floatView = bubble;

        params = new WindowManager.LayoutParams();
        params.width = bubbleSize;
        params.height = bubbleSize;
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        SharedPreferences sp = getSharedPreferences("miaojizhang_native", MODE_PRIVATE);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int savedX = sp.getInt("float_x", sw - bubbleSize + hideOffset);
        int savedY = sp.getInt("float_y", Math.max((int) (100 * density), sh / 2 - bubbleSize));
        params.x = clampX(savedX);
        params.y = clampY(savedY);

        floatView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (floatView != null) floatView.setAlpha(1f);
                    hideCloseMenuNow();
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    moved = false;
                    longPressed = false;
                    startLongPress(v);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true;
                        cancelLongPress();
                    }
                    params.x = startX + dx;
                    params.y = clampY(startY + dy);
                    safeUpdateFloatView();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelLongPress();
                    if (longPressed) {
                        snapToEdge();
                        return true;
                    }
                    if (!moved) openAddRecord();
                    else snapToEdge();
                    return true;
            }
            return false;
        });

        try {
            windowManager.addView(floatView, params);
            restoreFloatAfterFullscreenCheck();
        } catch (Exception e) {
            floatView = null;
            stopSelf();
        }
    }

    private GradientDrawable makeBubbleBackground() {
        int primary = readThemeColor();
        int light = mixColor(primary, Color.WHITE, 0.22f);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{light, primary});
        bg.setShape(GradientDrawable.OVAL);
        return bg;
    }

    private int readThemeColor() {
        String color = getSharedPreferences("miaojizhang_native", MODE_PRIVATE).getString("float_color", "#12b981");
        try { return Color.parseColor(color); } catch (Exception e) { return Color.rgb(18, 185, 129); }
    }

    private int mixColor(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return Color.rgb(r, g, bl);
    }

    private int clampX(int x) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int minX = -hideOffset;
        int maxX = sw - bubbleSize + hideOffset;
        return Math.max(minX, Math.min(maxX, x));
    }

    private int clampY(int y) {
        int sh = getResources().getDisplayMetrics().heightPixels;
        return Math.max(0, Math.min(sh - bubbleSize, y));
    }

    private void startLongPress(View v) {
        cancelLongPress();
        longPressRunnable = () -> {
            longPressed = true;
            try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Exception ignored) {}
            showCloseMenu();
        };
        handler.postDelayed(longPressRunnable, 650);
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void showCloseMenu() {
        if (floatView != null) floatView.setAlpha(1f);
        if (windowManager == null || closeMenuView != null || params == null) return;
        float density = getResources().getDisplayMetrics().density;
        TextView menu = new TextView(this);
        menu.setText("关闭悬浮窗");
        menu.setTextColor(Color.rgb(17, 24, 39));
        menu.setTextSize(15);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding((int) (16 * density), 0, (int) (16 * density), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(245, 255, 255, 255));
        bg.setCornerRadius(18 * density);
        bg.setStroke((int) (1 * density), mixColor(readThemeColor(), Color.WHITE, 0.62f));
        menu.setBackground(bg);
        menu.setElevation(12 * density);
        menu.setOnClickListener(v -> closeFloatingWindow());
        closeMenuView = menu;

        closeMenuParams = new WindowManager.LayoutParams();
        closeMenuParams.width = (int) (126 * density);
        closeMenuParams.height = (int) (46 * density);
        closeMenuParams.gravity = Gravity.TOP | Gravity.START;
        closeMenuParams.format = PixelFormat.TRANSLUCENT;
        closeMenuParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) closeMenuParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        else closeMenuParams.type = WindowManager.LayoutParams.TYPE_PHONE;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int menuW = closeMenuParams.width;
        boolean onLeft = params.x < sw / 2;
        closeMenuParams.x = onLeft ? Math.max(6, params.x + bubbleSize - hideOffset + 8) : Math.max(6, params.x - menuW - 8);
        closeMenuParams.y = clampY(params.y + (bubbleSize - closeMenuParams.height) / 2);

        try { windowManager.addView(closeMenuView, closeMenuParams); } catch (Exception ignored) { closeMenuView = null; }
        hideCloseMenuDelayed(3500);
    }

    private void hideCloseMenuDelayed(long delayMs) {
        if (hideMenuRunnable != null) handler.removeCallbacks(hideMenuRunnable);
        hideMenuRunnable = this::hideCloseMenuNow;
        handler.postDelayed(hideMenuRunnable, Math.max(0, delayMs));
    }

    private void hideCloseMenuNow() {
        if (hideMenuRunnable != null) handler.removeCallbacks(hideMenuRunnable);
        if (windowManager != null && closeMenuView != null) {
            try { windowManager.removeView(closeMenuView); } catch (Exception ignored) {}
        }
        closeMenuView = null;
    }

    private void closeFloatingWindow() {
        SharedPreferences sp = getSharedPreferences("miaojizhang_native", MODE_PRIVATE);
        sp
                .edit()
                .putBoolean("float_enabled", false)
                .putBoolean("float_pending_permission", false)
                .apply();
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
        if (isQuickNotificationEnabled()) {
            startAsForegroundService();
            syncFeatureViews();
        } else stopSelf();
    }

    private void openAddRecord() {
        hideCloseMenuNow();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("open_add", true);
        try { startActivity(intent); } catch (Exception ignored) {}
    }

    private void safeUpdateFloatView() {
        if (windowManager == null || floatView == null || params == null) return;
        params.x = clampX(params.x);
        params.y = clampY(params.y);
        try {
            windowManager.updateViewLayout(floatView, params);
        } catch (Exception e) {
            recreateFloatingButton();
        }
    }

    private void recreateFloatingButton() {
        hideCloseMenuNow();
        if (windowManager != null && floatView != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
        createFloatingButton();
    }


    private boolean shouldHideFloatForFullscreen() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            // 横屏视频/游戏等全屏场景下，直接隐藏悬浮球；回到竖屏后恢复半露吸边。
            return dm.widthPixels > dm.heightPixels;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean applyFullscreenHiddenState() {
        if (floatView == null) return false;
        if (shouldHideFloatForFullscreen()) {
            hideCloseMenuNow();
            if (floatView.getVisibility() != View.GONE) floatView.setVisibility(View.GONE);
            return true;
        }
        if (floatView.getVisibility() != View.VISIBLE) floatView.setVisibility(View.VISIBLE);
        return false;
    }

    private void restoreFloatAfterFullscreenCheck() {
        if (applyFullscreenHiddenState()) return;
        snapToEdge();
    }

    private void snapToEdge() {
        if (params == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        params.x = params.x + bubbleSize / 2 < sw / 2 ? -hideOffset : sw - bubbleSize + hideOffset;
        params.y = clampY(params.y);
        safeUpdateFloatView();
        if (floatView != null) {
            handler.removeCallbacks(dimRunnable);
            handler.postDelayed(dimRunnable, 1200);
        }
        getSharedPreferences("miaojizhang_native", MODE_PRIVATE)
                .edit()
                .putInt("float_x", params.x)
                .putInt("float_y", params.y)
                .apply();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideCloseMenuNow();
        handler.postDelayed(this::restoreFloatAfterFullscreenCheck, 180);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        hideCloseMenuNow();
        SharedPreferences sp = getSharedPreferences("miaojizhang_native", MODE_PRIVATE);
        if (sp.getBoolean("float_enabled", false) || sp.getBoolean("quick_notification_enabled", true)) {
            try {
                Intent restart = new Intent(getApplicationContext(), FloatingWindowService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restart);
                else startService(restart);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelLongPress();
        handler.removeCallbacks(dimRunnable);
        if (hideMenuRunnable != null) handler.removeCallbacks(hideMenuRunnable);
        hideCloseMenuNow();
        if (windowManager != null && floatView != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
        if (receiverRegistered) {
            try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        try { stopForeground(true); } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.miaojizhang.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.VibrationEffect;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.core.splashscreen.SplashScreen;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

public class MainActivity extends Activity {
    public static final String CHANNEL_ID = "miaojizhang_reminder";
    private static final int REQ_NOTIFICATION = 1001;
    private static final int REQ_FILE_CHOOSER = 1002;
    private WebView webView;
    private FrameLayout rootView;
    private View nativeSplashView;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean pageReady = false;
    private long nativeSplashShownAt;
    private boolean nativeSplashClosing = false;
    private boolean openAddAfterLoad = false;
    private AppUpdater appUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        splashScreen.setOnExitAnimationListener(provider ->
                provider.getView().animate()
                        .alpha(0f)
                        .setDuration(120L)
                        .withEndAction(provider::remove)
                        .start());
        showOrangeSystemBars();
        createNotificationChannel();
        requestNotificationPermissionIfNeeded();
        appUpdater = new AppUpdater(this, this::sendUpdateEvent);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        }

        webView.setBackgroundColor(Color.rgb(255, 248, 239));
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollBarStyle(WebView.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.setWebViewClient(new WebViewClient() {
            
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                hideNativeSplashWhenReady();
                maybeOpenAddFromIntent();
            }
        });
        webView.setWebChromeClient(new AppWebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        rootView = new FrameLayout(this);
        rootView.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        nativeSplashView = getLayoutInflater().inflate(R.layout.view_native_splash, rootView, false);
        rootView.addView(nativeSplashView);
        nativeSplashShownAt = android.os.SystemClock.uptimeMillis();
        setContentView(rootView);
        handleIntent(getIntent());
        webView.loadUrl("file:///android_asset/index.html");
        webView.postDelayed(this::hideNativeSplash, 1800L);
    }

    private void hideNativeSplashWhenReady() {
        long elapsed = android.os.SystemClock.uptimeMillis() - nativeSplashShownAt;
        long delay = Math.max(0L, 420L - elapsed);
        if (webView != null) webView.postDelayed(this::hideNativeSplash, delay);
    }

    private void hideNativeSplash() {
        if (nativeSplashView == null || nativeSplashClosing) return;
        nativeSplashClosing = true;
        View splash = nativeSplashView;
        splash.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction(() -> {
                    if (rootView != null) rootView.removeView(splash);
                    nativeSplashView = null;
                    restoreAppSystemBars();
                })
                .start();
    }

    private void showOrangeSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(255, 122, 0));
        window.setNavigationBarColor(Color.rgb(255, 122, 0));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            window.getDecorView().setSystemUiVisibility(0);
        }
    }

    private void restoreAppSystemBars() {
        Window window = getWindow();
        int appBarColor = Color.rgb(255, 248, 239);
        window.setStatusBarColor(appBarColor);
        window.setNavigationBarColor(appBarColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("open_add", false)) {
            openAddAfterLoad = true;
        }
    }

    private void maybeOpenAddFromIntent() {
        if (webView == null || !pageReady || !openAddAfterLoad) return;
        openAddAfterLoad = false;
        webView.postDelayed(() -> webView.evaluateJavascript("window.openAddFromFloating ? window.openAddFromFloating() : (window.openAdd && openAdd(false));", null), 220);
    }

    private static void startFloatingService(Context context) {
        try {
            Intent intent = new Intent(context, FloatingWindowService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {}
    }

    private void syncFloatingWindowAfterPermission() {
        SharedPreferences sp = getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
        boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        if (sp.getBoolean("float_pending_permission", false) && canDraw) {
            sp.edit().putBoolean("float_pending_permission", false).putBoolean("float_enabled", true).apply();
            startFloatingService(this);
        } else if ((sp.getBoolean("float_enabled", false) && canDraw) || sp.getBoolean("quick_notification_enabled", true)) {
            startFloatingService(this);
        }
        if (webView != null && pageReady) {
            webView.evaluateJavascript("window.refreshFloatingStatus && window.refreshFloatingStatus();", null);
        }
    }

    
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        maybeOpenAddFromIntent();
    }

    
    protected void onResume() {
        super.onResume();
        syncFloatingWindowAfterPermission();
        if (appUpdater != null) appUpdater.continuePendingInstall();
        if (webView != null && pageReady) {
            webView.evaluateJavascript("window.onMjyNativeResume && window.onMjyNativeResume();", null);
        }
    }

    @Override
    protected void onDestroy() {
        if (appUpdater != null) appUpdater.shutdown();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private void sendUpdateEvent(String event, JSONObject data) {
        if (webView == null || !pageReady) return;
        JSONObject message = new JSONObject();
        try {
            message.put("event", event);
            message.put("data", data == null ? new JSONObject() : data);
            String argument = JSONObject.quote(message.toString());
            webView.evaluateJavascript("window.onNativeUpdateEvent && window.onNativeUpdateEvent(" + argument + ");", null);
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "记账提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("每日记账与家长提醒");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    private class AppWebChromeClient extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (MainActivity.this.filePathCallback != null) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
            }
            MainActivity.this.filePathCallback = filePathCallback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
            } catch (Exception e) {
                MainActivity.this.filePathCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("提示")
                    .setMessage(message)
                    .setPositiveButton("确定", (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("确认")
                    .setMessage(message)
                    .setPositiveButton("确定", (d, w) -> result.confirm())
                    .setNegativeButton("取消", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setSelection(input.getText().length());
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            input.setPadding(pad, pad / 2, pad, pad / 2);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(message == null || message.length() == 0 ? "请输入" : message)
                    .setView(input)
                    .setPositiveButton("确定", (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton("取消", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) results = new Uri[]{uri};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    public class AndroidBridge {
        private final Context context;

        AndroidBridge(Context context) {
            this.context = context.getApplicationContext();
        }

        @JavascriptInterface
        public String getAppVersion() {
            JSONObject data = new JSONObject();
            try {
                data.put("versionName", BuildConfig.VERSION_NAME);
                data.put("versionCode", BuildConfig.VERSION_CODE);
            } catch (Exception ignored) {}
            return data.toString();
        }

        @JavascriptInterface
        public void checkUpdate(boolean manual) {
            MainActivity.this.runOnUiThread(() -> {
                if (appUpdater != null) appUpdater.check(manual);
            });
        }

        @JavascriptInterface
        public void downloadUpdate(String apkUrl, String sha256) {
            MainActivity.this.runOnUiThread(() -> {
                if (appUpdater != null) appUpdater.download(apkUrl, sha256);
            });
        }

        @JavascriptInterface
        public void cancelUpdateDownload() {
            if (appUpdater != null) appUpdater.cancel();
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                if (ms < 35) ms = 35;
                if (ms > 160) ms = 160;
                Vibrator vibrator;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    vibrator = vm == null ? null : vm.getDefaultVibrator();
                } else {
                    vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                }
                if (vibrator == null || !vibrator.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, 255));
                } else {
                    vibrator.vibrate(ms);
                }
            } catch (Exception ignored) {}
        }

        /* internal helper */
        private boolean canDrawOverlay() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
        }

        private void startFloatingService() {
            MainActivity.startFloatingService(context);
        }

        private void stopFloatingService() {
            try { context.stopService(new Intent(context, FloatingWindowService.class)); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setFloatingColor(String color) {
            if (color == null || !color.matches("^#[0-9a-fA-F]{6}$")) return;
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            sp.edit().putString("float_color", color).apply();
            if (sp.getBoolean("float_enabled", false) && canDrawOverlay()) {
                stopFloatingService();
                startFloatingService();
            }
        }

        @JavascriptInterface
        public String getFloatingWindowState() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            boolean enabled = sp.getBoolean("float_enabled", false);
            boolean can = canDrawOverlay();
            if (enabled && !can) {
                sp.edit().putBoolean("float_enabled", false).apply();
                enabled = false;
            }
            return enabled ? "on" : (can ? "off" : "need_permission");
        }

        @JavascriptInterface
        public String toggleFloatingWindow() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            boolean enabled = sp.getBoolean("float_enabled", false);
            if (enabled) {
                sp.edit().putBoolean("float_enabled", false).putBoolean("float_pending_permission", false).apply();
                if (sp.getBoolean("quick_notification_enabled", true)) startFloatingService();
                else stopFloatingService();
                return "disabled";
            }
            if (!canDrawOverlay()) {
                sp.edit().putBoolean("float_pending_permission", true).apply();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
                return "need_permission";
            }
            sp.edit().putBoolean("float_enabled", true).putBoolean("float_pending_permission", false).apply();
            startFloatingService();
            return "enabled";
        }

        @JavascriptInterface
        public String getQuickNotificationState() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            return sp.getBoolean("quick_notification_enabled", true) ? "on" : "off";
        }

        @JavascriptInterface
        public String toggleQuickNotification() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            boolean enabled = sp.getBoolean("quick_notification_enabled", true);
            if (enabled) {
                sp.edit().putBoolean("quick_notification_enabled", false).apply();
                if (sp.getBoolean("float_enabled", false) && canDrawOverlay()) startFloatingService();
                else stopFloatingService();
                return "disabled";
            }
            sp.edit().putBoolean("quick_notification_enabled", true).apply();
            startFloatingService();
            return "enabled";
        }

        @JavascriptInterface
        public void openFloatingPermission() {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }

        
        @JavascriptInterface
        public void scheduleDailyReminder(String time) {
            if (time == null || !time.matches("^\\d{1,2}:\\d{2}$")) return;
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return;

            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            sp.edit().putBoolean("reminder_enabled", true).putString("reminder_time", String.format("%02d:%02d", hour, minute)).apply();
            schedule(context, hour, minute);
        }

        @JavascriptInterface
        public void cancelDailyReminder() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            sp.edit().putBoolean("reminder_enabled", false).apply();
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(reminderIntent(context));
        }

        @JavascriptInterface
        public String exportBackupFile(String filename, String content) {
            if (filename == null || filename.trim().length() == 0) filename = "秒记账备份.json";
            filename = filename.replaceAll("[\\/:*?\"<>|]", "_");
            if (!filename.endsWith(".json")) filename = filename + ".json";
            if (content == null) content = "";
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "";
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    if (os == null) return "";
                    os.write(content.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    return "/Download/" + filename;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    return file.getAbsolutePath();
                }
            } catch (Exception e) {
                try {
                    File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "";
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    return file.getAbsolutePath();
                } catch (Exception ignored) {
                    return "";
                }
            }
        }

        @JavascriptInterface
        public String exportTextFile(String filename, String content) {
            if (filename == null || filename.trim().length() == 0) filename = "秒记账分析.txt";
            filename = filename.replaceAll("[\\/:*?\"<>|]", "_");
            if (!filename.endsWith(".txt")) filename = filename + ".txt";
            if (content == null) content = "";
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "";
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    if (os == null) return "";
                    os.write(content.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    return "/Download/" + filename;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    return file.getAbsolutePath();
                }
            } catch (Exception e) {
                try {
                    File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "";
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    return file.getAbsolutePath();
                } catch (Exception ignored) {
                    return "";
                }
            }
        }


        @JavascriptInterface
        public String exportPngFile(String filename, String base64Png) {
            if (filename == null || filename.trim().length() == 0) filename = "秒记账分析.png";
            filename = filename.replaceAll("[\\/:*?\"<>|]", "_");
            if (!filename.endsWith(".png")) filename = filename + ".png";
            if (base64Png == null) base64Png = "";
            try {
                byte[] bytes = Base64.decode(base64Png, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "";
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    if (os == null) return "";
                    os.write(bytes);
                    os.close();
                    return "/Download/" + filename;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(bytes);
                    fos.close();
                    return file.getAbsolutePath();
                }
            } catch (Exception e) {
                try {
                    byte[] bytes = Base64.decode(base64Png, Base64.DEFAULT);
                    File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "";
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(bytes);
                    fos.close();
                    return file.getAbsolutePath();
                } catch (Exception ignored) {
                    return "";
                }
            }
        }


        @JavascriptInterface
        public void sharePngFile(String filename, String base64Png) {
            if (filename == null || filename.trim().length() == 0) filename = "秒记账分析.png";
            filename = filename.replaceAll("[\\/:*?\"<>|]", "_");
            if (!filename.endsWith(".png")) filename = filename + ".png";
            final String safeName = filename;
            final String data = base64Png == null ? "" : base64Png;
            try {
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/秒记账");
                }
                Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return;
                OutputStream os = context.getContentResolver().openOutputStream(uri);
                if (os == null) return;
                os.write(bytes);
                os.close();
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(intent, "分享统计图片");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooser);
            } catch (Exception e) {
                // 分享失败时不打断用户，前端仍可走保存兜底
            }
        }

        @JavascriptInterface
        public String getSavedReminderTime() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            return sp.getBoolean("reminder_enabled", false) ? sp.getString("reminder_time", "21:00") : "";
        }

    }

    static void schedule(Context context, int hour, int minute) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        PendingIntent pi = reminderIntent(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        }
    }

    static PendingIntent reminderIntent(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, 20260429, intent, flags);
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.appBack ? window.appBack() : 'false';", value -> {
                if ("false".equals(value) || "null".equals(value)) {
                    MainActivity.super.onBackPressed();
                }
            });
        } else {
            super.onBackPressed();
        }
    }
}

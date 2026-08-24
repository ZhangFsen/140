package com.miaojizhang.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdater {
    interface Listener {
        void onEvent(String event, JSONObject data);
    }

    private final Activity activity;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private File pendingApk;

    AppUpdater(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    void check(boolean manual) {
        String configUrl = BuildConfig.UPDATE_JSON_URL;
        if (!isHttps(configUrl) || configUrl.contains("example.com")) {
            emitError(manual ? "请先在 app/build.gradle 中配置 UPDATE_JSON_URL" : "");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(configUrl, 10000, 15000);
                String text = readText(connection.getInputStream());
                JSONObject data = new JSONObject(text);
                validateUpdateJson(data);
                String apkUrl = data.getString("apkUrl");
                if (!isHttps(apkUrl)) throw new IOException("APK 下载地址必须使用 HTTPS");
                data.put("currentVersionName", BuildConfig.VERSION_NAME);
                data.put("currentVersionCode", BuildConfig.VERSION_CODE);
                emit(data.getInt("versionCode") > BuildConfig.VERSION_CODE ? "available" : "latest", data);
            } catch (Exception e) {
                if (manual) emitError(safeMessage(e, "检查更新失败，请稍后重试"));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    void download(String apkUrl, String expectedSha256) {
        if (!isHttps(apkUrl)) {
            emitError("APK 下载地址必须使用 HTTPS");
            return;
        }
        if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                && !expectedSha256.matches("(?i)^[0-9a-f]{64}$")) {
            emitError("更新配置中的 SHA-256 格式无效");
            return;
        }
        cancelled = false;
        executor.execute(() -> downloadInternal(apkUrl, expectedSha256));
    }

    void cancel() {
        cancelled = true;
    }

    void continuePendingInstall() {
        if (pendingApk == null || !pendingApk.isFile()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.getPackageManager().canRequestPackageInstalls()) {
            File apk = pendingApk;
            pendingApk = null;
            openInstallerNow(apk);
        }
    }

    void shutdown() {
        cancelled = true;
        executor.shutdownNow();
    }

    private void downloadInternal(String apkUrl, String expectedSha256) {
        File dir = new File(activity.getCacheDir(), "updates");
        File part = new File(dir, "miaojizhang-update.apk.part");
        File apk = new File(dir, "miaojizhang-update.apk");
        HttpURLConnection connection = null;
        try {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建更新缓存目录");
            connection = open(apkUrl, 15000, 30000);
            long total = connection.getContentLengthLong();
            long downloaded = 0;
            int lastPercent = -2;
            long lastNotify = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled) throw new InterruptedIOException("cancelled");
                    output.write(buffer, 0, count);
                    downloaded += count;
                    int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : -1;
                    long now = System.currentTimeMillis();
                    if (percent != lastPercent && (now - lastNotify >= 180 || percent == 100)) {
                        lastPercent = percent;
                        lastNotify = now;
                        JSONObject progress = new JSONObject();
                        progress.put("downloaded", downloaded);
                        progress.put("total", total);
                        progress.put("percent", percent);
                        emit("progress", progress);
                    }
                }
                output.flush();
            }
            emit("verifying", new JSONObject());
            if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                    && !sha256(part).equalsIgnoreCase(expectedSha256)) {
                throw new SecurityException("安装包校验失败，请重新下载");
            }
            verifyPackageName(part);
            if (apk.exists() && !apk.delete()) throw new IOException("无法替换旧安装包");
            if (!part.renameTo(apk)) throw new IOException("无法保存安装包");
            emit("installing", new JSONObject());
            activity.runOnUiThread(() -> activity.getWindow().getDecorView().postDelayed(() -> openInstaller(apk), 500));
        } catch (InterruptedIOException e) {
            part.delete();
            emit("cancelled", new JSONObject());
        } catch (Exception e) {
            part.delete();
            emitError(safeMessage(e, "下载更新失败，请稍后重试"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void verifyPackageName(File apk) throws IOException {
        PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || info.applicationInfo == null || !BuildConfig.APPLICATION_ID.equals(info.applicationInfo.packageName)) {
            throw new IOException("安装包应用标识不匹配");
        }
    }

    private void openInstaller(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                emit("permission", new JSONObject());
            } catch (Exception e) {
                emitError("无法打开安装权限设置");
            }
            return;
        }
        openInstallerNow(apk);
    }

    private void openInstallerNow(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            emitError("无法打开系统安装程序");
        }
    }

    private HttpURLConnection open(String address, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "MiaoJiZhang/" + BuildConfig.VERSION_NAME);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("服务器返回 " + code);
        }
        return connection;
    }

    private static void validateUpdateJson(JSONObject data) throws Exception {
        data.getInt("versionCode");
        data.getString("versionName");
        data.getString("apkUrl");
    }

    private static boolean isHttps(String value) {
        return value != null && value.toLowerCase().startsWith("https://");
    }

    private static String readText(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private void emitError(String message) {
        if (message == null || message.length() == 0) return;
        JSONObject data = new JSONObject();
        try { data.put("message", message); } catch (Exception ignored) {}
        emit("error", data);
    }

    private void emit(String event, JSONObject data) {
        activity.runOnUiThread(() -> listener.onEvent(event, data));
    }

    private static String safeMessage(Exception e, String fallback) {
        String value = e.getMessage();
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}

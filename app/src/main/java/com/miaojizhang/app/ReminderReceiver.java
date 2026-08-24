package com.miaojizhang.app;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Bitmap;

import androidx.core.app.NotificationCompat;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            rescheduleIfNeeded(context);
            return;
        }

        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(context, 100, open, flags);

            Bitmap largeIcon = NotificationIconHelper.currentLargeIcon(context);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
                    .setSmallIcon(NotificationIconHelper.currentIconRes(context))
                    .setContentTitle("秒记账提醒")
                    .setContentText("今天记账了吗？打开秒记账记录一下吧。")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setColor(NotificationIconHelper.currentColor(context))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            if (largeIcon != null) builder.setLargeIcon(largeIcon);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(20260429, builder.build());
        }
        rescheduleIfNeeded(context);
    }

    private void rescheduleIfNeeded(Context context) {
        SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
        if (!sp.getBoolean("reminder_enabled", false)) return;
        String time = sp.getString("reminder_time", "21:00");
        if (time == null || !time.matches("^\\d{1,2}:\\d{2}$")) return;
        String[] parts = time.split(":");
        MainActivity.schedule(context.getApplicationContext(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}

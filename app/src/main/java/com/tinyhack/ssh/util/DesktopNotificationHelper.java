package com.tinyhack.ssh.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.tinyhack.ssh.MainActivity;
import com.tinyhack.ssh.R;

import java.util.concurrent.atomic.AtomicInteger;

public final class DesktopNotificationHelper {
    private static final String CHANNEL_ID = "ghostty_desktop_notifications";
    private static final String CHANNEL_NAME = "Desktop Notifications";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(2000);
    private static Context appContext;

    private DesktopNotificationHelper() {}

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        createChannel(appContext);
    }

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notifications sent by terminal programs (OSC 9 / OSC 777)");
            nm.createNotificationChannel(ch);
        }
    }

    public static void show(String title, String body) {
        Context ctx = appContext;
        if (ctx == null) {
            com.tinyhack.ssh.util.SafeLog.w("TinySSHDesktopNotify", "appContext null, abort");
            return;
        }
        createChannel(ctx);

        String t = title != null && !title.isEmpty() ? title : "Terminal";
        String b = body != null ? body : "";
        // Keep notification readable even if body is huge
        if (b.length() > 512) b = b.substring(0, 512) + "…";

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t)
            .setContentText(b)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(b))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        int id = NEXT_ID.getAndIncrement();
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(id, builder.build());
                com.tinyhack.ssh.util.SafeLog.i("TinySSHDesktopNotify", "notified id=" + id);
            } else {
                com.tinyhack.ssh.util.SafeLog.w("TinySSHDesktopNotify", "NotificationManager null");
            }
        } catch (Exception e) {
            com.tinyhack.ssh.util.SafeLog.w("TinySSHDesktopNotify", "notify failed", e);
        }
    }
}

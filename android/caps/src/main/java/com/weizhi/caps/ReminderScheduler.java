package com.weizhi.caps;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.weizhi.platform.LocalWorkspace;
import com.weizhi.platform.MiniJson;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ReminderScheduler {
    static final String CHANNEL = "weizhi_reminders";
    private static final Map<String, Spec> SPECS = new ConcurrentHashMap<>();

    static final class Spec {
        final String title;
        final String body;
        final long atMs;

        Spec(String title, String body, long atMs) {
            this.title = title;
            this.body = body;
            this.atMs = atMs;
        }
    }

    private ReminderScheduler() {
    }

    static String schedule(Context context, LocalWorkspace workspace, String title, String body, long atMs) {
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("bad argument: reminders.schedule: title");
        }
        if (atMs <= 0L) {
            throw new IllegalArgumentException("bad argument: reminders.schedule: atMs");
        }
        String id = UUID.randomUUID().toString();
        SPECS.put(id, new Spec(title, body == null ? "" : body, atMs));
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            alarms.set(AlarmManager.RTC_WAKEUP, atMs, pending(context, id, title, body));
        }
        workspace.note("reminders.schedule", id + " " + title);
        return "{\"id\":" + MiniJson.quote(id) + ",\"atMs\":" + atMs + "}";
    }

    static String cancel(Context context, LocalWorkspace workspace, String id) {
        Spec spec = SPECS.remove(id);
        if (spec == null) {
            return MiniJson.error("bad argument: reminders.cancel: unknown id");
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            alarms.cancel(pending(context, id, spec.title, spec.body));
        }
        workspace.note("reminders.cancel", id);
        return "{\"ok\":true}";
    }

    static String fire(Context context, LocalWorkspace workspace, String id) {
        Spec spec = SPECS.get(id);
        if (spec == null) {
            return MiniJson.error("bad argument: reminders.fire: unknown id");
        }
        boolean notified = show(context, spec.title, spec.body);
        workspace.note("reminders.fire", id);
        return "{\"ok\":true,\"notified\":" + notified + "}";
    }

    static boolean show(Context context, String title, String body) {
        ensureChannel(context);
        if (Build.VERSION.SDK_INT >= 33) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body == null ? "" : body)
                .setAutoCancel(true)
                .build();
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return false;
            }
            manager.notify((title + body).hashCode(), notification);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Weizhi reminders", NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent pending(Context context, String id, String title, String body) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION);
        intent.putExtra("id", id);
        intent.putExtra("title", title);
        intent.putExtra("body", body);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags);
    }
}

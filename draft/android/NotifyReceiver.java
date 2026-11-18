package com.ibtkar.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * التنبيهات (نسخة تجريبية):
 *  - كل يوم الساعة ٩ الصبح: "افتحوا السيستم وشوفوا عندكم إيه النهارده".
 *  - كل ربع ساعة تقريباً بيبص على "اللي اتعمل" على السيرفر، ولو الشخص التاني سجّل حاجة
 *    بيطلع إشعار ("بسنت سجّلت مصروف ٥٠٠ لعلي"). اسم صاحب الجهاز بيجي من التطبيق نفسه.
 * مفيش سيرفر خاص: الموبايل نفسه هو اللي بيسأل Firebase.
 */
public class NotifyReceiver extends BroadcastReceiver {

    static final String ACTION_MORNING = "com.ibtkar.app.MORNING";
    static final String ACTION_FEED = "com.ibtkar.app.FEED_CHECK";
    static final String PREFS = "ibtkar_notify";
    static final String CH_MORNING = "morning_alarm";
    static final String CH_FEED = "activity";
    static final String FEED_URL =
            "https://ibtkar-system-default-rtdb.firebaseio.com/ibtkar_draft_feed.json?orderBy=%22%24key%22&limitToLast=20";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_MORNING.equals(a)) {
            showMorning(ctx);
            scheduleMorning(ctx); // اللي بعده بكرة
        } else if (ACTION_FEED.equals(a)) {
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try { checkFeed(ctx); } catch (Exception ignored) { } finally { pr.finish(); }
            }).start();
        } else {
            schedule(ctx); // بعد ريستارت الموبايل أو تحديث التطبيق
        }
    }

    // ------------------------------------------------------------------ مواعيد

    static void schedule(Context ctx) {
        ensureChannels(ctx);
        scheduleMorning(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pending(ctx, ACTION_FEED, 2));
    }

    static void scheduleMorning(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 5_000L) c.add(Calendar.DAY_OF_YEAR, 1);
        // نافذة ٥ دقايق: مش محتاجة صلاحية "منبّه بالظبط" وبتيجي ٩:٠٠ – ٩:٠٥
        am.setWindow(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), 5 * 60_000L, pending(ctx, ACTION_MORNING, 1));
    }

    private static PendingIntent pending(Context ctx, String action, int code) {
        Intent i = new Intent(ctx, NotifyReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ إشعارات

    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.deleteNotificationChannel("morning"); // القناة القديمة (صوت إشعار عادي)
        NotificationChannel morning = new NotificationChannel(CH_MORNING, "منبّه ٩ الصبح",
                NotificationManager.IMPORTANCE_HIGH);
        // صوت منبّه مش صوت إشعار، وبيشتغل على صوت المنبّه في الموبايل
        android.net.Uri alarm = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
        if (alarm == null) alarm = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
        morning.setSound(alarm, new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        morning.enableVibration(true);
        morning.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        morning.setBypassDnd(true);
        nm.createNotificationChannel(morning);
        nm.createNotificationChannel(new NotificationChannel(CH_FEED, "اللي اتعمل في السيستم",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 10, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void post(Context ctx, int id, String channel, String title, List<String> lines) {
        ensureChannels(ctx);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(lines.isEmpty() ? "" : lines.get(0))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .setPriority(CH_MORNING.equals(channel) ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(CH_MORNING.equals(channel) ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_SOCIAL);
        if (lines.size() > 1) {
            NotificationCompat.InboxStyle st = new NotificationCompat.InboxStyle();
            for (String l : lines) st.addLine(l);
            b.setStyle(st);
        } else if (!lines.isEmpty()) {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(lines.get(0)));
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build());
        } catch (SecurityException ignored) {
            // المستخدم مسمحش بالإشعارات
        }
    }

    static void showMorning(Context ctx) {
        // الجمعة إجازة — مفيش منبّه
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) return;
        showMorningNow(ctx);
    }

    static void showMorningNow(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String user = p.getString("user", "");
        String extra = p.getString("morning", "");
        List<String> lines = new ArrayList<>();
        lines.add("افتح البرنامج وسجّل شغل النهارده: المشاريع الشغالة وفيها إيه");
        if (!extra.isEmpty()) Collections.addAll(lines, extra.split("\n"));
        post(ctx, 901, CH_MORNING, "☀️ صباح الخير" + (user.isEmpty() ? "" : " يا " + user), lines);
    }

    static void checkFeed(Context ctx) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String me = p.getString("user", "");
        String last = p.getString("lastKey", "");

        HttpURLConnection c = (HttpURLConnection) new URL(FEED_URL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (c.getResponseCode() != 200) return;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        String body = sb.toString().trim();
        if (body.isEmpty() || body.equals("null")) return;
        JSONObject all = new JSONObject(body);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        if (keys.isEmpty()) return;
        String newest = keys.get(keys.size() - 1);
        if (last.isEmpty()) { // أول مرة: منبعتش القديم كله
            p.edit().putString("lastKey", newest).apply();
            return;
        }
        List<String> lines = new ArrayList<>();
        String who = "";
        for (String k : keys) {
            if (k.compareTo(last) <= 0) continue;
            JSONObject e = all.optJSONObject(k);
            if (e == null) continue;
            String by = e.optString("by", "");
            if (!me.isEmpty() && me.equals(by)) continue;
            String text = e.optString("text", "");
            if (text.isEmpty()) continue;
            lines.add(text);
            if (!by.isEmpty()) who = by;
        }
        p.edit().putString("lastKey", newest).apply();
        if (lines.isEmpty()) return;
        String title = lines.size() == 1
                ? "🔔 " + (who.isEmpty() ? "حاجة جديدة في السيستم" : "جديد من " + who)
                : "🔔 " + lines.size() + " حاجات جديدة" + (who.isEmpty() ? "" : " من " + who);
        post(ctx, 902, CH_FEED, title, lines.size() > 6 ? lines.subList(lines.size() - 6, lines.size()) : lines);
    }
}

package com.ibtkar.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;
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
    static final String ACTION_SNOOZE = "com.ibtkar.app.MORNING_SNOOZE";
    static final String ACTION_BACKUP = "com.ibtkar.app.NIGHTLY_BACKUP";
    static final String DB = "https://ibtkar-system-default-rtdb.firebaseio.com/";
    static final String DATA_PATH = "ibtkar_draft_v1";       // عند النقل للأساسي: ibtkar_sync_v2
    static final String BACKUP_PATH = "ibtkar_draft_backups"; // عند النقل للأساسي: ibtkar_backups
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
        } else if (ACTION_SNOOZE.equals(a)) {
            showMorningNow(ctx);
        } else if (ACTION_BACKUP.equals(a)) {
            scheduleBackup(ctx); // الليلة الجاية
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try { nightlyBackup(ctx); } catch (Exception ignored) { } finally { pr.finish(); }
            }).start();
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
        scheduleBackup(ctx);
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
        setAlarm(ctx, c.getTimeInMillis(), pending(ctx, ACTION_MORNING, 1));
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("nextMorning", c.getTimeInMillis()).apply();
    }

    /** فكّرني بعد كام دقيقة (من شاشة المنبّه). */
    static void snooze(Context ctx, int minutes) {
        setAlarm(ctx, System.currentTimeMillis() + minutes * 60_000L, pending(ctx, ACTION_SNOOZE, 3));
    }

    /** منبّه بالظبط زي تطبيقات المنبّه (setAlarmClock) — ولو مفيش صلاحية، أقرب حاجة ليه. */
    static void setAlarm(Context ctx, long when, PendingIntent pi) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, openApp(ctx)), pi);
                return;
            }
        } catch (SecurityException ignored) { }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    static boolean canExact(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
    }

    static boolean canFullScreen(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
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

    private static PendingIntent openDayPlan(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).putExtra("launchAction", "dayplan")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 11, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent alarmScreen(Context ctx) {
        Intent i = new Intent(ctx, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return PendingIntent.getActivity(ctx, 12, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        ensureChannels(ctx);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_MORNING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("☀️ صباح الخير" + (user.isEmpty() ? "" : " يا " + user))
                .setContentText(lines.get(0))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(android.text.TextUtils.join("\n", lines)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openDayPlan(ctx))
                // الشاشة الكاملة بالرنّة — لو الموبايل مقفول بتفتح لوحدها، ولو مفتوح بتنزل من فوق
                .setFullScreenIntent(alarmScreen(ctx), true)
                .addAction(0, "افتح البرنامج", openDayPlan(ctx));
        try {
            NotificationManagerCompat.from(ctx).notify(901, b.build());
        } catch (SecurityException ignored) { }
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

    // ------------------------------------------------------------------ نسخة احتياطية كل ليلة

    /** ١٢:٠٥ بالليل كل يوم — من غير ما يطلع أي حاجة على الشاشة. */
    static void scheduleBackup(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 5);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 5_000L) c.add(Calendar.DAY_OF_YEAR, 1);
        PendingIntent pi = pending(ctx, ACTION_BACKUP, 4);
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
                return;
            }
        } catch (SecurityException ignored) { }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
    }

    static String http(String method, String url, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20_000);
        con.setReadTimeout(120_000);
        con.setRequestMethod(method);
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = con.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    static void nightlyBackup(Context ctx) throws Exception {
        String data = http("GET", DB + DATA_PATH + ".json", null);
        if (data == null || data.length() < 50 || "null".equals(data.trim())) return; // مفيش بيانات — ما نكتبش نسخة فاضية
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, -10); // اللي بيتعمل ١٢:٠٥ يتسمى باسم اليوم اللي خلص
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String day = f.format(c.getTime());
        // ١) ملف على الموبايل
        try { saveLocal(ctx, "ibtkar-backup-" + day + ".json", data); } catch (Exception ignored) { }
        // ٢) نسخة على السيرفر لو لسه ما اتعملتش النهارده
        try {
            String exists = http("GET", DB + BACKUP_PATH + "/" + day + "/at.json", null);
            if (exists == null || "null".equals(exists.trim())) {
                String at = JSONObject.quote(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new java.util.Date()));
                http("PUT", DB + BACKUP_PATH + "/" + day + ".json", "{\"at\":" + at + ",\"by\":\"nightly\",\"state\":" + data + "}");
            }
            Calendar old = Calendar.getInstance();
            old.add(Calendar.DAY_OF_YEAR, -14);
            String cut = f.format(old.getTime());
            JSONObject keys = new JSONObject(http("GET", DB + BACKUP_PATH + ".json?shallow=true", null));
            Iterator<String> it = keys.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.matches("\\d{4}-\\d{2}-\\d{2}") && k.compareTo(cut) < 0) http("DELETE", DB + BACKUP_PATH + "/" + k + ".json", null);
            }
        } catch (Exception ignored) { }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lastBackup", day + " " + new SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date())).apply();
    }

    /** Download/Ibtkar/Backups — ونحتفظ بآخر ٣٠ ملف. */
    static void saveLocal(Context ctx, String name, String data) throws Exception {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            String rel = Environment.DIRECTORY_DOWNLOADS + "/Ibtkar/Backups/";
            Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            List<String[]> mine = new ArrayList<>(); // [id, name]
            try (Cursor cur = ctx.getContentResolver().query(col,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{rel}, null)) {
                while (cur != null && cur.moveToNext()) mine.add(new String[]{cur.getString(0), cur.getString(1)});
            }
            for (String[] m : mine) {
                if (name.equals(m[1])) ctx.getContentResolver().delete(Uri.withAppendedPath(col, m[0]), null, null);
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            Uri uri = ctx.getContentResolver().insert(col, v);
            if (uri == null) throw new Exception("insert failed");
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("open failed");
                os.write(bytes);
            }
            List<String> names = new ArrayList<>();
            for (String[] m : mine) if (m[1] != null && m[1].startsWith("ibtkar-backup-") && !name.equals(m[1])) names.add(m[1]);
            names.add(name);
            Collections.sort(names);
            int extra = names.size() - 30;
            for (String[] m : mine) {
                if (extra <= 0) break;
                if (m[1] != null && names.indexOf(m[1]) >= 0 && names.indexOf(m[1]) < extra && !name.equals(m[1])) {
                    ctx.getContentResolver().delete(Uri.withAppendedPath(col, m[0]), null, null);
                }
            }
        } else {
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Ibtkar/Backups");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream os = new FileOutputStream(new File(dir, name))) { os.write(bytes); }
            File[] all = dir.listFiles();
            if (all != null && all.length > 30) {
                List<String> names = new ArrayList<>();
                for (File x : all) names.add(x.getName());
                Collections.sort(names);
                for (int k = 0; k < names.size() - 30; k++) //noinspection ResultOfMethodCallIgnored
                    new File(dir, names.get(k)).delete();
            }
        }
    }
}

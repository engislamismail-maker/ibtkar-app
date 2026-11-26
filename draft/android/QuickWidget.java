package com.ibtkar.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import org.json.JSONObject;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/**
 * ⚡ ويدجت "تسجيل سريع" على الشاشة الرئيسية: مصروف / قبض / حسابات بالصوت / ملاحظة بالصوت.
 * كل زرار بيفتح البرنامج — والبرنامج بيطلب الرقم السري أو البصمة الأول، وبعدها يفتح الفورم على طول.
 */
public class QuickWidget extends AppWidgetProvider {

    private static PendingIntent open(Context c, String action, int code) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setData(Uri.parse("ibtkar://quick/" + action));
        i.putExtra("launchAction", action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 7100 + code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_quick);
            v.setOnClickPendingIntent(R.id.w_expense, open(context, "q_expense", 1));
            v.setOnClickPendingIntent(R.id.w_income, open(context, "q_income", 2));
            v.setOnClickPendingIntent(R.id.w_voice, open(context, "q_voice", 3));
            v.setOnClickPendingIntent(R.id.w_note, open(context, "q_note", 4));
            v.setOnClickPendingIntent(R.id.w_info, open(context, "q_calendar", 5));
            fill(context, v);
            manager.updateAppWidget(id, v);
        }
    }

    static final String PREFS = "ibtkar_widget";

    /** البيانات اللي البرنامج بيبعتها (setWidgetData) + آخر حركة من الإشعارات في الخلفية */
    private static void fill(Context c, RemoteViews v) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject d = new JSONObject(p.getString("data", "{}"));
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            boolean fresh = today.equals(d.optString("day", ""));
            String work = fresh ? d.optString("work", "") : "";
            String due = fresh ? d.optString("due", "") : "";
            String last = d.optString("last", "");
            long lastAt = p.getLong("data_at", 0), bgAt = p.getLong("bg_at", 0);
            String bg = p.getString("bg_last", "");
            if (!bg.isEmpty() && bgAt > lastAt) last = bg;
            v.setTextViewText(R.id.w_work, work.isEmpty() ? (fresh ? "مفيش حد متسجل" : "افتح البرنامج عشان يتحدّث") : work);
            v.setTextViewText(R.id.w_last, last.isEmpty() ? "—" : last);
            v.setTextViewText(R.id.w_due, due.isEmpty() ? (fresh ? "مفيش ✅" : "—") : due);
            v.setTextColor(R.id.w_due, due.isEmpty() ? 0xFFF5F1EC : 0xFFF4A6BE);
            String at = d.optString("at", "");
            v.setTextViewText(R.id.w_at, at.isEmpty() ? "افتح البرنامج مرة عشان الأرقام تظهر" : "آخر تحديث " + at + (bgAt > lastAt ? " · وصلت حركة جديدة" : ""));
        } catch (Exception e) {
            v.setTextViewText(R.id.w_at, "افتح البرنامج مرة عشان الأرقام تظهر");
        }
    }

    /** يحدّث كل الويدجت اللي على الشاشة */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, QuickWidget.class));
            if (ids != null && ids.length > 0) new QuickWidget().onUpdate(c, m, ids);
        } catch (Exception ignored) { }
    }

    static void saveData(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("data", json).putLong("data_at", System.currentTimeMillis()).apply();
        refresh(c);
    }

    static void saveBackgroundLast(Context c, String text) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (text == null || text.isEmpty() || text.equals(p.getString("bg_last", ""))) return;
        p.edit().putString("bg_last", text).putLong("bg_at", System.currentTimeMillis()).apply();
        refresh(c);
    }
}

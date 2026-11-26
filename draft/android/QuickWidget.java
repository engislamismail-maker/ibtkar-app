package com.ibtkar.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
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
            manager.updateAppWidget(id, v);
        }
    }
}

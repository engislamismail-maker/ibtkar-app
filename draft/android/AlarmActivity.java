package com.ibtkar.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * منبّه ٩ الصبح (نسخة تجريبية): شاشة كاملة فوق قفل الشاشة، صوت منبّه شغال لحد ما حد يدوس:
 * "افتح البرنامج" (بيفتح على شغل النهارده) — "فكّرني بعد ١٠ دقايق" — "سكّت".
 * بيسكت لوحده بعد ٣ دقايق.
 */
public class AlarmActivity extends Activity {

    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences p = getSharedPreferences(NotifyReceiver.PREFS, MODE_PRIVATE);
        String user = p.getString("user", "");
        String extra = p.getString("morning", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#2E2A2C"));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(96));
        lp.bottomMargin = dp(18);
        root.addView(logo, lp);

        TextView time = text(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()), 64, "#D59663", true);
        root.addView(time);
        root.addView(text("☀️ صباح الخير" + (user.isEmpty() ? "" : " يا " + user), 26, "#FFFFFF", true));
        TextView sub = text("افتح البرنامج وسجّل شغل النهارده:\nالمشاريع الشغالة وفيها إيه", 17, "#E7D9DD", false);
        sub.setPadding(0, dp(10), 0, dp(6));
        root.addView(sub);
        if (!extra.isEmpty()) {
            TextView ex = text(extra, 14, "#BFB3B6", false);
            ex.setPadding(0, 0, 0, dp(10));
            root.addView(ex);
        }

        root.addView(button("افتح البرنامج", "#C2547A", v -> {
            stopRinging();
            Intent i = new Intent(this, MainActivity.class)
                    .putExtra("launchAction", "dayplan")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        }), buttonParams(dp(24)));
        root.addView(button("فكّرني بعد ١٠ دقايق", "#4A4346", v -> {
            stopRinging();
            NotifyReceiver.snooze(this, 10);
            finish();
        }), buttonParams(dp(10)));
        root.addView(button("سكّت", "#4A4346", v -> {
            stopRinging();
            finish();
        }), buttonParams(dp(10)));

        setContentView(root);
        startRinging();
        handler.postDelayed(this::stopRinging, 3 * 60_000L);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, String bg, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bg));
        d.setCornerRadius(dp(14));
        b.setBackground(d);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private void startRinging() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            player = null;
        }
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = {0, 700, 500, 700, 500};
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                else vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) { }
    }

    private void stopRinging() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (vibrator != null) { try { vibrator.cancel(); } catch (Exception ignored) { } vibrator = null; }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(901);
    }

    @Override
    protected void onDestroy() {
        stopRinging();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        stopRinging();
        super.onBackPressed();
    }
}

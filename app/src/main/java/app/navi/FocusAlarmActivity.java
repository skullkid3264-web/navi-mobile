package app.navi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 伴走の時間・宿題の期限を知らせる、目覚ましの画面（鍵がかかっていても前に出る）。
 * 伴走：できた／まだ（＋10分）／次／伴走をやめる をPCへ返す。
 * 宿題：いま書く（フォームを開く）／10分後にもう一度（1日2回まで）。
 * 音は60秒で止まる（画面は残る）。電話の着信は、いつもどおり上に出る。
 */
public class FocusAlarmActivity extends Activity {

    private MediaPlayer player;
    private Vibrator vib;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build(getIntent());
        startSound();
        main.postDelayed(this::stopSound, 60_000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
        startSound();
    }

    private void build(Intent in) {
        final String kind = in == null ? "drive" : String.valueOf(in.getStringExtra("kind"));
        final String form = in == null ? "" : in.getStringExtra("form");
        final String cls = in == null ? "" : in.getStringExtra("cls");
        String title = in == null ? "" : in.getStringExtra("title");
        String body = in == null ? "" : in.getStringExtra("body");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(48), dp(28), dp(48));
        box.setBackgroundColor(Color.parseColor("#141826"));

        TextView t = new TextView(this);
        t.setText(title == null ? "" : title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText(body == null ? "" : body);
        s.setTextColor(Color.parseColor("#C9D1E8"));
        s.setTextSize(19);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(16), 0, dp(28));
        box.addView(s);

        if ("homework".equals(kind)) {
            box.addView(button("📝 いま書く", "#3B82F6", v -> {
                stopSound();
                Focus.cancelAlarmNote(this);
                Intent open = new Intent(this, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                open.putExtra("form", form == null ? "" : form);
                open.putExtra("cls", cls == null ? "" : cls);
                startActivity(open);
                finish();
            }));
            final String id = (form == null ? "" : form) + (cls == null || cls.isEmpty() ? "" : ":" + cls);
            if (Focus.canSnooze(this, id)) {
                box.addView(button("10分後にもう一度", "#374151", v -> {
                    Focus.countSnooze(this, id);
                    Focus.snooze(this, id, 10);
                    stopSound();
                    finish();
                }));
            } else {
                TextView n = new TextView(this);
                n.setText("「あとで」は今日はもう使いました。いま書こう。");
                n.setTextColor(Color.parseColor("#FCA5A5"));
                n.setGravity(Gravity.CENTER);
                n.setPadding(0, dp(12), 0, 0);
                box.addView(n);
            }
        } else {
            box.addView(button("✅ できた", "#16A34A", v -> send("できた")));
            box.addView(button("🔁 まだ（あと10分）", "#3B82F6", v -> send("まだ")));
            box.addView(button("➡ 次にする", "#6B7280", v -> send("次")));
            box.addView(button("伴走をやめる", "#374151", v -> send("伴走をやめる")));
        }
        setContentView(box);
    }

    private void send(final String text) {
        stopSound();
        final Context app = getApplicationContext();
        new Thread(() -> Focus.answer(app, text)).start();
        finish();
    }

    private Button button(String label, String color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(19);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void startSound() {
        stopSound();
        if (!Focus.alarmOn(this)) return;
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, u);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            player = null;
        }
        try {
            vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) {
                long[] p = {0, 700, 500, 700, 500};
                if (Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(VibrationEffect.createWaveform(p, 0));
                } else {
                    vib.vibrate(p, 0);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopSound() {
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Exception ignored) {
        }
        player = null;
        try {
            if (vib != null) vib.cancel();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopSound();
        super.onDestroy();
    }
}

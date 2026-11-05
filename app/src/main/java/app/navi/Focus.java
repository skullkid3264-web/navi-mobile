package app.navi;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * 伴走・宿題を、スマホでも「目覚ましのように」知らせる。
 * PCのN.A.V.I.が GAS に置いた「いまの伴走・宿題のようす」（focusGet）を取りに来て、
 *  ・伴走の時間が来たら、全画面＋音＋振動で知らせる（できた／まだ／次 をその場で返せる）
 *  ・宿題の期限が過ぎていたら、15分おきに全画面で知らせる（フォームをすぐ開ける）
 *  ・そのあいだは、決めたアプリを開くとNAVIの画面がかぶさる（FocusGuard）
 * どれも設定でいつでも止められる。電話・設定・NAVI自身は止めない。
 */
final class Focus {

    static final String CH_ALARM = "navi_focus_alarm";
    static final String CH_ONGOING = "navi_focus_now";
    static final int NOTE_ONGOING = 770001;
    static final int NOTE_ALARM = 770002;
    private static final String PREF = "navi_focus";
    private static final int CODE_TICK = 7001;
    private static final long MIN = 60_000L;

    /** 止めないアプリ（電話・設定・NAVI自身・ホーム画面など） */
    private static final String[] EXEMPT = {
            "app.navi", "com.android.settings", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.android.incallui", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.nothing.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.sec.android.app.launcher"
    };

    private Focus() {
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 設定

    static boolean alarmOn(Context c) {
        return sp(c).getBoolean("alarm", true);
    }

    static boolean blockOn(Context c) {
        return sp(c).getBoolean("block", true);
    }

    static void setOptions(Context c, boolean alarm, boolean block) {
        sp(c).edit().putBoolean("alarm", alarm).putBoolean("block", block).apply();
    }

    static Set<String> blocked(Context c) {
        Set<String> out = new HashSet<>();
        for (String s : sp(c).getString("blocked", "").split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    static void setBlocked(Context c, String csv) {
        sp(c).edit().putString("blocked", csv == null ? "" : csv).apply();
    }

    static boolean exempt(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        for (String e : EXEMPT) {
            if (e.equals(pkg)) return true;
        }
        return pkg.contains("launcher") || pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("emergency");
    }

    // ---------------------------------------------------------------- いまのようす

    static JSONObject state(Context c) {
        try {
            return new JSONObject(sp(c).getString("state", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 伴走中なら、その内容（なければ null） */
    static JSONObject drive(Context c) {
        JSONObject d = state(c).optJSONObject("drive");
        return (d != null && d.optLong("endAt", 0) > 0) ? d : null;
    }

    /** 期限が過ぎていて、まだ出していない宿題（いちばん早いもの。なければ null） */
    static JSONObject overdue(Context c, long now) {
        JSONArray hw = state(c).optJSONArray("homework");
        if (hw == null) return null;
        JSONObject best = null;
        for (int i = 0; i < hw.length(); i++) {
            JSONObject h = hw.optJSONObject(i);
            if (h == null || h.optBoolean("done")) continue;
            long due = h.optLong("dueAt", 0);
            if (due > 0 && due <= now && (best == null || due < best.optLong("dueAt"))) best = h;
        }
        return best;
    }

    private static long nextFutureDue(Context c, long now) {
        JSONArray hw = state(c).optJSONArray("homework");
        long best = 0;
        if (hw == null) return 0;
        for (int i = 0; i < hw.length(); i++) {
            JSONObject h = hw.optJSONObject(i);
            if (h == null || h.optBoolean("done")) continue;
            long due = h.optLong("dueAt", 0);
            if (due > now && (best == 0 || due < best)) best = due;
        }
        return best;
    }

    /** 伴走中か、出していない宿題があるか（アプリにかぶせる・常駐の知らせを出す条件） */
    static boolean active(Context c) {
        return drive(c) != null || overdue(c, System.currentTimeMillis()) != null;
    }

    private static boolean quietNow() {
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 6;
    }

    // ---------------------------------------------------------------- 取りに行く（裏のスレッドで呼ぶ）

    /** GASから最新のようすを取って、知らせ・予約を整える。つながらなければ、手元のようすで整える */
    static void sync(Context c) {
        if (Store.isPaired(c)) {
            try {
                JSONObject res = Api.call(Store.url(c), Store.token(c), "focusGet", new JSONObject());
                if (res.optBoolean("ok")) {
                    JSONObject r = res.optJSONObject("result");
                    if (r != null) sp(c).edit().putString("state", r.toString()).apply();
                }
            } catch (Exception ignored) {
            }
        }
        evaluate(c);
    }

    /** 手元のようすから、常駐の知らせ・目覚まし・次の確認の時刻を決める */
    static void evaluate(Context c) {
        long now = System.currentTimeMillis();
        JSONObject d = drive(c);
        JSONObject hw = overdue(c, now);
        updateOngoing(c, d, hw);

        if (alarmOn(c) && !quietNow()) {
            if (d != null && now >= d.optLong("endAt")) {
                String key = "drive:" + d.optLong("endAt");
                if (!sp(c).getBoolean("rang:" + key, false) && now - d.optLong("endAt") < 30 * MIN) {
                    sp(c).edit().putBoolean("rang:" + key, true).apply();
                    int nag = d.optInt("nag", 0);
                    String body = nag <= 1 ? "『" + d.optString("task") + "』は終わった？" : "返事がないよ。『" + d.optString("task") + "』、手は動いてる？";
                    ring(c, "drive", "⏱ 伴走の時間です", body, "", "");
                }
            } else if (hw != null) {
                String key = "hw:" + hw.optString("id");
                long last = sp(c).getLong("last:" + key, 0);
                long snooze = sp(c).getLong("snooze:" + key, 0);
                if (now - last >= 15 * MIN && now >= snooze) {
                    sp(c).edit().putLong("last:" + key, now).apply();
                    ring(c, "homework", "📝 宿題：" + hw.optString("title"), "期限が過ぎています。いま書こう。", hw.optString("form"), hw.optString("cls"));
                }
            }
        }
        scheduleNext(c, now, d, hw);
    }

    private static void scheduleNext(Context c, long now, JSONObject d, JSONObject hw) {
        long next = 0;
        if (d != null) {
            long end = d.optLong("endAt");
            next = end > now ? end : now + 2 * MIN;           // 伴走中は、時間が来るまで／来たら2分おきに確かめる
        }
        if (hw != null) next = next == 0 ? now + 5 * MIN : Math.min(next, now + 5 * MIN);
        long fut = nextFutureDue(c, now);
        if (fut > 0) next = next == 0 ? fut : Math.min(next, fut);
        if (d == null && hw == null && fut == 0 && state(c).optBoolean("mentor")) next = now + 60 * MIN;   // 指導中は、1時間おきに新しい宿題を見に行く
        if (next == 0) {
            cancelTick(c);
            return;
        }
        scheduleTick(c, Math.max(next, now + 30_000L));
    }

    static void scheduleTick(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickIntent(c, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = tickIntent(c, PendingIntent.FLAG_NO_CREATE);
        if (am != null && pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    private static PendingIntent tickIntent(Context c, int flags) {
        Intent i = new Intent(c, FocusReceiver.class);
        i.setAction(FocusReceiver.TICK);
        return PendingIntent.getBroadcast(c, CODE_TICK, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------------------------------------------------------------- 知らせ

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel a = new NotificationChannel(CH_ALARM, "伴走・宿題の目覚まし", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("伴走の時間・宿題の期限を、目覚ましのように知らせます");
        a.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        a.enableVibration(true);
        a.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
        a.setBypassDnd(true);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
        NotificationChannel o = new NotificationChannel(CH_ONGOING, "いまやること（伴走中・宿題）", NotificationManager.IMPORTANCE_LOW);
        o.setDescription("伴走中・宿題が残っている間、いまやることを出しておきます");
        nm.createNotificationChannel(o);
    }

    /** 全画面の目覚まし。画面が消えていれば全画面で、使っている最中なら上に大きく出す */
    static void ring(Context c, String kind, String title, String body, String form, String cls) {
        if (!Notices.canNotify(c)) return;
        ensureChannels(c);
        Intent full = new Intent(c, FocusAlarmActivity.class);
        full.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        full.putExtra("kind", kind);
        full.putExtra("title", title);
        full.putExtra("body", body);
        full.putExtra("form", form);
        full.putExtra("cls", cls);
        PendingIntent pi = PendingIntent.getActivity(c, NOTE_ALARM, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_ALARM)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(c).notify(NOTE_ALARM, b.build());
        } catch (SecurityException ignored) {
        }
        try {
            c.startActivity(full);             // かぶせる許可（アクセシビリティ）がオンなら、そのまま前に出る
        } catch (Exception ignored) {
        }
    }

    static void cancelAlarmNote(Context c) {
        NotificationManagerCompat.from(c).cancel(NOTE_ALARM);
    }

    /** 伴走中・宿題が残っている間、消せない知らせを出しておく（そこから できた／まだ／次 を返せる） */
    private static void updateOngoing(Context c, JSONObject d, JSONObject hw) {
        if (d == null && hw == null) {
            NotificationManagerCompat.from(c).cancel(NOTE_ONGOING);
            return;
        }
        if (!Notices.canNotify(c)) return;
        ensureChannels(c);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String title;
        String body;
        if (d != null) {
            title = "🎯 いまは、これだけ";
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN);
            body = d.optString("task") + "（" + f.format(new java.util.Date(d.optLong("endAt"))) + "まで）";
        } else {
            title = "📝 宿題がまだです";
            body = hw.optString("title") + "　タップしてフォームを開く";
            open.putExtra("form", hw.optString("form"));
            open.putExtra("cls", hw.optString("cls"));
        }
        PendingIntent pi = PendingIntent.getActivity(c, NOTE_ONGOING, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (d != null) {
            b.addAction(0, "できた", answerIntent(c, "できた", 1));
            b.addAction(0, "まだ", answerIntent(c, "まだ", 2));
            b.addAction(0, "次", answerIntent(c, "次", 3));
        }
        try {
            NotificationManagerCompat.from(c).notify(NOTE_ONGOING, b.build());
        } catch (SecurityException ignored) {
        }
    }

    static PendingIntent answerIntent(Context c, String text, int code) {
        Intent i = new Intent(c, FocusReceiver.class);
        i.setAction(FocusReceiver.ANSWER + "." + code);
        i.putExtra("text", text);
        return PendingIntent.getBroadcast(c, 7100 + code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------------------------------------------------------------- 返事（裏のスレッドで呼ぶ）

    /** 「できた」「まだ」「次」「伴走をやめる」を、PCのN.A.V.I.に送る（PCが受け取って、伴走を進める） */
    static boolean answer(Context c, String text) {
        if (!Store.isPaired(c)) return false;
        boolean ok = false;
        try {
            JSONObject p = new JSONObject();
            p.put("kind", "send");
            p.put("text", text);
            JSONObject res = Api.call(Store.url(c), Store.token(c), "mobileSubmit", p);
            ok = res.optBoolean("ok");
        } catch (Exception ignored) {
        }
        cancelAlarmNote(c);
        try {
            Thread.sleep(4000);                 // PCが受け取って、新しいようすを置くのを少し待つ（受け取りは10秒以内に終える）
        } catch (InterruptedException ignored) {
        }
        sync(c);
        return ok;
    }

    static void snooze(Context c, String id, long minutes) {
        sp(c).edit().putLong("snooze:hw:" + id, System.currentTimeMillis() + minutes * MIN).apply();
        cancelAlarmNote(c);
    }

    /** 同じ宿題の「あとで」は、1日2回まで */
    static boolean canSnooze(Context c, String id) {
        String k = "snoozeN:" + id + ":" + Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        return sp(c).getInt(k, 0) < 2;
    }

    static void countSnooze(Context c, String id) {
        String k = "snoozeN:" + id + ":" + Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        sp(c).edit().putInt(k, sp(c).getInt(k, 0) + 1).apply();
    }

    // ---------------------------------------------------------------- アプリにかぶせる

    static boolean shouldBlock(Context c, String pkg) {
        if (!blockOn(c) || exempt(pkg) || !blocked(c).contains(pkg) || !active(c)) return false;
        return System.currentTimeMillis() >= sp(c).getLong("allow:" + pkg, 0);
    }

    static void allowFor(Context c, String pkg, long minutes) {
        String k = "allowN:" + Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        sp(c).edit().putLong("allow:" + pkg, System.currentTimeMillis() + minutes * MIN).putInt(k, sp(c).getInt(k, 0) + 1).apply();
    }

    static int allowCountToday(Context c) {
        return sp(c).getInt("allowN:" + Calendar.getInstance().get(Calendar.DAY_OF_YEAR), 0);
    }

    static int countBlock(Context c) {
        String k = "blockN:" + Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        int n = sp(c).getInt(k, 0) + 1;
        sp(c).edit().putInt(k, n).apply();
        return n;
    }

    /** 設定画面に出す、いまの状態 */
    static String info(Context c, boolean a11y, boolean fullScreen) {
        try {
            JSONObject o = new JSONObject();
            o.put("alarm", alarmOn(c));
            o.put("block", blockOn(c));
            o.put("blocked", new JSONArray(blocked(c)));
            o.put("a11y", a11y);
            o.put("fullScreen", fullScreen);
            o.put("state", state(c));
            o.put("blockedToday", sp(c).getInt("blockN:" + Calendar.getInstance().get(Calendar.DAY_OF_YEAR), 0));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}

package app.navi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 予定の前の知らせを、スマホ自身で鳴らす（PCにもネットにもつながらなくても出る）。
 * 画面（JavaScript）が、鳴らす時刻の一覧 [{key, at(ms), title, body}] を渡す。ここでは、その時刻にアラームを仕掛けるだけ。
 */
final class Reminders {

    static final String CHANNEL = "navi_remind";
    private static final String PREF = "navi_remind";
    private static final int MAX = 60;

    private Reminders() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static PendingIntent pending(Context c, int code, Intent i, int flags) {
        return PendingIntent.getBroadcast(c, code, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Intent intentFor(Context c, int code) {
        Intent i = new Intent(c, RemindReceiver.class);
        i.setAction("app.navi.REMIND." + code);
        return i;
    }

    /** 前回の予約をすべて消して、新しい一覧で仕掛け直す。戻り値：仕掛けた数 */
    static int set(Context c, String json) {
        cancelAll(c);
        int n = 0;
        try {
            JSONArray arr = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return 0;
            long now = System.currentTimeMillis();
            StringBuilder codes = new StringBuilder();
            for (int k = 0; k < arr.length() && n < MAX; k++) {
                JSONObject o = arr.getJSONObject(k);
                long at = o.optLong("at", 0);
                if (at <= now + 5000) continue;
                int code = 1000 + n;
                Intent i = intentFor(c, code);
                i.putExtra("title", o.optString("title", "予定"));
                i.putExtra("body", o.optString("body", ""));
                i.putExtra("id", code);
                PendingIntent pi = pending(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT);
                schedule(am, at, pi);
                codes.append(code).append(',');
                n++;
            }
            sp(c).edit().putString("codes", codes.toString()).putString("json", json).apply();
        } catch (Exception ignored) {
        }
        return n;
    }

    private static void schedule(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);     // 時刻が数分ずれることがある
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        String codes = sp(c).getString("codes", "");
        if (am != null) {
            for (String s : codes.split(",")) {
                if (s.isEmpty()) continue;
                try {
                    int code = Integer.parseInt(s);
                    PendingIntent pi = pending(c, code, intentFor(c, code), PendingIntent.FLAG_NO_CREATE);
                    if (pi != null) {
                        am.cancel(pi);
                        pi.cancel();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        sp(c).edit().remove("codes").apply();
    }

    /** 端末の再起動・アプリの更新のあとに、保存してある一覧で仕掛け直す */
    static void restore(Context c) {
        String json = sp(c).getString("json", "");
        if (!json.isEmpty()) set(c, json);
    }
}

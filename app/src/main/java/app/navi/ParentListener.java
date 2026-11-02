package app.navi;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 保護者からのメッセージ通知を読み取り、同じWi-FiのPCへ直接送る（GAS＝Googleのサーバーには通さない）。
 * ・読むのは、設定で選んだアプリの通知だけ（既定はLINE）。名前のしぼり込みも使える
 * ・同じWi-Fiで送れなかった分は、この端末の中に残し（最大30件・2日）、つながったときに送る
 * ・端末の「通知へのアクセス」の許可を、自分で入れたときだけ動く
 */
public class ParentListener extends NotificationListenerService {

    private static final String PREF = "navi_parent";
    private static final int MAX_PENDING = 30;
    private static final long KEEP_MS = 2L * 24 * 3600 * 1000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------- 設定 ----------

    /** {"on":false,"pkgs":["jp.naver.line.android"],"words":[]} */
    static JSONObject config(Context c) {
        try {
            String s = sp(c).getString("config", "");
            if (!s.isEmpty()) return new JSONObject(s);
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject().put("on", false).put("pkgs", new JSONArray().put("jp.naver.line.android")).put("words", new JSONArray());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void setConfig(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            sp(c).edit().putString("config", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void setLan(Context c, String url) {
        sp(c).edit().putString("lan", url == null ? "" : url).apply();
    }

    static boolean accessEnabled(Context c) {
        return androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.getPackageName());
    }

    static int pendingCount(Context c) {
        synchronized (LOCK) {
            try {
                return new JSONArray(sp(c).getString("pending", "[]")).length();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    // ---------- 通知を受け取る ----------

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            final Context c = getApplicationContext();
            JSONObject cfg = config(c);
            if (sbn == null) return;
            String pkg = sbn.getPackageName();
            rememberApp(c, pkg);                    // 「どのアプリの通知か」を選べるように、アプリ名だけ覚える（本文は覚えない）
            if (!cfg.optBoolean("on", false)) return;
            if (!contains(cfg.optJSONArray("pkgs"), pkg)) return;
            Notification n = sbn.getNotification();
            if (n == null) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

            Bundle ex = n.extras;
            if (ex == null) return;
            CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            CharSequence txt = big != null ? big : ex.getCharSequence(Notification.EXTRA_TEXT);
            String t = title == null ? "" : title.toString();
            String body = txt == null ? "" : txt.toString();
            if (body.trim().isEmpty()) return;

            JSONArray words = cfg.optJSONArray("words");
            if (words != null && words.length() > 0) {
                boolean hit = false;
                for (int i = 0; i < words.length(); i++) {
                    String w = words.optString(i, "");
                    if (!w.isEmpty() && (t.contains(w) || body.contains(w))) hit = true;
                }
                if (!hit) return;
            }

            final JSONObject item = new JSONObject()
                    .put("app", appLabel(c, pkg))
                    .put("sender", t)
                    .put("text", body)
                    .put("sig", pkg + "|" + (t + "\n" + body).hashCode())
                    .put("at", System.currentTimeMillis());
            IO.execute(new Runnable() {
                @Override
                public void run() {
                    handle(c, item);
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 最近通知を出したアプリ（名前だけ・最大25件）。設定で、読み取る対象を選ぶために使う */
    private static void rememberApp(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName()) || pkg.equals("android") || pkg.equals("com.android.systemui")) return;
        synchronized (LOCK) {
            try {
                JSONArray old = new JSONArray(sp(c).getString("seen_apps", "[]"));
                JSONArray out = new JSONArray();
                out.put(new JSONObject().put("pkg", pkg).put("label", appLabel(c, pkg)).put("at", System.currentTimeMillis()));
                for (int i = 0; i < old.length() && out.length() < 25; i++) {
                    JSONObject o = old.getJSONObject(i);
                    if (!pkg.equals(o.optString("pkg"))) out.put(o);
                }
                sp(c).edit().putString("seen_apps", out.toString()).apply();
            } catch (Exception ignored) {
            }
        }
    }

    static String seenApps(Context c) {
        synchronized (LOCK) {
            return sp(c).getString("seen_apps", "[]");
        }
    }

    private static boolean contains(JSONArray a, String s) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) {
            if (s.equals(a.optString(i))) return true;
        }
        return false;
    }

    private static String appLabel(Context c, String pkg) {
        if ("jp.naver.line.android".equals(pkg)) return "LINE";
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------- 送る／溜める ----------

    private static final long DUP_MS = 5 * 60 * 1000;

    private static void handle(Context c, JSONObject item) {
        synchronized (LOCK) {
            try {
                JSONArray seen = new JSONArray(sp(c).getString("seen", "[]"));
                String sig = item.optString("sig");
                long nowMs = System.currentTimeMillis();
                for (int i = 0; i < seen.length(); i++) {
                    // 「指紋#時刻」。同じ内容の通知が5分以内にもう一度来たら（通知の更新・再表示）、二度送らない
                    String e = seen.optString(i);
                    int h = e.lastIndexOf('#');
                    if (h > 0 && sig.equals(e.substring(0, h))) {
                        long at = 0;
                        try { at = Long.parseLong(e.substring(h + 1)); } catch (Exception ignored) { }
                        if (nowMs - at < DUP_MS) return;
                    }
                }
                seen.put(sig + "#" + nowMs);
                JSONArray keep = new JSONArray();
                for (int i = Math.max(0, seen.length() - 60); i < seen.length(); i++) keep.put(seen.optString(i));
                sp(c).edit().putString("seen", keep.toString()).apply();
            } catch (Exception ignored) {
            }
            if (!send(c, item)) addPending(c, item);
        }
    }

    private static boolean send(Context c, JSONObject item) {
        String lan = sp(c).getString("lan", "");
        if (lan.isEmpty()) return false;
        try {
            JSONObject payload = new JSONObject().put("app", item.optString("app")).put("sender", item.optString("sender")).put("text", item.optString("text"));
            JSONObject r = Api.callLan(lan, Store.token(c), "parent", payload.toString());
            return !r.has("error");
        } catch (Exception e) {
            return false;
        }
    }

    private static void addPending(Context c, JSONObject item) {
        try {
            JSONArray old = new JSONArray(sp(c).getString("pending", "[]"));
            old.put(item);
            JSONArray out = new JSONArray();
            long now = System.currentTimeMillis();
            int from = Math.max(0, old.length() - MAX_PENDING);
            for (int i = from; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (now - o.optLong("at", now) <= KEEP_MS) out.put(o);
            }
            sp(c).edit().putString("pending", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 同じWi-Fiでつながったときに、溜めておいた分を送る（アプリの画面から呼ぶ）。戻り値：まだ残っている数 */
    static void flush(final Context c) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    try {
                        JSONArray old = new JSONArray(sp(c).getString("pending", "[]"));
                        JSONArray left = new JSONArray();
                        long now = System.currentTimeMillis();
                        boolean down = false;
                        for (int i = 0; i < old.length(); i++) {
                            JSONObject o = old.getJSONObject(i);
                            if (now - o.optLong("at", now) > KEEP_MS) continue;
                            if (!down && send(c, o)) continue;
                            down = true;
                            left.put(o);
                        }
                        sp(c).edit().putString("pending", left.toString()).apply();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sp(getApplicationContext()).edit().putBoolean("connected", true).putLong("connectedAt", System.currentTimeMillis()).apply();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sp(getApplicationContext()).edit().putBoolean("connected", false).apply();
    }

    static boolean connected(Context c) {
        return sp(c).getBoolean("connected", false);
    }

    /** 許可は入っているのに、アプリの更新などで接続が切れているとき、つなぎ直す */
    static void rebindIfNeeded(Context c) {
        try {
            if (accessEnabled(c) && !connected(c)) {
                NotificationListenerService.requestRebind(new ComponentName(c, ParentListener.class));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 端末に入っているアプリ（ホーム画面に出るもの）の一覧 [{pkg,label}]。名前順 */
    static String installedApps(Context c) {
        JSONArray out = new JSONArray();
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> list = c.getPackageManager().queryIntentActivities(i, 0);
            java.util.TreeMap<String, String> m = new java.util.TreeMap<>();
            for (android.content.pm.ResolveInfo r : list) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName())) continue;
                m.put(String.valueOf(r.loadLabel(c.getPackageManager())) + "\u0000" + pkg, pkg);
            }
            for (java.util.Map.Entry<String, String> e : m.entrySet()) {
                out.put(new JSONObject().put("pkg", e.getValue()).put("label", e.getKey().split("\u0000")[0]));
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }
}

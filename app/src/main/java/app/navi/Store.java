package app.navi;

import android.content.Context;
import android.content.SharedPreferences;

/** 登録したGASのアドレスと合言葉、通知の既読位置を保存する */
final class Store {

    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("navi", Context.MODE_PRIVATE);
    }

    static String url(Context c) {
        return sp(c).getString("url", "");
    }

    static String token(Context c) {
        return sp(c).getString("token", "");
    }

    static boolean isPaired(Context c) {
        return !url(c).isEmpty() && !token(c).isEmpty();
    }

    static void setPair(Context c, String url, String token) {
        sp(c).edit().putString("url", url.trim()).putString("token", token.trim()).remove("lastNotice").apply();
    }

    static void clearPair(Context c) {
        sp(c).edit().clear().apply();
    }

    /** 通知で知らせ済みの、いちばん新しいお知らせ番号（-1 = まだ一度も確認していない） */
    static long lastNotice(Context c) {
        return sp(c).getLong("lastNotice", -1);
    }

    static void setLastNotice(Context c, long id) {
        sp(c).edit().putLong("lastNotice", id).apply();
    }

    /** 「ナビ」の呼びかけ待ちをオンにしているか */
    static boolean wake(Context c) {
        return sp(c).getBoolean("wake", false);
    }

    static void setWake(Context c, boolean on) {
        sp(c).edit().putBoolean("wake", on).apply();
    }
}

package app.navi;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Nothing Phone (2a) の背面ライト（Glyph）の演出。アプリが画面に出ているあいだだけ動く（SDKの決まり）。
 * 対応していない端末・古いAndroidでは、何もしない（失敗しても本体の動きには影響させない）。
 */
final class GlyphHelper {

    private static Impl impl;
    private static String reason = "まだ試していません";

    private GlyphHelper() {
    }

    static void start(Context c) {
        if (impl != null) return;
        if (Build.VERSION.SDK_INT < 34) {
            reason = "Androidが古いため使えません（Android 14以上が必要。この端末は " + Build.VERSION.SDK_INT + "）";
            return;
        }
        try {
            Impl i = new Impl(c.getApplicationContext());
            if (i.supported()) {
                impl = i;
                reason = "サービスに接続しています…";
                i.init();
            } else {
                reason = "Nothing Phone (2a) として認識されませんでした（機種：" + Build.MODEL + "）";
            }
        } catch (Throwable e) {
            impl = null;
            reason = "起動に失敗：" + e;
        }
    }

    static void stop() {
        if (impl == null) return;
        try {
            impl.release();
        } catch (Throwable ignored) {
        }
        impl = null;
    }

    /** kind: ok／err／think／parent／queued／hello／remind／listen／off */
    static void cue(String kind) {
        if (impl == null) return;
        try {
            impl.cue(kind);
        } catch (Throwable ignored) {
        }
    }

    /** なぜ光る／光らないかを、画面に出す文章で返す */
    static String status() {
        if (impl != null && impl.ready) return "つながっています（Nothing Phone (2a)）";
        if (impl != null) return impl.why.isEmpty() ? reason : impl.why;
        return reason;
    }

    static boolean active() {
        return impl != null && impl.ready;
    }

    /** SDKのクラスは、この中でだけ使う（古い端末で読み込まれないように分ける） */
    private static final class Impl {

        private final Context ctx;
        private final Handler h = new Handler(Looper.getMainLooper());
        private com.nothing.ketchum.GlyphManager gm;
        volatile boolean ready;
        volatile String why = "";

        Impl(Context c) {
            ctx = c;
        }

        boolean supported() {
            return com.nothing.ketchum.Common.is23111();
        }

        void init() {
            gm = com.nothing.ketchum.GlyphManager.getInstance(ctx);
            gm.init(new com.nothing.ketchum.GlyphManager.Callback() {
                @Override
                public void onServiceConnected(ComponentName name) {
                    try {
                        gm.register(com.nothing.ketchum.Glyph.DEVICE_23111);
                        gm.openSession();
                        ready = true;
                        why = "";
                    } catch (Throwable e) {
                        ready = false;
                        why = "接続はできましたが、開始に失敗：" + e + "（Nothingの開発者向け設定＝デバッグモードが要るかもしれません）";
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    ready = false;
                    why = "Glyphのサービスから切れました";
                    try {
                        gm.closeSession();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        void release() {
            ready = false;
            try {
                gm.turnOff();
            } catch (Throwable ignored) {
            }
            try {
                gm.closeSession();
            } catch (Throwable ignored) {
            }
            gm.unInit();
        }

        void cue(final String kind) {
            if (!ready) return;
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        com.nothing.ketchum.GlyphFrame.Builder b = gm.getGlyphFrameBuilder();
                        if ("off".equals(kind)) {
                            gm.turnOff();
                        } else if ("listen".equals(kind)) {
                            gm.toggle(b.buildChannelA().build());
                        } else if ("err".equals(kind)) {
                            gm.animate(b.buildChannelA().buildChannelB().buildPeriod(250).buildCycles(3).buildInterval(100).build());
                        } else if ("think".equals(kind)) {
                            gm.animate(b.buildChannelB().buildPeriod(1200).buildCycles(40).buildInterval(100).build());
                        } else if ("parent".equals(kind)) {
                            gm.animate(b.buildChannelA().buildChannelC().buildPeriod(600).buildCycles(4).buildInterval(200).build());
                        } else if ("queued".equals(kind)) {
                            gm.animate(b.buildChannelB().buildPeriod(300).buildCycles(2).buildInterval(150).build());
                        } else if ("hello".equals(kind)) {
                            gm.animate(b.buildChannelA().buildChannelB().buildChannelC().buildPeriod(700).buildCycles(1).buildInterval(10).build());
                        } else if ("remind".equals(kind)) {
                            gm.animate(b.buildChannelB().buildChannelC().buildPeriod(1500).buildCycles(3).buildInterval(300).build());
                        } else {
                            gm.animate(b.buildChannelC().buildPeriod(900).buildCycles(1).buildInterval(10).build());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }
}

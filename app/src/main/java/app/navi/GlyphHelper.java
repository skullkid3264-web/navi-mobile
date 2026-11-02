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

    private GlyphHelper() {
    }

    static void start(Context c) {
        if (Build.VERSION.SDK_INT < 34 || impl != null) return;
        try {
            Impl i = new Impl(c.getApplicationContext());
            if (i.supported()) {
                impl = i;
                i.init();
            }
        } catch (Throwable ignored) {
            impl = null;
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

    /** kind: ok（できた）／remind（知らせ）／listen（聞いている）／off */
    static void cue(String kind) {
        if (impl == null) return;
        try {
            impl.cue(kind);
        } catch (Throwable ignored) {
        }
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
                    } catch (Throwable e) {
                        ready = false;
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    ready = false;
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

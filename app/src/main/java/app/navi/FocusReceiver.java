package app.navi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 伴走・宿題の「確かめる時刻」と、知らせのボタン（できた／まだ／次）を受ける */
public class FocusReceiver extends BroadcastReceiver {

    static final String TICK = "app.navi.FOCUS_TICK";
    static final String ANSWER = "app.navi.FOCUS_ANSWER";

    @Override
    public void onReceive(Context c, Intent intent) {
        final String action = intent == null || intent.getAction() == null ? "" : intent.getAction();
        final Context app = c.getApplicationContext();
        final PendingResult pr = goAsync();
        final String text = intent == null ? null : intent.getStringExtra("text");
        new Thread(() -> {
            try {
                if (action.startsWith(ANSWER) && text != null) {
                    Focus.answer(app, text);
                } else {
                    Focus.sync(app);
                }
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}

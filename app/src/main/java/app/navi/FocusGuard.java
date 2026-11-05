package app.navi;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 伴走中・宿題が残っている間に、決めたアプリ（SNS・動画など）が前に出たら、NAVIの画面をかぶせる。
 * 画面の中身は読まない（どのアプリが前に出たか＝パッケージ名だけを見る）。
 * 電話・設定・NAVI自身・ホーム画面は止めない。端末の「ユーザー補助」でいつでも止められる。
 */
public class FocusGuard extends AccessibilityService {

    private String lastPkg = "";
    private long lastAt = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && now - lastAt < 3000) return;
        lastPkg = pkg;
        lastAt = now;
        if (!Focus.shouldBlock(this, pkg)) return;
        try {
            Intent i = new Intent(this, FocusBlockActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra("pkg", pkg);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}

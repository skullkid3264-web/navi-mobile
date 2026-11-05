package app.navi;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** 伴走中・宿題が残っている間に、決めたアプリを開いたときにかぶさる画面 */
public class FocusBlockActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
    }

    private void build(Intent in) {
        final String pkg = in == null ? "" : String.valueOf(in.getStringExtra("pkg"));
        int n = Focus.countBlock(this);
        String app = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            app = String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception ignored) {
        }

        JSONObject d = Focus.drive(this);
        final JSONObject hw = Focus.overdue(this, System.currentTimeMillis());
        String title;
        String body;
        if (d != null) {
            title = "🎯 いまは『" + d.optString("task") + "』の時間";
            body = app + "は、伴走が終わってからにしよう。";
        } else if (hw != null) {
            title = "📝 宿題『" + hw.optString("title") + "』がまだです";
            body = "書き終わったら、" + app + "を使えます。";
        } else {
            finish();
            return;
        }
        body += "\n（今日" + n + "回目）";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(48), dp(28), dp(48));
        box.setBackgroundColor(Color.parseColor("#141826"));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText(body);
        s.setTextColor(Color.parseColor("#C9D1E8"));
        s.setTextSize(18);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(16), 0, dp(28));
        box.addView(s);

        box.addView(button(hw != null && d == null ? "📝 宿題を書く" : "🧭 NAVIに戻る", "#3B82F6", v -> {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (hw != null && d == null) {
                open.putExtra("form", hw.optString("form"));
                open.putExtra("cls", hw.optString("cls"));
            }
            startActivity(open);
            finish();
        }));
        box.addView(button("ホームに戻る", "#374151", v -> goHome()));
        int used = Focus.allowCountToday(this);
        if (used < 3) {
            box.addView(button("5分だけ使う（今日あと" + (3 - used) + "回）", "#1F2937", v -> {
                Focus.allowFor(this, pkg, 5);
                finish();
            }));
        } else {
            TextView no = new TextView(this);
            no.setText("「5分だけ」は今日はもう使いました。");
            no.setTextColor(Color.parseColor("#FCA5A5"));
            no.setGravity(Gravity.CENTER);
            no.setPadding(0, dp(12), 0, 0);
            box.addView(no);
        }
        setContentView(box);
    }

    private void goHome() {
        Intent h = new Intent(Intent.ACTION_MAIN);
        h.addCategory(Intent.CATEGORY_HOME);
        h.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(h);
        } catch (Exception ignored) {
        }
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goHome();
    }

    private Button button(String label, String color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

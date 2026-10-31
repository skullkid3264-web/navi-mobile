package app.navi;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** ホーム画面に置く「話す」ボタン。押すとアプリが開いて、すぐ聞き取りを始める */
public class TalkWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Intent i = new Intent(context, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("listen", true);
        PendingIntent pi = PendingIntent.getActivity(context, 5, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_talk);
            v.setOnClickPendingIntent(R.id.widget_root, pi);
            manager.updateAppWidget(id, v);
        }
    }
}

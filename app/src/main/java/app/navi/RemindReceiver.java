package app.navi;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** 仕掛けた時刻になったら通知を出す。再起動・アプリ更新のあとは、予約を仕掛け直す */
public class RemindReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Reminders.restore(c);
            return;
        }
        if (!Notices.canNotify(c)) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(Reminders.CHANNEL, "予定の前の知らせ", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("お出かけの前など、スマホ自身が鳴らす知らせ（PCにつながっていなくても出ます）");
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        int id = intent.getIntExtra("id", 1000);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("tab", "sched");
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = intent.getStringExtra("body");
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, Reminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(intent.getStringExtra("title"))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(c).notify(900000 + id, b.build());
        } catch (SecurityException ignored) {
        }
    }
}

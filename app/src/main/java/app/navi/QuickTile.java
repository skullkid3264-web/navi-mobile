package app.navi;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 画面の上から引き下げるクイック設定に出る「話す」ボタン（ロック画面からも押せる） */
public class QuickTile extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("listen", true);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}

package app.navi;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

/** 裏で動いて、GASに溜まった新しいお知らせを通知にする */
public class NoticeWorker extends Worker {

    public NoticeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        if (!Store.isPaired(c)) return Result.success();

        try {
            long last = Store.lastNotice(c);
            JSONObject p = new JSONObject();
            p.put("since", Math.max(last, 0));
            JSONObject res = Api.call(Store.url(c), Store.token(c), "noticesGet", p);
            if (!res.optBoolean("ok")) return Result.success();

            JSONObject r = res.getJSONObject("result");
            long latest = r.optLong("latest", 0);

            if (last < 0) {
                // 初めての確認では、昔のお知らせを一気に鳴らさない
                Store.setLastNotice(c, latest);
                return Result.success();
            }

            JSONArray items = r.optJSONArray("items");
            if (items != null) {
                // 通知が溜まりすぎないよう、新しい方から最大5件だけ出す
                int from = Math.max(0, items.length() - 5);
                for (int i = from; i < items.length(); i++) {
                    JSONObject n = items.getJSONObject(i);
                    Notices.show(c, n.optLong("id"), n.optString("title", "N.A.V.I."), n.optString("body", ""));
                }
            }
            Store.setLastNotice(c, latest);
            return Result.success();
        } catch (Exception e) {
            return Result.success();   // つながらないときは、次の15分後にまた試す
        }
    }
}

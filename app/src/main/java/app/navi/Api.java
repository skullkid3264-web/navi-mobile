package app.navi;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** GAS（VoiceApi.gs）の doPost を呼ぶ。アプリ側の通信はすべてここを通る */
final class Api {

    private Api() {
    }

    /** 成功なら GAS の返事 {ok, result|error} をそのまま返す。つながらないときは例外 */
    static JSONObject call(String url, String token, String action, JSONObject params) throws Exception {
        if (url == null || url.isEmpty() || token == null || token.isEmpty()) {
            throw new IOException("まだ登録されていません");
        }
        JSONObject body = new JSONObject();
        body.put("token", token);
        body.put("action", action);
        body.put("params", params == null ? new JSONObject() : params);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            String text = read(c.getInputStream());
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new IOException("想定外の返事です（GASのデプロイを「全員がアクセス可」にしているか確認してください）");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}

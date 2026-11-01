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

    /** 同じWi-Fiで直接つなげないとき（まだ何も送っていない）に投げる。呼び出し側は、GAS経由に切り替えてよい */
    static final class Unreachable extends IOException {
        Unreachable(String m) {
            super(m);
        }
    }

    /** 家庭内（プライベート）のアドレスだけを許す。合言葉を、外のアドレスへ送らないため */
    static boolean isPrivateUrl(String base) {
        try {
            URL u = new URL(base);
            if (!"http".equals(u.getProtocol())) return false;
            String h = u.getHost();
            if (h.startsWith("192.168.") || h.startsWith("10.")) return true;
            if (h.startsWith("172.")) {
                String[] p = h.split("\\.");
                if (p.length == 4) {
                    int second = Integer.parseInt(p[1]);
                    return second >= 16 && second <= 31;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** PCのN.A.V.I.に、同じWi-Fiから直接話しかける。PCの返事（JSON）をそのまま返す */
    static JSONObject callLan(String base, String key, String kind, String text) throws Exception {
        if (!isPrivateUrl(base)) throw new Unreachable("直接つなげるアドレスではありません");
        String url = base.endsWith("/") ? base + "api" : base + "/api";
        JSONObject body = new JSONObject();
        body.put("key", key);
        body.put("kind", kind);
        body.put("text", text == null ? "" : text);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(1500);
            c.setReadTimeout(130000);          // AIが考える時間（長い指示）を待つ
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try {
                c.connect();
            } catch (IOException e) {
                throw new Unreachable(String.valueOf(e.getMessage()));
            }
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
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

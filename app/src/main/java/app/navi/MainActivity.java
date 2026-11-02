package app.navi;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * N.A.V.I. モバイルの外枠。
 * 画面は assets/www/index.html で作り、ここでは
 * ・PC（GAS）との通信 ・スマホの音声認識 ・読み上げ ・通知 ・自動更新
 * を受け持つ。
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int REQ_INSTALL_PERMISSION = 11;
    private static final int REQ_NOTIFY = 21;
    private static final int REQ_AUDIO = 31;
    private static final int REQ_WAKE = 41;
    private static final int REQ_FILE = 51;
    private ValueCallback<Uri[]> fileCb;
    private Uri camUri;
    private String pendingOb;

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);

    private boolean pageIsReady = false;
    private boolean checkedOnLaunch = false;
    private String pendingTab;
    private boolean pendingPaired = false;
    private boolean pendingListen = false;

    // 更新
    private String pendingApkUrl;
    private File downloadedApk;
    private boolean downloading = false;

    // 音声
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFF14151C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);   // 短い声を、タップなしでも鳴らせるように
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        // 写真：「撮る／選ぶ」を出す（カメラの許可は、標準のカメラアプリが取るので、このアプリでは要らない）
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    Intent chooser = Intent.createChooser(pick, "写真を選ぶ／撮る");
                    try {
                        File dir = new File(getCacheDir(), "camera");
                        if (!dir.exists()) dir.mkdirs();
                        File f = File.createTempFile("photo_", ".jpg", dir);
                        camUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        cam.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                    } catch (Exception e) {
                        camUri = null;
                    }
                    startActivityForResult(chooser, REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");

        Notices.ensureChannel(this);
        if (Store.isPaired(this)) Notices.schedule(this);

        handleIntent(getIntent());

        // 「ナビ」呼びかけ待ちがオンのまま（再起動などで止まっていた）なら、開いたときに再開する
        if (Store.wake(this) && Store.isPaired(this)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWakeService();
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** 「navi://pair?u=…&k=…」で開かれたら登録する／通知から開かれたらお知らせ画面へ */
    private void handleIntent(Intent intent) {
        if (intent == null) return;

        Uri data = intent.getData();
        if (data != null && "navi".equals(data.getScheme())) {
            if (pairFromUri(data)) {
                if (pageIsReady) {
                    callJs("NAVI.onPaired && NAVI.onPaired()");
                } else {
                    pendingPaired = true;
                }
            }
        }

        if (intent.getBooleanExtra("listen", false) || "true".equals(intent.getStringExtra("listen"))) {
            intent.removeExtra("listen");
            if (pageIsReady) {
                callJs("NAVI.autoListen && NAVI.autoListen()");
            } else {
                pendingListen = true;
            }
        }

        String ob = intent.getStringExtra("ob");
        if (ob != null) {
            intent.removeExtra("ob");
            if (pageIsReady) {
                callJs("NAVI.openOb && NAVI.openOb(" + JSONObject.quote(ob) + ")");
            } else {
                pendingOb = ob;
            }
        }

        String tab = intent.getStringExtra("tab");
        if (tab != null) {
            if (pageIsReady) {
                callJs("NAVI.openTab && NAVI.openTab(" + JSONObject.quote(tab) + ")");
            } else {
                pendingTab = tab;
            }
        }
    }

    private boolean pairFromUri(Uri uri) {
        try {
            String u = uri.getQueryParameter("u");
            String k = uri.getQueryParameter("k");
            if (u == null || k == null || !u.startsWith("https://") || k.trim().isEmpty()) return false;
            Store.setPair(this, u, k);
            Notices.schedule(this);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.NAVI && NAVI.handleBack) ? NAVI.handleBack() : false", value -> {
            if (!"true".equals(value)) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        WakeService.setAppVisible(true);
    }

    private void startWakeService() {
        try {
            Intent i = new Intent(this, WakeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Store.setWake(this, false);
            callJs("NAVI.onWake && NAVI.onWake(false," + JSONObject.quote("呼びかけ待ちを始められませんでした：" + e.getMessage()) + ")");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WakeService.setAppVisible(false);
        stopListeningNow();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        GlyphHelper.start(this);
    }

    @Override
    protected void onStop() {
        GlyphHelper.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_AUDIO) {
            if (granted) {
                startListeningNow();
            } else {
                callJs("NAVI.onSpeechError && NAVI.onSpeechError(" + JSONObject.quote("マイクの許可がありません。端末の設定でマイクを許可してください") + ")");
            }
        } else if (requestCode == REQ_WAKE) {
            if (granted) {
                Store.setWake(this, true);
                startWakeService();
                callJs("NAVI.onWake && NAVI.onWake(true,'')");
            } else {
                callJs("NAVI.onWake && NAVI.onWake(false," + JSONObject.quote("マイクの許可がありません。端末の設定でマイクを許可してください") + ")");
            }
        } else if (requestCode == REQ_NOTIFY) {
            callJs("NAVI.onNotifyPerm && NAVI.onNotifyPerm(" + granted + ")");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            ValueCallback<Uri[]> cb = fileCb;
            fileCb = null;
            if (cb == null) return;
            Uri[] out = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getData() != null) {
                    out = new Uri[]{data.getData()};
                } else if (camUri != null) {
                    out = new Uri[]{camUri};     // カメラで撮ったとき（結果にアドレスが入らない）
                }
            }
            cb.onReceiveValue(out);
            return;
        }
        if (requestCode == REQ_INSTALL_PERMISSION) {
            if (canInstall() && downloadedApk != null) {
                installApk(downloadedApk);
            } else {
                callJs("NAVI.onUpdateError(" + JSONObject.quote("更新するには「このアプリからのインストール」を許可してください。") + ")");
            }
        }
    }

    // ---------------------------------------------------------------
    // 画面（JavaScript）との橋渡し。JS からは window.Android.xxx() で呼べる。
    // ---------------------------------------------------------------
    private class Bridge {

        @JavascriptInterface
        public String getInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("paired", Store.isPaired(MainActivity.this));
                String url = Store.url(MainActivity.this);
                o.put("host", url.isEmpty() ? "" : Uri.parse(url).getHost());
                o.put("version", BuildConfig.VERSION_NAME);
                o.put("canNotify", Notices.canNotify(MainActivity.this));
                o.put("speech", SpeechRecognizer.isRecognitionAvailable(MainActivity.this));
                o.put("wake", Store.wake(MainActivity.this) && WakeService.isRunning());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** 登録。文字は「navi://pair?u=…&k=…」の形か、GASのアドレスと合言葉を別々に */
        @JavascriptInterface
        public boolean setPair(String url, String token) {
            url = url == null ? "" : url.trim();
            token = token == null ? "" : token.trim();
            if (url.startsWith("navi://")) {
                return pairFromUri(Uri.parse(url));
            }
            if (!url.startsWith("https://") || token.isEmpty()) return false;
            Store.setPair(MainActivity.this, url, token);
            Notices.schedule(MainActivity.this);
            return true;
        }

        @JavascriptInterface
        public void unpair() {
            Store.clearPair(MainActivity.this);
            Notices.cancel(MainActivity.this);
        }

        /** PC（GAS）への呼び出し。結果は NAVI._cb(id, 結果の文字列) で返る */
        @JavascriptInterface
        public void call(final String id, final String action, final String paramsJson) {
            io.execute(() -> {
                String out;
                try {
                    JSONObject p = paramsJson == null || paramsJson.isEmpty() ? new JSONObject() : new JSONObject(paramsJson);
                    JSONObject res = Api.call(Store.url(MainActivity.this), Store.token(MainActivity.this), action, p);
                    out = res.toString();
                } catch (Exception e) {
                    try {
                        out = new JSONObject().put("ok", false).put("error", "つながりません：" + e.getMessage()).toString();
                    } catch (Exception ex) {
                        out = "{\"ok\":false,\"error\":\"つながりません\"}";
                    }
                }
                final String result = out;
                callJs("NAVI._cb(" + JSONObject.quote(id) + "," + JSONObject.quote(result) + ")");
            });
        }

        /** 同じWi-FiのPCに直接つなぐ。結果は NAVI._cb(id, {ok, result|error, connect?}) 。connect=true は「まだ何も送っていない」 */
        @JavascriptInterface
        public void callLan(final String id, final String base, final String kind, final String text) {
            io.execute(() -> {
                String out;
                try {
                    JSONObject r = Api.callLan(base, Store.token(MainActivity.this), kind, text);
                    if (r.has("error")) {
                        out = new JSONObject().put("ok", false).put("error", r.optString("error"))
                                .put("auth", r.optString("error").contains("合言葉")).toString();
                    } else {
                        out = new JSONObject().put("ok", true).put("result", r).toString();
                    }
                } catch (Api.Unreachable e) {
                    out = jsonFail("直接つなげません", true);
                } catch (Exception e) {
                    out = jsonFail("直接つないだ通信でエラー：" + e.getMessage(), false);
                }
                final String result = out;
                callJs("NAVI._cb(" + JSONObject.quote(id) + "," + JSONObject.quote(result) + ")");
            });
        }

        private String jsonFail(String msg, boolean connect) {
            try {
                return new JSONObject().put("ok", false).put("error", msg).put("connect", connect).toString();
            } catch (Exception e) {
                return "{\"ok\":false}";
            }
        }

        /** 端末に保存してある、今週の予定（なければ空の文字） */
        @JavascriptInterface
        public String getSnapshot() {
            return Store.snapshot(MainActivity.this);
        }

        /** 画面がPCから受け取った予定を、端末にも保存する（次回、通信できなくても見られる） */
        @JavascriptInterface
        public void saveSnapshot(String json) {
            try {
                JSONObject o = new JSONObject(json);
                long at = o.optLong("generatedAt", 0);
                if (at >= Store.snapshotAt(MainActivity.this)) Store.setSnapshot(MainActivity.this, json, at);
            } catch (Exception ignored) {
            }
        }

        /** 「ナビ」の呼びかけ待ちを、オン／オフする */
        @JavascriptInterface
        public void setWake(final boolean on) {
            main.post(() -> {
                if (on) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_WAKE);
                        return;
                    }
                    Store.setWake(MainActivity.this, true);
                    startWakeService();
                    callJs("NAVI.onWake && NAVI.onWake(true,'')");
                } else {
                    Store.setWake(MainActivity.this, false);
                    stopService(new Intent(MainActivity.this, WakeService.class));
                    callJs("NAVI.onWake && NAVI.onWake(false,'')");
                }
            });
        }

        @JavascriptInterface
        public void listen() {
            main.post(MainActivity.this::startListening);
        }

        @JavascriptInterface
        public void stopListen() {
            main.post(MainActivity.this::stopListeningNow);
        }

        @JavascriptInterface
        public void speak(final String text) {
            main.post(() -> speakNow(text));
        }

        /** 読み上げの声の一覧を、NAVI.onVoices(一覧, 今の設定) で返す */
        @JavascriptInterface
        public void listVoices() {
            main.post(MainActivity.this::sendVoices);
        }

        /** 声・速さ（0.5〜2）・高さ（0.5〜2）を保存して、すぐ反映する */
        @JavascriptInterface
        public void setVoice(final String name, final float rate, final float pitch) {
            main.post(() -> {
                float r = Math.max(0.5f, Math.min(2.0f, rate));
                float p = Math.max(0.5f, Math.min(2.0f, pitch));
                Store.setTts(MainActivity.this, name, r, p);
                withTts(MainActivity.this::applyVoice);
            });
        }

        /** 今の設定で試し聞きする */
        @JavascriptInterface
        public void speakSample() {
            main.post(() -> {
                if (tts != null) tts.stop();
                speakNow("こんにちは。ナビです。この声で読み上げます。");
            });
        }

        /** 端末の「テキスト読み上げ」の設定を開く（声の追加・エンジンの変更はここで） */
        @JavascriptInterface
        public void openTtsSettings() {
            main.post(() -> {
                try {
                    startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                        Toast.makeText(MainActivity.this, "設定を開けませんでした", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void stopSpeak() {
            main.post(() -> {
                if (tts != null) tts.stop();
            });
        }

        @JavascriptInterface
        public void requestNotify() {
            main.post(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                }
            });
        }

        @JavascriptInterface
        public void checkUpdate(boolean manual) {
            main.post(() -> checkForUpdate(manual));
        }

        @JavascriptInterface
        public void startUpdate() {
            main.post(MainActivity.this::downloadUpdate);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            main.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        /** 背面ライト（Nothing Phone (2a)）の演出。対応していない端末では何も起きない */
        @JavascriptInterface
        public void glyph(String kind) {
            GlyphHelper.cue(kind);
        }

        @JavascriptInterface
        public boolean glyphActive() {
            return GlyphHelper.active();
        }

        /** 予定の前の知らせの時刻一覧 [{at, title, body}] を受け取って、アラームを仕掛ける。戻り値：仕掛けた数 */
        @JavascriptInterface
        public int setReminders(String json) {
            return Reminders.set(MainActivity.this, json);
        }

        // ---- 保護者の通知の読み取り（同じWi-FiのPCへだけ送る） ----
        @JavascriptInterface
        public String getParentConfig() {
            try {
                JSONObject o = ParentListener.config(MainActivity.this);
                o.put("access", ParentListener.accessEnabled(MainActivity.this));
                o.put("pending", ParentListener.pendingCount(MainActivity.this));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setParentConfig(String json) {
            ParentListener.setConfig(MainActivity.this, json);
        }

        /** 通知へのアクセスの許可画面を開く（許可は、自分でここで入れる） */
        @JavascriptInterface
        public void openListenerSettings() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception ignored) {
                }
            });
        }

        /** 同じWi-Fiで直接つなげるPCのアドレスを、通知の読み取りにも教える。つながっているときに呼ぶと、溜めた分を送る */
        @JavascriptInterface
        public void setLanUrl(String url, boolean reachable) {
            ParentListener.setLan(MainActivity.this, url);
            if (reachable) ParentListener.flush(MainActivity.this);
        }

        @JavascriptInterface
        public void pageReady() {
            main.post(() -> {
                pageIsReady = true;
                if (pendingPaired) {
                    pendingPaired = false;
                    callJs("NAVI.onPaired && NAVI.onPaired()");
                }
                if (pendingListen) {
                    pendingListen = false;
                    callJs("NAVI.autoListen && NAVI.autoListen()");
                }
                if (pendingTab != null) {
                    callJs("NAVI.openTab && NAVI.openTab(" + JSONObject.quote(pendingTab) + ")");
                    pendingTab = null;
                }
                if (pendingOb != null) {
                    callJs("NAVI.openOb && NAVI.openOb(" + JSONObject.quote(pendingOb) + ")");
                    pendingOb = null;
                }
                if (!checkedOnLaunch) {
                    checkedOnLaunch = true;
                    checkForUpdate(false);
                }
            });
        }
    }

    private void callJs(final String code) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript("window.NAVI && " + code, null);
        });
    }

    // ---------------------------------------------------------------
    // 音声認識（スマホ内蔵）
    // ---------------------------------------------------------------
    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        startListeningNow();
    }

    private void startListeningNow() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            callJs("NAVI.onSpeechError(" + JSONObject.quote("この端末では音声認識が使えません（Googleアプリの音声入力を有効にしてください）") + ")");
            return;
        }
        if (tts != null) tts.stop();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    listening = true;
                    callJs("NAVI.onListening(true)");
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                }

                @Override
                public void onError(int error) {
                    listening = false;
                    callJs("NAVI.onListening(false)");
                    // 何も話さなかった／聞き取れなかったときは、静かに終わる
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        callJs("NAVI.onSpeechError(" + JSONObject.quote("聞き取れませんでした。もう一度どうぞ") + ")");
                    } else {
                        callJs("NAVI.onSpeechError(" + JSONObject.quote(speechErrorText(error)) + ")");
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    listening = false;
                    callJs("NAVI.onListening(false)");
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        callJs("NAVI.onSpeech(" + JSONObject.quote(list.get(0)) + ",true)");
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        callJs("NAVI.onSpeech(" + JSONObject.quote(list.get(0)) + ",false)");
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            callJs("NAVI.onSpeechError(" + JSONObject.quote("音声認識を始められませんでした") + ")");
        }
    }

    private void stopListeningNow() {
        if (recognizer != null && listening) {
            try {
                recognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
    }

    private static String speechErrorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "音声認識の通信に失敗しました（通信状態を確かめてください）";
            case SpeechRecognizer.ERROR_AUDIO:
                return "マイクから音を取れませんでした";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "マイクの許可がありません";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "音声認識が使用中です。少し待ってからもう一度";
            default:
                return "音声認識でエラーが起きました（" + error + "）";
        }
    }

    // ---------------------------------------------------------------
    // 読み上げ
    // ---------------------------------------------------------------
    private final ArrayList<Runnable> ttsWaiting = new ArrayList<>();
    private boolean ttsStarting = false;

    /** 読み上げの準備ができたら onReady を呼ぶ（初回は少し待つ） */
    private void withTts(final Runnable onReady) {
        if (tts != null && ttsReady) {
            onReady.run();
            return;
        }
        ttsWaiting.add(onReady);
        if (ttsStarting) return;
        ttsStarting = true;
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
                // 前の準備に失敗していたものを片づける
            }
        }
        tts = new TextToSpeech(this, status -> {
            ttsStarting = false;
            ttsReady = status == TextToSpeech.SUCCESS;
            ArrayList<Runnable> todo = new ArrayList<>(ttsWaiting);
            ttsWaiting.clear();
            if (!ttsReady) return;
            applyVoice();
            for (Runnable r : todo) r.run();
        });
    }

    /** 保存してある声・速さ・高さを反映する（見つからない声は標準に戻す） */
    private void applyVoice() {
        if (tts == null || !ttsReady) return;
        try {
            tts.setLanguage(Locale.JAPANESE);
            String want = Store.ttsVoice(this);
            if (!want.isEmpty()) {
                Set<Voice> all = tts.getVoices();
                if (all != null) {
                    for (Voice v : all) {
                        if (want.equals(v.getName())) {
                            tts.setVoice(v);
                            break;
                        }
                    }
                }
            }
            tts.setSpeechRate(Store.ttsRate(this));
            tts.setPitch(Store.ttsPitch(this));
        } catch (Exception ignored) {
            // 声が使えないときは、標準の声のまま話す
        }
    }

    private void speakNow(final String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String t = text.length() > 600 ? text.substring(0, 600) : text;
        withTts(() -> tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "navi"));
    }

    /** 日本語の声の一覧を JSON にして、画面（NAVI.onVoices）へ渡す */
    private void sendVoices() {
        withTts(() -> {
            try {
                JSONArray arr = new JSONArray();
                Set<Voice> all = tts.getVoices();
                if (all != null) {
                    for (Voice v : all) {
                        if (v.getLocale() == null || !"ja".equals(v.getLocale().getLanguage())) continue;
                        JSONObject o = new JSONObject();
                        o.put("name", v.getName());
                        o.put("quality", v.getQuality());
                        o.put("network", v.isNetworkConnectionRequired());
                        arr.put(o);
                    }
                }
                JSONObject cur = new JSONObject();
                cur.put("voice", Store.ttsVoice(this));
                cur.put("rate", Store.ttsRate(this));
                cur.put("pitch", Store.ttsPitch(this));
                callJs("NAVI.onVoices(" + arr.toString() + "," + cur.toString() + ")");
            } catch (Exception e) {
                callJs("NAVI.onVoices([],{})");
            }
        });
    }

    // ---------------------------------------------------------------
    // 自動更新：GitHub の最新リリースを見て、新しい版があれば知らせる
    // ---------------------------------------------------------------
    private void checkForUpdate(final boolean manual) {
        final String repo = BuildConfig.UPDATE_REPO;
        if (repo == null || repo.isEmpty()) {
            if (manual) callJs("NAVI.onUpdateError(" + JSONObject.quote("この版には更新先が設定されていません。") + ")");
            return;
        }
        io.execute(() -> {
            try {
                HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases/latest");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                JSONObject release = new JSONObject(readText(c.getInputStream()));
                c.disconnect();

                int latest = parseVersion(release.optString("tag_name", ""));
                String notes = release.optString("body", "");
                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.optString("name", "").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }

                if (latest > BuildConfig.VERSION_CODE && apkUrl != null) {
                    pendingApkUrl = apkUrl;
                    callJs("NAVI.onUpdateAvailable(" + JSONObject.quote(String.valueOf(latest)) + "," + JSONObject.quote(notes) + ")");
                } else if (manual) {
                    callJs("NAVI.onUpToDate()");
                }
            } catch (Exception e) {
                if (manual) {
                    callJs("NAVI.onUpdateError(" + JSONObject.quote("新しい版を確かめられませんでした。通信状態を確かめて、もう一度試してください。") + ")");
                }
            }
        });
    }

    private void downloadUpdate() {
        if (pendingApkUrl == null || downloading) return;
        downloading = true;
        final String url = pendingApkUrl;
        io.execute(() -> {
            File dir = new File(getCacheDir(), "updates");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "update.apk");
            try {
                HttpURLConnection c = open(url);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                callJs("NAVI.onUpdateProgress(" + pct + ")");
                            }
                        }
                    }
                }
                c.disconnect();
                main.post(() -> {
                    downloading = false;
                    downloadedApk = out;
                    if (canInstall()) {
                        installApk(out);
                    } else {
                        Toast.makeText(this, "次の画面で「この提供元のアプリを許可」をオンにして、戻ってください", Toast.LENGTH_LONG).show();
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                        try {
                            startActivityForResult(i, REQ_INSTALL_PERMISSION);
                        } catch (ActivityNotFoundException e) {
                            callJs("NAVI.onUpdateError(" + JSONObject.quote("設定画面を開けませんでした。設定から、このアプリのインストール許可をオンにしてください。") + ")");
                        }
                    }
                });
            } catch (Exception e) {
                main.post(() -> downloading = false);
                callJs("NAVI.onUpdateError(" + JSONObject.quote("新しい版を取得できませんでした。通信状態を確かめて、もう一度試してください。") + ")");
            }
        });
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void installApk(File apk) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            callJs("NAVI.onUpdateError(" + JSONObject.quote("インストール画面を開けませんでした。") + ")");
        }
    }

    // ---------------------------------------------------------------
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "NAVI-Android");
        return c;
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseVersion(String tag) {
        String digits = tag.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

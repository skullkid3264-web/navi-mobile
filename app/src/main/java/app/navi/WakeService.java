package app.navi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「ナビ」と呼びかけると答える、ハンズフリーの待機サービス。
 * ・ずっと聞いて、「ナビ」が聞こえたら続きの言葉をPCに送る（「ナビ、今日のまとめ」）
 * ・「ナビ」だけなら「はい」と答えて、次の言葉を待つ
 * ・返事は声で読み上げ、実行が必要な内容なら「実行しますか？」と聞く（「実行」「やめる」で答える）
 * アプリを開いている間は、マイクを譲るために待機を止める。
 */
public class WakeService extends Service {

    static final String ACTION_STOP = "app.navi.WAKE_STOP";
    private static final String CHANNEL = "navi_wake";
    private static final int NOTIF_ID = 77;

    private static final Pattern WAKE = Pattern.compile("(ナビ(?!ゲ)[ィー]?|なび(?!げ)|ナヴィ|navi)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YES = Pattern.compile("^(実行|実行して|実行する|はい|お願い|おねがい|お願いします|オッケー|ok|やって|やってください)[。！!、\\s]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO = Pattern.compile("^(やめる|やめて|キャンセル|いいえ|中止|だめ|ダメ)[。！!、\\s]*$");

    private enum State { IDLE, COMMAND, WORKING, CONFIRM, SPEAKING, PAUSED }

    private static WakeService instance;
    private static volatile boolean appVisible = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private State state = State.IDLE;
    private State afterSpeak = State.IDLE;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;
    private boolean listening = false;
    private boolean stopped = false;
    private int errors = 0;

    /** アプリの画面が開いている間は、マイクを譲る */
    static void setAppVisible(boolean visible) {
        appVisible = visible;
        final WakeService s = instance;
        if (s != null) s.main.post(s::onVisibilityChanged);
    }

    static boolean isRunning() {
        return instance != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Store.setWake(this, false);
            stopped = true;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        instance = this;
        stopped = false;
        Notification n = buildNotification("「ナビ」と呼びかけてください");
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.JAPANESE);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            main.post(WakeService.this::afterSpeaking);
                        }

                        @Override
                        public void onError(String utteranceId) {
                            main.post(WakeService.this::afterSpeaking);
                        }
                    });
                    if (pendingSpeech != null) {
                        String t = pendingSpeech;
                        pendingSpeech = null;
                        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "navi");
                    }
                }
            });
        }

        state = State.IDLE;
        scheduleListen(300);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        instance = null;
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    private void onVisibilityChanged() {
        if (stopped) return;
        if (appVisible) {
            if (recognizer != null) {
                try {
                    recognizer.cancel();
                } catch (Exception ignored) {
                }
            }
            listening = false;
            if (state == State.IDLE || state == State.COMMAND || state == State.CONFIRM) state = State.PAUSED;
        } else if (state == State.PAUSED) {
            state = State.IDLE;
            scheduleListen(500);
        }
    }

    private void scheduleListen(long delayMs) {
        main.postDelayed(this::startListen, delayMs);
    }

    private void startListen() {
        if (stopped || listening) return;
        if (appVisible) {
            if (state == State.IDLE || state == State.COMMAND || state == State.CONFIRM) state = State.PAUSED;
            return;
        }
        if (state == State.WORKING || state == State.SPEAKING || state == State.PAUSED) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            scheduleListen(15000);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    listening = true;
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
                public void onPartialResults(Bundle partialResults) {
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }

                @Override
                public void onError(int error) {
                    listening = false;
                    if (stopped) return;
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        Store.setWake(WakeService.this, false);
                        stopForeground(true);
                        stopSelf();
                        return;
                    }
                    boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    if (quiet) {
                        errors = 0;
                        if (state == State.COMMAND || state == State.CONFIRM) {
                            state = State.IDLE;
                            updateNotification("「ナビ」と呼びかけてください");
                        }
                        scheduleListen(200);
                    } else {
                        errors++;
                        scheduleListen(errors > 5 ? 10000 : 1500);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    listening = false;
                    errors = 0;
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list == null || list.isEmpty()) {
                        scheduleListen(200);
                        return;
                    }
                    handleText(list.get(0));
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            scheduleListen(3000);
        }
    }

    // ---------------------------------------------------------------
    private void handleText(String text) {
        String t = text == null ? "" : text.trim();
        switch (state) {
            case IDLE: {
                Matcher m = WAKE.matcher(t);
                if (!m.find()) {
                    scheduleListen(150);
                    return;
                }
                String rest = t.substring(m.end()).replaceAll("^[、。,.!！?？\\s]+", "").trim();
                if (rest.isEmpty()) {
                    updateNotification("お話しください…");
                    speak("はい", State.COMMAND);
                } else {
                    send(rest, "send");
                }
                break;
            }
            case COMMAND:
                if (t.isEmpty()) {
                    state = State.IDLE;
                    scheduleListen(150);
                } else {
                    send(t, "send");
                }
                break;
            case CONFIRM:
                if (YES.matcher(t).matches()) {
                    send("", "execute");
                } else if (NO.matcher(t).matches()) {
                    send("", "cancel");
                } else if (t.isEmpty()) {
                    state = State.IDLE;
                    scheduleListen(150);
                } else {
                    send(t, "send");
                }
                break;
            default:
                scheduleListen(300);
        }
    }

    /** PCに送って、返事を待つ（アプリの画面と同じ受信箱を使う） */
    private void send(final String text, final String kind) {
        state = State.WORKING;
        updateNotification("考え中…");
        final Context c = getApplicationContext();
        io.execute(() -> {
            JSONObject response = null;
            String error = null;
            try {
                JSONObject p = new JSONObject();
                p.put("kind", kind);
                p.put("text", text);
                JSONObject sub = Api.call(Store.url(c), Store.token(c), "mobileSubmit", p);
                if (!sub.optBoolean("ok")) throw new Exception(sub.optString("error", "送れませんでした"));
                String id = sub.optString("result");

                for (int i = 0; i < 90 && response == null; i++) {
                    Thread.sleep(i < 5 ? 1500 : 2500);
                    JSONObject q = new JSONObject();
                    q.put("id", id);
                    JSONObject r = Api.call(Store.url(c), Store.token(c), "mobileFetch", q);
                    if (!r.optBoolean("ok")) throw new Exception(r.optString("error", "受け取れませんでした"));
                    JSONObject result = r.optJSONObject("result");
                    if (result != null) response = result.optJSONObject("response");
                }
                if (response == null) error = "PCから返事がありません。PCのN.A.V.I.が起動していて、スマホから使うがオンか確認してください";
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                error = e.getMessage() == null ? "つながりません" : e.getMessage();
            }
            final JSONObject resp = response;
            final String err = error;
            main.post(() -> finishSend(resp, err));
        });
    }

    private void finishSend(JSONObject resp, String err) {
        if (stopped) return;
        if (err != null) {
            Notices.show(this, 1, "N.A.V.I.", "❌ " + err);
            updateNotification("「ナビ」と呼びかけてください");
            speak("うまくいきませんでした。" + err, State.IDLE);
            return;
        }
        String reply = resp.optString("reply", "");
        JSONArray plan = resp.optJSONArray("plan");
        boolean canExec = resp.optBoolean("can_execute", false);
        boolean needConfirm = plan != null && plan.length() > 0 && canExec;

        StringBuilder body = new StringBuilder(reply);
        if (plan != null) {
            for (int i = 0; i < plan.length(); i++) {
                JSONObject it = plan.optJSONObject(i);
                if (it != null) body.append("\n").append(i + 1).append(". ").append(it.optString("label"));
            }
        }
        Notices.show(this, 1, "N.A.V.I.", body.length() == 0 ? "完了しました" : body.toString());

        String spoken = reply.isEmpty() ? "完了しました" : reply;
        if (needConfirm) spoken += "。実行しますか？";
        updateNotification(needConfirm ? "「実行」か「やめる」と答えてください" : "「ナビ」と呼びかけてください");
        speak(spoken, needConfirm ? State.CONFIRM : State.IDLE);
    }

    private void speak(String text, State next) {
        state = State.SPEAKING;
        afterSpeak = next;
        String t = text.length() > 500 ? text.substring(0, 500) : text;
        if (tts == null) {
            afterSpeaking();
            return;
        }
        if (!ttsReady) {
            pendingSpeech = t;
            return;
        }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "navi");
    }

    private void afterSpeaking() {
        if (stopped || state != State.SPEAKING) return;
        state = afterSpeak;
        scheduleListen(300);
    }

    // ---------------------------------------------------------------
    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "「ナビ」呼びかけ待ち", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("オンにしている間、呼びかけを待っています");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, WakeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("N.A.V.I. 呼びかけ待ち中")
                .setContentText(text)
                .setContentIntent(openPi)
                .addAction(0, "オフにする", stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}

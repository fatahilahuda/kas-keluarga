package io.github.fatahilahuda.kaskeluarga;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * window.KasQuickNative for the web app, in the main app's WebView and in the quick-add popup.
 *
 * Voice uses SpeechRecognizer directly, in Indonesian, so the app shows its own listening sheet
 * instead of the Google dialog. Events go back to the page as window.__kasVoice({type, ...}):
 * ready, rms (level), partial (text), end, final (alts), error (code).
 */
public class KasNative {

    public static final int REQ_MIC = 4711;

    private final Activity activity;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private String pendingLang;
    private long lastRms;
    private volatile int navInsetDp;

    public KasNative(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    // ---------- voice ----------

    @JavascriptInterface
    public boolean voiceAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(activity);
    }

    @JavascriptInterface
    public void voiceStart(String lang) {
        main.post(() -> start(lang == null || lang.isEmpty() ? "id-ID" : lang));
    }

    @JavascriptInterface
    public void voiceStop() {
        main.post(() -> {
            if (recognizer != null) recognizer.stopListening();
        });
    }

    @JavascriptInterface
    public void voiceCancel() {
        main.post(this::destroy);
    }

    private void start(String lang) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang;
            ActivityCompat.requestPermissions(activity, new String[] { Manifest.permission.RECORD_AUDIO }, REQ_MIC);
            return;
        }
        destroy();
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            error("unavailable");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        recognizer.setRecognitionListener(new Listener());
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            error("other");
        }
    }

    /** Called by the host activity from onRequestPermissionsResult. */
    public void onPermission(boolean granted) {
        String lang = pendingLang;
        pendingLang = null;
        if (lang == null) return;
        if (granted) start(lang);
        else error("perm");
    }

    public void destroy() {
        if (recognizer == null) return;
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (RuntimeException ignored) {}
        recognizer = null;
    }

    private void error(String code) {
        emit(event("error").put2("code", code));
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { emit(event("ready")); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onRmsChanged(float rmsdB) {
            long now = SystemClock.uptimeMillis();
            if (now - lastRms < 70) return;
            lastRms = now;
            emit(event("rms").put2("level", rmsdB));
        }

        @Override public void onEndOfSpeech() { emit(event("end")); }

        @Override
        public void onError(int code) {
            String c;
            switch (code) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: c = "nomatch"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: c = "network"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: c = "perm"; break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: c = "busy"; break;
                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
                case 13: c = "lang"; break; // ERROR_LANGUAGE_UNAVAILABLE
                default: c = "other";
            }
            error(c);
            main.post(KasNative.this::destroy);
        }

        @Override
        public void onPartialResults(Bundle partial) {
            ArrayList<String> r = partial == null ? null : partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && r.get(0) != null && !r.get(0).isEmpty()) emit(event("partial").put2("text", r.get(0)));
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> r = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) {
                error("nomatch");
            } else {
                JSONArray alts = new JSONArray();
                for (String s : r) if (s != null) alts.put(s);
                emit(event("final").put2("alts", alts));
            }
            main.post(KasNative.this::destroy);
        }
    }

    // ---------- popup, widget ----------

    /** Hands this month's numbers to the home screen widget. */
    @JavascriptInterface
    public void widget(String json) {
        KasWidget.save(activity.getApplicationContext(), json);
    }

    /** Closes the quick-add popup (does nothing in the main app). */
    @JavascriptInterface
    public void close() {
        if (activity instanceof QuickAddActivity) main.post(activity::finish);
    }

    @JavascriptInterface
    public void openApp() {
        main.post(() -> {
            Intent i = new Intent(activity, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            if (activity instanceof QuickAddActivity) activity.finish();
        });
    }

    /** Height of the gesture/navigation bar in CSS px, for the popup's bottom padding. */
    @JavascriptInterface
    public int navInset() {
        return navInsetDp;
    }

    public void setNavInset(int dp) {
        navInsetDp = dp;
        main.post(() -> web.evaluateJavascript("document.documentElement.style.setProperty('--nb','" + dp + "px')", null));
    }

    // ---------- plumbing ----------

    private static class Ev extends JSONObject {
        Ev put2(String k, Object v) {
            try {
                put(k, v);
            } catch (JSONException ignored) {}
            return this;
        }
        Ev put2(String k, double v) {
            try {
                put(k, v);
            } catch (JSONException ignored) {}
            return this;
        }
    }

    private static Ev event(String type) {
        return new Ev().put2("type", type);
    }

    private void emit(JSONObject ev) {
        String js = "window.__kasVoice&&window.__kasVoice(" + ev.toString() + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }
}

package io.github.fatahilahuda.kaskeluarga;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
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
        muteBeep();
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            unmuteBeep();
            error("other");
        }
    }

    /** False when the phone is on silent or vibrate: the web app then skips its little sounds. */
    @JavascriptInterface
    public boolean soundOk() {
        AudioManager am = (AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
        return am == null || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    // The speech service plays its own "ding" when it starts listening. Muarakas already plays its own
    // chime, so the system streams that ding usually uses are muted for a moment around the start.
    private final java.util.List<Integer> muted = new java.util.ArrayList<>();
    private final Runnable unmute = this::unmuteBeep;

    private void muteBeep() {
        AudioManager am = (AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am == null || !muted.isEmpty()) return;
        for (int stream : new int[] { AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM }) {
            try {
                if (am.isStreamMute(stream)) continue;
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
                muted.add(stream);
            } catch (RuntimeException ignored) {}
        }
        main.removeCallbacks(unmute);
        main.postDelayed(unmute, 2000); // never leave the phone muted
    }

    private void unmuteBeep() {
        main.removeCallbacks(unmute);
        AudioManager am = (AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am != null) for (int stream : muted) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
            } catch (RuntimeException ignored) {}
        }
        muted.clear();
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
        if (!muted.isEmpty()) unmuteBeep();
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
        @Override
        public void onReadyForSpeech(Bundle params) {
            emit(event("ready"));
            glowState("listen");
            main.postDelayed(unmute, 450); // after the ding, before anything the user wants to hear
        }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onRmsChanged(float rmsdB) {
            long now = SystemClock.uptimeMillis();
            if (now - lastRms < 70) return;
            lastRms = now;
            emit(event("rms").put2("level", rmsdB));
            if (activity instanceof QuickAddActivity) {
                float lv = rmsdB;
                main.post(() -> ((QuickAddActivity) activity).setVoiceLevel(lv));
            }
        }

        @Override public void onEndOfSpeech() { emit(event("end")); glowState("on"); }

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
            glowState("on");
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

    // ---------- share sheet ("Catat di Muarakas") ----------

    /** What was shared to Muarakas, as JSON {kind:text|image, text, subject, image, pdf}; "" when none. */
    @JavascriptInterface
    public String shareTake() {
        String s = ShareActivity.take();
        return s == null ? "" : s;
    }

    // ---------- reminders (optional modules) ----------

    public static final int REQ_NOTIF = 4712;

    /** Bill list and this week's summary, for the 08:00 / 19:00 notifications (Reminders). */
    @JavascriptInterface
    public void reminders(String json) {
        Reminders.save(activity.getApplicationContext(), json);
        // Ask for notification permission once, the first time a reminder module is in use
        if (Build.VERSION.SDK_INT >= 33 && Reminders.wanted(activity) && !Reminders.canPost(activity)) askNotifOnce();
    }

    private void askNotifOnce() {
        android.content.SharedPreferences p = activity.getSharedPreferences(Reminders.PREFS, 0);
        if (p.getBoolean("asked", false)) return;
        p.edit().putBoolean("asked", true).apply();
        main.post(() -> ActivityCompat.requestPermissions(activity, new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF));
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

    // ---------- ways to open catat cepat (Pengaturan → Catat cepat) ----------

    /** True when this is the Android app with these switches (the web page shows the settings then). */
    @JavascriptInterface
    public boolean quickSettings() {
        return true;
    }

    /** "on", "off", or "perm" (turned on, waiting for the display-over-apps permission). */
    @JavascriptInterface
    public String bubbleState() {
        if (!BubbleService.wanted(activity)) return "off";
        return Settings.canDrawOverlays(activity) ? "on" : "perm";
    }

    @JavascriptInterface
    public String bubbleSet(boolean on) {
        BubbleService.prefs(activity).edit().putBoolean(BubbleService.WANT, on).apply();
        if (on && !Settings.canDrawOverlays(activity)) {
            main.post(() -> {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName())));
                } catch (RuntimeException e) {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                }
            });
            return "perm";
        }
        main.post(() -> BubbleService.sync(activity));
        return on ? "on" : "off";
    }

    private ComponentName launcherAlias() {
        return new ComponentName(activity.getPackageName(), "io.github.fatahilahuda.kaskeluarga.QuickLauncher");
    }

    /** The separate "Catat cepat" icon, for the Samsung side key (double press → open app). */
    @JavascriptInterface
    public boolean launcherIcon() {
        return activity.getPackageManager().getComponentEnabledSetting(launcherAlias()) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    @JavascriptInterface
    public void launcherIconSet(boolean on) {
        activity.getPackageManager().setComponentEnabledSetting(launcherAlias(),
            on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }

    /** Whether Muarakas is the phone's digital assistant (long-press side key / corner swipe). */
    @JavascriptInterface
    public boolean assistantOn() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = activity.getSystemService(RoleManager.class);
            return rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT);
        }
        String cur = Settings.Secure.getString(activity.getContentResolver(), "assistant");
        return cur != null && cur.startsWith(activity.getPackageName() + "/");
    }

    /** Android does not let apps take the assistant role themselves; this opens the picker. */
    @JavascriptInterface
    public void assistantSettings() {
        main.post(() -> {
            String[] actions = { "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_SETTINGS };
            for (String a : actions) {
                try {
                    activity.startActivity(new Intent(a));
                    return;
                } catch (RuntimeException ignored) {}
            }
        });
    }

    // ---------- catat cepat card window (QuickAddActivity) ----------

    /** Present only in the popup: the page reports its card height and the window follows. */
    @JavascriptInterface
    public void sheetHeight(int cssPx) {
        if (activity instanceof QuickAddActivity) main.post(() -> ((QuickAddActivity) activity).setCardHeightCss(cssPx));
    }

    /** Edge glow state drawn natively behind the card: on, listen, saved, off. */
    @JavascriptInterface
    public void glow(String state) {
        if (activity instanceof QuickAddActivity) main.post(() -> ((QuickAddActivity) activity).setGlow(state));
    }

    @JavascriptInterface
    public boolean blurOn() {
        return activity instanceof QuickAddActivity && ((QuickAddActivity) activity).blurOn();
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

    private void glowState(String s) {
        if (activity instanceof QuickAddActivity) main.post(() -> ((QuickAddActivity) activity).setGlow(s));
    }

    private static Ev event(String type) {
        return new Ev().put2("type", type);
    }

    private void emit(JSONObject ev) {
        String js = "window.__kasVoice&&window.__kasVoice(" + ev.toString() + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }
}

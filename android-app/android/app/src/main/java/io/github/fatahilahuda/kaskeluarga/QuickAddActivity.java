package io.github.fatahilahuda.kaskeluarga;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

/**
 * The "catat cepat" popup opened from the widget, app-icon shortcuts, Quick Settings tile,
 * side key, assistant gesture and floating bubble.
 *
 * Two windows: this activity is a see-through full-screen layer with a light dim and the
 * running edge glow (GlowView); the card is a separate floating window (a Dialog) holding the
 * web app in quick mode (index.html?quick=voice|text). Android 12+ blurs only what lies behind
 * that card window, so the frosted glass stays inside the card. The page reports the card's
 * height (KasNative.sheetHeight) and the window follows it.
 */
public class QuickAddActivity extends AppCompatActivity {

    static final String BASE = "https://fatahilahuda.github.io/kas-keluarga/";
    public static final String EXTRA_MODE = "mode";

    private WebView web;
    private KasNative bridge;
    private GlowView glow;
    private Dialog card;
    private String mode;
    private boolean sized;

    public static Intent intent(Context ctx, String mode) {
        return new Intent(ctx, QuickAddActivity.class)
            .putExtra(EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        overridePendingTransition(0, 0);
        mode = "text".equals(getIntent().getStringExtra(EXTRA_MODE)) ? "text" : "voice"; // assistant gesture and launcher icon: voice

        // Layer 1: dim + glow over the whole screen. Tapping outside the card closes.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(GlowView.dimColor());
        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnClickListener(v -> finish());
        setContentView(root);

        // Layer 2: the card window with the web app.
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setAlpha(0f);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(ws.getUserAgentString() + " KasKeluargaAndroid KasQuick");
        bridge = new KasNative(this, web);
        web.addJavascriptInterface(bridge, "KasQuickNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("fatahilahuda.github.io".equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/kas-keluarga")) {
                    if (u.getQueryParameter("quick") == null) {
                        startActivity(new Intent(QuickAddActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        finish();
                        return true;
                    }
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (RuntimeException ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Fallback if the page never reports a height (an old cached page): use a sensible one.
                web.postDelayed(() -> { if (!sized) setCardHeightPx(dp(560)); }, 1500);
                if ("text".equals(mode)) showKeyboard();
            }
        });

        card = new Dialog(this, R.style.QuickCard);
        card.setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setCanceledOnTouchOutside(true);
        card.setOnCancelListener(d -> finish());
        Window w = card.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
            bg.setCornerRadius(dp(28));
            w.setBackgroundDrawable(bg);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = getResources().getDisplayMetrics().widthPixels - dp(16);
            lp.height = 1; // grows to the card once the page reports its height
            lp.y = dp(10);
            w.setAttributes(lp);
            if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(dp(30));
        }
        root.post(() -> {
            if (!isFinishing()) card.show();
        });
        web.loadUrl(BASE + "?quick=" + mode);
    }

    /** True when the system will actually blur behind the card (off in battery saver, etc.). */
    boolean blurOn() {
        if (Build.VERSION.SDK_INT < 31) return false;
        WindowManager wm = getSystemService(WindowManager.class);
        return wm != null && wm.isCrossWindowBlurEnabled();
    }

    void setCardHeightCss(int cssPx) {
        setCardHeightPx(Math.round(cssPx * getResources().getDisplayMetrics().density));
    }

    private void setCardHeightPx(int px) {
        if (card == null || card.getWindow() == null || px <= 0) return;
        int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.9f);
        WindowManager.LayoutParams lp = card.getWindow().getAttributes();
        int h = Math.min(px, max);
        if (lp.height == h && sized) return;
        lp.height = h;
        card.getWindow().setAttributes(lp);
        if (!sized) {
            sized = true;
            web.animate().alpha(1f).setDuration(140).start();
        }
    }

    void setGlow(String state) {
        if (glow != null) glow.setState(state);
    }

    void setVoiceLevel(float rmsdB) {
        if (glow != null) glow.setVoiceLevel(rmsdB);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mode = "text".equals(intent.getStringExtra(EXTRA_MODE)) ? "text" : "voice";
        sized = false;
        web.setAlpha(0f);
        web.loadUrl(BASE + "?quick=" + mode);
    }

    private void showKeyboard() {
        web.requestFocus();
        web.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
        }, 260);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BubbleService.setHidden(true);
    }

    @Override
    protected void onPause() {
        BubbleService.setHidden(false);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == KasNative.REQ_MIC) {
            bridge.onPermission(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        }
    }

    @Override
    public void finish() {
        if (card != null && card.isShowing()) {
            try {
                card.dismiss();
            } catch (RuntimeException ignored) {}
        }
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.destroy();
        if (card != null && card.isShowing()) {
            try {
                card.dismiss();
            } catch (RuntimeException ignored) {}
        }
        if (web != null) {
            web.removeJavascriptInterface("KasQuickNative");
            web.destroy();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

package io.github.fatahilahuda.kaskeluarga;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The "catat cepat" popup opened from the home screen widget, the app-icon shortcuts and the
 * Quick Settings tile. A transparent window holding the web app in quick mode
 * (index.html?quick=voice|text), which draws only the Catat sheet over whatever is behind.
 * Same origin as the main app, so it reads and writes the same data.
 */
public class QuickAddActivity extends AppCompatActivity {

    static final String BASE = "https://fatahilahuda.github.io/kas-keluarga/";
    public static final String EXTRA_MODE = "mode";

    private WebView web;
    private KasNative bridge;
    private ProgressBar loader;
    private FrameLayout root;
    private String mode;

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

        mode = "text".equals(getIntent().getStringExtra(EXTRA_MODE)) ? "text" : "voice";

        root = new FrameLayout(this);
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
                    // Anything beyond the popup (e.g. "Buka Muarakas") belongs in the main app.
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
                loader.setVisibility(View.GONE);
                root.setBackgroundColor(Color.TRANSPARENT); // the page draws its own dim scrim
                web.animate().alpha(1f).setDuration(120).start();
                if ("text".equals(mode)) showKeyboard();
            }
        });

        loader = new ProgressBar(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(140);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(loader, lp);
        root.setBackgroundColor(Color.argb(0x6b, 6, 12, 10));
        setContentView(root);

        // Keyboard lifts the sheet; the gesture bar height goes to the page as --nb.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) web.getLayoutParams();
            p.bottomMargin = ime.bottom;
            web.setLayoutParams(p);
            bridge.setNavInset(ime.bottom > 0 ? 0 : Math.round(nav.bottom / getResources().getDisplayMetrics().density));
            return WindowInsetsCompat.CONSUMED;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        web.loadUrl(BASE + "?quick=" + mode);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mode = "text".equals(intent.getStringExtra(EXTRA_MODE)) ? "text" : "voice";
        web.loadUrl(BASE + "?quick=" + mode);
    }

    private void showKeyboard() {
        web.requestFocus();
        web.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
        }, 220);
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
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.destroy();
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

package io.github.fatahilahuda.kaskeluarga;

import android.content.pm.PackageManager;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must be registered before super.onCreate builds the bridge and loads the page.
        registerPlugin(KasShimPlugin.class);
        registerPlugin(KasNfcPlugin.class);
        registerPlugin(KasBioPlugin.class);
        registerPlugin(KasQuickPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Back from the "display over other apps" screen, or the app just opened: show the bubble if wanted.
        try {
            BubbleService.sync(this);
        } catch (RuntimeException ignored) {}
        // Something shared to "Catat di Muarakas" while the app was already open: tell the page.
        // On a cold start the page asks for it itself once it has loaded (KasNative.shareTake).
        if (ShareActivity.waiting() && getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().evaluateJavascript("window.__kasShare&&window.__kasShare()", null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == KasNative.REQ_MIC && KasQuickPlugin.bridge != null) {
            KasQuickPlugin.bridge.onPermission(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        }
    }
}

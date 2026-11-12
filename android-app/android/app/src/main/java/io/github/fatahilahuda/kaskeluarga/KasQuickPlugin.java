package io.github.fatahilahuda.kaskeluarga;

import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Exposes window.KasQuickNative (voice, widget data) to the main app's WebView.
 * Plugins load before Capacitor calls loadUrl, so the interface exists for the first page.
 */
@CapacitorPlugin(name = "KasQuick")
public class KasQuickPlugin extends Plugin {

    static KasNative bridge;

    @Override
    public void load() {
        bridge = new KasNative(getActivity(), getBridge().getWebView());
        getBridge().getWebView().addJavascriptInterface(bridge, "KasQuickNative");
    }

    @Override
    protected void handleOnDestroy() {
        if (bridge != null) bridge.destroy();
    }
}

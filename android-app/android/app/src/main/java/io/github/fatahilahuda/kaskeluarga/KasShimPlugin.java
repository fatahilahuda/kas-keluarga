package io.github.fatahilahuda.kaskeluarga;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Injects assets/kas-shim.js into the web app before its own scripts run.
 * Plugins load before Capacitor calls loadUrl, so the script is in place for the first page.
 */
@CapacitorPlugin(name = "KasShim")
public class KasShimPlugin extends Plugin {

    private static final String TAG = "KasShim";
    private static final String APP_ORIGIN = "https://fatahilahuda.github.io";
    private static final String SHIM_ASSET = "kas-shim.js";

    @Override
    public void load() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Logger.warn(TAG, "WebView too old for document-start scripts; sharing falls back to the web app's own behaviour");
            return;
        }
        try (InputStream in = getContext().getAssets().open(SHIM_ASSET)) {
            String script = new String(readFully(in), StandardCharsets.UTF_8);
            WebViewCompat.addDocumentStartJavaScript(getBridge().getWebView(), script, Collections.singleton(APP_ORIGIN));
        } catch (IOException | RuntimeException e) {
            Logger.error(TAG, "Could not inject " + SHIM_ASSET, e);
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}

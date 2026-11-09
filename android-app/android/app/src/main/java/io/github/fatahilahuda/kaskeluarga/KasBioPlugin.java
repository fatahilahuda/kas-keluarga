package io.github.fatahilahuda.kaskeluarga;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Fingerprint / face unlock for the app lock, used by the web app through window.KasBio (see kas-shim.js).
 * Android WebView has no WebAuthn, so the page asks the system BiometricPrompt instead.
 *
 * authenticate() always resolves with { ok: true } or { ok: false, reason }, where reason is
 * cancel | lockout | none | unavailable | error. A single unrecognised finger is not an error:
 * the prompt stays open and lets the user try again.
 */
@CapacitorPlugin(name = "KasBio")
public class KasBioPlugin extends Plugin {

    private static final String TAG = "KasBio";
    // Class 2 (weak) also covers face unlock on most phones; the PIN stays available in the app.
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK;

    @PluginMethod
    public void status(PluginCall call) {
        int code = BiometricManager.from(getContext()).canAuthenticate(AUTHENTICATORS);
        JSObject result = new JSObject();
        result.put("available", code == BiometricManager.BIOMETRIC_SUCCESS);
        result.put("reason", statusReason(code));
        call.resolve(result);
    }

    @PluginMethod
    public void authenticate(PluginCall call) {
        int code = BiometricManager.from(getContext()).canAuthenticate(AUTHENTICATORS);
        if (code != BiometricManager.BIOMETRIC_SUCCESS) {
            call.resolve(failure(statusReason(code)));
            return;
        }
        String title = call.getString("title", "Buka Muarakas");
        String subtitle = call.getString("subtitle");
        String cancel = call.getString("cancel", "Pakai PIN");

        getActivity().runOnUiThread(() -> {
            try {
                BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setNegativeButtonText(cancel)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .setConfirmationRequired(false);
                if (subtitle != null && !subtitle.isEmpty()) info.setSubtitle(subtitle);

                BiometricPrompt prompt = new BiometricPrompt(
                    (FragmentActivity) getActivity(),
                    ContextCompat.getMainExecutor(getContext()),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult authResult) {
                            JSObject ok = new JSObject();
                            ok.put("ok", true);
                            call.resolve(ok);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            call.resolve(failure(errorReason(errorCode)));
                        }
                    }
                );
                prompt.authenticate(info.build());
            } catch (RuntimeException e) {
                Logger.error(TAG, "Could not show the biometric prompt", e);
                call.resolve(failure("error"));
            }
        });
    }

    private static JSObject failure(String reason) {
        JSObject result = new JSObject();
        result.put("ok", false);
        result.put("reason", reason);
        return result;
    }

    private static String statusReason(int code) {
        switch (code) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return "ok";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "none";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "unavailable";
            default:
                return "error";
        }
    }

    private static String errorReason(int errorCode) {
        switch (errorCode) {
            case BiometricPrompt.ERROR_USER_CANCELED:
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
            case BiometricPrompt.ERROR_CANCELED:
            case BiometricPrompt.ERROR_TIMEOUT:
                return "cancel";
            case BiometricPrompt.ERROR_LOCKOUT:
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                return "lockout";
            case BiometricPrompt.ERROR_NO_BIOMETRICS:
                return "none";
            case BiometricPrompt.ERROR_HW_NOT_PRESENT:
            case BiometricPrompt.ERROR_HW_UNAVAILABLE:
                return "unavailable";
            default:
                return "error";
        }
    }
}

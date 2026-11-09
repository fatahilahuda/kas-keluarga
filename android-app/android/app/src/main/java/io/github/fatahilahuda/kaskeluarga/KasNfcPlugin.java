package io.github.fatahilahuda.kaskeluarga;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.tech.IsoDep;
import android.nfc.tech.Ndef;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Family invite over NFC, used by the web app through window.KasNfc (see kas-shim.js).
 *
 * Sending: NfcInviteService answers as an NFC tag holding the invite link while sharing is on.
 * Receiving: reader mode while the "Terima lewat NFC" sheet is open, or the system's own tag
 * dispatch (NDEF_DISCOVERED in the manifest) when the phone is simply unlocked.
 * Either way the link reaches the page as a "url" event.
 */
@CapacitorPlugin(name = "KasNfc")
public class KasNfcPlugin extends Plugin {

    private static final String TAG = "KasNfc";
    static final String APP_URL = "https://fatahilahuda.github.io/kas-keluarga/";
    private static final int READER_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V;
    private static final byte[] SELECT_NDEF_APP = { 0x00, (byte) 0xA4, 0x04, 0x00, 0x07, (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01, 0x00 };
    private static final byte[] SELECT_NDEF_FILE = { 0x00, (byte) 0xA4, 0x00, 0x0C, 0x02, (byte) 0xE1, 0x04 };

    private NfcAdapter adapter;
    private volatile boolean sharing;
    private volatile boolean reading;

    @Override
    public void load() {
        adapter = NfcAdapter.getDefaultAdapter(getContext());
        NfcInviteService.setReadListener(() -> notifyListeners("shared", new JSObject()));
        Intent launch = getActivity().getIntent();
        // Reopening from Recents replays the launch intent; that tap was already handled.
        if (launch != null && (launch.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) emitInvite(launch);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        emitInvite(intent);
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (sharing) applyShareMode();
        if (reading) enableReader();
    }

    @Override
    protected void handleOnPause() {
        // Preferred service and reader mode only hold while the activity is in front.
        if (sharing) clearShareMode();
        if (reading && adapter != null) adapter.disableReaderMode(getActivity());
        super.handleOnPause();
    }

    @Override
    protected void handleOnDestroy() {
        NfcInviteService.setMessage(null);
        NfcInviteService.setReadListener(null);
        super.handleOnDestroy();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject result = new JSObject();
        result.put("supported", adapter != null);
        result.put("enabled", adapter != null && adapter.isEnabled());
        result.put("hce", getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION));
        call.resolve(result);
    }

    @PluginMethod
    public void startShare(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !url.startsWith(APP_URL)) {
            call.reject("Not a Muarakas invite link");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("NFC is off");
            return;
        }
        NfcInviteService.setMessage(new NdefMessage(NdefRecord.createUri(url)).toByteArray());
        sharing = true;
        getActivity().runOnUiThread(() -> {
            applyShareMode();
            call.resolve();
        });
    }

    @PluginMethod
    public void stopShare(PluginCall call) {
        NfcInviteService.setMessage(null);
        if (sharing) {
            sharing = false;
            getActivity().runOnUiThread(this::clearShareMode);
        }
        call.resolve();
    }

    @PluginMethod
    public void startRead(PluginCall call) {
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("NFC is off");
            return;
        }
        reading = true;
        getActivity().runOnUiThread(() -> {
            enableReader();
            call.resolve();
        });
    }

    @PluginMethod
    public void stopRead(PluginCall call) {
        reading = false;
        getActivity().runOnUiThread(() -> {
            if (adapter != null) adapter.disableReaderMode(getActivity());
            call.resolve();
        });
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
        if (intent.resolveActivity(getContext().getPackageManager()) == null) intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        getActivity().startActivity(intent);
        call.resolve();
    }

    private ComponentName inviteService() {
        return new ComponentName(getContext(), NfcInviteService.class);
    }

    private void applyShareMode() {
        if (adapter == null) return;
        try {
            // Other apps may claim the same tag id; while Muarakas is in front, ours answers.
            CardEmulation.getInstance(adapter).setPreferredService(getActivity(), inviteService());
        } catch (RuntimeException e) {
            Logger.warn(TAG, "Could not prefer the invite service: " + e.getMessage());
        }
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                // Listen only, so this phone does not also try to read the other phone (or its wallet).
                adapter.setDiscoveryTechnology(getActivity(), NfcAdapter.FLAG_READER_DISABLE, NfcAdapter.FLAG_LISTEN_KEEP);
            } catch (RuntimeException e) {
                Logger.warn(TAG, "Could not switch to listen-only: " + e.getMessage());
            }
        }
    }

    private void clearShareMode() {
        if (adapter == null) return;
        try {
            CardEmulation.getInstance(adapter).unsetPreferredService(getActivity());
        } catch (RuntimeException e) {
            Logger.warn(TAG, "Could not clear the preferred service: " + e.getMessage());
        }
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                adapter.resetDiscoveryTechnology(getActivity());
            } catch (RuntimeException e) {
                Logger.warn(TAG, "Could not reset NFC discovery: " + e.getMessage());
            }
        }
    }

    private void enableReader() {
        if (adapter == null) return;
        Bundle extras = new Bundle();
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        adapter.enableReaderMode(getActivity(), this::onTag, READER_FLAGS, extras);
    }

    private void onTag(Tag tag) {
        NdefMessage message = readNdef(tag);
        if (message == null) return;
        for (NdefRecord record : message.getRecords()) {
            Uri uri = record.toUri();
            if (uri != null && emitUrl(uri.toString())) return;
        }
    }

    private void emitInvite(Intent intent) {
        if (intent == null || !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data != null) emitUrl(data.toString());
    }

    private boolean emitUrl(String url) {
        if (url == null || !url.startsWith(APP_URL)) return false;
        JSObject event = new JSObject();
        event.put("url", url);
        // Kept until the page has subscribed, for taps that launched the app.
        notifyListeners("url", event, true);
        return true;
    }

    private static NdefMessage readNdef(Tag tag) {
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            NdefMessage cached = ndef.getCachedNdefMessage();
            if (cached != null) return cached;
            try {
                ndef.connect();
                return ndef.getNdefMessage();
            } catch (Exception e) {
                Logger.warn(TAG, "NDEF read failed: " + e.getMessage());
            } finally {
                closeQuietly(ndef);
            }
        }
        IsoDep isoDep = IsoDep.get(tag);
        return isoDep != null ? readType4(isoDep) : null;
    }

    /** Fallback for phones whose NDEF detection skips emulated tags: walk the Type 4 file by hand. */
    private static NdefMessage readType4(IsoDep isoDep) {
        try {
            isoDep.connect();
            isoDep.setTimeout(1500);
            if (!isOk(isoDep.transceive(SELECT_NDEF_APP))) return null;
            if (!isOk(isoDep.transceive(SELECT_NDEF_FILE))) return null;
            byte[] head = isoDep.transceive(new byte[] { 0x00, (byte) 0xB0, 0x00, 0x00, 0x02 });
            if (!isOk(head) || head.length < 4) return null;
            int length = ((head[0] & 0xFF) << 8) | (head[1] & 0xFF);
            if (length <= 0 || length > 4096) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int offset = 2;
            while (body.size() < length) {
                int want = Math.min(NfcInviteService.MAX_READ, length - body.size());
                byte[] chunk = isoDep.transceive(new byte[] { 0x00, (byte) 0xB0, (byte) (offset >> 8), (byte) offset, (byte) want });
                if (!isOk(chunk) || chunk.length == 2) return null;
                body.write(chunk, 0, chunk.length - 2);
                offset += chunk.length - 2;
            }
            return new NdefMessage(Arrays.copyOf(body.toByteArray(), length));
        } catch (Exception e) {
            Logger.warn(TAG, "Type 4 read failed: " + e.getMessage());
            return null;
        } finally {
            closeQuietly(isoDep);
        }
    }

    private static boolean isOk(byte[] response) {
        return response != null && response.length >= 2 && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == 0x00;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing to do; the tag left the field.
        }
    }
}

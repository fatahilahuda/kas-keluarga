package io.github.fatahilahuda.kaskeluarga;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import java.util.Arrays;

/**
 * Makes the phone look like an NFC sticker (an NFC Forum Type 4 tag) holding the family invite link,
 * so another phone can read it by touching backs: Android opens Muarakas, iPhones offer the link.
 *
 * The service is registered with the system all the time but only answers while KasNfcPlugin has set
 * a message, that is while "Kirim lewat NFC" is active on the invite sheet.
 */
public class NfcInviteService extends HostApduService {

    private static final byte[] NDEF_AID = { (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01 };
    private static final byte[] OK = { (byte) 0x90, 0x00 };
    private static final byte[] NOT_FOUND = { 0x6A, (byte) 0x82 };
    private static final byte[] WRONG_OFFSET = { 0x6B, 0x00 };
    private static final byte[] UNSUPPORTED = { 0x6D, 0x00 };

    /** Max bytes a reader may ask for in one READ BINARY. */
    static final int MAX_READ = 0x3B;
    private static final int MAX_NDEF_FILE = 0x0400;

    /** Capability Container: mapping 2.0, NDEF file E104, read-only. */
    private static final byte[] CC_FILE = {
        0x00, 0x0F, // CC length
        0x20, // mapping version 2.0
        0x00, (byte) MAX_READ, // MLe
        0x00, 0x34, // MLc
        0x04, 0x06, // NDEF File Control TLV
        (byte) 0xE1, 0x04, // NDEF file id
        (byte) (MAX_NDEF_FILE >> 8), (byte) MAX_NDEF_FILE, // max NDEF file size
        0x00, // read access: granted
        (byte) 0xFF // write access: none
    };

    private static volatile byte[] ndefFile;
    private static volatile Runnable readListener;

    private boolean appSelected;
    private byte[] selectedFile;
    private boolean reported;

    /** Sets the NDEF message to serve, or null to stop answering. */
    static void setMessage(byte[] message) {
        if (message == null) {
            ndefFile = null;
            return;
        }
        if (message.length + 2 > MAX_NDEF_FILE) throw new IllegalArgumentException("NDEF message too long");
        byte[] file = new byte[message.length + 2];
        file[0] = (byte) (message.length >> 8);
        file[1] = (byte) message.length;
        System.arraycopy(message, 0, file, 2, message.length);
        ndefFile = file;
    }

    /** Called once per tap, when a reader has read the whole message. */
    static void setReadListener(Runnable listener) {
        readListener = listener;
    }

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        byte[] file = ndefFile;
        if (file == null || apdu == null || apdu.length < 4) return NOT_FOUND;
        int ins = apdu[1] & 0xFF;
        if (ins == 0xA4) return select(apdu, file);
        if (ins == 0xB0) return readBinary(apdu, file);
        return UNSUPPORTED;
    }

    private byte[] select(byte[] apdu, byte[] file) {
        int p1 = apdu[2] & 0xFF;
        int lc = apdu.length > 4 ? apdu[4] & 0xFF : 0;
        if (apdu.length < 5 + lc) return NOT_FOUND;
        byte[] data = Arrays.copyOfRange(apdu, 5, 5 + lc);
        if (p1 == 0x04) {
            appSelected = Arrays.equals(data, NDEF_AID);
            selectedFile = null;
            reported = false;
            return appSelected ? OK : NOT_FOUND;
        }
        if (p1 == 0x00 && appSelected && lc == 2 && data[0] == (byte) 0xE1) {
            if (data[1] == 0x03) {
                selectedFile = CC_FILE;
                return OK;
            }
            if (data[1] == 0x04) {
                selectedFile = file;
                return OK;
            }
        }
        return NOT_FOUND;
    }

    private byte[] readBinary(byte[] apdu, byte[] file) {
        byte[] current = selectedFile;
        if (current == null) return NOT_FOUND;
        int offset = ((apdu[2] & 0x7F) << 8) | (apdu[3] & 0xFF);
        if (offset > current.length) return WRONG_OFFSET;
        int le = apdu.length > 4 ? apdu[4] & 0xFF : 0;
        if (le == 0) le = 256;
        int n = Math.min(le, current.length - offset);
        byte[] response = new byte[n + 2];
        System.arraycopy(current, offset, response, 0, n);
        response[n] = (byte) 0x90;
        response[n + 1] = 0x00;
        if (current == file && offset + n >= file.length && offset + n > 2 && !reported) {
            reported = true;
            Runnable listener = readListener;
            if (listener != null) listener.run();
        }
        return response;
    }

    @Override
    public void onDeactivated(int reason) {
        appSelected = false;
        selectedFile = null;
    }
}

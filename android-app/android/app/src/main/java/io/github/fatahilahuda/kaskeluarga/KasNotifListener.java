package io.github.fatahilahuda.kaskeluarga;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Tangkap notifikasi bank": when a bank or e-wallet app posts a payment notification, offer a
 * one-tap "Catat?" that opens the quick popup with the amount and merchant filled in.
 *
 * Only the packages below are read; everything else is ignored. Nothing is recorded on its own
 * and nothing leaves the phone. The component is disabled unless the module is on (KasNative.notifSet).
 */
public class KasNotifListener extends NotificationListenerService {

    static final String CHANNEL = "tawaran_catat";

    /** Package prefixes of Indonesian banks and e-wallets. */
    static final String[] SOURCES = {
        "com.bca", "id.co.bca", "com.bcadigital.blu", "id.bmri.livin", "id.co.bankmandiri", "id.co.bri", "src.com.bni",
        "com.bni", "co.id.bankbsi", "com.bsm.", "id.co.cimbniaga", "net.myinfosys.PermataMobileX", "com.jago",
        "id.co.bankbkemobile", "com.btpn.dc", "com.btpn.", "com.gojek", "ovo.id", "id.dana", "com.shopee.id",
        "com.telkom.mwallet", "id.flip", "com.danamon", "id.co.ocbc", "com.maybank", "id.co.btn", "com.panin",
        "com.allobank", "id.superbank", "com.krom", "com.neobank", "id.co.bankraya"
    };

    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:rp\\.?|idr)\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]+)(?:[.,][0-9]{2}(?![0-9]))?");
    private static final Pattern SKIP = Pattern.compile("(?i)\\b(otp|kode verifikasi|kode rahasia|promo|voucher|diskon|dapatkan|hingga|tagihan kartu kredit anda|jatuh tempo|top ?up|isi saldo|penarikan gagal|gagal|dibatalkan)\\b");
    private static final Pattern MONEY_EVENT = Pattern.compile("(?i)(berhasil|sukses|pembayaran|bayar|transfer|debit|kredit|diterima|masuk|menerima|terima|transaksi|tarik tunai|qris|belanja|pembelian)");
    private static final Pattern INCOMING = Pattern.compile("(?i)(diterima|dana masuk|uang masuk|transfer masuk|kredit|menerima|kamu terima|anda terima|incoming|refund|pengembalian dana)");
    private static final Pattern PARTY = Pattern.compile("(?i)(?:\\bdi merchant|\\bdi|\\bke|\\bkepada|\\bdari|\\buntuk)\\s+([A-Za-z0-9&'][A-Za-z0-9&'. -]{1,30}?)(?=\\s+(?:sebesar|senilai|rp|idr|pada|tgl|tanggal|berhasil|telah|sudah|via|dengan|ref)\\b|[.,:;!\\n]|$)");

    private final Map<String, Long> seen = new HashMap<>();

    static boolean isSource(String pkg) {
        for (String s : SOURCES) if (pkg.startsWith(s)) return true;
        return false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (RuntimeException ignored) {
            // A notification we cannot read is simply not offered.
        }
    }

    private void handle(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName()) || !isSource(pkg) || sbn.isOngoing()) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle ex = n.extras;
        String text = (str(ex.getCharSequence(Notification.EXTRA_TITLE)) + ". " + str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
            + ". " + str(ex.getCharSequence(Notification.EXTRA_TEXT))).replace('\n', ' ');
        Offer o = parse(text);
        if (o == null) return;

        String key = pkg + "|" + o.amount + "|" + o.party;
        long now = System.currentTimeMillis();
        Long prev = seen.get(key);
        if (prev != null && now - prev < 15 * 60_000L) return; // banks often post the same payment twice
        seen.put(key, now);
        if (seen.size() > 50) seen.clear();
        offer(pkg, o);
    }

    static final class Offer {
        long amount;
        boolean in;
        String party = "";
    }

    /** "Pembayaran QRIS Rp45.000 ke INDOMARET SUDIRMAN berhasil" → 45000, out, "Indomaret Sudirman". */
    static Offer parse(String text) {
        if (SKIP.matcher(text).find() || !MONEY_EVENT.matcher(text).find()) return null;
        Matcher m = AMOUNT.matcher(text);
        if (!m.find()) return null;
        long amount;
        try {
            amount = Long.parseLong(m.group(1).replaceAll("[.,]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount < 1000 || amount > 2_000_000_000L) return null;
        Offer o = new Offer();
        o.amount = amount;
        o.in = INCOMING.matcher(text).find();
        Matcher p = PARTY.matcher(text);
        while (p.find()) {
            String s = p.group(1).trim();
            if (s.matches("(?i)(rekening|akun|anda|kamu|sini|bawah|aplikasi).*")) continue;
            o.party = title(s);
            break;
        }
        return o;
    }

    private static String title(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private void offer(String pkg, Offer o) {
        if (!Reminders.canPost(this)) return;
        Reminders.channels(this);
        String rp = "Rp " + NumberFormat.getIntegerInstance(new Locale("id", "ID")).format(o.amount);
        String app;
        try {
            app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            app = "bank";
        }
        String q = (o.party.isEmpty() ? (o.in ? "uang masuk" : "bayar") : (o.in ? "dari " : "") + o.party) + " " + o.amount;
        Intent i = new Intent(this, QuickAddActivity.class)
            .putExtra(QuickAddActivity.EXTRA_MODE, "text")
            .putExtra(QuickAddActivity.EXTRA_Q, q)
            .putExtra(QuickAddActivity.EXTRA_TY, o.in ? "in" : "out")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int id = (int) (System.currentTimeMillis() & 0x7fffffff);
        PendingIntent pi = PendingIntent.getActivity(this, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = "Catat " + rp + (o.party.isEmpty() ? "" : (o.in ? " dari " : " di ") + o.party) + "?";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1000 + (id % 100000), new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF1D7781)
            .setContentTitle(title)
            .setContentText((o.in ? "Uang masuk" : "Pembayaran") + " dari notifikasi " + app + ". Ketuk untuk mencatat.")
            .setContentIntent(pi)
            .addAction(0, "Catat", pi)
            .setAutoCancel(true)
            .setTimeoutAfter(6 * 3600_000L)
            .build());
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString();
    }
}

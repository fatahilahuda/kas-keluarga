package io.github.fatahilahuda.kaskeluarga;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Aktivitas & notifikasi": while the app is closed, checks the family's sync server about every
 * 15 minutes for changes made by the other person and posts one notification for them.
 *
 * The web app hands over what it needs (KasNative.activitySet): server address, family code, who
 * "me" is, member names, and how far it has already synced, so nothing seen in the app is
 * announced again. It reads the same records the app syncs: the change log written by the other
 * phone, plus catatan added from a watch or an older app that writes no log.
 */
public class ActivityWatch extends JobService {

    static final String PREFS = "kas_activity";
    static final String CHANNEL = "aktivitas";
    private static final int JOB_ID = 4801, NOTIF_ID = 4801;

    /** Saves the settings from the web app and starts or stops the periodic check. */
    static void set(Context ctx, String json) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        try {
            JSONObject o = new JSONObject(json);
            if (o.optString("code").isEmpty()) {
                p.edit().remove("cfg").apply();
                js.cancel(JOB_ID);
                return;
            }
            // The app has synced up to o.rev; never announce anything before that.
            long rev = Math.max(p.getLong("rev", 0), o.optLong("rev"));
            p.edit().putString("cfg", json).putLong("rev", rev).apply();
        } catch (Exception e) {
            return;
        }
        if (js.getPendingJob(JOB_ID) == null) {
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, ActivityWatch.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                check(getApplicationContext());
            } catch (Exception ignored) {
                // Offline or server busy: the next run tries again.
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static void check(Context ctx) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString("cfg", null);
        if (raw == null) return;
        JSONObject cfg = new JSONObject(raw);
        String me = cfg.optString("me");
        JSONObject names = cfg.optJSONObject("names");
        long rev = p.getLong("rev", 0), seen = cfg.optLong("seen");
        List<String> lines = new ArrayList<>();
        String who = null;
        for (int page = 0; page < 10; page++) {
            JSONArray rows = pull(cfg, rev);
            if (rows.length() == 0) break;
            Set<String> logged = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject d = rows.getJSONObject(i).optJSONObject("data");
                if (d != null && "log".equals(d.optString("t"))) logged.add(d.optString("tid"));
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                rev = Math.max(rev, r.optLong("rev"));
                JSONObject d = r.optJSONObject("data");
                if (d == null) continue;
                String kind = r.optString("kind"), by = d.optString("by");
                if (by.isEmpty() || by.equals(me)) continue;
                String line = null;
                // "la…" records are written by a phone on behalf of a watch catatan; the catatan itself is handled below.
                if ("x".equals(kind) && "log".equals(d.optString("t")) && d.optLong("at") > seen && !r.optString("id").startsWith("la")) line = logLine(d);
                else if ("tx".equals(kind) && !d.optBoolean("sealed") && !logged.contains(d.optString("id"))
                    && d.optLong("ts") > System.currentTimeMillis() - 2 * 86_400_000L && d.optLong("ts") == d.optLong("u", d.optLong("ts")))
                    line = "mencatat " + label(d) + " " + rp(d.optLong("amount"));
                if (line == null) continue;
                who = names != null ? names.optString(by, "Pasangan") : "Pasangan";
                lines.add(line);
            }
            if (rows.length() < 1000) break;
        }
        p.edit().putLong("rev", rev).apply();
        if (lines.isEmpty() || KasState.visible || !Reminders.canPost(ctx)) return;
        post(ctx, who, lines, cfg.optBoolean("lock"));
    }

    private static JSONArray pull(JSONObject cfg, long since) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.getString("url") + "/rest/v1/rpc/kas_pull").openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("apikey", cfg.getString("key"));
        c.setRequestProperty("Authorization", "Bearer " + cfg.getString("key"));
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(new JSONObject().put("p_code", cfg.getString("code")).put("p_since", since).toString().getBytes(StandardCharsets.UTF_8));
        }
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String s = b.toString("UTF-8").trim();
            return s.isEmpty() || s.equals("null") ? new JSONArray() : new JSONArray(s);
        } finally {
            c.disconnect();
        }
    }

    private static String logLine(JSONObject d) {
        String op = d.optString("op");
        if ("bulk".equals(op)) return "mengubah " + d.optInt("n") + " catatan sekaligus";
        JSONObject s = d.optJSONObject("snap");
        if (s == null) s = new JSONObject();
        String what = label(s);
        if ("add".equals(op)) return "mencatat " + what + " " + rp(s.optLong("amount"));
        if ("del".equals(op)) return "menghapus " + what + " " + rp(s.optLong("amount"));
        JSONObject ch = d.optJSONObject("ch");
        JSONArray amt = ch == null ? null : ch.optJSONArray("amount");
        if (amt != null && amt.length() == 2) return "mengubah " + what + ": " + rp(amt.optLong(0)) + " → " + rp(amt.optLong(1));
        return "mengubah " + what;
    }

    private static String label(JSONObject s) {
        String d = s.optString("desc");
        if (!d.isEmpty()) return d.length() > 40 ? d.substring(0, 38) + "…" : d;
        return "tf".equals(s.optString("type")) ? "pindah saldo" : "catatan";
    }

    private static String rp(long v) {
        return "Rp " + NumberFormat.getIntegerInstance(new Locale("id", "ID")).format(v);
    }

    private static void post(Context ctx, String who, List<String> lines, boolean locked) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Perubahan dari pasangan", NotificationManager.IMPORTANCE_DEFAULT));
        }
        // With the app lock on, say that something changed but not what, like the widget does.
        String title = lines.size() == 1 && !locked ? who + " " + lines.get(0) : lines.size() + " perubahan dari " + who;
        String text = locked ? "Buka Muarakas untuk melihat." : lines.size() == 1 ? "Ketuk untuk membuka riwayat." : lines.get(lines.size() - 1);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("tab", "log");
        PendingIntent pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF1D7781)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true);
        if (!locked && lines.size() > 1) {
            NotificationCompat.InboxStyle st = new NotificationCompat.InboxStyle().setBigContentTitle(title);
            for (int i = Math.max(0, lines.size() - 6); i < lines.size(); i++) st.addLine(who + " " + lines.get(i));
            b.setStyle(st);
        }
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
    }
}

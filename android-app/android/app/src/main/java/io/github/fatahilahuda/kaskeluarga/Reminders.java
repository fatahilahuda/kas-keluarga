package io.github.fatahilahuda.kaskeluarga;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.Calendar;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bill reminders (3 days before and on the due day) and the Sunday-evening weekly summary.
 *
 * The web app owns the data: every time it saves, it hands over the bill list and this week's
 * summary text (KasNative.reminders). This receiver only wakes twice a day, at 08:00 and 19:00,
 * and turns that snapshot into notifications. Nothing leaves the phone.
 */
public class Reminders extends BroadcastReceiver {

    static final String PREFS = "kas_reminders";
    static final String CHANNEL_BILL = "tagihan";
    static final String CHANNEL_WEEK = "rangkuman";
    private static final int MORNING = 8, EVENING = 19;

    /** Called from the web app with {bills:[{id,n,amt,day,paid}], week:{mon,title,body}|null}. */
    static void save(Context ctx, String json) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (json.equals(p.getString("data", ""))) return;
        p.edit().putString("data", json).apply();
        schedule(ctx);
    }

    static boolean wanted(Context ctx) {
        try {
            JSONObject o = new JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "{}"));
            JSONArray b = o.optJSONArray("bills");
            return (b != null && b.length() > 0) || o.optJSONObject("week") != null;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Next wake-up at 08:00 or 19:00, whichever comes first. Inexact is fine for reminders. */
    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, Reminders.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (!wanted(ctx)) return;
        Calendar now = Calendar.getInstance(), next = (Calendar) now.clone();
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        int h = now.get(Calendar.HOUR_OF_DAY);
        if (h < MORNING) next.set(Calendar.HOUR_OF_DAY, MORNING);
        else if (h < EVENING) next.set(Calendar.HOUR_OF_DAY, EVENING);
        else {
            next.add(Calendar.DAY_OF_MONTH, 1);
            next.set(Calendar.HOUR_OF_DAY, MORNING);
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            check(ctx);
        } catch (JSONException | RuntimeException ignored) {
            // A bad snapshot must never stop the next alarm from being set.
        }
        schedule(ctx);
    }

    private static void check(Context ctx) throws JSONException {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject o = new JSONObject(p.getString("data", "{}"));
        Calendar now = Calendar.getInstance();
        String today = String.format(Locale.US, "%04d-%02d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        String month = today.substring(0, 7);
        int hour = now.get(Calendar.HOUR_OF_DAY);

        // Bills: once a day, in the morning slot (or the first wake after it)
        JSONArray bills = o.optJSONArray("bills");
        if (bills != null && hour >= MORNING && !today.equals(p.getString("billDay", ""))) {
            p.edit().putString("billDay", today).apply();
            int last = now.getActualMaximum(Calendar.DAY_OF_MONTH), d = now.get(Calendar.DAY_OF_MONTH);
            for (int i = 0; i < bills.length(); i++) {
                JSONObject b = bills.getJSONObject(i);
                if (month.equals(b.optString("paid"))) continue;
                int due = Math.min(b.optInt("day", 1), last), left = due - d;
                String title;
                if (left == 3) title = b.optString("n") + " jatuh tempo 3 hari lagi";
                else if (left == 0) title = b.optString("n") + " jatuh tempo hari ini";
                else if (left == -1) title = b.optString("n") + " lewat jatuh tempo kemarin";
                else continue;
                String amt = b.optString("amt");
                post(ctx, CHANNEL_BILL, 100 + (b.optString("id").hashCode() & 0xffff), title,
                    (amt.isEmpty() ? "" : amt + ". ") + "Ketuk untuk mencatat pembayarannya.");
            }
        }

        // Weekly summary: Sunday evening, once per week
        JSONObject w = o.optJSONObject("week");
        if (w != null && now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && hour >= EVENING && !today.equals(p.getString("weekDay", ""))) {
            p.edit().putString("weekDay", today).apply();
            Calendar mon = (Calendar) now.clone();
            mon.add(Calendar.DAY_OF_MONTH, -6);
            String monIso = String.format(Locale.US, "%04d-%02d-%02d", mon.get(Calendar.YEAR), mon.get(Calendar.MONTH) + 1, mon.get(Calendar.DAY_OF_MONTH));
            boolean fresh = monIso.equals(w.optString("mon"));
            post(ctx, CHANNEL_WEEK, 90,
                fresh ? w.optString("title") : "Rangkuman minggu ini sudah siap",
                fresh ? w.optString("body") : "Buka Muarakas untuk melihat ke mana uangmu minggu ini.");
        }
    }

    static boolean canPost(Context ctx) {
        return Build.VERSION.SDK_INT < 33
            || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void channels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_BILL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_BILL, "Pengingat tagihan", NotificationManager.IMPORTANCE_DEFAULT));
        if (nm.getNotificationChannel(CHANNEL_WEEK) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_WEEK, "Rangkuman mingguan", NotificationManager.IMPORTANCE_LOW));
        if (nm.getNotificationChannel(KasNotifListener.CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(KasNotifListener.CHANNEL, "Tawaran catat dari bank", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static void post(Context ctx, String channel, int id, String title, String body) {
        if (!canPost(ctx)) return;
        channels(ctx);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(id, new NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF1D7781)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build());
    }
}

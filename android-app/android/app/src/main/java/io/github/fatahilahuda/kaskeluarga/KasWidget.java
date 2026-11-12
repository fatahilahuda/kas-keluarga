package io.github.fatahilahuda.kaskeluarga;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONObject;

/**
 * Home screen widget: what is left of this month's budget, plus Suara and Ketik buttons that
 * open the quick-add popup. The numbers come from the web app (KasNative.widget) each time it
 * saves, so the widget never needs the network or the family code.
 */
public class KasWidget extends AppWidgetProvider {

    private static final String PREFS = "kas_widget";
    private static final String KEY = "snapshot";

    static void save(Context ctx, String json) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (json != null && json.equals(p.getString(KEY, null))) return;
        p.edit().putString(KEY, json).apply();
        updateAll(ctx);
    }

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, KasWidget.class));
        for (int id : ids) m.updateAppWidget(id, build(ctx));
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(ctx));
    }

    private static PendingIntent activity(Context ctx, int req, Intent i) {
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_kas);
        JSONObject s = null;
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            if (raw != null) s = new JSONObject(raw);
        } catch (Exception ignored) {}

        boolean show = s != null && s.optBoolean("ok") && !s.optBoolean("locked");
        if (show) {
            v.setTextViewText(R.id.w_label, s.optString("label"));
            v.setTextViewText(R.id.w_big, s.optString("big"));
            v.setTextViewText(R.id.w_sub, s.optString("sub"));
            String tone = s.optString("tone", "ok");
            int pct = s.optInt("pct");
            int[] bars = { R.id.w_bar_ok, R.id.w_bar_warn, R.id.w_bar_bad };
            String[] tones = { "ok", "warn", "bad" };
            for (int i = 0; i < 3; i++) {
                boolean on = tones[i].equals(tone);
                v.setViewVisibility(bars[i], on ? View.VISIBLE : View.GONE);
                if (on) v.setProgressBar(bars[i], 100, pct, false);
            }
            v.setTextColor(R.id.w_big, ctx.getColor("bad".equals(tone) && s.optString("big").startsWith("−") ? R.color.w_bad : R.color.w_ink));
            v.setTextColor(R.id.w_sub, ctx.getColor("bad".equals(tone) ? R.color.w_bad : "warn".equals(tone) ? R.color.w_warn : R.color.w_accent));
        } else {
            v.setTextViewText(R.id.w_label, "Muarakas");
            v.setTextViewText(R.id.w_big, "Catat cepat");
            v.setTextViewText(R.id.w_sub, s != null && s.optBoolean("locked") ? "Angka disembunyikan karena kunci aktif" : "Ketuk mikrofon atau keyboard");
            v.setTextColor(R.id.w_big, ctx.getColor(R.color.w_ink));
            v.setTextColor(R.id.w_sub, ctx.getColor(R.color.w_muted));
            v.setViewVisibility(R.id.w_bar_ok, View.GONE);
            v.setViewVisibility(R.id.w_bar_warn, View.GONE);
            v.setViewVisibility(R.id.w_bar_bad, View.GONE);
        }

        v.setOnClickPendingIntent(R.id.w_info, activity(ctx, 30, new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        v.setOnClickPendingIntent(R.id.w_mic, activity(ctx, 31, QuickAddActivity.intent(ctx, "voice")));
        v.setOnClickPendingIntent(R.id.w_kbd, activity(ctx, 32, QuickAddActivity.intent(ctx, "text")));
        return v;
    }
}

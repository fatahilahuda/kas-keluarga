package io.github.fatahilahuda.kaskeluarga;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * "Gelembung melayang": a small round mic button that stays on the edge of the screen above
 * other apps. Tap: catat dengan suara. Long-press: catat dengan mengetik. Drag to move; it snaps
 * to the nearest side. Runs as a foreground service so Android does not kill it.
 */
public class BubbleService extends Service {

    static final String PREFS = "kas_quick";
    static final String WANT = "bubble";
    private static final String CHANNEL = "bubble";
    private static final String ACTION_OFF = "io.github.fatahilahuda.muarakas.BUBBLE_OFF";
    private static BubbleService running;

    private WindowManager wm;
    private FrameLayout bubble;
    private WindowManager.LayoutParams lp;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---------- control from the app ----------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean wanted(Context c) {
        return prefs(c).getBoolean(WANT, false);
    }

    /** Starts the bubble if the user turned it on and the overlay permission is granted. */
    static void sync(Context c) {
        boolean go = wanted(c) && Settings.canDrawOverlays(c);
        if (go && running == null) {
            ContextCompat.startForegroundService(c, new Intent(c, BubbleService.class));
        } else if (!go && running != null) {
            running.stopSelf();
        }
    }

    static boolean isRunning() {
        return running != null;
    }

    /** The popup hides the bubble while it is open, so it never covers the sheet. */
    static void setHidden(boolean hidden) {
        BubbleService s = running;
        if (s != null && s.bubble != null) s.bubble.setVisibility(hidden ? View.GONE : View.VISIBLE);
    }

    // ---------- service ----------

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        startInForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_OFF.equals(intent.getAction())) {
            prefs(this).edit().putBoolean(WANT, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Gelembung Catat cepat", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Menjaga tombol catat cepat tetap tampil di layar");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent off = PendingIntent.getService(this, 41, new Intent(this, BubbleService.class).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent voice = PendingIntent.getActivity(this, 42, QuickAddActivity.intent(this, "voice"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_mic)
            .setContentTitle("Gelembung Catat cepat aktif")
            .setContentText("Ketuk untuk catat dengan suara")
            .setContentIntent(voice)
            .addAction(0, "Matikan", off)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        ServiceCompat.startForeground(this, 40, n, type);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        int size = dp(56);
        bubble = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(ContextCompat.getColor(this, R.color.w_accent));
        bg.setStroke(dp(2), 0x33FFFFFF);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_w_mic);
        icon.setBackground(bg);
        icon.setPadding(dp(15), dp(15), dp(15), dp(15));
        icon.setElevation(dp(6));
        icon.setContentDescription("Catat cepat");
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(size, size);
        ip.setMargins(dp(6), dp(6), dp(6), dp(6));
        bubble.addView(icon, ip);
        bubble.setAlpha(0.92f);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(size + dp(12), size + dp(12), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        SharedPreferences p = prefs(this);
        lp.x = p.getInt("bx", dm.widthPixels - lp.width);
        lp.y = p.getInt("by", (int) (dm.heightPixels * 0.62f));

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final long longPress = ViewConfiguration.getLongPressTimeout();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved, longFired;
            final Runnable onLong = () -> {
                longFired = true;
                bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                open("text");
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        longFired = false;
                        bubble.setAlpha(1f);
                        main.postDelayed(onLong, longPress);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > slop) {
                            moved = true;
                            main.removeCallbacks(onLong);
                        }
                        if (moved) {
                            lp.x = startX + Math.round(dx);
                            lp.y = startY + Math.round(dy);
                            wm.updateViewLayout(bubble, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(onLong);
                        bubble.setAlpha(0.92f);
                        if (moved) snap();
                        else if (!longFired && e.getActionMasked() == MotionEvent.ACTION_UP) open("voice");
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(bubble, lp);
    }

    private void snap() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int target = lp.x + lp.width / 2 < dm.widthPixels / 2 ? 0 : dm.widthPixels - lp.width;
        lp.y = Math.max(dp(40), Math.min(lp.y, dm.heightPixels - lp.height - dp(40)));
        ValueAnimator a = ValueAnimator.ofInt(lp.x, target);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            if (bubble == null) return;
            lp.x = (int) an.getAnimatedValue();
            wm.updateViewLayout(bubble, lp);
        });
        a.start();
        prefs(this).edit().putInt("bx", target).putInt("by", lp.y).apply();
    }

    private void open(String mode) {
        startActivity(QuickAddActivity.intent(this, mode));
    }

    @Override
    public void onDestroy() {
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (RuntimeException ignored) {}
        }
        bubble = null;
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

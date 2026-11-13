package io.github.fatahilahuda.kaskeluarga;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Build;
import android.os.SystemClock;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;

/**
 * The running glow around the screen edge behind the catat cepat card, in Muarakas colours.
 * Drawn as many thin rounded-rect rings whose opacity falls to zero going inward, so there is
 * no hard line: a slow multicolour base, plus a bright band running around the edge. While
 * listening it reaches further in with the voice level; it flashes green when saved.
 */
public class GlowView extends View {

    private static final int[] BASE = { 0xFF3BB3AB, 0xFF7F96FF, 0xFFA496F0, 0xFFE0C25A, 0xFF4FC1B4, 0xFF3BB3AB };
    private static final int[] BAND = { 0x003BB3AB, 0x003BB3AB, 0xFF3BB3AB, 0xFF7F96FF, 0xFFC9B8FF, 0xFFFFE8A3, 0x00FFE8A3 };
    private static final int[] BASE_OK = { 0xFF4CC48E, 0xFF3BB3AB, 0xFFB6F0D6, 0xFF4CC48E };
    private static final int[] BAND_OK = { 0x004CC48E, 0x004CC48E, 0xFF4CC48E, 0xFFB6F0D6, 0xFF3BB3AB, 0xFFE6FFF3, 0x00E6FFF3 };
    private static final float[] BAND_POS = { 0f, 0.53f, 0.67f, 0.78f, 0.87f, 0.93f, 1f };

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint runPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF r = new RectF();
    private final long t0 = SystemClock.uptimeMillis();
    private SweepGradient baseShader, runShader;
    private int shaderW, shaderH;
    private boolean saved;
    private String state = "on";
    private float level, shown, cornerPx;

    public GlowView(Context c) {
        super(c);
        basePaint.setStyle(Paint.Style.STROKE);
        runPaint.setStyle(Paint.Style.STROKE);
        cornerPx = dp(44);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setState(String s) {
        boolean wasSaved = saved;
        state = s == null ? "on" : s;
        saved = "saved".equals(state);
        if (saved != wasSaved) shaderW = 0;
        invalidate();
    }

    /** rmsdB from the speech recognizer. */
    public void setVoiceLevel(float rmsdB) {
        level = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f));
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 31) {
            RoundedCorner rc = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
            if (rc != null && rc.getRadius() > 0) cornerPx = rc.getRadius();
        }
        return super.onApplyWindowInsets(insets);
    }

    private void shaders(int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        baseShader = new SweepGradient(cx, cy, saved ? BASE_OK : BASE, null);
        runShader = new SweepGradient(cx, cy, saved ? BAND_OK : BAND, BAND_POS);
        basePaint.setShader(baseShader);
        runPaint.setShader(runShader);
        shaderW = w;
        shaderH = h;
    }

    private float lvSmooth;

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (w != shaderW || h != shaderH) shaders(w, h);
        long t = SystemClock.uptimeMillis() - t0;
        boolean listen = "listen".equals(state);
        shown = Math.min(1f, shown + 0.06f);
        lvSmooth += ((listen ? level : 0f) - lvSmooth) * 0.25f;

        float cx = w / 2f, cy = h / 2f;
        float runPeriod = listen ? 1700f : saved ? 1200f : 2800f;
        m.setRotate((t % (long) runPeriod) / runPeriod * 360f, cx, cy);
        runShader.setLocalMatrix(m);
        Matrix mb = new Matrix();
        mb.setRotate((t % 8000L) / 8000f * 360f, cx, cy);
        baseShader.setLocalMatrix(mb);

        float depth = dp(listen ? 40f + lvSmooth * 34f : 32f);
        int steps = 30;
        float step = depth / steps;
        basePaint.setStrokeWidth(step * 2.4f);
        runPaint.setStrokeWidth(step * 2.4f);
        for (int k = 0; k < steps; k++) {
            float f = 1f - k / (float) steps;
            float a = f * f * shown;
            float inset = step * (k + 0.5f);
            r.set(inset, inset, w - inset, h - inset);
            float rr = Math.max(cornerPx - inset, 0f);
            basePaint.setAlpha((int) (255 * a * (listen ? 0.26f : 0.2f)));
            c.drawRoundRect(r, rr, rr, basePaint);
            runPaint.setAlpha((int) (255 * a * 0.46f));
            c.drawRoundRect(r, rr, rr, runPaint);
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        basePaint.setShader(null);
        runPaint.setShader(null);
    }

    static int dimColor() {
        return Color.argb(0x38, 0, 0, 0);
    }
}

package io.github.fatahilahuda.kaskeluarga;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Catat di Muarakas" in the phone's share sheet. Takes what was shared (text, a list, a photo or
 * screenshot of a receipt, a PDF transfer receipt), hands it to the web app and opens it.
 * No screen of its own: it reads the shared item, then the main app takes over.
 *
 * The web app decides what it is: a transfer or shop receipt fills the Catat form (images go
 * through the same receipt reader as "Scan nota"), a list can go to List belanja.
 */
public class ShareActivity extends Activity {

    private static final int MAX_SIDE = 2000;
    private static volatile String pending;

    /** The shared item as JSON, once; null when there is nothing waiting. */
    static synchronized String take() {
        String p = pending;
        pending = null;
        return p;
    }

    static boolean waiting() {
        return pending != null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        Intent in = getIntent();
        new Thread(() -> {
            String json = read(in);
            runOnUiThread(() -> {
                if (json != null) {
                    pending = json;
                    startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("share", true));
                }
                finish();
                overridePendingTransition(0, 0);
            });
        }).start();
    }

    private String read(Intent in) {
        try {
            JSONObject o = new JSONObject();
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
            Uri stream = null;
            if (Intent.ACTION_SEND.equals(in.getAction())) stream = in.getParcelableExtra(Intent.EXTRA_STREAM);
            else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
                ArrayList<Uri> all = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (all != null && !all.isEmpty()) stream = all.get(0); // one receipt at a time
            }
            String type = in.getType() == null ? "" : in.getType();
            if (stream != null) {
                String mime = getContentResolver().getType(stream);
                if (mime == null) mime = type;
                String img = null;
                if (mime.startsWith("image/")) img = image(stream);
                else if (mime.equals("application/pdf")) img = pdf(stream);
                else if (mime.startsWith("text/")) text = readText(stream);
                if (img != null) o.put("kind", "image").put("image", img).put("pdf", mime.equals("application/pdf"));
            }
            if (!o.has("kind")) {
                if (text == null || text.toString().trim().isEmpty()) return null;
                o.put("kind", "text");
            }
            if (text != null) o.put("text", text.toString());
            if (subject != null) o.put("subject", subject);
            return o.toString();
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Photo or screenshot, scaled to at most 2000 px so it crosses into the page quickly. */
    private String image(Uri uri) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            try (InputStream s = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(s, null, b); }
            int sample = 1;
            while (Math.max(b.outWidth, b.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm;
            try (InputStream s = getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(s, null, o); }
            return bm == null ? null : dataUrl(fit(bm));
        } catch (Exception e) {
            return null;
        }
    }

    /** First page of a PDF receipt (BCA, Livin and others share transfer proofs as PDF). */
    private String pdf(Uri uri) {
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
             PdfRenderer r = new PdfRenderer(fd)) {
            if (r.getPageCount() == 0) return null;
            try (PdfRenderer.Page p = r.openPage(0)) {
                float scale = Math.min(3f, MAX_SIDE / (float) Math.max(p.getWidth(), p.getHeight()));
                Bitmap bm = Bitmap.createBitmap(Math.round(p.getWidth() * scale), Math.round(p.getHeight() * scale), Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return dataUrl(bm);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String readText(Uri uri) {
        try (InputStream s = getContentResolver().openInputStream(uri)) {
            byte[] buf = new byte[64 * 1024];
            int n = s.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fit(Bitmap bm) {
        int big = Math.max(bm.getWidth(), bm.getHeight());
        if (big <= MAX_SIDE) return bm;
        float s = MAX_SIDE / (float) big;
        return Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * s), Math.round(bm.getHeight() * s), true);
    }

    private static String dataUrl(Bitmap bm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}

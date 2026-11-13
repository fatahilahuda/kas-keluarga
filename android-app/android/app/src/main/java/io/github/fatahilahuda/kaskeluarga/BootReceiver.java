package io.github.fatahilahuda.kaskeluarga;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the floating bubble back after a restart or an app update, if the user had it on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            BubbleService.sync(ctx);
        } catch (RuntimeException ignored) {
            // Starting a foreground service can be refused in rare states; the app retries on next open.
        }
    }
}

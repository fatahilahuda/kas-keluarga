package io.github.fatahilahuda.kaskeluarga;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an app update: reminder alarms again, and the floating bubble if the user had it on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Reminders.schedule(ctx);
        try {
            BubbleService.sync(ctx);
        } catch (RuntimeException ignored) {
            // Starting a foreground service can be refused in rare states; the app retries on next open.
        }
    }
}

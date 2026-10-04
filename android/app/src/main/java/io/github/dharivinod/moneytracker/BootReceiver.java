package io.github.dharivinod.moneytracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the shake listener after the phone restarts or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!ShakeService.isEnabled(c)) return;
        try {
            ShakeService.sync(c);
        } catch (RuntimeException e) {
            // The system refused a background start; the listener starts the next time the app is opened.
        }
    }
}

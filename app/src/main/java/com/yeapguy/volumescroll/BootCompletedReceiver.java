package com.yeapguy.volumescroll;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the step counter service after a reboot (always on with a pedometer). */
public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!StepStatsStore.hasPedometer(context)) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            context.startForegroundService(new Intent(context, StepCounterService.class));
        } else {
            context.startService(new Intent(context, StepCounterService.class));
        }
    }
}

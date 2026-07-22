package com.weizhi.caps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.weizhi.caps.REMIND";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        ReminderScheduler.show(context, intent.getStringExtra("title"), intent.getStringExtra("body"));
    }
}

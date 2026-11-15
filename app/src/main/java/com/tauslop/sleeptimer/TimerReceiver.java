package com.tauslop.sleeptimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the alarm and the notification actions. Only explicit intents reach it. */
public final class TimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case Timer.ACTION_FIRE:
                PendingResult pending = goAsync(); // keep alive during the fade
                Timer.fire(c, pending::finish);
                break;
            case Timer.ACTION_EXTEND:
                Timer.extend(c);
                break;
            case Timer.ACTION_CANCEL:
                Timer.cancel(c);
                break;
        }
    }
}

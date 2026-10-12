package com.tinyhack.ssh.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Non-exported notification actions which do not bring the terminal to the foreground. */
public final class KittyNotificationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        KittyNotificationManager.receive(intent);
    }
}

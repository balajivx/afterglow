package com.pixelcontrols.battery;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** "It's fine" on a Battery watch notification: never report that app again (undo in the app). */
public class BatteryWatchReceiver extends BroadcastReceiver {
    static final String ACTION_FINE = "com.pixelcontrols.battery.FINE";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!ACTION_FINE.equals(i.getAction())) return;
        String pkg = i.getStringExtra("pkg");
        if (pkg == null) return;
        BatteryWatch.setMuted(c, pkg, true);
        c.getSystemService(NotificationManager.class).cancel(i.getIntExtra("id", 0));
        Toast.makeText(c, BatteryWatch.label(c, pkg) + ": won't be reported again", Toast.LENGTH_SHORT).show();
    }
}

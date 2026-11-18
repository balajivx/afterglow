package com.pixelcontrols.adblock;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification buttons: Pause 10 min / Resume / Turn off. */
public class AdblockActionReceiver extends BroadcastReceiver {
    static final String PAUSE = "com.pixelcontrols.adblock.PAUSE";
    static final String RESUME = "com.pixelcontrols.adblock.RESUME";
    static final String STOP = "com.pixelcontrols.adblock.STOP";

    static PendingIntent pending(Context c, String action) {
        Intent i = new Intent(c, AdblockActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, action.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        AdblockVpnService s = AdblockVpnService.instance;
        if (PAUSE.equals(a)) {
            if (s != null) s.pause();
        } else if (RESUME.equals(a)) {
            if (s != null) s.resumeNow();
        } else if (STOP.equals(a)) {
            Adblock.stop(context);
        }
        Adblock.refreshTile(context);
    }
}

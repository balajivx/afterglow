package com.pixelcontrols;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Opening a screen from a Quick Settings tile. A tile is a background service: a plain startActivity() from it is
 * silently blocked on Android 14+; startActivityAndCollapse() is the allowed way (and collapses the panel).
 */
public final class TileLaunch {
    private TileLaunch() {}

    @SuppressWarnings("deprecation")
    public static void open(TileService t, Intent i, int requestCode) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                t.startActivityAndCollapse(PendingIntent.getActivity(t, requestCode, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                t.startActivityAndCollapse(i);
            }
        } catch (RuntimeException e) {
            Log.w("TileLaunch", "could not open " + i, e);
        }
    }
}

package com.pixelcontrols.photo;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings "DSLR" tile: edits the photo you just took. Android doesn't let a tile see
 * which photo is open on screen, so it takes the newest camera photo, picks the settings that
 * suit it and saves the edited copy.
 */
public class DslrTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.setLabel("DSLR");
        t.setSubtitle("Edit latest photo");
        t.updateTile();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        Intent i = new Intent(this, EnhanceActivity.class)
                .putExtra(EnhanceActivity.EXTRA_LATEST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}

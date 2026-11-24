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
    public void onClick() {   // DSLR is a photo editor: opening its screen is the point
        com.pixelcontrols.TileLaunch.open(this, new Intent(this, EnhanceActivity.class)
                .putExtra(EnhanceActivity.EXTRA_LATEST, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), 1);
    }
}

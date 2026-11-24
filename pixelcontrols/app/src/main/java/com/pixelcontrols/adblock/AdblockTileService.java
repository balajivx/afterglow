package com.pixelcontrols.adblock;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.Locale;

/** Quick Settings tile: tap to turn adblock on/off. Long-press opens the app's settings. */
public class AdblockTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (Adblock.isActive(this)) {
            Adblock.stop(this);
        } else if (Adblock.consentIntent(this) != null) {
            launchToggleActivity();        // first run: Android must show the VPN consent dialog
        } else {
            try {
                Adblock.start(this);
            } catch (IllegalStateException e) {
                launchToggleActivity();    // background-start restriction: go via an activity
            }
        }
        update();
    }

    private void launchToggleActivity() {
        com.pixelcontrols.TileLaunch.open(this, new Intent(this, ToggleAdblockActivity.class)
                .putExtra(ToggleAdblockActivity.EXTRA_FORCE_ON, true), 0);
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Adblock.isActive(this);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Adblock");
        String sub;
        if (!on) sub = "Off";
        else if (Adblock.isPaused()) sub = "Paused";
        else if (Adblock.MODE_DNS.equals(Adblock.mode(this))) sub = "On · Lite";
        else {
            DnsFilterEngine e = AdblockVpnService.currentEngine();
            sub = e == null ? "Starting…" : String.format(Locale.US, "%,d blocked", e.blockedQueries.get());
        }
        t.setSubtitle(sub);
        t.updateTile();
    }
}

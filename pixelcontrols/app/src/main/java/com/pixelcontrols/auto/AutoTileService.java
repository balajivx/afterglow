package com.pixelcontrols.auto;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings "Android Auto" tile. Lit = wired (Wi-Fi turned off by this app while plugged in). Tap: wired ->
 * Wi-Fi back on (wireless); wireless on the car's Android Auto port -> Wi-Fi off (wired). On a charging-only port
 * a tap does nothing (there's no wired Android Auto there). Needs no helper daemon: the app switches Wi-Fi itself
 * (see Car). Ported from AA Switch 2.0.2.
 */
public class AutoTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        show();
        later();
    }

    @Override
    public void onStopListening() {
        main.removeCallbacksAndMessages(null);
    }

    /** Wi-Fi and the plug change a moment after: look again. */
    private void later() {
        main.postDelayed(this::show, 1500);
        main.postDelayed(this::show, 4000);
    }

    @Override
    public void onClick() {
        Car.log(this, "tile tapped · " + Car.state(this));
        if (Car.wifiOffByUs(this)) Car.goWireless(this, true);
        else if (!Car.plugged(this)) toast("Plug the cable into the car's Android Auto port first");
        else if (Car.port(this) == Car.Port.CHARGING) toast("This is the charging port: use the car's Android Auto port for wired");
        else if (!Car.wifiOn(this)) toast("Wi-Fi is already off: unplug and plug the cable back in for wired");
        else if (!Car.goWired(this)) toast("Android didn't let Pixel Controls turn Wi-Fi off");
        show();
        later();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(getApplicationContext(), s, android.widget.Toast.LENGTH_LONG).show();
    }

    private void show() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Android Auto");
        t.setState(Car.wifiOffByUs(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(Car.describe(this));
        t.updateTile();
    }
}

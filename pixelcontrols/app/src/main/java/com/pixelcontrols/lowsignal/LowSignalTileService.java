package com.pixelcontrols.lowsignal;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import com.pixelcontrols.LowSignal;
import com.pixelcontrols.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LowSignalTileService extends TileService {

    private static final String TAG = "LowSignalTileService";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        final boolean current = LowSignal.isActive(this);
        final boolean target = !current;
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(target ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setSubtitle(target ? "Boost on" : "Off");
            tile.updateTile();
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final LowSignal.Result res = target ? LowSignal.turnOn(LowSignalTileService.this)
                                                    : LowSignal.turnOff(LowSignalTileService.this);
                final String msg = LowSignal.describe(target, res);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateTileState();
                        // Never open a screen from here (it would close the Quick Settings panel). Without VPN permission
                        // the DNS accelerator is skipped; everything else is on. install.sh grants the permission.
                        Toast.makeText(LowSignalTileService.this, res.consent != null
                                ? msg + " · DNS accelerator needs VPN permission: open Pixel Controls once" : msg,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean active = LowSignal.isActive(this);
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_low_signal));
        tile.setSubtitle(active ? "Boost on" : "Off");
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_signal_low));
        tile.updateTile();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}

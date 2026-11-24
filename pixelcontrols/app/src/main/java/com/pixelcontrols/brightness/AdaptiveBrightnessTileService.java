package com.pixelcontrols.brightness;

import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import com.pixelcontrols.R;

public class AdaptiveBrightnessTileService extends TileService {

    private static final String TAG = "AdaptiveBrightnessTile";
    private ContentObserver observer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastReportedPercent = -1;
    private int lastReportedState = -1;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            updateTileState();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                // Coalesce rapid brightness adjustments to save CPU and battery
                handler.removeCallbacks(updateRunnable);
                handler.postDelayed(updateRunnable, 60);
            }
        };
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false,
                    observer
            );
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    observer
            );
        } catch (Exception e) {
            Log.e(TAG, "Error registering observer", e);
        }
        updateTileState();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        handler.removeCallbacks(updateRunnable);
        try {
            getContentResolver().unregisterContentObserver(observer);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering observer", e);
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!AdaptiveBrightnessManager.canWriteSettings(this)) {
            Toast.makeText(this, "Grant \"Modify system settings\" permission to change brightness", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            com.pixelcontrols.TileLaunch.open(this, intent, 31);
            return;
        }
        boolean wasAdaptive = AdaptiveBrightnessManager.isAdaptive(this);
        boolean changed = wasAdaptive
                ? AdaptiveBrightnessManager.restoreManualPreset(this)
                : AdaptiveBrightnessManager.setAdaptive(this, true);
        if (!changed) {
            Toast.makeText(this, "Could not change brightness mode", Toast.LENGTH_LONG).show();
        }
        // Force refresh on direct click
        lastReportedPercent = -1;
        lastReportedState = -1;
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean isAdaptive = AdaptiveBrightnessManager.isAdaptive(this);
        int mPercent = AdaptiveBrightnessManager.getManualBrightnessPercent(this);
        int targetState = isAdaptive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;

        // Skip redundant IPC Binder transactions if state has not changed
        if (tile.getState() == targetState && lastReportedState == targetState && lastReportedPercent == mPercent) {
            return;
        }
        lastReportedState = targetState;
        lastReportedPercent = mPercent;

        tile.setLabel(getString(R.string.tile_brightness_mode));
        tile.setState(targetState);

        int iconRes = isAdaptive ? R.drawable.ic_brightness_auto : R.drawable.ic_brightness_manual;
        tile.setIcon(Icon.createWithResource(this, iconRes));

        tile.setSubtitle(isAdaptive ? "Auto" : "M · " + mPercent + "%");
        tile.setContentDescription(isAdaptive ? "Brightness mode: Auto" : "Brightness mode: Manual M, "
                + mPercent + " percent");
        tile.updateTile();
    }
}

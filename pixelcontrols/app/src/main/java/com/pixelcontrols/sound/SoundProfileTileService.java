package com.pixelcontrols.sound;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import com.pixelcontrols.R;

public class SoundProfileTileService extends TileService {

    private static final String TAG = "SoundProfileTile";
    private BroadcastReceiver audioReceiver;
    private int lastReportedProfile = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        audioReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateTileState();
            }
        };
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            filter.addAction("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(audioReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(audioReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering receiver", e);
        }
        updateTileState();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        try {
            unregisterReceiver(audioReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Log.i(TAG, "onClick");
        if (!SoundProfileManager.hasNotificationPolicyAccess(this)) {
            Toast.makeText(this, "Allow Do Not Disturb access for Pixel Controls", Toast.LENGTH_LONG).show();
            com.pixelcontrols.TileLaunch.open(this, SoundProfileManager.notificationPolicyAccessIntent(), 21);
            return;
        }
        // Apply right here so the Quick Settings panel stays open. This works once install.sh has set the
        // CONTROL_AUDIO app-op to allow (Android 16+ otherwise ignores volume changes from the background); if Android
        // still refuses, fall back to applying from an invisible screen (that closes the panel).
        int next = SoundProfileManager.nextProfile(this);
        SoundProfileManager.Result r = SoundProfileManager.applyProfile(this, next);
        if (r == SoundProfileManager.Result.BLOCKED_IN_BACKGROUND) {
            Log.i(TAG, "background change refused: applying from the front instead");
            com.pixelcontrols.TileLaunch.open(this, new Intent(this, SoundApplyActivity.class)
                    .putExtra(SoundApplyActivity.EXTRA_PROFILE, next), 22);
        } else if (r == SoundProfileManager.Result.NO_ACCESS) {
            Toast.makeText(this, SoundProfileManager.message(r, next), Toast.LENGTH_LONG).show();
            com.pixelcontrols.TileLaunch.open(this, SoundProfileManager.notificationPolicyAccessIntent(), 21);
        }
        lastReportedProfile = -1;
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        int profile = SoundProfileManager.getActiveProfile(this);
        // Avoid redundant Binder transactions if profile and state are identical
        if (tile.getState() == Tile.STATE_ACTIVE && lastReportedProfile == profile) {
            return;
        }
        lastReportedProfile = profile;

        tile.setLabel(getString(R.string.tile_sound_profile));
        tile.setState(Tile.STATE_ACTIVE);

        int iconRes;
        String subtitle;

        switch (profile) {
            case SoundProfileManager.PROFILE_SILENT:
                iconRes = R.drawable.ic_sound_vibrate;
                subtitle = "Silent (Vibrate)";
                break;
            case SoundProfileManager.PROFILE_NIGHT:
                iconRes = R.drawable.ic_sound_night;
                subtitle = "Night Mode (40%)";
                break;
            case SoundProfileManager.PROFILE_NORMAL:
            default:
                iconRes = R.drawable.ic_sound_normal;
                subtitle = "Normal";
                break;
        }

        tile.setIcon(Icon.createWithResource(this, iconRes));
        tile.setSubtitle(subtitle);
        tile.setContentDescription("Sound Profile: " + subtitle);
        tile.updateTile();
    }
}

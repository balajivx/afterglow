package com.pixelcontrols.sound;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import android.content.ComponentName;

/**
 * Invisible screen the Sound Profile tile opens: Android 16+ only lets an app change volumes / ringer mode while one of
 * its screens is in front, so the profile is applied here (in onResume, when this screen is really in front), checked,
 * and the screen closes. Also shows over the lock screen. Extra "profile": 0/1/2, or -1 (default) = next profile.
 */
public class SoundApplyActivity extends Activity {
    public static final String EXTRA_PROFILE = "profile";
    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.Log.i("SoundApplyActivity", "onCreate");
        setShowWhenLocked(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.i("SoundApplyActivity", "onResume done=" + done);
        if (done) return;
        done = true;
        int want = getIntent().getIntExtra(EXTRA_PROFILE, -1);
        final int profile = want >= 0 && want <= 2 ? want : SoundProfileManager.nextProfile(this);
        SoundProfileManager.Result r = SoundProfileManager.applyProfile(this, profile);
        if (r == SoundProfileManager.Result.BLOCKED_IN_BACKGROUND) {
            // the window may not have had focus yet: one more try a moment later
            new Handler(Looper.getMainLooper()).postDelayed(() -> finishWith(SoundProfileManager.applyProfile(this, profile), profile), 400);
            return;
        }
        finishWith(r, profile);
    }

    private void finishWith(SoundProfileManager.Result r, int profile) {
        Toast.makeText(getApplicationContext(), SoundProfileManager.message(r, profile), Toast.LENGTH_SHORT).show();
        if (r == SoundProfileManager.Result.NO_ACCESS) {
            try { startActivity(SoundProfileManager.notificationPolicyAccessIntent()); } catch (Exception ignored) { }
        }
        try {
            TileService.requestListeningState(this, new ComponentName(this, SoundProfileTileService.class));
        } catch (RuntimeException ignored) { }
        finish();
        overridePendingTransition(0, 0);
    }
}

package com.pixelcontrols.sound;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Sound profiles: Normal (your own volumes), Silent (vibrate, media muted), Night (ring 40%, notifications and media
 * off).
 *
 * Two Android rules make this fragile, so everything is checked:
 *  - Changing the ringer mode needs Do Not Disturb access (install.sh grants it: `cmd notification allow_dnd`).
 *  - Android 16+ "audio hardening" silently ignores volume / ringer changes from an app in the background. They only
 *    work while a Pixel Controls screen is in front: the tile therefore opens SoundApplyActivity (invisible) to apply.
 * applyProfile reads the result back; a profile counts as active only once the phone actually shows it.
 */
public final class SoundProfileManager {

    private static final String TAG = "SoundProfileManager";
    private static final String PREFS_NAME = "sound_profile_prefs";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";
    private static final String KEY_SAVED_RING = "saved_ring_volume";
    private static final String KEY_SAVED_NOTIF = "saved_notif_volume";
    private static final String KEY_SAVED_MUSIC = "saved_music_volume";

    public static final int PROFILE_NORMAL = 0;
    public static final int PROFILE_SILENT = 1;
    public static final int PROFILE_NIGHT = 2;

    /** Outcome of applyProfile. */
    public enum Result { OK, NO_ACCESS, BLOCKED_IN_BACKGROUND }

    private SoundProfileManager() {}

    /** Do Not Disturb access (required for ringer mode changes). */
    public static boolean hasNotificationPolicyAccess(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    public static Intent notificationPolicyAccessIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
    }

    /** Open settings to grant Do Not Disturb access (from an activity; tiles use TileLaunch). */
    public static void requestNotificationPolicyAccess(Context context) {
        Intent intent = notificationPolicyAccessIntent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int nightRing(AudioManager am) {
        return Math.max(1, Math.round(am.getStreamMaxVolume(AudioManager.STREAM_RING) * 0.40f));
    }

    /** What the phone is actually set to right now (no stored guesses). */
    public static int detectProfile(Context context) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return prefs(context).getInt(KEY_ACTIVE_PROFILE, PROFILE_NORMAL);
        int mode = am.getRingerMode();
        if (mode == AudioManager.RINGER_MODE_VIBRATE || mode == AudioManager.RINGER_MODE_SILENT) return PROFILE_SILENT;
        int ring = am.getStreamVolume(AudioManager.STREAM_RING);
        int notif = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        int music = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (notif == 0 && music == 0 && Math.abs(ring - nightRing(am)) <= 1) return PROFILE_NIGHT;
        return PROFILE_NORMAL;
    }

    public static int getActiveProfile(Context context) {
        return detectProfile(context);
    }

    private static boolean matches(AudioManager am, int profile) {
        int mode = am.getRingerMode();
        int ring = am.getStreamVolume(AudioManager.STREAM_RING);
        int notif = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        int music = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        switch (profile) {
            case PROFILE_SILENT: return mode == AudioManager.RINGER_MODE_VIBRATE && music == 0;
            case PROFILE_NIGHT:  return mode == AudioManager.RINGER_MODE_NORMAL && notif == 0 && music == 0 && ring == nightRing(am);
            default:             return mode == AudioManager.RINGER_MODE_NORMAL && ring > 0;
        }
    }

    // Only change what differs: every ringer/volume change makes the system UI (which also runs Quick Settings) do
    // work, and fewer changes keep the next tile tap quick.
    private static void ringer(AudioManager am, int mode) {
        if (am.getRingerMode() != mode) am.setRingerMode(mode);
    }

    private static void volume(AudioManager am, int stream, int index) {
        if (am.getStreamVolume(stream) != index) am.setStreamVolume(stream, index, 0);
    }

    /**
     * Applies a profile and checks the phone really changed. Call from a Pixel Controls screen in front
     * (MainActivity / SoundApplyActivity): from the background Android 16+ ignores it (BLOCKED_IN_BACKGROUND).
     */
    public static Result applyProfile(Context context, int profile) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return Result.BLOCKED_IN_BACKGROUND;
        boolean dnd = hasNotificationPolicyAccess(context);
        // Leaving Silent needs DND access too (vibrate -> normal is a ringer mode change).
        if (!dnd && (profile != PROFILE_NORMAL || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)) {
            Log.w(TAG, "no Do Not Disturb access");
            return Result.NO_ACCESS;
        }
        SharedPreferences prefs = prefs(context);
        int maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING);
        int maxNotif = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
        int maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        // Leaving Normal (as the phone really is now, not as last stored): remember your volumes.
        if (profile != PROFILE_NORMAL && detectProfile(context) == PROFILE_NORMAL) {
            int ring = am.getStreamVolume(AudioManager.STREAM_RING);
            int notif = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            int music = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            prefs.edit()
                    .putInt(KEY_SAVED_RING, ring > 0 ? ring : Math.max(1, Math.round(maxRing * 0.70f)))
                    .putInt(KEY_SAVED_NOTIF, notif > 0 ? notif : Math.max(1, Math.round(maxNotif * 0.70f)))
                    .putInt(KEY_SAVED_MUSIC, music > 0 ? music : Math.max(1, Math.round(maxMusic * 0.60f)))
                    .commit();
        }

        try {
            switch (profile) {
                case PROFILE_SILENT:
                    ringer(am, AudioManager.RINGER_MODE_VIBRATE);
                    volume(am, AudioManager.STREAM_MUSIC, 0);
                    break;
                case PROFILE_NIGHT:
                    ringer(am, AudioManager.RINGER_MODE_NORMAL);
                    volume(am, AudioManager.STREAM_RING, nightRing(am));
                    volume(am, AudioManager.STREAM_NOTIFICATION, 0);
                    volume(am, AudioManager.STREAM_MUSIC, 0);
                    break;
                case PROFILE_NORMAL:
                default:
                    ringer(am, AudioManager.RINGER_MODE_NORMAL);
                    volume(am, AudioManager.STREAM_RING, prefs.getInt(KEY_SAVED_RING, Math.max(1, Math.round(maxRing * 0.70f))));
                    volume(am, AudioManager.STREAM_NOTIFICATION, prefs.getInt(KEY_SAVED_NOTIF, Math.max(1, Math.round(maxNotif * 0.70f))));
                    volume(am, AudioManager.STREAM_MUSIC, prefs.getInt(KEY_SAVED_MUSIC, Math.max(1, Math.round(maxMusic * 0.60f))));
                    break;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "refused", e);
            return Result.NO_ACCESS;
        }
        boolean ok = matches(am, profile);
        Log.i(TAG, "profile " + profile + (ok ? " applied" : " NOT applied (Android ignored it: app not in front)")
                + " ringer=" + am.getRingerMode() + " ring=" + am.getStreamVolume(AudioManager.STREAM_RING)
                + " notif=" + am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) + " music=" + am.getStreamVolume(AudioManager.STREAM_MUSIC));
        if (!ok) return Result.BLOCKED_IN_BACKGROUND;
        prefs.edit().putInt(KEY_ACTIVE_PROFILE, profile).apply();
        return Result.OK;
    }

    public static int nextProfile(Context context) {
        return (detectProfile(context) + 1) % 3;
    }

    public static String getProfileName(int profile) {
        switch (profile) {
            case PROFILE_SILENT: return "Silent";
            case PROFILE_NIGHT: return "Night Mode";
            case PROFILE_NORMAL:
            default: return "Normal";
        }
    }

    public static String getProfileDescription(int profile) {
        switch (profile) {
            case PROFILE_SILENT: return "Vibration mode, no sounds";
            case PROFILE_NIGHT: return "Ring 40%, notifications & media 0%";
            case PROFILE_NORMAL:
            default: return "Regular sound settings";
        }
    }

    public static String message(Result r, int profile) {
        switch (r) {
            case OK: return "Sound: " + getProfileName(profile);
            case NO_ACCESS: return "Allow Do Not Disturb access for Pixel Controls to change sound profiles";
            default: return "Android didn't allow the change; open Pixel Controls and try again";
        }
    }
}

package com.pixelcontrols.brightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

public final class AdaptiveBrightnessManager {

    private static final String TAG = "AdaptiveBrightnessMgr";
    private static final String PREFS = "brightness_mode";
    private static final String MANUAL_LEVEL = "manual_level";
    private static final String MANUAL_PERCENT = "manual_percent";

    // Hybrid Log Gamma (HLG) constants matching AOSP SettingsLib BrightnessUtils and SystemUI
    private static final float HLG_R = 0.5f;
    private static final float HLG_A = 0.17883277f;
    private static final float HLG_B = 0.28466892f;
    private static final float HLG_C = 0.55991073f;

    public static float getMaxLinearLevel() {
        if ("Google".equalsIgnoreCase(Build.MANUFACTURER)) {
            return 169.85f; // Pixel OLED normal max limit (171 - 1)
        }
        return 254.0f; // Xiaomi / Samsung / standard Android (255 - 1)
    }

    public static int getMaxLevel() {
        if ("Google".equalsIgnoreCase(Build.MANUFACTURER)) {
            return 171; // Pixel OLED normal max limit
        }
        return 255; // Xiaomi / standard Android
    }

    public static boolean canWriteSettings(Context context) {
        return Settings.System.canWrite(context);
    }

    /**
     * Converts a perceptual gamma percentage (0–100%) from the UI slider to the
     * hardware-backed Settings.System.SCREEN_BRIGHTNESS level (1–255).
     */
    public static int percentToBrightnessLevel(int percent) {
        percent = Math.max(0, Math.min(100, percent));
        if (percent <= 0) return 1;
        int maxLvl = getMaxLevel();
        if (percent >= 100) return maxLvl;

        float gamma = percent / 100.0f;
        float ret;
        if (gamma <= HLG_R) {
            ret = (gamma / HLG_R) * (gamma / HLG_R);
        } else {
            ret = (float) Math.exp((gamma - HLG_C) / HLG_A) + HLG_B;
        }
        ret = Math.max(0f, Math.min(12f, ret));
        float norm = ret / 12f;
        int level = Math.round(1f + norm * getMaxLinearLevel());
        return Math.max(1, Math.min(255, level));
    }

    /**
     * Converts a raw Settings.System.SCREEN_BRIGHTNESS level back to the exact
     * perceptual gamma percentage (0–100%) displayed on the swipe-down brightness slider.
     */
    public static int brightnessLevelToPercent(int level) {
        level = Math.max(1, Math.min(255, level));
        if (level <= 1) return 0;
        int maxLvl = getMaxLevel();
        if (level >= maxLvl) return 100;

        float norm = ((float) (level - 1) / getMaxLinearLevel()) * 12f;
        float gamma;
        if (norm <= 1f) {
            gamma = (float) Math.sqrt(Math.max(0f, norm)) * HLG_R;
        } else {
            gamma = HLG_A * (float) Math.log(Math.max(0.0001f, norm - HLG_B)) + HLG_C;
        }
        return Math.max(0, Math.min(100, Math.round(gamma * 100f)));
    }

    public static boolean isAdaptive(Context context) {
        try {
            int mode = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
            return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            Log.e(TAG, "isAdaptive failed", e);
            return false;
        }
    }

    public static boolean setAdaptive(Context context, boolean enable) {
        if (!canWriteSettings(context)) {
            Log.w(TAG, "Cannot set adaptive mode: WRITE_SETTINGS permission not granted");
            return false;
        }
        int mode = enable
                ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        try {
            boolean ok = Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mode
            );
            Log.d(TAG, "setAdaptive via Settings.System: " + enable + " -> " + ok);
            return ok;
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException in Settings.System.putInt", se);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "setAdaptive failed", e);
            return false;
        }
    }

    public static int getCurrentBrightnessLevel(Context context) {
        try {
            int level = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 15);
            return Math.max(1, Math.min(255, level));
        } catch (Exception e) {
            Log.e(TAG, "getCurrentBrightnessLevel failed", e);
            return 15;
        }
    }

    public static int getCurrentBrightnessPercent(Context context) {
        return brightnessLevelToPercent(getCurrentBrightnessLevel(context));
    }

    public static boolean setBrightnessLevel(Context context, int level) {
        if (!canWriteSettings(context)) {
            Log.w(TAG, "Cannot set brightness level: WRITE_SETTINGS permission not granted");
            return false;
        }
        level = Math.max(1, Math.min(255, level));
        try {
            return Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, level);
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException in setBrightnessLevel", se);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "setBrightnessLevel failed", e);
            return false;
        }
    }

    public static boolean setBrightnessPercent(Context context, int percent) {
        int level = percentToBrightnessLevel(percent);
        return setBrightnessLevel(context, level);
    }

    public static int getManualBrightnessLevel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(MANUAL_LEVEL)) {
            return prefs.getInt(MANUAL_LEVEL, 15);
        }
        int percent = getManualBrightnessPercent(context);
        return percentToBrightnessLevel(percent);
    }

    public static int getManualBrightnessPercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(MANUAL_PERCENT)) {
            return prefs.getInt(MANUAL_PERCENT, 50);
        }
        if (prefs.contains(MANUAL_LEVEL)) {
            return brightnessLevelToPercent(prefs.getInt(MANUAL_LEVEL, 15));
        }
        return getCurrentBrightnessPercent(context);
    }

    public static void saveManualPreset(Context context, int percent) {
        int level = percentToBrightnessLevel(percent);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(MANUAL_PERCENT, percent)
                .putInt(MANUAL_LEVEL, level)
                .apply();
        Log.i(TAG, "Saved M preset: " + percent + "% (level " + level + ")");
    }

    public static boolean restoreManualPreset(Context context) {
        int level = getManualBrightnessLevel(context);
        boolean levelSet = setBrightnessLevel(context, level);
        boolean modeSet = setAdaptive(context, false);
        Log.d(TAG, "restoreManualPreset: level=" + level + ", levelSet=" + levelSet
                + ", modeSet=" + modeSet);
        return levelSet && modeSet && !isAdaptive(context);
    }

    public static boolean setManualFavorite(Context context) {
        return restoreManualPreset(context);
    }

    public static void rememberManualBrightness(Context context) {
        // Intentionally no-op so that the saved "M" preset bookmark is not overwritten
        // until the user explicitly taps "Save M" in the app.
    }
}

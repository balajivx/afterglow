package com.pixelcontrols.auto;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;

/**
 * Wired / wireless Android Auto without any helper. Wireless Android Auto runs over Wi-Fi: with Wi-Fi off it ends,
 * and with the cable in, the car runs Android Auto over USB. So "wired" = Wi-Fi off (by this app) while plugged
 * into the car; "wireless" = Wi-Fi on. The app may switch Wi-Fi because it targets Android 9 (WifiManager
 * .setWifiEnabled; checked on the Pixel 9a, Android 17, 20 Nov 2026).
 */
public final class Car {
    public static final String PREFS = "car";

    private Car() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A car's Bluetooth (car stereo or hands-free kit), e.g. "My Car". */
    public static boolean isCar(BluetoothDevice d) {
        try {
            BluetoothClass k = d == null ? null : d.getBluetoothClass();
            if (k == null) return false;
            int dc = k.getDeviceClass();
            return dc == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || dc == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE;
        } catch (SecurityException e) {
            return false;
        }
    }

    public static boolean auto(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    public static boolean wifiOffByUs(Context c) {
        return prefs(c).getBoolean("wifi_off", false);
    }

    /** On, or switching on. */
    public static boolean wifiOn(Context c) {
        WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
        if (w == null) return false;
        int s = w.getWifiState();
        return s == WifiManager.WIFI_STATE_ENABLED || s == WifiManager.WIFI_STATE_ENABLING;
    }

    public static boolean plugged(Context c) {
        Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int p = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return p == BatteryManager.BATTERY_PLUGGED_USB || p == BatteryManager.BATTERY_PLUGGED_AC;
    }

    private static Intent usbState(Context c) {
        return c.getApplicationContext().registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"));
    }

    /**
     * A USB data link with whatever the cable goes to (a computer, the car's Android Auto port). A charge-only port
     * never makes one. (On this Pixel the plug type can't tell: a computer reads as "AC" too.)
     */
    public static boolean dataLink(Context c) {
        if (prefs(c).contains("sim_data")) return prefs(c).getBoolean("sim_data", false); // SimReceiver (tests)
        Intent s = usbState(c);
        return s != null && s.getBooleanExtra("connected", false);
    }

    /** The car's USB handshake for Android Auto (accessory mode), as far as Android tells an app. */
    static boolean accessory(Context c) {
        if (prefs(c).getBoolean("sim_accessory", false)) return true; // SimReceiver (tests from a computer)
        try {
            UsbManager u = c.getSystemService(UsbManager.class);
            if (u != null && u.getAccessoryList() != null && u.getAccessoryList().length > 0) return true;
        } catch (Exception ignored) {
        }
        Intent s = usbState(c);
        return s != null && s.getBooleanExtra("accessory", false);
    }

    /** What this port looks like to the phone: data link or not, the charger's max current and voltage. */
    public static String portKey(Context c) {
        String sim = prefs(c).getString("sim_port", null);
        if (sim != null) return sim;
        Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int ma = b == null ? -1 : b.getIntExtra("max_charging_current", -1) / 1000;
        int mv = b == null ? -1 : b.getIntExtra("max_charging_voltage", -1) / 1000;
        return (dataLink(c) ? "data" : "power") + ":" + ma + "mA:" + mv + "mV";
    }

    public enum Port { ANDROID_AUTO, CHARGING, UNKNOWN }

    /**
     * Which of the car's ports the cable is in (2026-11-24: the car has an Android Auto port and a charging port, and
     * plugging into the charging one must never switch). No data link: charging. The car's Android Auto handshake:
     * Android Auto. Otherwise what was learned for this port (the user's answer, or wired Android Auto having
     * started on it); not learned yet: UNKNOWN (ask).
     */
    public static Port port(Context c) {
        if (!plugged(c)) return Port.UNKNOWN;
        if (accessory(c)) return Port.ANDROID_AUTO;
        if (!dataLink(c)) return Port.CHARGING;
        String k = portKey(c);
        boolean aa = learned(c, "aa_ports").contains(k), ch = learned(c, "charge_ports").contains(k);
        if (aa && !ch) return Port.ANDROID_AUTO;
        if (ch && !aa) return Port.CHARGING;
        return Port.UNKNOWN;
    }

    static java.util.Set<String> learned(Context c, String which) {
        return new java.util.HashSet<>(prefs(c).getStringSet(which, new java.util.HashSet<>()));
    }

    /** Remember this port as the Android Auto port (aa) or the charging port. */
    public static void learnPort(Context c, String key, boolean aa) {
        java.util.Set<String> yes = learned(c, aa ? "aa_ports" : "charge_ports"), no = learned(c, aa ? "charge_ports" : "aa_ports");
        yes.add(key);
        no.remove(key);
        prefs(c).edit().putStringSet(aa ? "aa_ports" : "charge_ports", yes).putStringSet(aa ? "charge_ports" : "aa_ports", no).apply();
        log(c, "learned " + key + " = " + (aa ? "Android Auto port" : "charging port"));
    }

    public static void forgetPorts(Context c) {
        prefs(c).edit().remove("aa_ports").remove("charge_ports").apply();
        log(c, "forgot the car's ports");
    }

    /** On the car's Android Auto port (as far as known). */
    public static boolean carOnUsb(Context c) {
        return port(c) == Port.ANDROID_AUTO;
    }

    /** Android Auto is running (wired or wireless): the phone is in car mode while it projects. */
    public static boolean aaRunning(Context c) {
        try {
            android.app.UiModeManager m = c.getSystemService(android.app.UiModeManager.class);
            return m != null && m.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_CAR;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- what happened in the car: the last 40 events, shown in the app (diagnosis after a drive)
    public static synchronized void log(Context c, String event) {
        String line = new java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + event;
        android.util.Log.i("PCCar", event);
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("carlog", Context.MODE_PRIVATE);
        String[] old = p.getString("log", "").split("\n");
        StringBuilder b = new StringBuilder(line);
        for (int i = 0; i < old.length && i < 39; i++) if (!old[i].isEmpty()) b.append('\n').append(old[i]);
        p.edit().putString("log", b.toString()).apply();
    }

    public static String logText(Context c) {
        return c.getApplicationContext().getSharedPreferences("carlog", Context.MODE_PRIVATE).getString("log", "");
    }

    /** Plug, port and Android Auto state in one line for the log. */
    public static String state(Context c) {
        return "plugged=" + plugged(c) + " port=" + (plugged(c) ? portKey(c) + " " + port(c) : "-") + " accessory=" + accessory(c) + " inCar=" + CarService.running
                + " wifi=" + wifiOn(c) + " aa=" + aaRunning(c);
    }

    /** Wired: Wi-Fi off, remembered so only this app turns it back on. False when Wi-Fi was off already. */
    @SuppressWarnings("deprecation")
    public static boolean goWired(Context c) {
        if (!wifiOn(c)) return false;
        WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
        boolean ok = w.setWifiEnabled(false);
        if (ok) prefs(c).edit().putBoolean("wifi_off", true).apply();
        log(c, ok ? "wired: Wi-Fi off" : "wired: Android refused to turn Wi-Fi off");
        if (ok) checkWiredStarted(c);
        return ok;
    }

    /** Wireless (or leaving the car): Wi-Fi back on if this app turned it off ([force]: on regardless). */
    @SuppressWarnings("deprecation")
    public static void goWireless(Context c, boolean force) {
        if (!force && !wifiOffByUs(c)) return;
        prefs(c).edit().putBoolean("wifi_off", false).apply();
        log(c, "wireless: Wi-Fi on");
        c.getSystemService(android.app.NotificationManager.class).cancel(REPLUG_NOTE);
        WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
        if (w != null && !w.isWifiEnabled()) w.setWifiEnabled(true);
    }

    /** Wired is possible only on the car's Android Auto port: the cable is in and the car did its Android Auto
     *  USB handshake. A charging-only port (some cars have one of each) never does, so it never offers wired. */
    public static boolean canGoWired(Context c) {
        // A tap is the user's own choice: any cable but the charging port (no data link, or learned as charging).
        return plugged(c) && port(c) != Port.CHARGING;
    }

    /**
     * After Wi-Fi goes off: if Android Auto hasn't come back over the cable within 15 s, the car is still holding the
     * USB link from the wireless session and needs to see the phone plugged in afresh. No app can reset USB, so say so
     * once (a notification); it clears itself when Android Auto starts or Wi-Fi comes back.
     */
    public static void checkWiredStarted(Context ctx) {
        final Context c = ctx.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            if (!wifiOffByUs(c) || !plugged(c)) return;
            if (aaRunning(c)) {
                log(c, "wired Android Auto running");
                if (dataLink(c)) learnPort(c, portKey(c), true);
                nm.cancel(REPLUG_NOTE);
                return;
            }
            log(c, "no Android Auto 15 s after Wi-Fi off: asked to replug · " + state(c));
            nm.createNotificationChannel(new android.app.NotificationChannel("car_replug", "Android Auto: replug cable",
                    android.app.NotificationManager.IMPORTANCE_HIGH));
            nm.notify(REPLUG_NOTE, new android.app.Notification.Builder(c, "car_replug").setSmallIcon(com.pixelcontrols.R.drawable.ic_car)
                    .setContentTitle("Unplug and plug the cable back in")
                    .setContentText("Wi-Fi is off: the car starts wired Android Auto when it sees the phone plugged in again.")
                    .setAutoCancel(true).setTimeoutAfter(5 * 60_000L).build());
        }, 15_000L);
        // Safety net: Wi-Fi off but no Android Auto 90 s later, and no replug meanwhile: a wrong port. Wi-Fi back on.
        final long wiredAt = System.currentTimeMillis();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!wifiOffByUs(c) || aaRunning(c)) {
                if (aaRunning(c) && dataLink(c)) learnPort(c, portKey(c), true);
                return;
            }
            if (prefs(c).getLong("plugAt", 0) > wiredAt) return;   // replugged since: the car is starting it
            log(c, "no Android Auto 90 s after Wi-Fi off and no replug: Wi-Fi back on · " + state(c));
            goWireless(c, true);
        }, 90_000L);
    }

    static final int REPLUG_NOTE = 4;

    /** What the tile and app say: lit = wired. */
    public static String describe(Context c) {
        boolean p = plugged(c);
        if (wifiOffByUs(c)) return p ? "Wired · tap for wireless" : "Wi-Fi off · tap to turn on";
        if (!wifiOn(c)) return "Wi-Fi is off";
        if (!p) return "Wireless";
        Port pt = port(c);
        return pt == Port.CHARGING ? "Charging port · wireless" : "Wireless · tap for wired";
    }
}

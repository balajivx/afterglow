package com.pixelcontrols.auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Testing without the car, from a computer only (requires android.permission.DUMP, which only the adb shell has):
 *   adb shell am broadcast -n com.pixelcontrols/.auto.SimReceiver --es car "My Car" --ez connected true
 *   adb shell am broadcast -n com.pixelcontrols/.auto.SimReceiver --ez accessory true    (the car's USB handshake)
 * Plugging in is simulated with `dumpsys battery set ac 1` / `dumpsys battery reset`.
 * Also on-device test hooks for the no-daemon features (tests/device_check.sh):
 *   --ez lowsignal true|false     Low signal ON / OFF exactly as the tile does it
 *   --ez lite true|false          Lite adblock's Private DNS switch + watchdog, without changing the adblock setting
 */
public class SimReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i.getBooleanExtra("batterycheck", false) || i.hasExtra("batterynotify")) {
            final Context app = c.getApplicationContext();
            final String pkg = i.getStringExtra("batterynotify");
            final PendingResult done = goAsync();
            new Thread(() -> {
                try {
                    if (pkg != null) com.pixelcontrols.battery.BatteryWatch.sample(app, pkg);
                    else com.pixelcontrols.battery.BatteryWatch.check(app, true);
                } finally { done.finish(); }
            }).start();
            return;
        }
        if (i.getBooleanExtra("batteryprobe", false)) {   // --ez batteryprobe true: log the Battery watch signals
            final Context app = c.getApplicationContext();
            final PendingResult done = goAsync();
            new Thread(() -> { try { com.pixelcontrols.battery.BatteryProbe.run(app); } finally { done.finish(); } }).start();
            return;
        }
        if (i.hasExtra("lowsignal") || i.hasExtra("lite")) {
            final Context app = c.getApplicationContext();
            final boolean ls = i.hasExtra("lowsignal"), on = i.getBooleanExtra(ls ? "lowsignal" : "lite", false);
            final PendingResult done = goAsync();
            new Thread(() -> {
                try {
                    if (ls) {
                        if (on) com.pixelcontrols.LowSignal.turnOn(app); else com.pixelcontrols.LowSignal.turnOff(app);
                    } else {
                        if (on) com.pixelcontrols.adblock.Adblock.liteOn(app); else com.pixelcontrols.adblock.Adblock.liteOff(app);
                    }
                } finally {
                    done.finish();
                }
            }).start();
            return;
        }
        if (i.hasExtra("accessory")) Car.prefs(c).edit().putBoolean("sim_accessory", i.getBooleanExtra("accessory", false)).apply();
        // --ez data true|false, --es port "data:1500mA:5000mV": a port as the car's would look (tests); --ez simreset true
        if (i.hasExtra("data")) Car.prefs(c).edit().putBoolean("sim_data", i.getBooleanExtra("data", false)).apply();
        if (i.hasExtra("port")) Car.prefs(c).edit().putString("sim_port", i.getStringExtra("port")).apply();
        if (i.getBooleanExtra("simreset", false)) Car.prefs(c).edit().remove("sim_data").remove("sim_port").remove("sim_accessory").apply();
        if (i.getBooleanExtra("forgetports", false)) Car.forgetPorts(c);
        if (i.hasExtra("answer")) {   // --es answer yes|no: as if the user tapped the port question
            Intent a = new Intent(c, CarPortReceiver.class).setAction("yes".equals(i.getStringExtra("answer")) ? CarPortReceiver.YES : CarPortReceiver.NO)
                    .putExtra("key", Car.portKey(c));
            new CarPortReceiver().onReceive(c, a);
        }
        if (i.hasExtra("connected")) {
            if (i.getBooleanExtra("connected", false)) {
                Car.prefs(c).edit().putString("car", i.getStringExtra("car")).apply();
                CarService.start(c);
            } else {
                c.stopService(new Intent(c, CarService.class));
                Car.goWireless(c, false);
            }
        }
    }
}

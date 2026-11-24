package com.pixelcontrols.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.pixelcontrols.R;

/**
 * Runs only while the phone is connected to a car's Bluetooth. Cable in, and the car takes Android Auto over USB
 * (accessory mode, within seconds; a plain charger never does) with Wi-Fi on: Wi-Fi off, so Android Auto moves to
 * the cable. Cable out: Wi-Fi back on, 15 s later (a quick re-plug keeps it wired). Once per plug-in: switching
 * back to wireless by hand sticks until the next plug-in.
 */
public class CarService extends Service {
    private static final int NOTE = 3;
    /** Connected to a car's Bluetooth right now (Low signal focus lets the car's navigation / music apps through). */
    public static volatile boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean doneThisPlug;
    private final Runnable restore = () -> Car.goWireless(this, false);

    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Car.log(c, (Intent.ACTION_POWER_CONNECTED.equals(i.getAction()) ? "cable in · " : "cable out · ") + Car.state(c));
            if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) {
                Car.prefs(c).edit().putLong("plugAt", System.currentTimeMillis()).apply();
                // Re-plugged before Wi-Fi came back: stay wired only if this is the Android Auto port again
                // (lookForCar decides; a charging-only port brings Wi-Fi back at once).
                main.removeCallbacks(restore);
                lookForCar(0);
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) {
                doneThisPlug = false;
                main.removeCallbacksAndMessages(null);
                main.postDelayed(restore, 15_000); // a quick re-plug into the Android Auto port stays wired
            }
        }
    };

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, CarService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        Car.log(this, "car connected: " + Car.prefs(this).getString("car", "?") + " · " + Car.state(this));
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("car", "In the car", NotificationManager.IMPORTANCE_MIN));
        String car = Car.prefs(this).getString("car", "the car");
        startForeground(NOTE, new Notification.Builder(this, "car").setSmallIcon(R.drawable.ic_car)
                .setContentTitle("Android Auto · " + car)
                .setContentText(Car.auto(this) ? "Plug in the cable for wired Android Auto" : "Use the tile to switch")
                .setOngoing(true).build());
        IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(power, f);
        if (Car.plugged(this)) lookForCar(0); // already plugged in when the car's Bluetooth came up
    }

    /**
     * After plug-in, check every second for up to 20 s for the car's Android Auto USB handshake. The car's
     * charging-only port never does it: then nothing changes, and if Wi-Fi was off from an earlier wired plug-in
     * (the cable moved from the Android Auto port to the charging port), it comes back on.
     */
    /**
     * After plug-in, look every second for up to 20 s (the USB data link takes a moment). The Android Auto port
     * (handshake, or learned): wired. The charging port (no data link, or learned): nothing, and Wi-Fi back if an
     * earlier plug-in turned it off. A data port not seen before: ask once which it is (remembered).
     */
    private void lookForCar(int tries) {
        if (!Car.plugged(this)) return;
        Car.Port port = Car.port(this);
        if (port == Car.Port.ANDROID_AUTO) {
            Car.log(this, "Android Auto port after " + tries + " s · " + Car.state(this)
                    + (doneThisPlug ? " · already done this plug-in" : !Car.auto(this) ? " · automatic switch is off" : ""));
            if (!doneThisPlug && Car.auto(this) && Car.wifiOn(this)) Car.goWired(this);
            doneThisPlug = true;
            return;
        }
        if (port == Car.Port.UNKNOWN && Car.dataLink(this)) {
            if (!doneThisPlug) {
                Car.log(this, "new data port, asking · " + Car.state(this));
                CarPortReceiver.ask(this, Car.portKey(this));
            }
            doneThisPlug = true;
            return;
        }
        if (tries < 20) { main.postDelayed(() -> lookForCar(tries + 1), 1000); return; }
        Car.log(this, (Car.dataLink(this) ? "charging port (learned) · " : "charging port (no data link in 20 s) · ") + Car.state(this));
        Car.goWireless(this, false); // wireless Android Auto needs Wi-Fi
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Car.log(this, "car disconnected");
        main.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(power);
        } catch (Exception ignored) {
        }
        Car.goWireless(this, false); // leaving the car: Wi-Fi as it was
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}

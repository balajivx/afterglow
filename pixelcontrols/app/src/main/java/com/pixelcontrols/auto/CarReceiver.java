package com.pixelcontrols.auto;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The car's Bluetooth connecting or going (delivered even right after a phone restart, with nothing running):
 * in the car, CarService listens for the cable; out of it, Wi-Fi comes back if this app turned it off.
 */
public class CarReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (!Car.isCar(d)) {
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction()))
                Car.log(c, "Bluetooth " + safeName(d) + " connected, not a car (class " + btClass(d) + ")");
            return;
        }
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())) {
            Car.prefs(c).edit().putString("car", safeName(d)).apply();
            CarService.start(c);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(i.getAction())) {
            c.stopService(new Intent(c, CarService.class)); // its onDestroy turns Wi-Fi back on
            Car.goWireless(c, false);
        }
    }

    private static String btClass(BluetoothDevice d) {
        try {
            return d == null || d.getBluetoothClass() == null ? "?" : Integer.toHexString(d.getBluetoothClass().getDeviceClass());
        } catch (SecurityException e) {
            return "no permission";
        }
    }

    private static String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return "car";
        }
    }
}

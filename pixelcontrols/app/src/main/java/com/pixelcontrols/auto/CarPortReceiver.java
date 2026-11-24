package com.pixelcontrols.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.pixelcontrols.R;

/**
 * "Is this the Android Auto port?": asked once for a car USB port with a data link that isn't known yet (the car
 * has an Android Auto port and a charging port). The answer is remembered (Car.learnPort); Yes also switches.
 */
public class CarPortReceiver extends BroadcastReceiver {
    static final String YES = "com.pixelcontrols.auto.PORT_AA", NO = "com.pixelcontrols.auto.PORT_CHARGING";
    static final int NOTE = 5;

    static void ask(Context c, String key) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("car_port", "Android Auto: which port", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent yes = PendingIntent.getBroadcast(c, 51, new Intent(c, CarPortReceiver.class).setAction(YES).putExtra("key", key),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent no = PendingIntent.getBroadcast(c, 52, new Intent(c, CarPortReceiver.class).setAction(NO).putExtra("key", key),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTE, new Notification.Builder(c, "car_port").setSmallIcon(R.drawable.ic_car)
                .setContentTitle("Is this the car's Android Auto port?")
                .setContentText("Asked once per port: Pixel Controls remembers your answer.")
                .addAction(new Notification.Action.Builder(null, "Yes, go wired", yes).build())
                .addAction(new Notification.Action.Builder(null, "No, charging only", no).build())
                .setAutoCancel(true).setTimeoutAfter(10 * 60_000L).build());
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String key = i.getStringExtra("key");
        c.getSystemService(NotificationManager.class).cancel(NOTE);
        if (key == null) return;
        boolean aa = YES.equals(i.getAction());
        Car.learnPort(c, key, aa);
        if (aa && Car.plugged(c) && Car.wifiOn(c)) Car.goWired(c);
    }
}

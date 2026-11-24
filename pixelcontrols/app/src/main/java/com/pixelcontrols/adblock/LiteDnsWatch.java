package com.pixelcontrols.adblock;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import com.pixelcontrols.SecureSettings;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Lite adblock watchdog, no helper daemon. Woken (with the app closed) when a network becomes available (a
 * PendingIntent network callback; Android sends these only for new networks, which is when a DoT-blocking network
 * shows up) and by an inexact ~15 min alarm for a network that breaks while connected. Both re-armed after a
 * restart by BootReceiver. If the strict
 * AdGuard Private DNS that WE set can't be reached while the internet itself works (weak signal, a network blocking
 * port 853), Android has no fallback and the phone shows "no internet": switch to Automatic so the phone keeps
 * working. Needs two failed checks 15 s apart; never touches a strict DNS the user set.
 */
public class LiteDnsWatch extends BroadcastReceiver {
    private static final String TAG = "LiteDnsWatch";

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 41, new Intent(c, LiteDnsWatch.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent alarmPi(Context c) {
        return PendingIntent.getBroadcast(c, 42, new Intent(c, LiteDnsWatch.class).setAction("check"), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void register(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkRequest r = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cm.registerNetworkCallback(r, pi(c));
        } catch (RuntimeException e) {
            Log.w(TAG, "register failed", e);
        }
        try {
            android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + android.app.AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                    android.app.AlarmManager.INTERVAL_FIFTEEN_MINUTES, alarmPi(c));
        } catch (RuntimeException e) {
            Log.w(TAG, "alarm failed", e);
        }
    }

    public static void unregister(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            c.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(pi(c));
        } catch (RuntimeException ignored) {
        }
        try {
            c.getSystemService(android.app.AlarmManager.class).cancel(alarmPi(c));
        } catch (RuntimeException ignored) {
        }
    }

    /** Strict Private DNS configured, the network has internet but Android couldn't validate it. */
    static boolean dnsBroken(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            return nc != null && lp != null && lp.getPrivateDnsServerName() != null
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Raw IP connectivity (no DNS involved): is there any internet to fall back to? */
    static boolean internetUp() {
        for (String ip : new String[]{"1.1.1.1", "8.8.8.8"}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, 443), 5000);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context c = ctx.getApplicationContext();
        if (!SecureSettings.strictByUs(c)) {
            unregister(c);   // Lite isn't on (or the user changed DNS themselves): nothing to watch
            return;
        }
        // A new network is validated a few seconds after it appears: look at 10 s, and again at 25 s.
        final PendingResult done = goAsync();
        new Thread(new Runnable() { @Override public void run() {
            try {
                Thread.sleep(10_000);
                boolean first = SecureSettings.strictByUs(c) && dnsBroken(c);
                Thread.sleep(15_000);
                boolean second = SecureSettings.strictByUs(c) && dnsBroken(c);
                if (first && second && internetUp()) {             // two failed checks in a row, internet up
                    SecureSettings.privateDnsFix(c, true);
                    Log.i(TAG, "AdGuard DNS unreachable: Private DNS switched to Automatic");
                    Adblock.toast(c, "AdGuard DNS can't be reached here: switched to Automatic DNS so the internet keeps working.");
                    Adblock.refreshTile(c);
                }
            } catch (InterruptedException ignored) {
            } finally {
                done.finish();
            }
        } }, "lite-dns-watch").start();
    }
}

package com.pixelcontrols;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.pixelcontrols.adblock.LiteDnsWatch;

/**
 * After a phone restart: bring back what Android doesn't keep running by itself.
 *  - The VPN (Full adblock filter, or Low signal's DNS accelerator) if it was on.
 *  - The Lite adblock DNS watchdog (network callbacks and alarms don't survive a restart).
 * Everything else is a setting or permission that Android keeps.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (SecureSettings.strictByUs(c)) LiteDnsWatch.register(c);
        com.pixelcontrols.battery.BatteryWatch.schedule(c);
        // Low signal was on: Android drops Battery Saver at a restart (or once charged past 90%); put it back. The
        // original values stay backed up from the first ON, so OFF still restores them exactly.
        if (LowSignal.isActive(c)) SecureSettings.lowSignalOn(c);
        try {
            if (com.pixelcontrols.adblock.Adblock.wantedOn(c)
                    && com.pixelcontrols.adblock.Adblock.MODE_VPN.equals(com.pixelcontrols.adblock.Adblock.mode(c))
                    && android.net.VpnService.prepare(c) == null
                    && !com.pixelcontrols.adblock.AdblockVpnService.isRunning()) {
                com.pixelcontrols.adblock.Adblock.start(c);
                android.util.Log.i("BootReceiver", "VPN filter restarted after the phone restart");
            }
        } catch (RuntimeException e) {
            android.util.Log.w("BootReceiver", "could not restart the VPN filter", e);
        }
    }
}

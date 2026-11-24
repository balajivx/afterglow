#!/bin/bash
# One-shot phone setup for Pixel Controls 2.7 (USB or Wireless debugging):
#   ./install.sh            install the app, retire AA Switch, grant permissions, start the helper daemon
#   ./install.sh --check    the same, then run the on-device checks (tests/device_check.sh)
# Safe to re-run.
set -e
cd "$(dirname "$0")"
APK=build/pixelcontrols.apk
[ -f "$APK" ] || ./build.sh
find_pixel() {
    local d m
    for d in $(adb devices | awk '$2 == "device" {print $1}'); do
        m=$(adb -s "$d" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
        if echo "$m" | grep -qi pixel; then echo "$d"; return; fi
    done
}
DEV=$(find_pixel)
[ -n "$DEV" ] || { echo "No Pixel on adb. Plug in USB and tap 'Allow' on the phone."; exit 1; }
A() { adb -s "$DEV" "$@"; }
echo "Phone: $DEV ($(A shell getprop ro.product.model | tr -d '\r'), Android $(A shell getprop ro.build.version.release | tr -d '\r'))"

echo "1. Installing Pixel Controls $(sed -n 's/.*versionName "\(.*\)"/\1/p' app/build.gradle)..."
OUT=$(A install -r "$APK" 2>&1) && echo "   $OUT" || {
    echo "   $OUT"
    if ! echo "$OUT" | grep -q "UPDATE_INCOMPATIBLE\|signatures do not match"; then exit 1; fi
    # The installed copy was signed on another computer: Android only allows a fresh install. Keep its settings:
    # both builds are debuggable, so run-as can copy them out and back in.
    echo "   Installed copy is signed with another key: backing up its settings, then reinstalling..."
    mkdir -p backups
    BK=backups/pixelcontrols-data-$(date +%Y%m%d-%H%M%S).tar
    A shell am force-stop com.pixelcontrols
    if A exec-out run-as com.pixelcontrols tar cf - shared_prefs files > "$BK" 2>/dev/null && [ -s "$BK" ]; then
        echo "   settings saved to $BK"
    else
        rm -f "$BK"; BK=""
        echo "   (couldn't read the old settings: adblock lists, bypass apps and presets start fresh)"
    fi
    A uninstall com.pixelcontrols >/dev/null
    A install "$APK"
    if [ -n "$BK" ]; then
        A shell am force-stop com.pixelcontrols
        A push "$BK" /data/local/tmp/pc_restore.tar >/dev/null
        if A shell "cat /data/local/tmp/pc_restore.tar | run-as com.pixelcontrols tar xf - && echo restored" | grep -q restored; then
            echo "   settings restored"
        else
            echo "   couldn't restore settings automatically (backup kept in $BK)"
        fi
        A shell rm -f /data/local/tmp/pc_restore.tar
    fi
    echo "   Note: Android asks for VPN permission again the first time you turn on Adblock."
}

echo "2. Retiring AA Switch (Android Auto wired/wireless is now in Pixel Controls)..."
if A shell pm list packages com.balaji.aaswitch | grep -q aaswitch; then
    A shell pm uninstall com.balaji.aaswitch >/dev/null && echo "   removed" || echo "   could not remove (remove it by hand)"
else
    echo "   not installed"
fi

echo "3. Permissions..."
A shell cmd appops set --user 0 com.pixelcontrols WRITE_SETTINGS allow || true
A shell pm grant com.pixelcontrols android.permission.WRITE_SECURE_SETTINGS || true
A shell pm grant com.pixelcontrols android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
A shell pm grant com.pixelcontrols android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
A shell cmd deviceidle whitelist +com.pixelcontrols >/dev/null || true
# Do Not Disturb access: needed to switch sound profiles (ringer mode)
A shell cmd notification allow_dnd com.pixelcontrols || true
# Android 16+: let the Sound Profile tile change volumes from the Quick Settings panel (default: only from the front)
A shell cmd appops set com.pixelcontrols CONTROL_AUDIO allow 2>/dev/null || true
A shell cmd appops set com.pixelcontrols CONTROL_AUDIO_PARTIAL allow 2>/dev/null || true
# VPN permission (the one-time "Connection request" OK) for the adblock filter / Low signal DNS accelerator, so tiles
# never have to open that dialog (which would close the Quick Settings panel)
A shell cmd appops set com.pixelcontrols ACTIVATE_VPN allow 2>/dev/null || true

# Low signal gives the weak link to the app on screen: it needs to know which app that is (usage access), and
# Pixel Controls itself (its DNS filter answers every app's lookups) must stay online in the background.
A shell cmd appops set com.pixelcontrols GET_USAGE_STATS allow 2>/dev/null || true
PCUID=$(A shell cmd package list packages -U com.pixelcontrols | tr -d '\r' | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -n 1)
[ -n "$PCUID" ] && A shell cmd netpolicy add restrict-background-whitelist "$PCUID" >/dev/null 2>&1 || true
# Earlier 2.7 test builds exempted these apps from Data Saver; that would let them use data in the background, so undo it.
for pkg in com.google.android.apps.nbu.paisa.user com.phonepe.app net.one97.paytm in.org.npci.upiapp com.sbi.lotusintouch \
           com.csam.icici.bank.imobile com.snapwork.hdfc com.axis.mobile com.google.android.apps.maps com.spotify.music \
           com.google.android.apps.youtube.music com.jio.media.jiobeats com.gaana com.google.android.projection.gearhead; do
    uid=$(A shell cmd package list packages -U "$pkg" 2>/dev/null | tr -d '\r' | sed -n "s/^package:$pkg uid:\([0-9]*\).*/\1/p" | head -n 1)
    if [ -n "$uid" ]; then A shell cmd netpolicy remove restrict-background-whitelist "$uid" >/dev/null 2>&1 || true; fi
    if [ "$pkg" != com.google.android.projection.gearhead ]; then A shell cmd deviceidle whitelist -"$pkg" >/dev/null 2>&1 || true; fi
done
true

echo "4. Helper daemon (optional extras: 4G lock, Data Saver while charging, car display brightness)..."
A push start_daemon.sh /data/local/tmp/start_daemon.sh >/dev/null
A shell rm -f /data/local/tmp/start_lowsignal_daemon.sh
A shell sh /data/local/tmp/start_daemon.sh start
# open the app once: it writes the LADB copy of the script and refreshes its tiles
A shell am start -n com.pixelcontrols/.MainActivity >/dev/null 2>&1 || true

echo
echo "Done. Quick Settings: swipe down twice → pencil → add 'Android Auto' (Pixel Controls) in place of the old AA Switch tile."
if [ "$1" = "--check" ]; then
    echo
    PC_DEV=$DEV bash tests/device_check.sh
fi

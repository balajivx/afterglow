#!/bin/bash
# On-device checks for Pixel Controls 2.7 (phone on USB adb). Leaves the phone as it found it: settings are compared
# before/after, the simulated cable/car are reset, Wi-Fi is put back on, the helper is left running.
#   bash tests/device_check.sh          (PC_DEV=<serial> to pick a phone)
DEV=${PC_DEV:-$(adb devices | awk '$2 == "device" {print $1; exit}')}
[ -n "$DEV" ] || { echo "no phone on adb"; exit 1; }
A() { adb -s "$DEV" "$@"; }
# adb shell with retries (a USB blip must not turn into a false result)
sh_() { local i out; for i in 1 2 3; do out=$(A shell "$@" 2>/dev/null) && { printf '%s' "$out" | tr -d '\r'; return 0; }; A wait-for-device; sleep 1; done; return 1; }
q() { sh_ "echo '$1' | toybox nc -w 20 127.0.0.1 28888"; }
PASS=0; FAIL=0; NOTES=""
ok(){ if [ "$1" = "$2" ]; then PASS=$((PASS+1)); echo "  PASS  $3"; else FAIL=$((FAIL+1)); echo "  FAIL  $3 (got '$1', want '$2')"; fi; }
note(){ NOTES="$NOTES\n  - $*"; echo "  NOTE  $*"; }
g(){ sh_ settings get global "$1"; }
snap(){ for k in low_power low_power_sticky preferred_network_mode mobile_data_always_on network_avoid_bad_wifi private_dns_mode private_dns_specifier battery_saver_constants; do
          printf '%s=%s\n' "$k" "$(g $k)"; done; sh_ cmd netpolicy get restrict-background; }
up(){ q STATUS | grep -q '^STATUS:' && echo up || echo down; }
procs(){ sh_ "ps -A -o ARGS" | grep -cE '/data/local/tmp/(run_lowsignal_loop|pc_brightness_watch|pc_dns_watchdog)\.sh|nc -s 127.0.0.1 -p 28888'; }
hook(){ A shell am broadcast -n com.pixelcontrols/.auto.SimReceiver "$@" >/dev/null 2>&1; sleep 4; }
wifi(){ sh_ cmd wifi status | grep -ci '^wifi is enabled'; }

# Tile taps and the on-screen app test need an unlocked phone (Android asks to unlock before an app's tile runs).
A shell dumpsys battery reset 2>/dev/null          # real charger state (an interrupted run may have left "unplugged")
sh_ svc power stayon true; A shell input keyevent WAKEUP
if [ "$(sh_ dumpsys window | grep -c 'isKeyguardShowing=true')" != 0 ]; then
    echo ">> Unlock the phone (it stays awake during the checks)..."
    for i in $(seq 1 200); do [ "$(sh_ dumpsys window | grep -c "isKeyguardShowing=true")" = 0 ] && break; sleep 3; done
fi
UNLOCKED=$([ "$(sh_ dumpsys window | grep -c 'isKeyguardShowing=true')" = 0 ] && echo 1 || echo 0)
# Lite adblock left on by an interrupted run: turn it off the same way the tile does
if [ "$UNLOCKED" = 1 ] && [ "$(sh_ run-as com.pixelcontrols cat shared_prefs/adblock.xml | grep -c 'name="enabled" value="true"')" = 1 ] \
   && [ "$(sh_ settings get global private_dns_specifier)" = dns.adguard-dns.com ] && [ "$(sh_ run-as com.pixelcontrols cat shared_prefs/adblock.xml | grep -c 'mode">dns')" = 1 ]; then
    A shell cmd statusbar expand-settings; sleep 5; A shell cmd statusbar click-tile com.pixelcontrols/.adblock.AdblockTileService; sleep 5; A shell cmd statusbar collapse
    echo ">> turned off Lite adblock left on by an earlier interrupted run"
fi
[ "$UNLOCKED" = 1 ] || echo ">> still locked: tile and focus checks will be skipped"

echo "== app"
ok "$(sh_ dumpsys package com.pixelcontrols | grep -m1 versionName | sed 's/.*=//')" "2.7" "Pixel Controls 2.7 installed"
ok "$(sh_ dumpsys package com.pixelcontrols | grep -m1 -o 'targetSdk=[0-9]*')" "targetSdk=28" "targets Android 9 (Wi-Fi switching allowed)"
ok "$(sh_ pm list packages com.balaji.aaswitch | grep -c aaswitch)" "0" "AA Switch retired"
ok "$(sh_ dumpsys package com.pixelcontrols | grep -c 'WRITE_SECURE_SETTINGS: granted=true')" "1" "WRITE_SECURE_SETTINGS granted (no daemon needed for settings)"
A shell logcat -b crash -c 2>/dev/null
A shell am start -n com.pixelcontrols/.MainActivity >/dev/null 2>&1; sleep 3
ok "$(sh_ cmd appops get com.pixelcontrols GET_USAGE_STATS | grep -c allow)" "1" "usage access (knows the app on screen)"

sh_ sh /data/local/tmp/start_daemon.sh stop >/dev/null
BEFORE=$(snap)
echo "== no daemon: Low signal and Lite adblock done by the app itself"
ok "$(up)" "down" "helper stopped for this part"
hook --ez lowsignal true
ok "$(g low_power)|$(g low_power_sticky)|$(g mobile_data_always_on)|$(g network_avoid_bad_wifi)" "1|1|1|1" "Low signal ON: Battery Saver, mobile data ready, Wi-Fi handover"
ok "$(g private_dns_mode)" "opportunistic" "Low signal ON: Private DNS Automatic"
hook --ez lowsignal false
ok "$(snap)" "$BEFORE" "Low signal OFF: every setting back exactly"
hook --ez lite true
ok "$(g private_dns_mode)|$(g private_dns_specifier)" "hostname|dns.adguard-dns.com" "Lite adblock: AdGuard Private DNS set by the app"
if adb devices -l | grep "^$DEV " | grep -q 'usb:'; then
    # AdGuard unreachable on a new network: the app's watchdog must fall back to Automatic by itself
    sh_ settings put global private_dns_specifier unreachable-dot.invalid
    sh_ svc wifi disable; sleep 4; sh_ svc wifi enable
    m=""; for i in $(seq 1 12); do sleep 5; m=$(g private_dns_mode); [ "$m" = opportunistic ] && break; done
    ok "$m" "opportunistic" "Lite watchdog (no daemon): unreachable DNS on a new network -> Automatic"
fi
hook --ez lite false
ok "$(snap)" "$BEFORE" "Lite off: Private DNS back exactly"

echo "== Low signal focus: the app on screen gets the link (no helper, on the charger)"
if [ "$UNLOCKED" = 1 ]; then
    sh_ sh /data/local/tmp/start_daemon.sh stop >/dev/null
    F0=$(snap); hook --ez lowsignal true; A shell logcat -c
    # Play Store uses the phone's resolver (Chrome has its own secure DNS and never asks the phone)
    A shell am start -a android.intent.action.VIEW -d "market://details?id=com.spotify.music" com.android.vending >/dev/null 2>&1; sleep 15
    ok "$(sh_ logcat -d -s FocusGate:I | grep -c 'com.android.vending allowed (on screen)')" "1" "Play Store on screen: its lookups go out"
    A shell input keyevent HOME; sleep 75
    HB=$(sh_ logcat -d -s FocusGate:I | grep -c 'held back')
    if [ "$HB" -gt 0 ]; then ok "yes" "yes" "background apps held back ($HB app(s) in 75 s, e.g. $(sh_ logcat -d -s FocusGate:I | grep -m1 'held back' | sed 's/.*FocusGate: //'))"
    else note "no background app looked anything up in 75 s (nothing to hold back)"; fi
    hook --ez lowsignal false
    ok "$(snap)" "$F0" "Low signal off: every setting back exactly"
    sh_ sh /data/local/tmp/start_daemon.sh start >/dev/null
else
    note "phone locked: focus check skipped (unlock it and run again)"
fi

echo "== helper start / stop at will"
sh_ sh /data/local/tmp/start_daemon.sh start >/dev/null
ok "$(up)" "up" "start (Mac / USB)"
sleep 2; ok "$(procs)" "2" "loop + listener, one each"
sh_ sh /data/local/tmp/start_daemon.sh start >/dev/null
ok "$(up)" "up" "restart"
sleep 2; ok "$(procs)" "2" "still one each after restart"
hook --ez lowsignal true
ok "$(sh_ cmd netpolicy get restrict-background | grep -ci enabled)|$(g low_power)" "1|1" "Low signal ON with helper: Data Saver extra + app's Battery Saver"
hook --ez lowsignal false
ok "$(snap)" "$BEFORE" "Low signal OFF: every setting back exactly (helper + app)"
sh_ sh /data/local/tmp/start_daemon.sh stop >/dev/null
ok "$(up)" "down" "stop (./daemon.sh stop)"
ok "$(procs)" "0" "no helper processes left"
ok "$(sh_ '[ -f /data/local/tmp/pc_daemon.user_stopped ] && echo y')" "y" "remembered as stopped by you"
sh_ sh /data/local/tmp/start_daemon.sh start >/dev/null
hook --ez lowsignal true
ok "$(q SHUTDOWN)" "OK:SHUTDOWN" "app's Stop button (SHUTDOWN)"
sleep 4
ok "$(up)" "down" "helper gone after app stop"
ok "$(sh_ '[ -f /data/local/tmp/pc_datasaver_backup ] && echo y || echo n')|$(sh_ cmd phone get-allowed-network-types-for-users | grep -c NR)|$(g low_power)" "n|1|1" "stop undid the helper's extras (its Data Saver, 4G lock); app's Low signal stays on"
hook --ez lowsignal false
ok "$(snap)" "$BEFORE" "then Low signal OFF: everything back exactly"
sh_ sh /data/local/tmp/start_daemon.sh start >/dev/null
ok "$(up)" "up" "starts again"
ok "$(q AA_WIRED)|$(q PRIVDNS_AUTO)|$(q KEEP)|$(q 'AA_NIGHT_DIM status')" "ERR:unknown_cmd|ERR:unknown_cmd|ERR:unknown_cmd|ERR:unknown_cmd" "removed helper commands are gone (incl. car dimming)"

echo "== Android Auto wired/wireless (simulated car + cable)"
if ! adb devices -l | grep "^$DEV " | grep -q 'usb:'; then
    note "skipped: phone is on Wireless debugging (turning Wi-Fi off would cut this connection)"
else
    [ "$(wifi)" = 1 ] || { sh_ svc wifi enable; sleep 4; }
    A shell dumpsys battery set ac 1
    hook --es car TestCar --ez accessory true --ez connected true; sleep 3
    ok "$(wifi)" "0" "car + cable on the Android Auto port: Wi-Fi off (wired)"
    ok "$(sh_ dumpsys activity services com.pixelcontrols | grep -c 'ServiceRecord.*auto.CarService')" "1" "in-car service running"
    ok "$(up)" "up" "helper survives Wi-Fi off (started over USB, Wireless debugging not needed)"
    hook --ez accessory false --ez connected false
    A shell dumpsys battery reset; sleep 3
    ok "$(sh_ dumpsys activity services com.pixelcontrols | grep -c 'ServiceRecord.*auto.CarService')" "0" "in-car service stopped"
    ok "$(wifi)" "1" "leaving the car: Wi-Fi back on"
    ok "$(up)" "up" "helper still running"
fi

echo "== Quick Settings tiles (real taps; each put back as it was)"
[ "$UNLOCKED" = 1 ] || note "phone locked: tile taps skipped"
tap(){ A shell input keyevent WAKEUP; A shell cmd statusbar expand-settings; sleep 4; A shell cmd statusbar click-tile "com.pixelcontrols/$1"; sleep 4; A shell cmd statusbar collapse; sleep 1; }
vol(){ echo "$(sh_ cmd audio get-stream-volume 2 | grep -oE '[0-9]+$')/$(sh_ cmd audio get-stream-volume 5 | grep -oE '[0-9]+$')/$(sh_ cmd audio get-stream-volume 3 | grep -oE '[0-9]+$')"; }
if [ "$UNLOCKED" = 1 ] && [ "$(sh_ settings get secure sysui_qs_tiles | grep -c 'com.pixelcontrols/.sound.SoundProfileTileService')" = 1 ]; then
    sh_ cmd notification allow_dnd com.pixelcontrols
    V0=$(vol); MAXR=$(sh_ cmd audio get-max-volume 2 | grep -oE '[0-9]+$'); NR=$(( (MAXR * 4 + 5) / 10 )); [ $NR -lt 1 ] && NR=1
    # get to Normal first (the cycle is Normal -> Silent -> Night -> Normal)
    tap .sound.SoundProfileTileService; S1=$(vol); tap .sound.SoundProfileTileService; S2=$(vol); tap .sound.SoundProfileTileService; S3=$(vol)
    ok "$(printf '%s\n' "$S1" "$S2" "$S3" | grep -c "^0/0/0$")" "1" "Sound tile: Silent reached (all 0, vibrate)"
    ok "$(printf '%s\n' "$S1" "$S2" "$S3" | grep -c "^$NR/0/0$")" "1" "Sound tile: Night reached (ring 40%, rest 0)"
    ok "$S3" "$V0" "Sound tile: three taps = back to the starting volumes exactly"
    # the panel must stay open after a tap (the profile is applied by the tile itself, no screen opened)
    A shell input keyevent WAKEUP; A shell cmd statusbar expand-settings; sleep 4
    A shell cmd statusbar click-tile com.pixelcontrols/.sound.SoundProfileTileService; sleep 4
    ok "$(sh_ dumpsys window | grep -m1 -c 'mCurrentFocus=Window{[^ ]* [^ ]* NotificationShade')" "1" "Sound tile: Quick Settings panel stays open"
    A shell cmd statusbar click-tile com.pixelcontrols/.sound.SoundProfileTileService; sleep 5
    A shell cmd statusbar click-tile com.pixelcontrols/.sound.SoundProfileTileService; sleep 5
    A shell cmd statusbar collapse; sleep 1
    ok "$(vol)" "$V0" "Sound tile: three more taps, back to the starting volumes"
else
    note "Sound Profile tile not in Quick Settings: tile taps skipped"
fi
if [ "$UNLOCKED" = 1 ] && [ "$(sh_ settings get secure sysui_qs_tiles | grep -c 'BrightnessTileService')" = 1 ]; then
    BM=$(sh_ settings get system screen_brightness_mode); BV=$(sh_ settings get system screen_brightness)
    tap .brightness.AdaptiveBrightnessTileService
    ok "$(sh_ settings get system screen_brightness_mode)" "$([ "$BM" = 1 ] && echo 0 || echo 1)" "Brightness tile: Auto <-> M switches"
    tap .brightness.AdaptiveBrightnessTileService
    ok "$(sh_ settings get system screen_brightness_mode)" "$BM" "Brightness tile: second tap back"
    [ "$BM" = 0 ] && sh_ settings put system screen_brightness "$BV"
fi
if [ "$UNLOCKED" = 1 ] && [ "$(sh_ settings get secure sysui_qs_tiles | grep -c 'AdblockTileService')" = 1 ] && [ "$(sh_ run-as com.pixelcontrols cat shared_prefs/adblock.xml | grep -c 'name="mode">dns')" = 1 ] \
   && [ "$(sh_ run-as com.pixelcontrols cat shared_prefs/adblock.xml | grep -c 'name="enabled" value="true"')" = 0 ]; then
    D0=$(g private_dns_mode)/$(g private_dns_specifier)
    tap .adblock.AdblockTileService
    ok "$(g private_dns_mode)/$(g private_dns_specifier)" "hostname/dns.adguard-dns.com" "Adblock tile: Lite on"
    tap .adblock.AdblockTileService
    ok "$(g private_dns_mode)/$(g private_dns_specifier)" "$D0" "Adblock tile: off, Private DNS back exactly"
fi
if [ "$UNLOCKED" = 1 ] && [ "$(sh_ settings get secure sysui_qs_tiles | grep -c 'com.pixelcontrols/.auto.AutoTileService')" = 1 ]; then
    W0=$(wifi); tap .auto.AutoTileService
    ok "$(wifi)" "$W0" "Android Auto tile outside the car: Wi-Fi left alone"
fi
ok "$(sh_ settings get secure sysui_qs_tiles | grep -c 'AutoDimTileService')" "0" "AA Dim tile gone from Quick Settings"
if [ "$UNLOCKED" = 1 ] && [ "$(sh_ settings get secure sysui_qs_tiles | grep -c 'LowSignalTileService')" = 1 ]; then
    sh_ cmd appops set com.pixelcontrols ACTIVATE_VPN allow
    sh_ sh /data/local/tmp/start_daemon.sh stop >/dev/null   # no helper: everything below must come from the app alone
    L0=$(snap); sh_ dumpsys battery unplug      # on battery, so Battery Saver really switches (and could flip the theme)
    focus(){ sh_ dumpsys window | grep -m1 -oE 'mCurrentFocus=Window\{[^ ]+ [^ ]+ [A-Za-z.]+' | awk '{print $NF}'; }
    A shell input keyevent WAKEUP; A shell cmd statusbar expand-settings; sleep 4; A shell logcat -c
    A shell cmd statusbar click-tile com.pixelcontrols/.lowsignal.LowSignalTileService; sleep 7
    ok "$(focus)" "NotificationShade" "Low signal tile ON: Quick Settings panel stays open"
    ok "$(sh_ dumpsys power | grep -c 'Battery Saver is currently: ON')" "1" "Low signal tile ON: Battery Saver on"
    ok "$(sh_ logcat -d | grep -c 'Config changes=200')" "0" "Low signal tile ON: theme not changed"
    ok "$(sh_ dumpsys activity services com.pixelcontrols | grep -c 'ServiceRecord.*AdblockVpnService')" "1" "Low signal tile ON: DNS accelerator running"
    ok "$(g mobile_data_always_on)|$(g network_avoid_bad_wifi)" "1|1" "Low signal tile ON: mobile data ready, Wi-Fi handover"
    ok "$(sh_ cmd netpolicy get restrict-background | grep -c enabled)" "1" "Low signal tile ON (no helper): Data Saver on"
    PCUID=$(sh_ cmd package list packages -U com.pixelcontrols | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
    ok "$(sh_ cmd netpolicy list restrict-background-whitelist | grep -cw "$PCUID")" "1" "Pixel Controls exempt from Data Saver (permanent, no helper)"
    A shell cmd statusbar click-tile com.pixelcontrols/.lowsignal.LowSignalTileService; sleep 8
    ok "$(focus)" "NotificationShade" "Low signal tile OFF: panel still open"
    A shell cmd statusbar collapse; sh_ dumpsys battery reset; sleep 2
    ok "$(snap)" "$L0" "Low signal tile OFF: every setting back exactly"
    sh_ sh /data/local/tmp/start_daemon.sh start >/dev/null
fi
sh_ settings put global stay_on_while_plugged_in 0
A shell dumpsys battery reset 2>/dev/null
ok "$(sh_ logcat -d -b crash | grep -c com.pixelcontrols)" "0" "no Pixel Controls crashes during the checks"

echo
echo "device: $PASS passed, $FAIL failed"
[ -n "$NOTES" ] && printf "notes:$NOTES\n"
[ $FAIL -eq 0 ]

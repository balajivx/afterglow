#!/bin/bash
# Battery watch feasibility probe on the phone (USB): which per-app battery signals Android gives Pixel Controls.
#   bash tests/battery_probe.sh
A() { adb "$@"; }
A shell pm grant com.pixelcontrols android.permission.BATTERY_STATS 2>&1 | sed 's/^/grant: /'
A shell logcat -c
A shell am broadcast -n com.pixelcontrols/.auto.SimReceiver --ez batteryprobe true >/dev/null
for i in $(seq 1 20); do sleep 1; A shell logcat -d -s PCBattery:I | grep -q "done" && break; done
A shell logcat -d -s PCBattery:I | sed 's/^.*PCBattery: //'

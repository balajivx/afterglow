#!/bin/bash
# Goes back to the stock Google TV launcher.
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm uninstall --user 0 com.balaji.tvhome
adb shell input keyevent KEYCODE_HOME
echo "Stock launcher restored."

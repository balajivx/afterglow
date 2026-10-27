#!/bin/bash
# Installs the launcher over ADB (push + pm install: plain "adb install" can hang on Android 14).
set -e
PKG=com.balaji.tvhome
APK="$(cd "$(dirname "$0")" && pwd)/build/tvhome.apk"
adb push "$APK" /data/local/tmp/tvhome.apk
adb shell pm install -r /data/local/tmp/tvhome.apk
adb shell rm /data/local/tmp/tvhome.apk
# Lets the Wi-Fi pill read the network name
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
# Lets the home screen turn the picture keys back on if Android ever turns them off
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS || true
echo "Installed. Next: ./activate.sh"

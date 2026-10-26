#!/bin/bash
# Makes the new launcher the default Home and checks that it really took over.
PKG=com.balaji.tvhome
adb shell cmd package set-home-activity $PKG/.MainActivity
adb shell input keyevent KEYCODE_HOME
sleep 2
echo "--- focused app (should mention $PKG) ---"
adb shell dumpsys activity activities | grep mFocusedApp
echo "--- Home apps ---"
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME | grep -o "packageName=[^ ]*" | sort -u

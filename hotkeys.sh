#!/bin/bash
# Turns on the picture hotkeys (the remote's number keys, in any app): Afterglow's accessibility service.
SVC=com.balaji.tvhome/com.balaji.tvhome.Hotkeys
now=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case ":$now:" in
  *":$SVC:"*) ;;
  ":null:"|"::") adb shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) adb shell settings put secure enabled_accessibility_services "$now:$SVC" ;;
esac
adb shell settings put secure accessibility_enabled 1
echo "Picture keys on. Press 0 on the remote to see them."

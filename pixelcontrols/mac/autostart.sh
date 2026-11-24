#!/bin/bash
# Mac auto-start for the Pixel Controls helper: whenever the Pixel is on USB and the helper isn't running, start it.
# It never restarts a helper you stopped yourself (app's Stop button / ./daemon.sh stop) until you start it again.
#   mac/autostart.sh install    run every minute in the background (LaunchAgent)
#   mac/autostart.sh remove     stop doing that
#   mac/autostart.sh once       one check now (what the LaunchAgent runs)
DIR=$(cd "$(dirname "$0")/.." && pwd)
LABEL=com.afterglow.pixelcontrols.autostart
PLIST=$HOME/Library/LaunchAgents/$LABEL.plist
LOG=$HOME/Library/Logs/pixelcontrols-autostart.log
ADB=$(command -v adb || echo /opt/homebrew/bin/adb)
case "${1:-once}" in
    install)
        mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
        cat > "$PLIST" <<P
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>$DIR/mac/autostart.sh</string><string>once</string></array>
  <key>StartInterval</key><integer>60</integer>
  <key>RunAtLoad</key><true/>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>$(dirname "$ADB"):/usr/bin:/bin</string></dict>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict></plist>
P
        launchctl unload "$PLIST" 2>/dev/null; launchctl load "$PLIST" && echo "installed: checks every minute (log: $LOG)" ;;
    remove)
        launchctl unload "$PLIST" 2>/dev/null; rm -f "$PLIST"; echo "removed" ;;
    once)
        for d in $("$ADB" devices 2>/dev/null | awk '$2 == "device" {print $1}'); do
            # USB only (not Wireless debugging), and only the Pixel
            "$ADB" devices -l | grep "^$d " | grep -q "usb:" || continue
            "$ADB" -s "$d" shell getprop ro.product.model 2>/dev/null | grep -qi pixel || continue
            st=$("$ADB" -s "$d" shell 'if echo STATUS | toybox nc -w 3 127.0.0.1 28888 2>/dev/null | grep -q "^STATUS:"; then echo up;
                                         elif [ -f /data/local/tmp/pc_daemon.user_stopped ]; then echo user_stopped; else echo down; fi' | tr -d '\r')
            if [ "$st" = down ]; then
                "$ADB" -s "$d" push "$DIR/start_daemon.sh" /data/local/tmp/start_daemon.sh >/dev/null 2>&1 &&
                "$ADB" -s "$d" shell 'PC_BY=mac-autostart sh /data/local/tmp/start_daemon.sh start' >/dev/null &&
                echo "$(date '+%F %T') $d: helper started"
            fi
        done ;;
    *) echo "usage: mac/autostart.sh install|remove|once"; exit 2 ;;
esac

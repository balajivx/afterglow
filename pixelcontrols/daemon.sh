#!/bin/bash
# Start / stop / check the Pixel Controls helper daemon from this Mac (phone on USB or Wireless debugging).
#   ./daemon.sh start | stop | restart | status | log      [-s SERIAL]
# On the phone itself, LADB runs the same script (Pixel Controls → Network → Start copies the command).
cd "$(dirname "$0")"
S=(); if [ "$2" = "-s" ] && [ -n "$3" ]; then S=(-s "$3"); fi
adb "${S[@]}" get-state >/dev/null 2>&1 || { echo "No phone on adb. Plug in USB (or connect Wireless debugging) and allow debugging."; exit 1; }
push() { adb "${S[@]}" push start_daemon.sh /data/local/tmp/start_daemon.sh >/dev/null; }
case "${1:-status}" in
    start)   push && adb "${S[@]}" shell sh /data/local/tmp/start_daemon.sh start ;;
    stop)    adb "${S[@]}" shell '[ -f /data/local/tmp/start_daemon_ctl.sh ] && sh /data/local/tmp/start_daemon_ctl.sh stop || echo "not installed"' ;;
    restart) push && adb "${S[@]}" shell sh /data/local/tmp/start_daemon.sh stop; adb "${S[@]}" shell sh /data/local/tmp/start_daemon.sh start ;;
    status)  adb "${S[@]}" shell '[ -f /data/local/tmp/start_daemon_ctl.sh ] && sh /data/local/tmp/start_daemon_ctl.sh status || echo "not installed"' ;;
    log)     adb "${S[@]}" shell 'tail -n 40 /data/local/tmp/pc_daemon.log /data/local/tmp/pc_watchdog.log 2>/dev/null' ;;
    *) echo "usage: ./daemon.sh start|stop|restart|status|log [-s SERIAL]"; exit 2 ;;
esac

#!/system/bin/sh
# PixelControls helper daemon: the two Low signal extras Android reserves for the adb shell.
#   - the 4G lock (stay off a weak 5G signal)
#   - Data Saver while charging (on battery the app switches it on itself, through Battery Saver)
# Everything else (adblock, Private DNS, Low signal's own settings and app focus) is done by the app, no helper.
# It must run as the adb shell user, so it is started through adb from a computer (USB): ./daemon.sh start
#   sh start_daemon.sh          start (or restart) the daemon
#   sh start_daemon.sh stop     stop it: undoes Low signal mode, puts Private DNS back, ends every daemon process
#   sh start_daemon.sh status   running or not, and how the last run ended
# Why it can stop by itself: everything started through adb lives in adbd's process group, and when adbd restarts
# Android kills that whole group. adbd restarts when Wireless debugging turns off (Wi-Fi off or disconnected,
# e.g. wired Android Auto in the car), when USB debugging is toggled, and on some USB mode changes. The daemon
# itself never touches Wi-Fi or USB; its state lives in files, so a restart carries on where it was.

T=${PC_T:-/data/local/tmp}

# stop / status don't need fresh scripts (and must not rewrite scripts that are running)
case "$1" in
    stop|status) [ -f $T/start_daemon_ctl.sh ] && PC_T=$T exec ${PC_SH:-/system/bin/sh} $T/start_daemon_ctl.sh "$1" ;;
    ""|start) ;;
    *) echo "usage: sh start_daemon.sh [start|stop|status]"; exit 2 ;;
esac

# 1. Write the helper logic (one command per connection, one reply line)
cat << EOF > $T/lowsignal_helper.sh.new
#!/system/bin/sh
T=$T
EOF
cat << 'EOF' >> $T/lowsignal_helper.sh.new
LS_MARK=$T/pc_lowsignal_on
DS_BACKUP=$T/pc_datasaver_backup
OLD_KEEP=$T/pc_keepalive_added       # exemptions added by versions before 2.7

while read -r line; do
    cmd=$(echo "$line" | tr -d '\r\n')
    set -- $cmd
    cmd="$1"; shift
    case "$cmd" in
        BOOST_ON)
            # Low signal extras. Data Saver: background apps stop using the weak link (the app does this itself on
            # battery through Battery Saver; this also covers charging). 4G lock: no hunting for a weak 5G signal.
            touch "$LS_MARK"
            if [ ! -f "$DS_BACKUP" ]; then
                if cmd netpolicy get restrict-background </dev/null 2>/dev/null | grep -qi "enabled"; then
                    echo enabled > "$DS_BACKUP"; else echo disabled > "$DS_BACKUP"; fi
            fi
            cmd netpolicy set restrict-background true </dev/null >/dev/null 2>&1
            cmd phone set-allowed-network-types-for-users -s 0 01111111111111111111 </dev/null >/dev/null 2>&1
            echo "OK:BOOST_ON"
            ;;
        OFF)
            # Undo only what BOOST_ON changed; nothing of ours on = change nothing (the app sends OFF every time).
            if [ -f "$LS_MARK" ] || [ -f "$DS_BACKUP" ]; then
                if [ -f "$DS_BACKUP" ] && [ "$(cat "$DS_BACKUP")" = "enabled" ]; then
                    cmd netpolicy set restrict-background true </dev/null >/dev/null 2>&1
                else
                    cmd netpolicy set restrict-background false </dev/null >/dev/null 2>&1
                fi
                cmd phone set-allowed-network-types-for-users -s 0 11111111111111111111 </dev/null >/dev/null 2>&1
                rm -f "$LS_MARK" "$DS_BACKUP"
            fi
            if [ -f "$OLD_KEEP" ]; then
                while read -r kind a b; do
                    case "$kind" in
                        net) cmd netpolicy remove restrict-background-whitelist "$a" </dev/null >/dev/null 2>&1 ;;
                        idle) cmd deviceidle whitelist -"$a" </dev/null >/dev/null 2>&1 ;;
                    esac
                done < "$OLD_KEEP"
                rm -f "$OLD_KEEP"
            fi
            echo "OK:OFF"
            ;;
        STATUS)
            saver=$(cmd netpolicy get restrict-background </dev/null 2>/dev/null | grep -ic "enabled")
            types=$(cmd phone get-allowed-network-types-for-users </dev/null 2>/dev/null | grep -ic "NR")
            ls=0; [ -f "$LS_MARK" ] && ls=1
            since=$(cat "$T/pc_daemon.running" 2>/dev/null)
            echo "STATUS:saver=$saver:has_nr=$types:low_signal=$ls:since=${since:-0}"
            ;;
        SHUTDOWN)
            # Stop button in the app. Reply first, then stop everything in the background once this
            # connection has closed (the listener we're answering through is one of the things stopped).
            echo "OK:SHUTDOWN"
            nohup ${PC_SH:-/system/bin/sh} $T/start_daemon_ctl.sh stop app </dev/null >/dev/null 2>&1 &
            ;;
        *)
            echo "ERR:unknown_cmd"
            ;;
    esac
    break
done
EOF
chmod 755 $T/lowsignal_helper.sh.new && mv -f $T/lowsignal_helper.sh.new $T/lowsignal_helper.sh

cat << EOF > $T/run_lowsignal_loop.sh.new
#!/system/bin/sh
echo \$\$ > $T/pc_loop.pid
# stop as soon as this loop isn't the current one (a restart may start a new loop while this one sleeps)
while [ -f $T/pc_daemon.running ] && [ "\$(cat $T/pc_loop.pid 2>/dev/null)" = "\$\$" ]; do
    toybox nc -s 127.0.0.1 -p 28888 -L $T/lowsignal_helper.sh </dev/null >/dev/null 2>&1
    sleep 1
done
EOF
chmod 755 $T/run_lowsignal_loop.sh.new && mv -f $T/run_lowsignal_loop.sh.new $T/run_lowsignal_loop.sh

# 3. start / stop / status. Saved as start_daemon_ctl.sh so the helper's SHUTDOWN command can run "stop".
cat << 'CTLEOF' > $T/start_daemon_ctl.sh.new
#!/system/bin/sh
T=${PC_T:-/data/local/tmp}
SH=${PC_SH:-/system/bin/sh}
LOG=$T/pc_daemon.log
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }
# keep the log small
[ -f "$LOG" ] && [ "$(wc -l < "$LOG")" -gt 300 ] && { tail -n 150 "$LOG" > "$LOG.tmp"; mv "$LOG.tmp" "$LOG"; }

# Stop the process in pid file $1 if it is still running script $2 (after a phone restart a pid file can name an
# unrelated process, even this script).
stop_pidfile() {
    [ -f "$1" ] || return 0
    pid=$(cat "$1"); rm -f "$1"
    case "$pid" in ""|*[!0-9]*) return 0 ;; esac
    [ "$pid" = "$$" ] && return 0
    { tr '\0' ' ' 2>/dev/null < /proc/$pid/cmdline || ps -o args= -p "$pid" 2>/dev/null; } | grep -q "$2" || return 0
    pkill -P "$pid" 2>/dev/null
    kill "$pid" 2>/dev/null
}

stop_jobs() {
    rm -f $T/pc_daemon.running          # the loop runner exits instead of restarting the listener
    stop_pidfile $T/pc_watchdog.pid pc_dns_watchdog      # left by versions before 2.7
    stop_pidfile $T/pc_brightness_watch.pid pc_brightness_watch   # left by versions before 2.7
    stop_pidfile $T/pc_aa_watch.pid pc_aa_watch          # left by versions before 2.7
    stop_pidfile $T/pc_loop.pid run_lowsignal_loop
    # only our own listener (port 28888), never someone else's nc
    for p in $(pgrep -f "toybox nc -s 127\.0\.0\.1 -p 28888 -L " 2>/dev/null); do
        [ "$p" = "$$" ] || kill "$p" 2>/dev/null
    done
}

# Our listener answers STATUS; if the nc client misbehaves, a LISTEN socket on 127.0.0.1:28888 (hex 70D8) counts.
listening() {
    echo STATUS | toybox nc -w 2 127.0.0.1 28888 2>/dev/null | grep -q "^STATUS:" && return 0
    grep -q "0100007F:70D8 00000000:0000 0A" /proc/net/tcp 2>/dev/null
}

case "${1:-start}" in
    start)
        # Marked running but nobody answers: the previous run was killed rather than stopped.
        if [ -f $T/pc_daemon.running ] && ! listening; then
            log "previous run ended without a stop (adbd restarted: Wireless debugging/USB debugging off or a USB mode change)"
        fi
        stop_jobs
        # Versions before 2.7 switched Wi-Fi off for wired Android Auto and could die before turning it back on.
        if [ -f $T/pc_aa_wifi_off ]; then
            rm -f $T/pc_aa_wifi_off $T/pc_aa_job.pid
            svc wifi enable </dev/null >/dev/null 2>&1
            log "Wi-Fi turned back on (left off by an older version)"
        fi
        # files of jobs older versions ran (Android Auto watcher, DNS watchdog: both are in the app now)
        rm -f $T/pc_aa_job.pid $T/pc_aa_state $T/pc_aa_watch.sh $T/pc_dns_watchdog.sh $T/pc_strict_by_us $T/pc_dns_fallback \
              $T/pc_brightness_watch.sh $T/pc_brightness_watch.log $T/pc_aa_brightness.state $T/pc_aa_watch.log $T/pc_watchdog.log
        date +%s > $T/pc_daemon.running
        rm -f $T/pc_daemon.user_stopped
        nohup $SH $T/run_lowsignal_loop.sh </dev/null >/dev/null 2>&1 &
        n=0; while [ $n -lt 20 ] && ! listening; do sleep 0.25; n=$((n + 1)); done
        if listening; then
            log "started (${PC_BY:-adb})"
            echo "PixelControls helper running on 127.0.0.1:28888 (Low signal extras: 4G lock, Data Saver while charging)"
        else
            log "start failed: listener did not come up"
            echo "PixelControls helper FAILED to start (port 28888 busy?)"; exit 1
        fi
        ;;
    stop)
        # Put back everything the daemon changed, then end every daemon process.
        echo OFF | $SH $T/lowsignal_helper.sh >/dev/null 2>&1      # undoes Data Saver / 4G lock if they're on
        [ "$2" = app ] && sleep 1          # let the app's connection close first
        stop_jobs
        # stopped on purpose: the Mac's auto-start leaves it stopped until you start it again
        [ "$2" != auto ] && touch $T/pc_daemon.user_stopped
        log "stopped (${2:-adb})"
        echo "PixelControls helper stopped (its extras undone)"
        ;;
    status)
        if listening; then
            echo "running since $(date -d @"$(cat $T/pc_daemon.running 2>/dev/null)" 2>/dev/null)"
        else
            echo "not running"
        fi
        echo "last events:"; tail -n 5 "$LOG" 2>/dev/null
        ;;
    *)
        echo "usage: sh start_daemon.sh [start|stop|status]"; exit 2 ;;
esac
CTLEOF
chmod 755 $T/start_daemon_ctl.sh.new && mv -f $T/start_daemon_ctl.sh.new $T/start_daemon_ctl.sh

PC_T=$T exec ${PC_SH:-/system/bin/sh} $T/start_daemon_ctl.sh "${1:-start}"

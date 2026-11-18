#!/system/bin/sh
# PixelControls helper daemon. Run once per boot from a computer:
#   adb push start_daemon.sh /data/local/tmp/ && adb shell sh /data/local/tmp/start_daemon.sh
# Runs as the ADB shell user so it can change global settings an app can't.

T=/data/local/tmp

# 1. Write the helper logic (one command per connection, one reply line)
cat << 'EOF' > $T/lowsignal_helper.sh
#!/system/bin/sh
DNS_BACKUP=/data/local/tmp/pc_privdns_backup
STRICT_MARK=/data/local/tmp/pc_strict_by_us
FALLBACK_MARK=/data/local/tmp/pc_dns_fallback
LS_MARK=/data/local/tmp/pc_lowsignal_on
SET_BACKUP=/data/local/tmp/pc_settings_backup
DS_BACKUP=/data/local/tmp/pc_datasaver_backup
KEEP=/data/local/tmp/pc_keepalive_added

# Remember a setting's value before Low signal mode changes it (first time only).
save_setting() {
    grep -q "^$1 $2 " "$SET_BACKUP" 2>/dev/null && return 0
    echo "$1 $2 $(settings get "$1" "$2" 2>/dev/null)" >> "$SET_BACKUP"
}

restore_settings() {
    [ -f "$SET_BACKUP" ] || return 1
    while read -r ns key val; do
        if [ -z "$val" ] || [ "$val" = "null" ]; then settings delete "$ns" "$key" </dev/null >/dev/null 2>&1
        else settings put "$ns" "$key" "$val" </dev/null >/dev/null 2>&1; fi
    done < "$SET_BACKUP"
    rm -f "$SET_BACKUP"
    return 0
}

# The app on screen right now (package name), or nothing.
foreground_pkg() {
    dumpsys activity activities </dev/null 2>/dev/null | grep -m 1 -E "topResumedActivity|mResumedActivity" \
        | sed -n 's/.* u[0-9][0-9]* \([A-Za-z0-9_.]*\)\/.*/\1/p'
}

# Let one app keep using data in the background while Data Saver and Battery Saver are on.
# Only exemptions we add are recorded, so OFF never removes ones the user or system made.
keep_alive() {
    pkg="$1"
    [ -n "$pkg" ] || return 0
    uid=$(cmd package list packages -U "$pkg" </dev/null 2>/dev/null | sed -n "s/^package:$pkg uid:\([0-9][0-9]*\).*/\1/p" | head -n 1)
    [ -n "$uid" ] || return 0
    if ! cmd netpolicy list restrict-background-whitelist </dev/null 2>/dev/null | grep -qw "$uid"; then
        cmd netpolicy add restrict-background-whitelist "$uid" </dev/null >/dev/null 2>&1 && echo "net $uid $pkg" >> "$KEEP"
    fi
    if ! cmd deviceidle whitelist </dev/null 2>/dev/null | grep -q ",$pkg,"; then
        cmd deviceidle whitelist +"$pkg" </dev/null >/dev/null 2>&1 && echo "idle $pkg" >> "$KEEP"
    fi
}

release_all() {
    [ -f "$KEEP" ] || return 0
    while read -r kind a b; do
        case "$kind" in
            net) cmd netpolicy remove restrict-background-whitelist "$a" </dev/null >/dev/null 2>&1 ;;
            idle) cmd deviceidle whitelist -"$a" </dev/null >/dev/null 2>&1 ;;
        esac
    done < "$KEEP"
    rm -f "$KEEP"
}

backup_dns() {
    # Remember the user's own Private DNS setting once, so we can put it back exactly.
    if [ ! -f "$DNS_BACKUP" ]; then
        m=$(settings get global private_dns_mode 2>/dev/null)
        s=$(settings get global private_dns_specifier 2>/dev/null)
        echo "${m}|${s}" > "$DNS_BACKUP"
    fi
}

restore_dns() {
    [ -f "$DNS_BACKUP" ] || return 0
    IFS='|' read -r m s < "$DNS_BACKUP"
    if [ -z "$m" ] || [ "$m" = "null" ]; then settings delete global private_dns_mode >/dev/null 2>&1
    else settings put global private_dns_mode "$m"; fi
    if [ -z "$s" ] || [ "$s" = "null" ]; then settings delete global private_dns_specifier >/dev/null 2>&1
    else settings put global private_dns_specifier "$s"; fi
    rm -f "$DNS_BACKUP"
}

while read -r line; do
    cmd=$(echo "$line" | tr -d '\r\n')
    case "$cmd" in
        ON)
            touch "$LS_MARK"
            # Remember everything we change so OFF can put it back exactly.
            if [ ! -f "$DS_BACKUP" ]; then
                if cmd netpolicy get restrict-background </dev/null 2>/dev/null | grep -qi "enabled"; then
                    echo enabled > "$DS_BACKUP"; else echo disabled > "$DS_BACKUP"; fi
            fi
            for k in low_power low_power_sticky battery_saver_constants mobile_data_always_on \
                     network_avoid_bad_wifi preferred_network_mode preferred_network_mode1 preferred_network_mode2; do
                save_setting global "$k"
            done
            # Data Saver: background apps stop competing for the weak link, so the app you're
            # using gets all of it.
            cmd netpolicy set restrict-background true </dev/null >/dev/null 2>&1
            # Stay on LTE/3G/2G instead of hunting for a weak 5G signal (drops only the 5G bit).
            cmd phone set-allowed-network-types-for-users -s 0 01111111111111111111 </dev/null >/dev/null 2>&1
            settings put global battery_saver_constants enable_datasaver=true
            settings put global low_power 1
            settings put global low_power_sticky 1
            settings put global preferred_network_mode 9,9
            settings put global preferred_network_mode1 9
            settings put global preferred_network_mode2 9
            # Keep mobile data ready, and leave a weak Wi-Fi for mobile data quickly.
            settings put global mobile_data_always_on 1
            settings put global network_avoid_bad_wifi 1
            # Weak signal: strict Private DNS (DNS-over-TLS, port 853) is the first thing to fail,
            # and Android never falls back from strict mode, so every lookup dies and the phone
            # reports "no Internet access". Automatic mode encrypts when the network allows it and
            # falls back to plain DNS when it doesn't. The user's own setting is backed up.
            backup_dns
            rm -f "$STRICT_MARK"
            settings put global private_dns_mode opportunistic
            # Keep the app you're using, and PixelControls (its adblock DNS filter answers every
            # app's lookups), online even when they're in the background.
            keep_alive com.pixelcontrols
            keep_alive "$(foreground_pkg)"
            echo "OK:ON"
            ;;
        OFF)
            rm -f "$LS_MARK"
            if [ -f "$DS_BACKUP" ] && [ "$(cat "$DS_BACKUP")" = "enabled" ]; then
                cmd netpolicy set restrict-background true </dev/null >/dev/null 2>&1
            else
                cmd netpolicy set restrict-background false </dev/null >/dev/null 2>&1
            fi
            rm -f "$DS_BACKUP"
            cmd phone set-allowed-network-types-for-users -s 0 11111111111111111111 </dev/null >/dev/null 2>&1
            if ! restore_settings; then
                # Turned on by an older version (no backup): fall back to sensible defaults.
                settings put global low_power 0
                settings put global low_power_sticky 0
                settings put global battery_saver_constants enable_datasaver=false
                MANUF=$(getprop ro.product.manufacturer 2>/dev/null)
                if [ "$MANUF" = "Xiaomi" ] || [ "$MANUF" = "xiaomi" ]; then NORM="32"; else NORM="27"; fi
                settings put global preferred_network_mode "${NORM},${NORM}"
                settings put global preferred_network_mode1 "$NORM"
                settings put global preferred_network_mode2 "$NORM"
                settings put global mobile_data_always_on 1
            fi
            restore_dns          # back to exactly what the user had before
            release_all
            echo "OK:OFF"
            ;;
        KEEP\ *)
            # The app asks to keep a specific app online (e.g. UPI/banking) during Low signal mode.
            pkg=${cmd#KEEP }
            case "$pkg" in
                ""|*[!A-Za-z0-9_.]*) echo "ERR:bad_package" ;;
                *)  if [ -f "$LS_MARK" ]; then keep_alive "$pkg"; echo "OK:KEEP"; else echo "ERR:low_signal_off"; fi ;;
            esac
            ;;
        KEEPALIVE_FG)
            # Called every few seconds by the watchdog while Low signal mode is on.
            if [ -f "$LS_MARK" ]; then
                fg=$(foreground_pkg)
                case "$fg" in
                    ""|*launcher*|com.android.systemui|com.pixelcontrols) ;;
                    *)  n=0; [ -f "$KEEP" ] && n=$(grep -c "^idle" "$KEEP")
                        [ "$n" -lt 12 ] && keep_alive "$fg" ;;
                esac
            fi
            echo "OK:KEEPALIVE_FG"
            ;;
        STATUS)
            saver=$(cmd netpolicy get restrict-background </dev/null 2>/dev/null | grep -ic "enabled")
            types=$(cmd phone get-allowed-network-types-for-users </dev/null 2>/dev/null | grep -ic "NR")
            lp=$(settings get global low_power 2>/dev/null)
            pdns=$(settings get global private_dns_mode 2>/dev/null)
            fb=0; [ -f "$FALLBACK_MARK" ] && fb=1
            ls=0; [ -f "$LS_MARK" ] && ls=1
            kept=0; [ -f "$KEEP" ] && kept=$(grep -c "^idle" "$KEEP")
            echo "STATUS:saver=$saver:has_nr=$types:low_power=$lp:private_dns=$pdns:dns_fallback=$fb:low_signal=$ls:kept=$kept"
            ;;
        DSLR_ON)
            settings put system display_color_mode 0 </dev/null >/dev/null 2>&1
            settings put secure display_color_mode 0 </dev/null >/dev/null 2>&1
            settings put system accelerometer_rotation 1 </dev/null >/dev/null 2>&1
            settings put global low_power 0 </dev/null >/dev/null 2>&1
            echo "OK:DSLR_ON"
            ;;
        DSLR_OFF)
            settings put system display_color_mode 3 </dev/null >/dev/null 2>&1
            settings put secure display_color_mode 3 </dev/null >/dev/null 2>&1
            echo "OK:DSLR_OFF"
            ;;
        ADBLOCK_DNS_ON)
            # Lite adblock: system-wide Private DNS through AdGuard DNS (blocks ads + trackers)
            backup_dns
            rm -f "$FALLBACK_MARK"
            settings put global private_dns_mode hostname
            settings put global private_dns_specifier dns.adguard-dns.com
            touch "$STRICT_MARK"     # the watchdog may only undo strict DNS that we set
            echo "OK:ADBLOCK_DNS_ON"
            ;;
        ADBLOCK_DNS_OFF)
            rm -f "$STRICT_MARK" "$FALLBACK_MARK"
            restore_dns
            echo "OK:ADBLOCK_DNS_OFF"
            ;;
        PRIVDNS_AUTO)
            # Full filter: strict Private DNS would bypass the app's local filter
            backup_dns
            rm -f "$STRICT_MARK"
            settings put global private_dns_mode opportunistic
            echo "OK:PRIVDNS_AUTO"
            ;;
        PRIVDNS_FIX)
            # Network doctor: strict Private DNS is unreachable and the phone has no internet.
            settings put global private_dns_mode opportunistic
            rm -f "$STRICT_MARK"
            echo "OK:PRIVDNS_FIX"
            ;;
        PRIVDNS_RESTORE)
            restore_dns
            echo "OK:PRIVDNS_RESTORE"
            ;;
        *)
            echo "ERR:unknown_cmd"
            ;;
    esac
    break
done
EOF
chmod 755 $T/lowsignal_helper.sh

# 0. Clean up after older PixelControls versions, whose Low signal mode left strict
#    one.one.one.one Private DNS behind ("Private DNS server cannot be accessed").
if [ "$(settings get global private_dns_mode 2>/dev/null)" = "hostname" ] \
   && [ "$(settings get global private_dns_specifier 2>/dev/null)" = "one.one.one.one" ] \
   && [ ! -f $T/pc_privdns_backup ]; then
    settings put global private_dns_mode opportunistic
    settings delete global private_dns_specifier >/dev/null 2>&1
    echo "Fixed: Private DNS left in strict mode by an older version is now Automatic"
fi

# 2a. Stop a previous DNS watchdog
if [ -f $T/pc_watchdog.pid ]; then
    OLDW=$(cat $T/pc_watchdog.pid)
    kill "$OLDW" 2>/dev/null
    pkill -P "$OLDW" 2>/dev/null
    rm -f $T/pc_watchdog.pid
fi

# 2b. Stop any previous instance (the loop runner too, not just nc, so loops don't pile up)
if [ -f $T/pc_loop.pid ]; then
    OLD=$(cat $T/pc_loop.pid)
    kill "$OLD" 2>/dev/null          # the loop shell defers this until its child exits...
    pkill -P "$OLD" 2>/dev/null      # ...so also stop the listener it is waiting on
    rm -f $T/pc_loop.pid
fi
killall nc 2>/dev/null || true

# 3. Start loop runner
cat << 'EOF' > $T/run_lowsignal_loop.sh
#!/system/bin/sh
echo $$ > /data/local/tmp/pc_loop.pid
while true; do
    toybox nc -s 127.0.0.1 -p 28888 -L /data/local/tmp/lowsignal_helper.sh </dev/null >/dev/null 2>&1
    sleep 1
done
EOF
chmod 755 $T/run_lowsignal_loop.sh

nohup /system/bin/sh $T/run_lowsignal_loop.sh </dev/null >/dev/null 2>&1 &
# 4. DNS watchdog. If strict Private DNS that WE set (Lite adblock) can't be reached while the
#    internet itself works (weak signal, carrier blocking port 853), fall back to Automatic so the
#    phone keeps working. Needs 2 failed checks in a row; never touches a strict DNS the user set.
cat << 'WDEOF' > $T/pc_dns_watchdog.sh
#!/system/bin/sh
T=/data/local/tmp
INTERVAL=${PC_WD_INTERVAL:-10}
echo $$ > $T/pc_watchdog.pid
fails=0
while true; do
    sleep "$INTERVAL"
    # Low signal mode: keep whichever app is on screen online in the background too.
    [ -f $T/pc_lowsignal_on ] && echo KEEPALIVE_FG | /system/bin/sh $T/lowsignal_helper.sh >/dev/null 2>&1
    if [ "$(settings get global private_dns_mode 2>/dev/null)" != "hostname" ] || [ ! -f $T/pc_strict_by_us ]; then
        fails=0; continue
    fi
    # ping exit codes: 0 = reply, 1 = name resolved but no reply, 2 = name could not be resolved
    timeout 15 ping -c 1 -W 5 dns.google >/dev/null 2>&1; dnsrc=$?
    if [ $dnsrc -ne 2 ] && [ $dnsrc -ne 124 ]; then fails=0; continue; fi
    # DNS is failing. Only blame Private DNS if raw IP connectivity works; with no signal at
    # all there is nothing to fix.
    if timeout 10 ping -c 1 -W 5 1.1.1.1 >/dev/null 2>&1 || timeout 10 ping -c 1 -W 5 8.8.8.8 >/dev/null 2>&1; then
        fails=$((fails + 1))
    else
        fails=0; continue
    fi
    if [ $fails -ge 2 ]; then
        settings put global private_dns_mode opportunistic
        rm -f $T/pc_strict_by_us
        touch $T/pc_dns_fallback
        echo "$(date) strict Private DNS unreachable; switched to Automatic" >> $T/pc_watchdog.log
        fails=0
    fi
done
WDEOF
chmod 755 $T/pc_dns_watchdog.sh
nohup /system/bin/sh $T/pc_dns_watchdog.sh </dev/null >/dev/null 2>&1 &

echo "PixelControls helper started on 127.0.0.1:28888 (DNS watchdog running)"

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
            cmd netpolicy set restrict-background true </dev/null >/dev/null 2>&1
            cmd phone set-allowed-network-types-for-users -s 0 01111111111111111111 </dev/null >/dev/null 2>&1
            settings put global battery_saver_constants enable_datasaver=true
            settings put global low_power 1
            settings put global low_power_sticky 1
            settings put global preferred_network_mode 9,9
            settings put global preferred_network_mode1 9
            settings put global preferred_network_mode2 9
            # Weak signal: strict Private DNS (DNS-over-TLS, port 853) is the first thing to fail,
            # and Android never falls back from strict mode, so every lookup dies and the phone
            # reports "no Internet access". Automatic mode encrypts when the network allows it and
            # falls back to plain DNS when it doesn't. The user's own setting is backed up.
            backup_dns
            rm -f "$STRICT_MARK"
            settings put global private_dns_mode opportunistic
            settings put global mobile_data_always_on 0
            echo "OK:ON"
            ;;
        OFF)
            cmd netpolicy set restrict-background false </dev/null >/dev/null 2>&1
            cmd phone set-allowed-network-types-for-users -s 0 11111111111111111111 </dev/null >/dev/null 2>&1
            settings put global low_power 0
            settings put global low_power_sticky 0
            settings put global battery_saver_constants enable_datasaver=false

            MANUF=$(getprop ro.product.manufacturer 2>/dev/null)
            if [ "$MANUF" = "Xiaomi" ] || [ "$MANUF" = "xiaomi" ]; then
                NORM="32"
            else
                NORM="27"
            fi
            settings put global preferred_network_mode "${NORM},${NORM}"
            settings put global preferred_network_mode1 "$NORM"
            settings put global preferred_network_mode2 "$NORM"
            restore_dns          # back to exactly what the user had before
            settings put global mobile_data_always_on 1
            echo "OK:OFF"
            ;;
        STATUS)
            saver=$(cmd netpolicy get restrict-background 2>/dev/null | grep -ic "enabled")
            types=$(cmd phone get-allowed-network-types-for-users 2>/dev/null | grep -ic "NR")
            lp=$(settings get global low_power 2>/dev/null)
            pdns=$(settings get global private_dns_mode 2>/dev/null)
            fb=0; [ -f "$FALLBACK_MARK" ] && fb=1
            echo "STATUS:saver=$saver:has_nr=$types:low_power=$lp:private_dns=$pdns:dns_fallback=$fb"
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
INTERVAL=${PC_WD_INTERVAL:-30}
echo $$ > $T/pc_watchdog.pid
fails=0
while true; do
    sleep "$INTERVAL"
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

#!/bin/bash
# Simulated phone: settings store, network state, Android-like ping. Runs the real start_daemon.sh under mksh.
D=/tmp/dt
export PATH=$D/bin:$PATH
export PC_WD_INTERVAL=1
H=$D/tmp/lowsignal_helper.sh
PASS=0; FAIL=0
ok(){ if [ "$1" = "$2" ]; then PASS=$((PASS+1)); echo "  PASS  $3"; else FAIL=$((FAIL+1)); echo "  FAIL  $3 (got '$1', want '$2')"; fi; }
q(){ printf "$1\r\n" | mksh $H; }
mode(){ settings get global private_dns_mode; }
spec(){ settings get global private_dns_specifier; }
net(){ echo "$1" > $D/net_dot; echo "$2" > $D/net_ip; }   # net <dot ok|blocked> <ip ok|down>
killall_pc(){ for p in $(ps -eo pid,args | grep -E "^ *[0-9]+ (/bin/mksh $D/tmp/(run_lowsignal_loop|pc_dns_watchdog)|sleep 30)" | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; }
fresh(){ killall_pc; rm -f $D/db $D/tmp/pc_* ; touch $D/db; }

# Android-like ping: name lookups go through the system resolver, which honours Private DNS.
cat > $D/bin/ping <<'EOF'
#!/bin/sh
host=""; for a in "$@"; do host=$a; done
dot=$(cat /tmp/dt/net_dot); ip=$(cat /tmp/dt/net_ip)
case "$host" in
  [0-9]*) [ "$ip" = ok ] && exit 0 || exit 1 ;;
esac
[ "$ip" = ok ] || exit 2                                  # no network: nothing resolves
m=$(/tmp/dt/bin/settings get global private_dns_mode)
if [ "$m" = hostname ] && [ "$dot" != ok ]; then exit 2; fi   # strict DoT unreachable: no fallback
exit 0
EOF
chmod +x $D/bin/ping

echo "== Scenario A: user has NextDNS (strict); Low signal ON/OFF"
fresh; net ok ok
settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.nextdns.io
mksh $D/start.sh >/dev/null 2>&1; sleep 1
ok "$(q ON)" "OK:ON" "ON replies"
ok "$(mode)" "opportunistic" "Low signal ON uses Automatic DNS, not strict"
ok "$(spec)" "dns.nextdns.io" "user's hostname kept for later"
ok "$(q OFF)" "OK:OFF" "OFF replies"
ok "$(mode)|$(spec)" "hostname|dns.nextdns.io" "Low signal OFF restores user's exact setting"
ok "$( [ -f $D/tmp/pc_privdns_backup ] && echo y || echo n)" "n" "backup cleaned up"

echo "== Scenario A2: user had no Private DNS set; Low signal ON/OFF"
fresh; net ok ok; mksh $D/start.sh >/dev/null 2>&1; sleep 1
q ON >/dev/null; ok "$(mode)" "opportunistic" "ON -> Automatic"
q OFF >/dev/null; ok "$(mode)|$(spec)" "null|null" "OFF -> back to unset"

echo "== Scenario B: Lite adblock in a weak area (DoT blocked, internet up)"
fresh; net blocked ok; mksh $D/start.sh >/dev/null 2>&1; sleep 1
ok "$(q ADBLOCK_DNS_ON)" "OK:ADBLOCK_DNS_ON" "Lite adblock on"
ok "$(mode)|$(spec)" "hostname|dns.adguard-dns.com" "strict AdGuard set"
sleep 6
ok "$(mode)" "opportunistic" "watchdog rescued connectivity (strict -> Automatic)"
ok "$(q STATUS | grep -o 'dns_fallback=[01]')" "dns_fallback=1" "STATUS reports the fallback to the app"
ok "$(grep -c 'switched to Automatic' $D/tmp/pc_watchdog.log)" "1" "fallback logged once"
ok "$(q ADBLOCK_DNS_OFF)|$(mode)|$(spec)" "OK:ADBLOCK_DNS_OFF|null|null" "Lite off restores original"
ok "$(q STATUS | grep -o 'dns_fallback=[01]')" "dns_fallback=0" "fallback flag cleared"

echo "== Scenario C: Lite adblock, no signal at all (internet down)"
fresh; net blocked down; mksh $D/start.sh >/dev/null 2>&1; sleep 1
q ADBLOCK_DNS_ON >/dev/null; sleep 6
ok "$(mode)" "hostname" "no network: watchdog leaves settings alone"

echo "== Scenario D: Lite adblock, good network (DoT works)"
fresh; net ok ok; mksh $D/start.sh >/dev/null 2>&1; sleep 1
q ADBLOCK_DNS_ON >/dev/null; sleep 6
ok "$(mode)|$(spec)" "hostname|dns.adguard-dns.com" "working strict DNS is kept"

echo "== Scenario E: user's OWN strict DNS broken (not set by us)"
fresh; net blocked ok; mksh $D/start.sh >/dev/null 2>&1; sleep 1
settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.nextdns.io; sleep 6
ok "$(mode)|$(spec)" "hostname|dns.nextdns.io" "watchdog never overrides the user's own choice"

echo "== Scenario F: Lite adblock on, then Low signal ON in weak area (app re-asserts Lite)"
fresh; net blocked ok; settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.nextdns.io
mksh $D/start.sh >/dev/null 2>&1; sleep 1
q ADBLOCK_DNS_ON >/dev/null; q ON >/dev/null; q ADBLOCK_DNS_ON >/dev/null   # app's reassertAfterDaemonChange
sleep 6
ok "$(mode)" "opportunistic" "phone stays online"
q ADBLOCK_DNS_OFF >/dev/null; q OFF >/dev/null
ok "$(mode)|$(spec)" "hostname|dns.nextdns.io" "everything off -> user's NextDNS restored"

echo "== Scenario G: Full filter (VPN) + Low signal"
fresh; net blocked ok; mksh $D/start.sh >/dev/null 2>&1; sleep 1
q PRIVDNS_AUTO >/dev/null; q ON >/dev/null; q PRIVDNS_AUTO >/dev/null
ok "$(mode)" "opportunistic" "Automatic DNS (filter works, no strict failure)"
q OFF >/dev/null; q PRIVDNS_RESTORE >/dev/null; ok "$(mode)" "null" "restored"

echo "== Scenario H: restart daemon 3x"
fresh; net ok ok
for i in 1 2 3; do mksh $D/start.sh >/dev/null 2>&1; sleep 1.5; done
ok "$(ps -eo args | grep -cE "^/bin/mksh $D/tmp/run_lowsignal_loop.sh")" "1" "exactly one listener loop"
ok "$(ps -eo args | grep -cE "^/bin/mksh $D/tmp/pc_dns_watchdog.sh")" "1" "exactly one DNS watchdog"
ok "$(q STATUS | cut -c1-7)" "STATUS:" "still answering"

killall_pc
echo "RESULT: $PASS passed, $FAIL failed"

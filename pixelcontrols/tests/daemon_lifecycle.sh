#!/bin/bash
# Helper daemon tests on a simulated phone (start / stop / restart, Low signal extras). Runs the real start_daemon.sh under mksh (Android's
# shell) with stand-ins for settings, cmd, svc, dumpsys, getprop, ping and toybox nc (a real TCP listener).
#   bash tests/daemon_lifecycle.sh
HERE=$(cd "$(dirname "$0")/.." && pwd)
command -v mksh >/dev/null || { echo "needs mksh (brew install mksh)"; exit 2; }
D=$(mktemp -d "${TMPDIR:-/tmp}/pcd.XXXXXX")
export PC_T=$D/tmp PC_SH=mksh PC_WD_INTERVAL=1 PC_BR_STEP=1 PATH=$D/bin:$PATH STUB=$D
mkdir -p $D/tmp $D/bin
PASS=0; FAIL=0
ok(){ if [ "$1" = "$2" ]; then PASS=$((PASS+1)); echo "  PASS  $3"; else FAIL=$((FAIL+1)); echo "  FAIL  $3 (got '$1', want '$2')"; fi; }

# ---- stand-ins -----------------------------------------------------------------------------------------------
cat > $D/bin/settings <<'S'
#!/bin/sh
db=$STUB/db; touch $db
case "$1" in
  get) v=$(grep "^$2 $3=" $db | tail -n 1 | cut -d= -f2-); echo "${v:-null}" ;;
  put) grep -v "^$2 $3=" $db > $db.t; echo "$2 $3=$4" >> $db.t; mv $db.t $db ;;
  delete) grep -v "^$2 $3=" $db > $db.t; mv $db.t $db ;;
esac
S
cat > $D/bin/cmd <<'S'
#!/bin/sh
echo "cmd $*" >> $STUB/calls
case "$1 $2" in
  "netpolicy get") [ -f $STUB/saver ] && echo "Restrict background status: enabled" || echo "Restrict background status: disabled" ;;
  "netpolicy set") [ "$4" = true ] && touch $STUB/saver || rm -f $STUB/saver ;;
  "netpolicy list") cat $STUB/wl_net 2>/dev/null ;;
  "netpolicy add") echo "$4" >> $STUB/wl_net ;;
  "netpolicy remove") grep -vx "$4" $STUB/wl_net > $STUB/w.t 2>/dev/null; mv $STUB/w.t $STUB/wl_net ;;
  "package list") echo "package:$5 uid:10$(echo "$5" | wc -c | tr -d ' ')00" ;;
  "deviceidle whitelist") case "$3" in +*) echo "${3#+}" >> $STUB/wl_idle ;; -*) grep -vx "${3#-}" $STUB/wl_idle > $STUB/w.t; mv $STUB/w.t $STUB/wl_idle ;;
                          *) [ -f $STUB/wl_idle ] && sed 's/^/user,/; s/$/,10000/' $STUB/wl_idle ;; esac ;;
  "phone get-allowed-network-types-for-users") echo "NR LTE" ;;
  "display get-displays") echo 0; [ -f $STUB/car ] && echo 7 ;;
  "display set-brightness") echo "$3" > $STUB/car_brightness ;;
  "display get-brightness") [ -f $STUB/car_brightness ] && cat $STUB/car_brightness ;;
  "wifi status") [ -f $STUB/wifi_off ] && echo "Wifi is disabled" || echo "Wifi is enabled" ;;
esac
exit 0
S
cat > $D/bin/svc <<'S'
#!/bin/sh
echo "svc $*" >> $STUB/calls
[ "$1 $2" = "wifi enable" ] && rm -f $STUB/wifi_off
[ "$1 $2" = "wifi disable" ] && touch $STUB/wifi_off
exit 0
S
cat > $D/bin/dumpsys <<'S'
#!/bin/sh
[ "$1" = activity ] && echo "  topResumedActivity=ActivityRecord{1 u0 $(cat $STUB/fg 2>/dev/null || echo com.google.android.youtube)/.Main t1}"
exit 0
S
printf '#!/bin/sh\necho ""\n' > $D/bin/getprop
cat > $D/bin/ping <<'S'
#!/bin/sh
host=""; for a in "$@"; do host=$a; done
case "$host" in [0-9]*) [ "$(cat $STUB/net_ip 2>/dev/null)" = down ] && exit 1 || exit 0 ;; esac
[ "$(cat $STUB/net_ip 2>/dev/null)" = down ] && exit 2
m=$(settings get global private_dns_mode)
[ "$m" = hostname ] && [ "$(cat $STUB/net_dot 2>/dev/null)" = blocked ] && exit 2
exit 0
S
printf '#!/bin/sh\nshift\nexec "$@"\n' > $D/bin/timeout
# toybox nc: "-s ADDR -p PORT -L PROG" forking listener; "-w N HOST PORT" client
cat > $D/bin/toybox <<'S'
#!/usr/bin/env python3
import os, socket, sys, signal
a = sys.argv[2:]
if '-L' in a:
    host = a[a.index('-s') + 1]; port = int(a[a.index('-p') + 1]); prog = a[a.index('-L') + 1:]
    signal.signal(signal.SIGCHLD, signal.SIG_IGN)
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: s.bind((host, port))
    except OSError: sys.exit(1)
    s.listen(8)
    while True:
        c, _ = s.accept()
        if os.fork() == 0:
            s.close(); os.dup2(c.fileno(), 0); os.dup2(c.fileno(), 1); c.close()
            os.execvp(os.environ.get('PC_SH', 'sh'), [os.environ.get('PC_SH', 'sh')] + prog)
        c.close()
else:
    w = float(a[a.index('-w') + 1]) if '-w' in a else 5
    rest = [x for i, x in enumerate(a) if x != '-w' and (i == 0 or a[i - 1] != '-w')]
    try:
        c = socket.create_connection((rest[0], int(rest[1])), timeout=w)
        c.sendall(sys.stdin.buffer.read()); c.shutdown(socket.SHUT_WR)
        out = b''
        while True:
            d = c.recv(4096)
            if not d: break
            out += d
        sys.stdout.buffer.write(out)
    except Exception:
        sys.exit(1)
S
chmod +x $D/bin/*

q(){ printf '%s\n' "$1" | $D/bin/toybox nc -w 3 127.0.0.1 28888; }
up(){ q STATUS | grep -q '^STATUS:' && echo up || echo down; }
procs(){ ps -axo args= | grep -E "$D/tmp/(run_lowsignal_loop|pc_dns_watchdog|pc_brightness_watch)|nc -s 127.0.0.1 -p 28888" | grep -vc grep; }
saver(){ [ -f $D/saver ] && echo on || echo off; }
types(){ grep -o 'set-allowed-network-types-for-users -s 0 [01]*' $D/calls 2>/dev/null | tail -n 1 | awk '{print $NF}'; }
start(){ mksh "$HERE/start_daemon.sh" "$@"; }
killhard(){ for p in $(ps -axo pid=,args= | grep -E "$D/tmp/|nc -s 127.0.0.1 -p 28888" | grep -v grep | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; }
set_user(){ settings put global low_power 0; settings put global preferred_network_mode 27,27
            settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.nextdns.io; }
trap 'killhard; rm -rf $D' EXIT

echo "== 1. start"
out=$(start)
ok "$(echo "$out" | grep -c 'helper running')" "1" "start reports running"
ok "$(up)" "up" "listener answers"
ok "$(procs)" "2" "loop + listener (no other jobs)"
ok "$(q STATUS | grep -c ':since=[1-9]')" "1" "STATUS says since when"
ok "$(grep -c 'started' $D/tmp/pc_daemon.log)" "1" "start logged"

echo "== 2. restart: still exactly one of each, not reported as a crash"
start >/dev/null; start >/dev/null
ok "$(up)" "up" "listener answers"
ok "$(procs)" "2" "no duplicates after 2 restarts"
ok "$(grep -c 'without a stop' $D/tmp/pc_daemon.log)" "0" "clean restart is not a crash"

echo "== 3. Low signal extras: Data Saver + 4G lock, nothing else touched"
settings put global low_power 0; settings put global private_dns_mode hostname
ok "$(q OFF)" "OK:OFF" "OFF with nothing on"
ok "$(grep -c 'set-allowed-network-types' $D/calls)" "0" "...changes nothing"
ok "$(q BOOST_ON)" "OK:BOOST_ON" "BOOST_ON"
ok "$(saver)|$(types)" "on|01111111111111111111" "Data Saver on, 5G off (4G lock)"
ok "$(q STATUS | grep -c low_signal=1)" "1" "STATUS shows it"
ok "$(settings get global low_power)|$(settings get global private_dns_mode)" "0|hostname" "app-owned settings untouched"
ok "$(q OFF)" "OK:OFF" "OFF"
ok "$(saver)|$(types)" "off|11111111111111111111" "Data Saver off, 5G allowed again"
touch $D/saver; q BOOST_ON >/dev/null; q OFF >/dev/null
ok "$(saver)" "on" "your own Data Saver (on before) stays on"
rm -f $D/saver

echo "== 4. stop from the computer: extras undone, every process gone"
q BOOST_ON >/dev/null
out=$(start stop)
ok "$(echo "$out" | grep -c 'stopped')" "1" "stop reports stopped"
ok "$(up)" "down" "listener gone"
ok "$(procs)" "0" "no helper processes left"
ok "$(saver)" "off" "Data Saver undone"
ok "$(grep -c 'stopped (adb)' $D/tmp/pc_daemon.log)" "1" "stop logged"
ok "$(start status | head -n 1)" "not running" "status: not running"
ok "$( [ -f $D/tmp/pc_daemon.user_stopped ] && echo y || echo n)" "y" "remembered as stopped by you"
ok "$(start stop | grep -c 'stopped')" "1" "stop when already stopped is harmless"

echo "== 5. Stop button in the app (SHUTDOWN over the socket)"
start >/dev/null; q BOOST_ON >/dev/null
ok "$(q SHUTDOWN)" "OK:SHUTDOWN" "app gets its reply before the helper goes"
sleep 3
ok "$(up)" "down" "listener gone"
ok "$(procs)" "0" "no helper processes left"
ok "$(saver)" "off" "extras undone"
ok "$(grep -c 'stopped (app)' $D/tmp/pc_daemon.log)" "1" "app stop logged"
ok "$(start | grep -c 'helper running')" "1" "starts again after an app stop"
ok "$( [ -f $D/tmp/pc_daemon.user_stopped ] && echo y || echo n)" "n" "starting clears 'stopped by you'"

echo "== 6. killed by Android (phone restart / adbd restart): start again carries on"
q BOOST_ON >/dev/null
killhard; sleep 0.5
ok "$(up)" "down" "dead"
start >/dev/null
ok "$(grep -c 'without a stop' $D/tmp/pc_daemon.log)" "1" "unexpected end is logged"
ok "$(q STATUS | grep -c low_signal=1)" "1" "extras state survived"
ok "$(q OFF)" "OK:OFF" "OFF (what the app sends once the helper is back)"
ok "$(saver)" "off" "extras undone after the kill"

echo "== 7. leftovers of older versions are cleaned up"
start stop >/dev/null
touch $D/wifi_off $D/tmp/pc_aa_wifi_off $D/tmp/pc_dns_watchdog.sh $D/tmp/pc_strict_by_us $D/tmp/pc_brightness_watch.sh
printf 'net 10305 com.phonepe.app\nidle com.phonepe.app\n' > $D/tmp/pc_keepalive_added; echo com.phonepe.app > $D/wl_idle; echo 10305 > $D/wl_net
start >/dev/null
ok "$( [ -f $D/wifi_off ] && echo off || echo on)" "on" "Wi-Fi left off by an old Android Auto switch: back on"
ok "$(ls $D/tmp/pc_aa_wifi_off $D/tmp/pc_dns_watchdog.sh $D/tmp/pc_strict_by_us $D/tmp/pc_brightness_watch.sh 2>/dev/null | wc -l | tr -d ' ')" "0" "old files removed"
q OFF >/dev/null
ok "$(grep -c phonepe $D/wl_idle)|$(grep -c 10305 $D/wl_net)" "0|0" "old keep-online exemptions released"

echo "== 8. removed commands (now done by the app, or gone)"
: > $D/calls
for c in ON KEEP KEEPALIVE_FG ADBLOCK_DNS_ON PRIVDNS_AUTO PRIVDNS_RESTORE DSLR_ON AA_WIRED AA_WIRELESS WIFI_DEBUG_CHECK AA_NIGHT_DIM; do
    ok "$(q $c)" "ERR:unknown_cmd" "$c removed"
done
ok "$(grep -cE 'svc (wifi|usb)|settings put' $D/calls)" "0" "helper never touches Wi-Fi, USB or settings"

start stop >/dev/null

echo "== 9. unknown argument"
start bogus >/dev/null 2>&1; ok "$?" "2" "usage error"

echo
echo "daemon lifecycle: $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]

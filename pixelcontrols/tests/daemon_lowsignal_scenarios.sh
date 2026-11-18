#!/bin/bash
D=/tmp/dt; S=$D/state
export PATH=$D/bin:$PATH
export PC_WD_INTERVAL=1
H=$D/tmp/lowsignal_helper.sh
PASS=0; FAIL=0
ok(){ if [ "$1" = "$2" ]; then PASS=$((PASS+1)); echo "  PASS  $3"; else FAIL=$((FAIL+1)); echo "  FAIL  $3 (got '$1', want '$2')"; fi; }
q(){ printf "$1\r\n" | mksh $H; }
g(){ settings get global "$1"; }
killall_pc(){ for p in $(ps -eo pid,args | grep -E "^ *[0-9]+ (/bin/mksh $D/tmp/(run_lowsignal_loop|pc_dns_watchdog)|sleep 30)" | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; }
fresh(){ killall_pc; rm -rf $D/db $D/tmp/pc_* $S; mkdir -p $S; touch $D/db $S/wl_net $S/wl_idle; echo disabled > $S/rb; echo ok > $D/net_dot; echo ok > $D/net_ip; echo com.google.android.apps.nexuslauncher > $S/fg; }
wl_net(){ tr '\n' ' ' < $S/wl_net | sed 's/ $//'; }
wl_idle(){ tr '\n' ' ' < $S/wl_idle | sed 's/ $//'; }
field(){ q STATUS | tr ':' '\n' | grep "^$1=" | cut -d= -f2; }

# ---- fake Android services
cat > $D/bin/cmd <<'EOF'
#!/bin/sh
S=/tmp/dt/state
case "$1 $2" in
  "netpolicy set") [ "$4" = true ] && echo enabled > $S/rb || echo disabled > $S/rb ;;
  "netpolicy get") echo "Restrict background status: $(cat $S/rb)" ;;
  "netpolicy list") echo "Restrict background whitelisted UIDs: $(tr '\n' ' ' < $S/wl_net)" ;;
  "netpolicy add") echo "$4" >> $S/wl_net ;;
  "netpolicy remove") grep -vx "$4" $S/wl_net > $S/t; mv $S/t $S/wl_net ;;
  "deviceidle whitelist")
     if [ -z "$3" ]; then sed 's/^/user,/; s/$/,10000/' $S/wl_idle; echo "system-excidle,com.android.phone,1001"
     else case "$3" in +*) echo "${3#+}" >> $S/wl_idle ;; -*) grep -vx "${3#-}" $S/wl_idle > $S/t; mv $S/t $S/wl_idle ;; esac; fi ;;
  "package list")
     case "$5" in
       com.pixelcontrols) echo "package:com.pixelcontrols uid:10301" ;;
       com.spotify.music) echo "package:com.spotify.music uid:10302" ;;
       in.startv.hotstar) echo "package:in.startv.hotstar uid:10303" ;;
       com.phonepe.app) echo "package:com.phonepe.app uid:10305" ;;
       com.google.android.youtube) echo "package:com.google.android.youtube.tv uid:10399"; echo "package:com.google.android.youtube uid:10304" ;;
     esac ;;
  "phone set-allowed-network-types-for-users") echo "$5" > $S/types ;;
  "phone get-allowed-network-types-for-users") [ "$(cut -c1 $S/types 2>/dev/null)" = 1 ] && echo "NR LTE" || echo "LTE" ;;
esac
exit 0
EOF
cat > $D/bin/dumpsys <<'EOF'
#!/bin/sh
echo "  ResumedActivity: ActivityRecord{1a2b u0 $(cat /tmp/dt/state/fg)/.Main t7}"
echo "  topResumedActivity=ActivityRecord{1a2b u0 $(cat /tmp/dt/state/fg)/.Main t7}"
EOF
chmod +x $D/bin/cmd $D/bin/dumpsys

echo "== 1. Leftover strict one.one.one.one from the old version is cleaned up at start"
fresh; settings put global private_dns_mode hostname; settings put global private_dns_specifier one.one.one.one
mksh $D/start.sh >/dev/null 2>&1; sleep 1
ok "$(g private_dns_mode)|$(g private_dns_specifier)" "opportunistic|null" "migrated to Automatic"

echo "== 2. Low signal ON while Spotify is on screen"
fresh
settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.nextdns.io
settings put global low_power 0; settings put global mobile_data_always_on 0; settings put global preferred_network_mode 27,27
echo 10500 > $S/wl_net                      # some other app the user had already exempted
echo com.spotify.music > $S/fg
mksh $D/start.sh >/dev/null 2>&1; sleep 1
ok "$(q ON)" "OK:ON" "ON replies"
ok "$(cat $S/rb)" "enabled" "Data Saver on (background apps off the weak link)"
ok "$(cat $S/types)" "01111111111111111111" "5G off, LTE/3G/2G kept"
ok "$(g low_power)|$(g mobile_data_always_on)|$(g network_avoid_bad_wifi)" "1|1|1" "battery saver, mobile data ready, leave bad Wi-Fi"
ok "$(g private_dns_mode)" "opportunistic" "Automatic DNS (no strict-DNS outage)"
ok "$(wl_net)" "10500 10301 10302" "PixelControls + Spotify exempt from Data Saver"
ok "$(wl_idle)" "com.pixelcontrols com.spotify.music" "PixelControls + Spotify exempt from Battery Saver"
ok "$(field low_signal)|$(field kept)" "1|2" "STATUS reports mode + kept apps"

echo "== 3. Switching apps while Low signal is on"
echo in.startv.hotstar > $S/fg; sleep 3
ok "$(wl_idle)" "com.pixelcontrols com.spotify.music in.startv.hotstar" "Hotstar picked up automatically"
echo com.google.android.apps.nexuslauncher > $S/fg; sleep 3
ok "$(field kept)" "3" "home screen is not added"
echo com.google.android.youtube > $S/fg; sleep 3
ok "$(grep -c 10304 $S/wl_net)|$(grep -c 10399 $S/wl_net)" "1|0" "exact package match (YouTube, not YouTube TV)"
sleep 3; ok "$(grep -c 10304 $S/wl_net)" "1" "no duplicate entries on repeated ticks"

echo "== 4. Weak network: DNS still resolves"
echo blocked > $D/net_dot; ping -c1 dns.google >/dev/null 2>&1; ok "$?" "0" "lookups work with Automatic DNS on weak signal"

echo "== 5. Low signal OFF restores everything exactly"
ok "$(q OFF)" "OK:OFF" "OFF replies"
ok "$(cat $S/rb)" "disabled" "Data Saver back to the user's previous state (off)"
ok "$(cat $S/types)" "11111111111111111111" "5G allowed again"
ok "$(g low_power)|$(g mobile_data_always_on)|$(g network_avoid_bad_wifi)|$(g preferred_network_mode)" "0|0|null|27,27" "settings restored to the user's originals"
ok "$(g private_dns_mode)|$(g private_dns_specifier)" "hostname|dns.nextdns.io" "user's own Private DNS restored"
ok "$(wl_net)" "10500" "only our exemptions removed; the user's own kept"
ok "$(wl_idle)" "" "Battery Saver exemptions removed"
ok "$(ls $D/tmp/pc_settings_backup $D/tmp/pc_keepalive_added $D/tmp/pc_lowsignal_on 2>/dev/null | wc -l)" "0" "state files cleaned up"
echo com.spotify.music > $S/fg; sleep 3
ok "$(wl_idle)" "" "no keep-alive after OFF"

echo "== 6. User already had Data Saver ON and Spotify exempt"
fresh; echo enabled > $S/rb; echo 10302 > $S/wl_net; echo com.spotify.music > $S/wl_idle; echo com.spotify.music > $S/fg
mksh $D/start.sh >/dev/null 2>&1; sleep 1
q ON >/dev/null; q OFF >/dev/null
ok "$(cat $S/rb)" "enabled" "Data Saver stays on after OFF"
ok "$(wl_net)|$(wl_idle)" "10302|com.spotify.music" "user's own Spotify exemptions untouched"

echo "== 7. OFF after an ON from the OLD version (no backup)"
fresh; mksh $D/start.sh >/dev/null 2>&1; sleep 1
settings put global low_power 1; settings put global preferred_network_mode 9,9; echo enabled > $S/rb
q OFF >/dev/null
ok "$(g low_power)|$(g preferred_network_mode)|$(cat $S/rb)" "0|27,27|disabled" "sensible defaults restored"

echo "== 8. ON twice, then OFF: originals still restored (backup not overwritten)"
fresh; settings put global mobile_data_always_on 0; mksh $D/start.sh >/dev/null 2>&1; sleep 1
q ON >/dev/null; q ON >/dev/null; q OFF >/dev/null
ok "$(g mobile_data_always_on)|$(cat $S/rb)" "0|disabled" "double ON is safe"

echo "== 9. KEEP: the app keeps UPI/banking apps online during Low signal"
fresh; mksh $D/start.sh >/dev/null 2>&1; sleep 1
ok "$(q 'KEEP com.phonepe.app')" "ERR:low_signal_off" "refused while Low signal is off"
q ON >/dev/null
ok "$(q 'KEEP com.phonepe.app')" "OK:KEEP" "accepted while Low signal is on"
ok "$(grep -c 10305 $S/wl_net)|$(grep -c com.phonepe.app $S/wl_idle)" "1|1" "PhonePe exempt from Data Saver + Battery Saver"
ok "$(q 'KEEP com.x;touch /tmp/dt/pwned')" "ERR:bad_package" "shell injection rejected"
ok "$(q 'KEEP $(touch /tmp/dt/pwned2)')" "ERR:bad_package" "command substitution rejected"
ok "$(ls /tmp/dt/pwned /tmp/dt/pwned2 2>/dev/null | wc -l)" "0" "nothing executed"
q OFF >/dev/null
ok "$(grep -c 10305 $S/wl_net)|$(grep -c com.phonepe.app $S/wl_idle)" "0|0" "released on OFF"

killall_pc
echo "RESULT: $PASS passed, $FAIL failed"

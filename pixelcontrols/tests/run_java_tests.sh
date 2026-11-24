#!/bin/bash
# Plain-Java tests on the Mac: blocklist, DNS hedging/cache, Low signal focus decisions.
#   bash tests/run_java_tests.sh
cd "$(dirname "$0")/.."
J=${JAVA_HOME:-$(brew --prefix openjdk@17)}/bin
AJ=$(ls "${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}"/platforms/android-34/android.jar):$(find app/build/intermediates -name R.jar -path "*debug*" | head -1)
S=$(mktemp -d); SRC=app/src/main/java/com/pixelcontrols
"$J/javac" -nowarn -d "$S" -cp "app/src/main/java:$AJ" $SRC/adblock/Blocklist.java $SRC/adblock/AdblockDefaults.java \
    $SRC/adblock/DnsPacket.java $SRC/adblock/DnsCache.java $SRC/adblock/HedgedResolver.java $SRC/adblock/DnsFilterEngine.java \
    $SRC/adblock/FocusGate.java $SRC/battery/BatteryWatch.java tests/AdblockTest.java tests/NetTest.java tests/FocusTest.java tests/BatteryWatchTest.java 2>&1 | grep error
rc=0
(cd "$S" && "$J/java" -cp ".:$AJ" AdblockTest blocklist "$OLDPWD/app/src/main/assets/blocklist.txt" | tail -1) || rc=1
(cd "$S" && "$J/java" -cp ".:$AJ" NetTest | tail -1) || rc=1
(cd "$S" && "$J/java" -cp ".:$AJ" com.pixelcontrols.adblock.FocusTest) || rc=1
(cd "$S" && "$J/java" -cp ".:$AJ" com.pixelcontrols.battery.BatteryWatchTest) || rc=1
rm -rf "$S"; exit $rc

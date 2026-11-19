#!/bin/bash
# Builds the watch sports app -> build/watchsports.apk
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)}"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
mkdir -p build
cp app/build/outputs/apk/debug/app-debug.apk build/watchsports.apk
echo "Built: $(pwd)/build/watchsports.apk"

#!/bin/bash
# Builds the launcher APK -> build/tvhome.apk
set -e
cd "$(dirname "$0")/launcher"
export ANDROID_HOME="${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)}"
echo "sdk.dir=$ANDROID_HOME" > local.properties

# The Android Gradle plugin used here needs Gradle 8.x, but Homebrew installs Gradle 9.
# Generate a Gradle 8.7 wrapper in a scratch folder and use that.
if [ ! -f gradlew ]; then
  tmp="$(mktemp -d)"
  (cd "$tmp" && touch settings.gradle && gradle wrapper --gradle-version 8.7 --no-validate-url)
  cp -R "$tmp/gradlew" "$tmp/gradlew.bat" "$tmp/gradle" .
fi

./gradlew assembleDebug
mkdir -p ../build
cp app/build/outputs/apk/debug/app-debug.apk ../build/tvhome.apk
echo "Built: $(cd .. && pwd)/build/tvhome.apk"

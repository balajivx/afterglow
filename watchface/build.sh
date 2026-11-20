#!/bin/bash
# Builds the Gunners x Scuderia watch face -> build/watchface.apk
set -e
cd "$(dirname "$0")"
RES=app/src/main/res/drawable-nodpi
mkdir -p "$RES" app/src/main/res/raw
python3 art/make_walls.py "$RES"
python3 art/make_bg.py "$RES"
python3 art/make_cleanfeed_art.py "$RES"
python3 art/make_face_cf.py app/src/main/res/raw/watchface.xml      # Clean Feed (classic: art/make_face.py)
python3 -c "import sys; sys.path.insert(0,'art/redesign'); import make_broadcast3 as m; m.clean_feed(m.THEMES['ferrari']).save('$RES/preview.png')"
export ANDROID_HOME="${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)}"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
mkdir -p build
cp app/build/outputs/apk/debug/app-debug.apk build/watchface.apk
echo "Built: $(pwd)/build/watchface.apk"

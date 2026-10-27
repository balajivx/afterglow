#!/bin/bash
# Stops the streaming apps from running in the background (Android's "background restriction"). They open
# and play as always; they just can't keep services and jobs going once you leave them, so they stop
# holding the TV's 3 GB of memory. Measured during live cricket: with four of them idling, SonyLIV lost
# 8 frames in 95 s; with them gone, 3 in 125 s.
#   ./quiet-apps.sh        restrict them
#   ./quiet-apps.sh undo   let them run in the background again
APPS="com.netflix.ninja com.amazon.amazonvideo.livingroom in.startv.hotstar com.sonyliv com.graymatrix.did
ahaflix.tv com.cricfytv.sports com.apple.atve.sony.appletv com.google.android.youtube.tv org.smarttube.stable
com.sonypicturescore"
mode=ignore; [ "$1" = undo ] && mode=allow
for p in $APPS; do
  adb shell pm path "$p" >/dev/null 2>&1 || continue
  adb shell cmd appops set "$p" RUN_ANY_IN_BACKGROUND $mode
  echo "$p: background $([ $mode = ignore ] && echo restricted || echo allowed)"
done

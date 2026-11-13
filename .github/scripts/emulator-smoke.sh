#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa-screenshots
trap 'adb logcat -d -v time > qa-screenshots/logcat.txt || true' EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.puremusic.app android.permission.READ_MEDIA_AUDIO
adb shell pm grant com.puremusic.app android.permission.RECORD_AUDIO
adb logcat -c
for screen in songs playlists settings identify downloads visualizer player; do
  echo "SMOKE: starting $screen"
  adb shell am force-stop com.puremusic.app
  adb shell am start -W -n com.puremusic.app/.MainActivity --es screen "$screen"
  sleep 3
  adb shell pidof com.puremusic.app >/dev/null
  adb shell screencap -p "/sdcard/pure_$screen.png"
  adb pull "/sdcard/pure_$screen.png" "qa-screenshots/$screen.png"
done
echo "PASS: all 7 application screens started without process exit"

#!/usr/bin/env bash
# Runs inside the emulator: instrumented tests, then installs the app,
# opens it and saves screenshots (light and dark) for review.
set -euo pipefail
PKG=com.supun.sweeper

# Separate runs: connectedAndroidTest uninstalls the app when it finishes,
# so installing in the same Gradle invocation would be undone.
./gradlew --no-daemon connectedDebugAndroidTest
./gradlew --no-daemon installDebug

shot() { # $1 = night mode (no|yes), $2 = file name
  adb shell cmd uimode night "$1"
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity"
  sleep 4
  if ! adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG"; then
    echo "App is not in the foreground; refusing to save a misleading screenshot" >&2
    adb shell dumpsys activity activities | grep -i resumed >&2 || true
    exit 1
  fi
  adb exec-out screencap -p > "screenshots/$2"
}

mkdir -p screenshots
shot no home-light.png
shot yes home-dark.png

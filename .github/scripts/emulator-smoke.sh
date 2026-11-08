#!/usr/bin/env bash
# Runs inside the emulator: instrumented tests, then installs the app,
# opens it and saves screenshots (light and dark) for review.
set -euo pipefail
PKG=com.supun.sweeper

# Separate runs: connectedAndroidTest uninstalls the app when it finishes,
# so installing in the same Gradle invocation would be undone.
./gradlew --no-daemon connectedDebugAndroidTest
./gradlew --no-daemon installDebug

# shot <night mode: no|yes> <file name> [extra am start args...]
# SHOT_WAIT (seconds, default 4) is how long to wait after launch before the screenshot.
shot() {
  local night="$1" name="$2"
  shift 2
  adb shell cmd uimode night "$night"
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" "$@"
  sleep "${SHOT_WAIT:-4}"
  if ! adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG"; then
    echo "App is not in the foreground; refusing to save a misleading screenshot" >&2
    adb shell dumpsys activity activities | grep -i resumed >&2 || true
    exit 1
  fi
  adb exec-out screencap -p > "screenshots/$name"
}

mkdir -p screenshots

# Usage access revoked: home with the "Grant" line, and the Apps permission card.
adb shell appops set "$PKG" GET_USAGE_STATS default
shot no home-light.png
shot no apps-permission-light.png --es open apps

# Usage access granted: give the scan time to finish before each screenshot.
adb shell appops set "$PKG" GET_USAGE_STATS allow
SHOT_WAIT=8 shot no home-breakdown-light.png
SHOT_WAIT=8 shot no apps-list-light.png --es open apps
SHOT_WAIT=8 shot yes apps-list-dark.png --es open apps
adb shell cmd uimode night no

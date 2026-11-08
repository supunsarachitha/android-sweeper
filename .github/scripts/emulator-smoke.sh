#!/usr/bin/env bash
# Runs inside the emulator: instrumented tests, then installs the app,
# opens it and saves screenshots (light and dark) for review.
set -euo pipefail
./gradlew --no-daemon connectedDebugAndroidTest installDebug
mkdir -p screenshots
adb shell cmd uimode night no
adb shell am start -W -n com.supun.sweeper/.MainActivity
sleep 4
adb exec-out screencap -p > screenshots/home-light.png
adb shell cmd uimode night yes
adb shell am force-stop com.supun.sweeper
adb shell am start -W -n com.supun.sweeper/.MainActivity
sleep 4
adb exec-out screencap -p > screenshots/home-dark.png

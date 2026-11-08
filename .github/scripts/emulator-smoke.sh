#!/usr/bin/env bash
# Runs inside the emulator: instrumented tests, then installs the app,
# opens it and saves screenshots (light and dark) for review.
set -euo pipefail
PKG=com.supun.sweeper

mkdir -p screenshots
# Always keep the device log with the screenshots, so a crash can be diagnosed from the artifact.
cleanup_files() { :; }
on_exit() {
  adb logcat -d -t 4000 > screenshots/logcat.txt 2>/dev/null || true
  cleanup_files
}
trap on_exit EXIT

# Granting All files access to a running app can restart its process (the storage view changes), which
# would kill the instrumented test. So grant it once up front; the tests' own grant is then a no-op.
./gradlew --no-daemon installDebug
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow

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

# --- Phase 3: files and trash ---------------------------------------------------------------
# Test files, made through the shell like a user's own downloads.
BIG=/sdcard/Download/sweeper-test-big.bin
OLD=/sdcard/Download/old-report.pdf
APKCOPY=/sdcard/Download/sweeper-copy.apk
TRASH=/sdcard/.SweeperTrash
TRASHED=11111111-1111-1111-1111-111111111111
cleanup_files() {
  adb shell "rm -f $BIG $OLD $APKCOPY $TRASH/$TRASHED $TRASH/index.tsv $TRASH/.nomedia; rmdir $TRASH" || true
}
adb shell "dd if=/dev/zero of=$BIG bs=1048576 count=60"
adb shell "echo old-report > $OLD; touch -d '2025-01-01' $OLD || touch -t 202501010000 $OLD"
adb shell "cp \$(pm path $PKG | head -1 | cut -d: -f2) $APKCOPY"
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow

SHOT_WAIT=10 shot no files-large-light.png --es open files
SHOT_WAIT=10 shot no files-old-light.png --es open files --es tab old
SHOT_WAIT=10 shot no files-apk-light.png --es open files --es tab apk
SHOT_WAIT=10 shot yes files-dark.png --es open files

# A trashed file, written the way the app writes it (see Trash.kt): <trash>/<id> plus a TSV index line.
adb shell "mkdir -p $TRASH && dd if=/dev/zero of=$TRASH/$TRASHED bs=1048576 count=5"
adb shell "printf '$TRASHED\t%s000\t5242880\t/storage/emulated/0/Download/holiday-video.mp4\n' \$(date +%s) > $TRASH/index.tsv"
SHOT_WAIT=6 shot no trash-light.png --es open trash
SHOT_WAIT=6 shot no home-trash-light.png

adb shell cmd uimode night no
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE default

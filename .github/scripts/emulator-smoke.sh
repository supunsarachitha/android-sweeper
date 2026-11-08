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

# --- Phase 4: duplicates --------------------------------------------------------------------
# Two pairs of identical files (2 MB and 500 KB) in different folders, made through the shell.
DUP_BIG_A=/sdcard/Download/sweeper-dup-big.bin
DUP_BIG_B=/sdcard/Pictures/sweeper-dup-big-copy.bin
DUP_SMALL_A=/sdcard/Documents/sweeper-dup-notes.bin
DUP_SMALL_B=/sdcard/Download/sweeper-dup-notes-copy.bin
cleanup_files() {
  adb shell "rm -f $BIG $OLD $APKCOPY $TRASH/$TRASHED $TRASH/index.tsv $TRASH/.nomedia; rmdir $TRASH" || true
  adb shell "rm -f $DUP_BIG_A $DUP_BIG_B $DUP_SMALL_A $DUP_SMALL_B" || true
}
adb shell "mkdir -p /sdcard/Pictures /sdcard/Documents"
adb shell "dd if=/dev/urandom of=$DUP_BIG_A bs=1024 count=2048 && cp $DUP_BIG_A $DUP_BIG_B"
adb shell "dd if=/dev/urandom of=$DUP_SMALL_A bs=1024 count=500 && cp $DUP_SMALL_A $DUP_SMALL_B"

SHOT_WAIT=15 shot no duplicates-light.png --es open duplicates
SHOT_WAIT=15 shot no duplicates-selected-light.png --es open duplicates --es demo keepoldest
SHOT_WAIT=15 shot yes duplicates-dark.png --es open duplicates

# --- Phase 5: junk cleanup ------------------------------------------------------------------
# An empty folder chain, temp files and a thumbnail, made through the shell. Only files and
# directories are created here, so plain rm -f and rmdir clean them up again.
JUNK_EMPTY=/sdcard/Download/sweeper-junk-empty
JUNK_TMP=/sdcard/Download/sweeper-junk.tmp
JUNK_PART=/sdcard/Documents/sweeper-junk-2.part
JUNK_DL=/sdcard/Pictures/sweeper-junk-3.crdownload
JUNK_OFFICE='/sdcard/Download/~$sweeper-junk.docx'
JUNK_THUMB=/sdcard/DCIM/.thumbnails/sweeper-thumb.jpg
cleanup_files() {
  adb shell "rm -f $BIG $OLD $APKCOPY $TRASH/$TRASHED $TRASH/index.tsv $TRASH/.nomedia; rmdir $TRASH" || true
  adb shell "rm -f $DUP_BIG_A $DUP_BIG_B $DUP_SMALL_A $DUP_SMALL_B" || true
  adb shell "rm -f $JUNK_TMP $JUNK_PART $JUNK_DL $JUNK_THUMB; rm -f '$JUNK_OFFICE'" || true
  adb shell "rmdir $JUNK_EMPTY/a/b $JUNK_EMPTY/a $JUNK_EMPTY; rmdir /sdcard/DCIM/.thumbnails" || true
}
adb shell "mkdir -p $JUNK_EMPTY/a/b /sdcard/DCIM/.thumbnails /sdcard/Documents /sdcard/Pictures"
adb shell "dd if=/dev/urandom of=$JUNK_TMP bs=1024 count=300"
adb shell "dd if=/dev/urandom of=$JUNK_PART bs=1024 count=120"
adb shell "dd if=/dev/urandom of=$JUNK_DL bs=1024 count=80"
adb shell "echo lock > '$JUNK_OFFICE'"
adb shell "dd if=/dev/urandom of=$JUNK_THUMB bs=1024 count=40"

# The junk scan has to finish in the same process before Home is shown, because the "of junk"
# line on Home is kept in memory: open Junk, wait for the scan, then go back to Home.
SHOT_WAIT=20 shot no junk-light.png --es open junk
adb shell input keyevent KEYCODE_BACK
sleep 4
# The Junk card is below the fold: scroll Home to the bottom so the "of junk" line is visible.
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}
H=${SIZE#*x}
for _ in 1 2 3; do
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 300
done
sleep 2
adb exec-out screencap -p > screenshots/home-final-light.png
SHOT_WAIT=20 shot yes junk-dark.png --es open junk

adb shell cmd uimode night no
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE default

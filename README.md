# Android Sweeper

A personal Android storage cleaner. It is sideloaded (not on Google Play), has no internet
permission and sends nothing anywhere. Nothing is removed without a review screen and a
confirm dialog first.

Requirements: Android 8.0 (API 26) or newer.

## What it does

- **Home:** how full the internal storage is, a bar showing what uses the space (apps, images,
  video, audio, other), and a card for each tool below. After a scan, the Duplicates and Junk
  cards show how much they found (kept in memory, so it resets when the app is closed).
- **Apps:** every installed app with its size and cache, sortable, with a switch for system
  apps. Tapping an app opens its Android App info page.
- **Large & old files:** three tabs. Large files (chips: 10, 50, 100 and 500 MB), old downloads
  (not modified for 30, 90, 180 or 365 days) and leftover APK files, with the app name and
  version and an "Installed" badge when that version is already on the phone.
- **Duplicates:** finds files with the same size and the same SHA-256, groups them and lists
  the copies. "Keep oldest" per group, or one button for all groups. One copy of every file
  always stays.
- **Junk cleanup:** four sections, each with a select-all checkbox:
  - empty folders,
  - thumbnail caches (`DCIM/.thumbnails`, `Pictures/.thumbnails`),
  - temp files (`*.tmp`, `*.temp`, `*.part`, `*.crdownload`, `~$*`, `Thumbs.db`, `.DS_Store`),
  - this app's own cache.
- **Trash:** everything removed from the lists above (except empty folders and the app's own
  cache) lands here. See below.

Every scan shows progress and can be cancelled.

## Permissions and why

| Permission | Why |
|---|---|
| Usage access (`PACKAGE_USAGE_STATS`) | Reads the size and cache of each app and the storage breakdown. You grant it in Android's Settings. |
| See all apps (`QUERY_ALL_PACKAGES`) | Lists every installed app, not only the ones Android shows to all apps. |
| All files access (`MANAGE_EXTERNAL_STORAGE`) | Finds files anywhere on shared storage and moves them into the trash. On Android 8-10 the normal storage permissions (read/write) are used instead. |

There is no internet permission, and the release check in CI fails if one ever appears. The
app explains each permission before it sends you to Settings.

## The trash

Removed files are moved (not copied) to a hidden folder `.SweeperTrash` on the same storage,
with an index recording where each file came from.

- Files stay for **7 days**. The Trash screen lists them with the days left.
- **Restore** puts a file back where it was. If that name is taken, it comes back as
  `name (restored).ext`.
- **Delete now** and **Empty trash** remove files for good (after a confirm).
- Items older than 7 days are deleted automatically every time the app is opened.
- Empty folders and the app's own cache hold nothing worth keeping, so they are removed right
  away; the confirm dialog says so. After cleaning, a Snackbar offers Undo for the trashed part.

## Safety rules

- **No symlinks:** scanners never follow them, and the cleaner never acts on one. A folder that
  holds only a symlink is not empty.
- **Inside shared storage only:** every path is checked against the storage root right before
  it is touched, not only when it was scanned.
- **Protected places:** `Android/data`, `Android/obb` and the trash folder are never scanned.
  Nothing under `Android/` is ever reported as junk. The storage root and the standard
  top-level folders (Alarms, Android, Audiobooks, DCIM, Documents, Download, Movies, Music,
  Notifications, Pictures, Podcasts, Recordings, Ringtones, in any letter case) are never
  removed, even when empty.
- **Empty folders:** a folder counts as empty only if nothing is in its whole subtree, hidden
  files included. Right before deleting, each folder is checked again and removed with a
  plain delete that fails on a non-empty folder.
- **Duplicates:** same size and equal full SHA-256, and at least one copy per group can never
  be selected. A copy is only trashed if the kept one is still unchanged.
- **Review first:** nothing is removed without a list to check and a confirm dialog.

## Install

1. Open the repository's **Releases** page and download the `.apk` of the latest release.
2. Check the file. The release notes contain the APK's SHA-256 and the signing certificate's
   SHA-256. On Linux or macOS:

   ```
   sha256sum android-sweeper-*.apk
   ```

   The result must equal the "SHA-256" line in the notes. To check the signing certificate
   (Android SDK build-tools):

   ```
   apksigner verify --print-certs android-sweeper-*.apk
   ```

   The "certificate SHA-256 digest" must equal the "Signing certificate SHA-256" line in the
   notes (it is also kept in `.github/signing-cert-sha256.txt`).
3. Open the APK on the phone and allow installing from that source when Android asks.
   Later releases install over the old one because they share the signing certificate.

## Build

CI (GitHub Actions) builds a signed release APK and runs the unit tests on every push,
plus instrumented tests on an API 34 emulator with screenshots. Download the APK from the
run's `apk` artifact.

Releases: bump `versionName`/`versionCode` in `app/build.gradle.kts`, then push a matching
tag (`git tag v0.2.0 && git push origin v0.2.0`). CI runs all tests and publishes a GitHub
release with the signed APK attached. Latest APK: the repo's Releases page.

Local build: JDK 17 and the Android SDK, then `./gradlew assembleDebug`.

Dependency verification is on (`gradle/verification-metadata.xml` pins a SHA-256 for every
artifact). To add or update a dependency: change the version on a `deps/<name>` branch and
push it, download the `verification-metadata` artifact from the "Dependency verification
metadata" run, review the diff, commit the file to your branch, then delete the `deps/<name>`
branch. Every `uses:` in the workflows is pinned to a full commit SHA, and a new Android
permission has to be added to the allowlist in `.github/scripts/check-apk.sh` with a reason.

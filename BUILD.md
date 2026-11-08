# Android Sweeper build plan (phases 2-5)

Anyone resuming this work: read this file and PROGRESS.md first, then follow the rules below.

## Owner decisions (2026-11-08)
- Personal, sideloaded app. No Google Play. No third-party helper apps (no Shizuku).
- Each phase is merged to `main` and released (`vX.Y.0`) once CI is green and the screenshots have been checked
  (looked at, not just downloaded). The owner reviews afterwards.
- Deleting = move to the in-app trash. The trash auto-empties items older than **7 days**; restore is possible until then.
- Coding workers and the resume job use Sonnet.

## Rules
- One issue per phase (label `enhancement`), one branch `phase-N-<slug>`, one PR with `Closes #N`. Commit messages end
  with `Part of #N`. Never mention Hermes/AI in repo text. Never force-push `main`. Never push red to `main`.
- Token: `TOKEN=$(sed -n 's#https://[^:]*:\([^@]*\)@github.com.*#\1#p' /opt/data/home/.git-credentials | head -1)`, never printed.
  REST only (no gh CLI).
- No local builds (the host is too small). CI is the compiler: poll `GET /actions/runs?head_sha=<sha>` every 20 s,
  each terminal call under 7 minutes.
- **No new dependencies** unless unavoidable. Gradle dependency verification is on: a new artifact or version means
  pushing the change to a `deps/<name>` branch, downloading the `verification-metadata` artifact from the
  "Dependency verification metadata" run, committing it, then deleting the deps branch.
- Every `uses:` in workflows is pinned to a full commit SHA (the repo setting rejects tags).
- New Android permissions go on the allowlist in `.github/scripts/check-apk.sh`, each with a reason. INTERNET never.
- Toolchain: AGP 9.5.1 built-in Kotlin 2.4.21 (no kotlin-android plugin in :app, no kotlinOptions), compileSdk 37,
  targetSdk 36, minSdk 26. material-icons is NOT available; use vector drawables.
- File-safety rules (docs/security/review-0.1.0.md):
  1. Never follow symlinks (`LinkOption.NOFOLLOW_LINKS`, `Files.walkFileTree` without FOLLOW_LINKS); act only on
     canonical paths inside the primary shared storage root (`Environment.getExternalStorageDirectory()`); skip
     `Android/data`, `Android/obb` and the trash folder.
  2. Every removal goes through a review list and a confirm dialog, then into the trash (except empty folders,
     which hold nothing and are removed directly after review).
  3. Duplicates: same size AND full SHA-256 equal; at least one copy per group can never be selected.
  4. All the pure logic (scanning rules, trash, duplicate grouping) lives in Android-free Kotlin classes taking a root
     `java.io.File`/`Path` and an injectable clock, and is unit-tested on the JVM with temp dirs (including a symlink
     case).

## Release procedure (after a phase PR is green and screenshots were checked)
1. Bump `versionCode` (+1) and `versionName` (`0.N.0`) in app/build.gradle.kts in the PR, CI green.
2. Merge the PR via REST (`PUT /pulls/{n}/merge`, merge_method `merge`), delete the branch, check the issue closed.
3. `git tag v0.N.0 <merge sha> && git push origin v0.N.0`; wait for the tag run; the release job publishes the APK.
4. Verify: `GET /releases/tags/v0.N.0` has the APK asset; download it into
   /projects/Hermes/android-sweeper-builds/v0.N.0/ and confirm the SHA-256 matches the release notes.
5. Update PROGRESS.md on main (`[skip ci]`).

## Phase 2: storage overview + per-app usage (v0.2.0, issue #5)
Spec in issue #5: usage access flow, breakdown bar, Apps screen (sizes, sort, system toggle, tap → App info),
"Open Android storage settings". Permissions: PACKAGE_USAGE_STATS, QUERY_ALL_PACKAGES.

## Phase 3: large & old files, leftover APKs, trash (v0.3.0)
- Permission "All files access": `MANAGE_EXTERNAL_STORAGE` (API 30+, request with
  `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` + package URI, fall back to
  `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`; check `Environment.isExternalStorageManager()`). API 26-29:
  READ/WRITE_EXTERNAL_STORAGE runtime permission (`maxSdkVersion="29"`), `requestLegacyExternalStorage="true"`.
  Explain plainly before sending the user to Settings; re-check on resume. Add to the check-apk allowlist.
- Scanner (pure Kotlin, on Dispatchers.IO, progress callback, cancellable): walk the storage root without following
  links, skipping Android/data, Android/obb, the trash folder and hidden dirs starting with "." (except when a later
  phase asks for them). Results:
  - **Large files:** size >= threshold (chips: 10 MB, 50 MB default, 100 MB, 500 MB), sorted by size.
  - **Old downloads:** files in `Download/` (recursive) not modified for >= 90 days (chips 30/90/180/365).
  - **APK files:** `*.apk` anywhere. Show the package label/version via `PackageManager.getPackageArchiveInfo`, plus
    a badge "Installed" when that package is installed with versionCode >= the APK's.
- Review screen per category: checkbox rows (name, folder, size, modified date), "Select all", a footer with the
  selected count + size, and a "Move to trash" button behind a confirm dialog showing count and size.
- **Trash** (pure Kotlin `Trash(root, clock)`): folder `<storage root>/.SweeperTrash/` holding a `.nomedia` file;
  items are moved with `Files.move` (same volume = rename) to `<trash>/<uuid>`, with an index `index.json` (org.json
  or a simple line format) recording original path, size and trashedAt. Operations: trash(files), restore(id)
  (if the original path exists, restore as "name (restored).ext"), deleteNow(id), emptyAll(),
  purgeExpired(7 days) — called on app start and when the Trash screen opens. After moves, call
  `MediaScannerConnection.scanFile` for old and new paths. Trash screen reachable from Home ("Trash: N items,
  X MB"): list with Restore / Delete now, plus "Empty trash" (confirm).
- Home: the "Large & old files" card becomes active and opens a screen with the three category tabs.
- Tests: JVM unit tests for the scanner rules (thresholds, age, skips, symlink not followed, Android/data skipped)
  and Trash (trash/restore/conflict/purge after 7 days with a fake clock/deleteNow/index survives reload).
  Instrumented: grant with `appops set com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow`, create a 60 MB file under
  /sdcard/Download via shell (`dd if=/dev/zero of=/sdcard/Download/sweeper-test-big.bin bs=1M count=60`), check it
  appears in Large files, trash it, check it is in Trash and gone from Download, restore it, check it is back.
  Clean up test files at the end.
- Screenshots: large-files list, confirm dialog, trash screen (light), one dark.

## Phase 4: duplicates (v0.4.0)
- Scan the same tree (same skips; min size 1 KB). Group by size, then by SHA-256 of the first 64 KB, then confirm with
  the full SHA-256 (streamed, 1 MB buffer). Show progress and allow cancel.
- Groups sorted by wasted space ((n-1) × size). Each group lists its copies (path, modified date). Per-group action
  "Keep oldest, select the rest"; a global "Select all duplicates (keep one per group)". Selecting every copy of a
  group must be impossible (the UI blocks the last checkbox and the logic rejects it).
- Move the selected files to the trash (Phase 3 Trash), confirm dialog first.
- Home: "Duplicates" card active, showing "X MB in duplicates" after a scan.
- Tests: JVM grouping (same size different content not grouped, same content grouped, the 64 KB prefix collision with
  a different tail is NOT grouped, keep-one invariant). Instrumented: create two identical files plus one same-size
  different file via shell, check one group of 2 appears, trash one, check the trash.

## Phase 5: junk cleanup (v0.5.0)
- Categories (each a checkbox list in review):
  - **Empty folders:** folders with no files in their subtree, found bottom-up. Never the storage root, never the
    standard top-level folders (Alarms, Android, Audiobooks, DCIM, Documents, Download, Movies, Music,
    Notifications, Pictures, Podcasts, Recordings, Ringtones), never inside Android/. Removed directly after review
    (`Files.delete` deepest first, each re-checked empty right before deleting).
  - **Thumbnail caches:** `DCIM/.thumbnails` and `Pictures/.thumbnails` contents → trash.
  - **Temp files:** `*.tmp`, `*.temp`, `*.part`, `*.crdownload`, `~$*`, `Thumbs.db`, `.DS_Store` outside Android/ → trash.
  - **This app's own cache:** `context.cacheDir` + `externalCacheDir` → delete directly.
- Home: "Junk cleanup" card active, showing "X MB of junk" after a scan. A final "Clean selected" moves/deletes per
  the rules above, with one confirm dialog that lists the counts per category.
- Tests: JVM empty-folder detection (nested empties, protected names, a folder with a hidden file is not empty),
  temp-file matching. Instrumented: create an empty nested folder and a .tmp file via shell, clean, check.
- After v0.5.0: refresh README (features, permissions and why, trash behaviour), add a "## Done" section to
  PROGRESS.md, and remove the resume cron job (its id is in PROGRESS.md).

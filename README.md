# Android Sweeper

A personal Android storage cleaner (sideloaded, not on Google Play).

Planned features:
- Storage overview with per-app usage
- Large and old files, leftover APKs
- Duplicate files
- Empty folders, temp and thumbnail junk

Nothing is ever deleted without a review screen first.

## Build

CI (GitHub Actions) builds a signed release APK and runs the unit tests on every push,
plus instrumented tests on an API 34 emulator with screenshots. Download the APK from the
run's `apk` artifact.

Releases: bump `versionName`/`versionCode` in `app/build.gradle.kts`, then push a matching
tag (`git tag v0.2.0 && git push origin v0.2.0`). CI runs all tests and publishes a GitHub
release with the signed APK attached. Latest APK: the repo's Releases page.

Local build: JDK 17 and the Android SDK, then `./gradlew assembleDebug`.

Requirements: Android 8.0 (API 26) or newer.

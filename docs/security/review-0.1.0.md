# Security review: Android Sweeper v0.1.0

Date: 2026-11-08. Scope: the repo at `3c784f8` (tag v0.1.0), its CI, every third-party component it pulls in, the released APK, the repo's GitHub settings, and the signing key on the build host.

## Method

- **Secrets:** gitleaks over the full git history (values redacted); searched history and the build host for the keystore password and for `.p12` files.
- **Third-party code:** the 44 libraries shipped inside the APK (read from the APK's `META-INF/*.version` files), the build plugins, the test libraries, the Gradle wrapper and the GitHub Actions, all checked against OSV/GitHub advisories plus the Gradle project's own advisories. Each Action tag was resolved to the commit it runs today.
- **APK:** parsed the compiled `AndroidManifest.xml` (permissions, exported components, debug flags) and checked the signature against our certificate.
- **GitHub settings:** read via REST (Actions permissions, token defaults, Dependabot, branch protection).

## Summary

| # | Finding | Severity | Area |
|---|---------|----------|------|
| 1 | Gradle 8.11.1 is affected by 5 high-severity Gradle advisories | High (build-time) | Toolchain |
| 2 | Kotlin Gradle plugin 2.1.0: CVE-2026-53914 (build-cache deserialization) | Medium (build-time) | Toolchain |
| 3 | GitHub Actions pinned to mutable tags, 2-4 major versions behind; signing secrets are in the same job | Medium | CI supply chain |
| 4 | The whole toolchain and library set is about 2 years old (picked from late-2024 versions) | Medium | Maintenance |
| 5 | No dependency verification and no repository content filtering in Gradle | Low | Supply chain |
| 6 | Dependabot alerts off, and GitHub has no Gradle dependency data | Low | Monitoring |
| 7 | Single copy of the signing key, with its password in the same folder | Low (availability) | Signing |
| 8 | Release has no published SHA-256 / certificate fingerprint to check before installing | Info | Release |
| 9 | No `dataExtractionRules`: Android 12+ device-to-device transfer ignores `allowBackup=false` | Info (no data stored yet) | App |

No vulnerability was found in the code that ships inside the APK (0 advisories across all 44 libraries).

## Findings

### 1. Gradle wrapper 8.11.1 (High, build-time)
- **Where:** `gradle/wrapper/gradle-wrapper.properties`.
- **What:** affected by GHSA-mqwm-5m85-gmcv (CVE-2026-22865), GHSA-w78c-w6vf-rw82 (CVE-2026-22816) and GHSA-j5m7-59rp-24f5. If a repository fails in certain ways (no answer, unknown host, SSL failure), Gradle keeps going to the next repository instead of failing, so a dependency could come from somewhere else. Also GHSA-xwqc-3h47-hg64 and GHSA-mvvg-497x-hmj8: daemon/worker sockets deserialize untrusted data.
- **Impact here:** the repository fall-through is the real risk. `google()` is declared before `mavenCentral()` and `gradlePluginPortal()`, and the plugin portal accepts uploads from anyone. The daemon issues need an attacker already running code on the same CI runner (runners are throwaway), so they are low.
- **Fix:** Gradle 9.9.0 (or 8.14.6 if AGP 9 migration is a problem). Pair it with finding 5.
- **Done when:** the wrapper points to a patched version with its published `distributionSha256Sum`, and CI is green.

### 2. Kotlin Gradle plugin 2.1.0 (Medium, build-time)
- **What:** GHSA-r937-wjx7-w2jp / CVE-2026-53914. Code execution through unsafe deserialization of build-cache metadata. Fixed in 2.4.20.
- **Impact here:** the Gradle build cache is not enabled, and only this repo's own workflows can write to its Actions cache. Exploitation needs write access we don't expose. Still a known-vulnerable build tool.
- **Fix:** Kotlin 2.4.21 (or AGP 9 built-in Kotlin at that version).

### 3. GitHub Actions pinning (Medium)
- **Where:** `.github/workflows/ci.yml`.

| Action | Tag in use | Runs today | Latest |
|---|---|---|---|
| actions/checkout | v4 | v4.4.0 | v7.0.1 |
| actions/setup-java | v4 | v4.9.1 | v6.2.0 |
| gradle/actions/setup-gradle | v4 | v4.4.3 | v6.4.1 |
| actions/upload-artifact | v4 | v4.6.2 | v7.0.2 |
| actions/download-artifact | v4 | v4.3.0 | v8.0.2 |
| reactivecircus/android-emulator-runner | v2 | v2.38.0 | v2.38.0 |

- **What:** tags can be moved by whoever controls the Action's repo (the 2025 tj-actions/changed-files compromise worked this way). `setup-gradle` runs in the build job, which holds the signing key and password.
- **Impact:** a hijacked Action could steal the signing key. With it, an attacker can sign an APK that installs over Android Sweeper as an "update" and keeps its permissions (the app will later have All files access).
- **Not affected:** GHSA-cxww-7g56-2vh6 (download-artifact path traversal) is fixed in 4.1.3; the tag runs 4.3.0.
- **Fix:** move to current majors, pin each `uses:` to a full commit SHA with a version comment, then turn on the repo setting that requires SHA pinning (`sha_pinning_required`, currently false). Optionally limit allowed Actions to GitHub-owned plus the two named publishers (currently `all`).

### 4. Old toolchain (Medium)
- AGP 8.7.3 (latest 9.5.1), Compose BOM 2024.12.01 (latest 2026.09.00), kotlinx-coroutines 1.7.3, AndroidX libs from late 2024. I picked these at setup from older knowledge instead of checking current releases. That is my mistake.
- No known CVEs in the shipped libraries, but every month behind means missing fixes and a harder later upgrade. The app is tiny now, so this is the cheapest moment.
- **Fix:** upgrade together with findings 1 and 2. Check: CI green, screenshots unchanged.

### 5. Dependency verification (Low)
- Gradle only trusts TLS to the repositories. There are no checksums of expected artifacts (`gradle/verification-metadata.xml`), and any repository may serve any group.
- **Fix:** content filtering in `settings.gradle.kts` (`google()` limited to `androidx.*`, `com.android.*`, `com.google.*`), plus SHA-256 dependency verification generated in CI (`--write-verification-metadata sha256`) and committed. A changed or swapped artifact then fails the build.

### 6. Monitoring (Low)
- Dependabot alerts are disabled. GitHub has no dependency graph for Gradle projects unless CI submits one.
- **Fix:** enable Dependabot alerts (alerts only; version-update PRs stay off per owner policy) and add `gradle/actions/dependency-submission` on pushes to `main`.

### 7. Signing key availability (Low)
- `sweeper.p12` and `password.txt` exist only in a private folder on the build host (dir 700, files 600), apart from the CI secrets. GitHub secrets can't be read back. No other copies were found on the host or in history.
- If the folder is lost, future APKs won't install over the current one. You'd have to uninstall, which wipes app data.
- **Fix (owner):** copy both files to an offline backup or password manager.

### 8. Release integrity (Info)
- **Fix:** have the release job add the APK's SHA-256 and the signing certificate fingerprint to the release notes:
  `22:20:A2:A0:D4:88:D2:69:92:66:86:7F:ED:DE:56:5B:B9:65:14:27:F2:AA:01:AC:E8:87:85:AE:81:33:E6:BA`.

### 9. Backup/transfer rules (Info)
- **Fix:** add `android:dataExtractionRules` excluding everything, before Phase 2 starts storing settings or scan results.

## Reviewed and sound
- Git history: gitleaks found 0 leaks, the keystore password appears in no commit, and no key or `local.properties` file was ever committed. `.gitignore` covers `*.p12`, `*.jks`, `*.keystore`.
- Gradle wrapper jar SHA-256 matches Gradle's published checksum for 8.11.1.
- Workflow: no untrusted `${{ }}` expressions in `run:` scripts (secrets go through `env:`); the default `GITHUB_TOKEN` is read-only; only the tag-only release job gets `contents: write`; other repos have no Actions access (`none`). Signing secrets are only in the build job; the emulator job (third-party emulator Action) gets none.
- APK: requests **no permissions** (no `INTERNET`, so the app cannot send data anywhere); not debuggable, not testOnly, no cleartext flag; `allowBackup=false`; v2-signed with our certificate. Exported components are the launcher activity plus AndroidX `ProfileInstallReceiver`, which is guarded by `android.permission.DUMP` (system/shell only).
- Shipped libraries: 44 AndroidX/Compose/coroutines artifacts, 0 OSV advisories.
- GitHub: branch protection and rulesets need GitHub Pro on a private repo. Accepted, since the owner is the only pusher.

## Security requirements for the cleaning phases (2-5)
The biggest real risk of a cleaner is deleting the wrong files. These rules apply to the coming phases:
1. Never follow symlinks; resolve canonical paths and act only inside `/storage/emulated/0`. Skip `Android/data` and `Android/obb`.
2. Every delete goes through a review list. Files move to an in-app trash with undo first, and are only removed for good later.
3. Duplicates are confirmed by a full SHA-256 plus same size, never size or a partial hash alone, and at least one copy is always kept.
4. The app never gets `INTERNET`. A unit test reads the merged manifest and fails if `INTERNET` appears or `debuggable` is set in release.
5. All files access and usage access are requested only when the feature that needs them is opened, with a plain explanation.

## Fix plan
- **S1 Toolchain** (findings 1, 2, 4): Gradle 9.9.0, AGP 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00, current AndroidX. Check: CI green, screenshots unchanged.
- **S2 CI supply chain** (3, 5): current Actions pinned to SHAs, `sha_pinning_required` on, repository content filters, dependency verification metadata, manifest guard test.
- **S3 Monitoring and release** (6, 8, 9): Dependabot alerts, dependency submission, SHA-256 and certificate fingerprint in release notes, `dataExtractionRules`. Release v0.1.1.
- **Owner:** back up the signing key (7).

## Fix status (v0.1.1, PR #4)

| # | Status | How |
|---|--------|-----|
| 1 | Fixed | Gradle 9.9.0 with `distributionSha256Sum`; wrapper jar checked against Gradle's published SHA-256 |
| 2 | Fixed | Kotlin 2.4.21 (AGP 9.5.1 built-in Kotlin; the verification metadata confirms KGP 2.4.21 is what resolves) |
| 3 | Fixed | All Actions on current majors, pinned to commit SHAs; read-only default token; no persisted checkout credentials; key file deleted after the build. `gradle/actions/setup-gradle` removed: its v6 caching is a proprietary component under separate terms, and it ran next to the signing key. `setup-java`'s Gradle cache replaces it; Gradle's `wrapper-validation` stays. Repo setting "require SHA pinning" turned on after merge. |
| 4 | Fixed | AGP 9.5.1, Compose BOM 2026.09.00, core 1.19.1, activity 1.13.0, lifecycle 2.11.0, compileSdk 37 / targetSdk 36 |
| 5 | Fixed | `google()` exclusive for `androidx.*`, `com.android.*`, `com.google.testing.platform.*`; everything else from Maven Central only; Plugin Portal removed. `gradle/verification-metadata.xml` pins SHA-256 for 604 artifacts (362 components), generated from fresh downloads by `dependency-metadata.yml` |
| 6 | Fixed | Dependabot alerts on (version-update PRs stay off). `osv.yml` scans on every push/PR and weekly: runtime libraries, AGP/KGP, Gradle (from Gradle's GitHub advisory feed, which OSV does not carry) and pinned Actions (OSV cannot version-match Actions, so ranges are evaluated in the script). Each check was confirmed to flag a known-bad version before relying on it. |
| 7 | Owner | Back up the signing key folder |
| 8 | Fixed | `check-apk.sh` runs on every build and again before publishing: permission allowlist (INTERNET never allowed), not debuggable/testOnly, exactly one signing certificate equal to `.github/signing-cert-sha256.txt`. Release notes carry the APK SHA-256 and certificate fingerprint. |
| 9 | Fixed | `dataExtractionRules` exclude all domains from cloud backup and device transfer |

Remaining, accepted: the scan reports build-time-only advisories inside AGP's own dependencies (Guava, BouncyCastle, commons-lang3, httpclient, jose4j, jdom2) as warnings. They run only inside the build, never ship in the APK, and are fixed by AGP updates.

Not tested: dependency verification against a tampered artifact. Gradle enforces it whenever the metadata file is present; no tamper test was run.

Updating dependencies from now on: change versions, push to a `deps/<name>` branch, download the `verification-metadata` artifact, review the diff, commit it.

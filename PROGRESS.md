# Progress

Last updated: 2026-11-08 07:50 UTC

| Phase | Issue | Branch / PR | Status | Release |
|---|---|---|---|---|
| 1 Setup + CI | #1 | main | done | v0.1.0 |
| Security fixes | #3 | PR #4 | done | v0.1.1 |
| 2 Storage overview + apps | #5 | PR #6 | done | v0.2.0 |
| 3 Large/old files, APKs, trash | #7 | PR #8 | done | v0.3.0 |
| 4 Duplicates | #9 | PR #10 | done | v0.4.0 |
| 5 Junk cleanup | #11 | PR #12 | done | v0.5.0 |

Resume cron job: `f5f9f73a758f` removed after v0.5.0.

## Done

All five phases are released (v0.1.0 to v0.5.0, plus the v0.1.1 security release). Each phase was merged after
green CI (build, unit tests, APK check, API 34 emulator tests) and a review of its code and screenshots.
Fixes made in review before merging:
- Phase 3: the trash writes its index before moving files and never deletes unindexed files.
- Phase 4: a duplicate is only trashed if the kept copy is unchanged in size and modified time.
- Phase 5: `.nomedia` marker files are never junk.

Not verified anywhere: a real phone, Android 8-10, and large real file trees.

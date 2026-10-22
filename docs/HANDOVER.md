# Handover

**Updated:** 2026-10-22, phase 1 started.

- **Branch:** `claude/nocturne-hardening`, base `22766fd`.
- **Plan:** `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`.
- **Register:** `docs/review/register.md`. Everything is open unless marked otherwise.

## Integrated
- Baseline recorded, with screenshots in `docs/screenshots/baseline/`.

## In flight
- Contracts done (`0a7e677`). Task briefs are in the plan (`e4b0cae`).
- Task 1 (BLE) and Task 2 (Persistence) are being implemented in parallel worktrees.

## Next tasks
1. Review and integrate Tasks 1 and 2 (task review each, then merge, then the full suite).
2. Task 3 (night state core), then Task 4 (app runtime).
3. Tasks 5 (UI) and 6 (platform features) in parallel, then Task 7 (release).
4. Final review, after-screenshots, docs, draft PR, test APK.

## Reproduce
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
SCREENSHOT_TAG=after ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture*'   # renders to app/build/screenshots/after
```

## Continuation prompt (if this session ends)
> Continue the Nocturne hardening on branch `claude/nocturne-hardening` in `Willzy12h/bedjet-android`. Read `docs/HANDOVER.md`, `docs/review/register.md` and `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`. Resume at "Next tasks", keep the register and handover updated after each package, and follow the brief's constraints in the plan.

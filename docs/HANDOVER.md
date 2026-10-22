# Handover

**Updated:** 2026-10-22, phase 0.

- **Branch:** `claude/nocturne-hardening`, base `22766fd`.
- **Plan:** `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`.
- **Register:** `docs/review/register.md`. Everything is open unless marked otherwise.

## Integrated
- Baseline recorded, with screenshots in `docs/screenshots/baseline/`.

## In flight
- Phase 0 contracts (lead).

## Next tasks
1. Finish the contracts: clock, device id, Settings split.
2. Launch W1 BLE, W2 Persistence and W3 Runtime in isolated worktrees.

## Reproduce
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
SCREENSHOT_TAG=after ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture*'   # renders to app/build/screenshots/after
```

## Continuation prompt (if this session ends)
> Continue the Nocturne hardening on branch `claude/nocturne-hardening` in `Willzy12h/bedjet-android`. Read `docs/HANDOVER.md`, `docs/review/register.md` and `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`. Resume at "Next tasks", keep the register and handover updated after each package, and follow the brief's constraints in the plan.

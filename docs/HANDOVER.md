# Handover

**Updated:** 2026-10-22, checkpoint after Tasks 1–2. Paused here at the owner's request (token budget).

- **Branch:** `claude/nocturne-hardening`, pushed. The base is `claude/lucid-mayer-i1h2gy` @ `22766fd`.
- **Plan and task briefs:** `docs/superpowers/plans/2026-10-22-nocturne-hardening.md` (Tasks 1–7, global constraints, carried items).
- **Register:** `docs/review/register.md`.
- **Ledger** (rulings, deferred minors, review outcomes): `docs/review/sdd-ledger.md`.

## Integrated and verified
- **Phase 0:** baseline (`docs/screenshots/baseline/`), and the contracts: `Graph.clock`, `DeviceId`, `Settings.deviceId()`, Settings in its own file.
- **Task 1, Bluetooth transport (R01–R08, R21 link side).**
  - A session-owned GATT state machine behind `GattPort`, with stale callbacks rejected and the session retired after a timeout or cancellation.
  - A cancelled attach closes the GATT; abandoned connects are cancelled.
  - `prepareForStart()` makes sure only a fresh status confirms a start.
  - Connects only to the chosen target, with bounded retry; `discover()`; `suspendAutoConnect` / `resumeAutoConnect`.
  - Task review, then fix round 1 (2 Important fixed), then scoped re-review: clean.
- **Task 2, persistence (R19/R20 storage side, R22–R25, V02).**
  - Durable before publish, with a serial off-main writer.
  - Device-bound original-slot backups with an empty marker (`recordOriginalSlot`, `originalSlot`, `originalsFor`); migrated originals are adopted by the first real BedJet.
  - Validated, all-or-nothing import with a pre-import snapshot.
  - Schema 2 with migration; secrets excluded from backup and device transfer.
  - Saves are ordered before the commands that depend on them.
  - Task review, then fix round 1 (2 Important plus 1 spec gap fixed), then re-review: clean.
- **Verification at the checkpoint:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passes: core 99/99, app 89/89 (4 screenshot tests skipped by design), lint 0 errors. This is automated only; no hardware has been tested.

## Not started (briefs ready in the plan)
1. **Task 3:** night state and planning core.
   - Immutable active-night snapshot and revisions.
   - A guard refusing nights over 16 h (the 06:34 → 24.5 h finding).
   - One alarm policy and one start-now preview.
   - Truthful limits.
   - Tonight-only wake override and resume backends; temporary "Use tonight".
2. **Task 4:** app runtime.
   - Takeover from the Remote and physical remote.
   - Serialised commands with night/revision identity.
   - Original-slot backup before every write.
   - Pending re-time with retry and expiry; no past-wake re-arming.
   - Foreground-service checks and shortcut security.
   - Pinning, Stop tracking, diagnostics, history events.
   - Also the carried items in the plan.
3. **Task 5:** UI and accessibility in the existing design.
   - Snackbar, editor drafts, TalkBack, strings, haptics.
   - Scheduled theme, first-run setup with BedJet selection, morning check-in.
   - UI for override, resume, pinning and status. Also the carried items.
4. **Task 6:** platform features. Richer widget, Quick Settings tile, bedtime reminders, charger auto-start (off by default), Health Connect overlay, History CSV, Lull-compatible hand-over.
5. **Task 7:** release signing (no debug-key fallback) and R8 shrinking. Then the final whole-branch review, after-screenshots, the Samsung + BedJet acceptance checklist, a draft PR to `claude/lucid-mayer-i1h2gy`, and a test APK.

**Order:** Task 3, then Task 4, then Tasks 5 and 6 in parallel, then Task 7. Every task is: implementer, task review, fix rounds, scoped re-review, merge, full suite.

## Known interim state on this branch
- The link won't connect until a BedJet is chosen (`Settings.targetDevice`). Adopting the address remembered by older versions and the selection UI are in Tasks 4 and 5. Demo mode works.
- "Forget this BedJet" shows an untrue message until Task 5.
- No original slot backup is saved while no BedJet is chosen.

## Reproduce
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
SCREENSHOT_TAG=after ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture*'   # renders to app/build/screenshots/after
```

## Continuation prompt
> Continue the Nocturne hardening in `Willzy12h/bedjet-android` on branch `claude/nocturne-hardening`. Read `docs/HANDOVER.md`, `docs/superpowers/plans/2026-10-22-nocturne-hardening.md` (global constraints and Tasks 3–7, including the "Carried from Tasks 1–2" notes), `docs/review/register.md` and `docs/review/sdd-ledger.md`. Use superpowers:subagent-driven-development with Opus at medium effort. Run Task 3, then Task 4, then Tasks 5 and 6 in parallel worktrees, then Task 7. Each task gets an implementer, a task review, fix rounds and a scoped re-review before merging. Keep the existing UI design. Update the register, the ledger and this handover after each task. Finish with a final whole-branch review, after-screenshots, the acceptance checklist, a draft PR to `claude/lucid-mayer-i1h2gy` and a test APK with its SHA-256.

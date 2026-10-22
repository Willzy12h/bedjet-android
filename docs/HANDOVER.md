# Handover

**Updated:** 2026-10-22, Astra continuation from `83e6efa`. Tasks 1–3 are integrated and verified automatically. Task 4 is in progress in an isolated worktree; no hardware has been tested.

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
- **Task 3, night-state/planning core (R09–R12, R17/R29 core, R33–R36; F01/F02 backends).**
  - Saved profile snapshots, exact applied programme revisions, explicit confidence and pending state; v1 remains readable with a flagged fallback.
  - One wake/source policy and start preview; 1–16-hour starts, 32-step guard and truthful mode/fan caps.
  - Prepared identity-checked mutations, local stop/expiry, expiring tonight choice/override and resume backend.
  - Independent review found two timing defects; fix round 1 and scoped re-review closed them. Eligibility is rechecked after upload/readback and immediately before the actual start command, including delayed clock synchronisation.
  - Fixed a Demo-only fresh-status seam; deterministic evening fixtures replace wall-clock-dependent tests. Real GATT was not redesigned.
  - **Runtime integration remains:** Task 4 must replace legacy adapters, supply durable callbacks/original-slot backup and persist complete intended state/command generation. Tasks 4–6 finish presentation and feature wiring.
  - Evidence: `docs/review/task-3-implementation.md`, `task-3-review.md`, `task-3-rereview.md`.
- **Latest full gate (Task 3 merge):** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain` passed in 52 s: **140 core passed; 85 app passed and four screenshot tests skipped; zero failures/errors; lint zero errors and two warnings** (`OldTargetApi`, `ObsoleteSdkInt`). Debug APK: 60,654,288 bytes. Installing SDK 36 for the later Health Connect task exposed the target-35 warning; targetSdk has not changed. Automated only.


## Remaining tasks (briefs ready in the plan)

1. **Task 4 — in progress:** app runtime, alarms, service and command ownership. Read its expanded lead integration rulings as well as the carried notes: durable complete mutation intent, original-slot backup before writes, pending retries/expiry, identity/target binding, takeover/recovery, shortcut security, pin/restore/resume, job-owned operations and a whole-command hand-over gate.
2. **Task 5:** UI/accessibility in the existing Midnight Ink, Daylight and Simple design; scheduled theme, setup/device selection, morning check-in, strings/haptics, drafts, recovery and feature controls. Choice expiry must refresh an open screen.
3. **Task 6:** platform features in parallel with Task 5. Richer widgets/tile, reminders, best-effort charger jobs (off by default), Health Connect, CSV and pinned Lull hand-over. Read `docs/review/platform-preflight.md` and the plan's platform rulings; do not implement an unsupported manifest charger receiver/FGS path.
4. **Task 7:** release signing and shrinking. Then a final whole-branch review and one fix wave, after-screenshots with `Graph.clock`, completed screenshot comparison, draft PR to `claude/lucid-mayer-i1h2gy` and a test-signed release APK with version/SHA-256.

**Order:** Task 4, then Tasks 5 and 6 in parallel isolated worktrees, then Task 7. Each task: test-first implementer, independent task review, fix rounds, scoped re-review, merge, full gate, register/ledger/handover update and push. Use **GPT-6 Astra at medium effort**, per the owner's model substitution. Tasks 1–3 are complete and must not be redone.

The supervised Samsung + BedJet 3 checklist is drafted at `docs/review/samsung-bedjet-acceptance.md`; **every physical result is NOT RUN**. Fill in final candidate metadata and attach automated evidence at release hand-off.

## Known interim state on this branch
- The link won't connect until a BedJet is chosen (`Settings.targetDevice`). Adopting the address remembered by older versions and the selection UI are in Tasks 4 and 5. Demo mode works.
- "Forget this BedJet" shows an untrue message until Task 5.
- No original slot backup is saved while no BedJet is chosen.

## Continuation verification

Historical baseline gate on this host passed: **99 core passed; 89 app tests total, 85 passed and four screenshot tests skipped; zero lint errors, one ObsoleteSdkInt warning**. The original handover's “89/89” included the four skipped captures. No hardware was tested.

Build environment on this host: a full JDK 17 is required (the system Java is a JRE). `JAVA_HOME=/workspace/scratch/ad4eb857ceea/nocturne-jdk17/jdk-17.0.20.1+1`, `GRADLE_USER_HOME=/workspace/scratch/ad4eb857ceea/gradle-cache`, SDK `/workspace/scratch/ad4eb857ceea/android-sdk`. Gradle requires approved elevated execution for local coordination sockets; existing dependencies are cached and the baseline ran with `--offline`. These paths are host-specific, not project configuration.

## Reproduce
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
SCREENSHOT_TAG=after ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture*'   # renders to app/build/screenshots/after
```

## Continuation prompt
> Continue Nocturne hardening in `Willzy12h/bedjet-android`, branch `claude/nocturne-hardening`. Read `docs/HANDOVER.md`, `docs/superpowers/plans/2026-10-22-nocturne-hardening.md` (global constraints, Tasks 4–7 and all lead/carried rulings), `docs/review/register.md`, `docs/review/sdd-ledger.md`, and the Task 3 review/API hand-off. Tasks 1–3 are merged and auto-verified; do not redo them. Use superpowers:subagent-driven-development with GPT-6 Astra at medium effort. Implement Task 4, then Tasks 5 and 6 in parallel isolated worktrees, then Task 7. In each fresh task worktree the implementer's first shell step is `git reset --hard claude/nocturne-hardening`, then create local.properties. Use test-first implementation, independent task review, fixes/scoped re-review, merge and the full four-task Gradle gate. Write agent reports to scratch and copy to the main SDD workspace. Preserve the current design, UK English, BedJet 3 only, truthful pending/failed/unknown, no hardware claims. Update register/ledger/handover and push after each task. CLI Git has no write credentials on this host: use the authorised connector with identical-tree verification/non-force expected-head updates if still necessary. Finish with whole-branch review + one fix wave, clock-pinned after screenshots versus baseline, the Samsung/BedJet acceptance checklist, a draft PR to `claude/lucid-mayer-i1h2gy`, and a test-signed release APK/version/SHA-256. Checkpoint before stopping if budget runs low.

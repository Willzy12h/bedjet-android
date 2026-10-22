# Handover

**Updated:** 2026-10-22, Astra continuation from `83e6efa`. Tasks 1–4 and 6 are integrated, independently reviewed and verified automatically. Task 5's verified UI checkpoints now need the reviewed platform integration and its independent review. No hardware has been tested.

- **Branch:** `claude/nocturne-hardening`. Publication uses expected-head/non-force checks and identical-tree verification through the authorised connector; local implementation hashes in reports are recorded in published commit messages. The earlier connector rejection is resolved after renewed instruction and a normal retry. Base: `claude/lucid-mayer-i1h2gy` @ `22766fd`.
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
  - Task 4 has integrated durable callbacks, original-slot backup and complete intended-state/command-generation persistence. Tasks 5–6 finish presentation and feature wiring.
  - Evidence: `docs/review/task-3-implementation.md`, `task-3-review.md`, `task-3-rereview.md`.
- **Task 4, app runtime and command ownership.**
  - Durable command intent, coalesced alarm observations and received wake/end delivery; no automatic replay after an interrupted or uncertain hardware mutation.
  - Captured target/night/revision validation under one command owner, service-owned UI operations, bounded job-owned starts and whole-command hand-over.
  - Recovery, override, original-slot pin/restore, diagnostics, quiet notification and identity-bound replacement/resume confirmation APIs.
  - Independent review found five Important gaps; fix round 1 and scoped re-review closed all five. Reports: `docs/review/task-4-implementation.md`, `task-4-review.md`, `task-4-rereview.md`.
  - **Frozen UI/platform contracts:** read the Task 4 implementation report before Tasks 5/6. Use `BedJetService.runOwned` for real UI hardware work, captured requests for dialogs, `performJobOwned` for eligible charger starts, and coordinator release/take-back for hand-over. Do not add another command owner.
- **Task 6, platform features (U03/U08 platform side; F03/F04/F06/F07/F10/F11/F12).** Richer widgets, tile, reminders, default-off charger jobs, optional Health Connect, CSV and exact-name Lull hand-over adapters are implemented. Independent review found JobService stop-identity and CSV formula defects; fix round 1 and scoped re-review closed both. Read `docs/review/task-6-implementation.md`, `task-6-review.md`, `task-6-rereview.md` and `task-6-integration.md`. Task5 still owns the four real screen placements, navigation/theme integration and origin-owned feedback adoption. Do not add another command owner.
- **Latest full gate (Task 6 merge):** the required four-task command passed in a fresh checkout of local merge `37fb4c0` in **3 min 38 s; all 59 tasks executed: 138 core passed; 190 app passed and four screenshot tests skipped; zero failures/errors; debug assembled; lint zero errors and 14 documented warnings**. Debug APK: 65,604,270 bytes. See `docs/review/task-6-integration.md`; the earlier Task4 gate remains recorded separately. Automated only, not warning-free.


## Remaining tasks (briefs ready in the plan)

1. **Task 5:** merge the reviewed/published Task6 into its existing clean branch; wire `PlatformSettingsSection`, `HistoryExportButton`, per-record `HealthSleepOverlay` and idle/active `PhoneHandoverMenuItems`. Adopt `runOwned(..., notifyFailures=false)` only with visible ordinary/timeout failures and genuine cancellation propagation. Preserve its verified scheduled theme, atomic setup selection, consumed tile intent, drafts, accessibility, check-in and current design. Extend actual capture coverage to the new controls, then run its independent review/fix loop, merge, full gate and push. See `docs/review/task-5-implementation.md` and the current task brief.
2. **Task 7:** release signing and shrinking. Then a final whole-branch review and one fix wave, after-screenshots with `Graph.clock`, completed screenshot comparison, draft PR to `claude/lucid-mayer-i1h2gy` and a test-signed release APK with version/SHA-256. Candidate version: `0.2.0-hardening-test.1` / code `2`; this is not a production release.

**Order:** Tasks5/6 were implemented in parallel; Task6 is now complete and Task5 finishes their UI integration before Task7. Each task: test-first implementer, independent task review, fix rounds, scoped re-review, merge, full gate, register/ledger/handover update and push. Use **GPT-6 Astra at medium effort**, per the owner's model substitution. Tasks1–4 and6 are complete and must not be redone.

The supervised Samsung + BedJet 3 checklist is drafted at `docs/review/samsung-bedjet-acceptance.md`; **every physical result is NOT RUN**. Fill in final candidate metadata and attach automated evidence at release hand-off.

## Active implementation worktrees

- Task5: `/root/hardening_task5`, `nocturne-wt-task5`, branch `astra/nocturne-task5`; report in scratch `task-5-report.md` when complete.
- Completed Task6: `/root/hardening_task6`, `nocturne-wt-task6`, branch `astra/nocturne-task6`, implementation/fix `4131eaa..b973d1f`. It is merged and verified; leave it unchanged unless a later scoped finding requires work.
- Task5's two clean commits are `7117c85` and `0691f86`, after identical-tree metadata alignment. The three independent follow-ups are complete: latest full gate passed 142 core/177 app tests, five capture skips, debug assembly and lint zero errors/two warnings. Its report is `docs/review/task-5-implementation.md`; only the Task6-dependent UI/API wiring remains before its independent review.
- Both began at local `9bc63a2` and have now been mechanically aligned onto its published equivalent `d7368a9b915f3fea1d46a293d7b2ee6351f5a57a`, preserving identical task trees and backup refs. Continue their current work; never reset unfinished changes. Task5 awaits the reviewed/published Task6 merge. The reset-first instruction applies to **new** task worktrees only.
- Shared feature-UI and tile navigation contracts are in the ledger and task briefs. MainActivity's `openTonight` request overrides restored navigation while retaining a recoverable dirty editor draft.
- Next integration: merge the latest reviewed/published Task6 main into Task5's committed branch and resume Task5 to adopt the reviewed service failure-notification policy and wire the four feature composables before Task5's independent review. The hand-over overflow must be reachable while idle as well as during a night. Keep operation-timeout feedback visible at its origin; normal lifecycle cancellation still propagates.
- While Task6 is reviewed, Task5 may finish its already-approved independent setup transaction, handled tile-intent consumption and held-open configuration theme tick. It must commit that work before alignment; the four feature calls and service API adoption still follow the reviewed Task6 merge.

## Known interim state on this branch
- Task 4 adopts a valid remembered legacy address and has a truthful Forget implementation. Explicit discovery/selection and the full setup flow are Task 5.
- No original-slot backup is saved without a selected real BedJet. Demo remains separate.
- Hardware-dependent reliability, Samsung background behaviour, Lull interoperability and real accessibility remain supervised acceptance checks, not automated claims.

## Continuation verification

Historical baseline gate on this host passed: **99 core passed; 89 app tests total, 85 passed and four screenshot tests skipped; zero lint errors, one ObsoleteSdkInt warning**. The original handover's “89/89” included the four skipped captures. No hardware was tested.

Build environment on this host: a full JDK 17 is required (the system Java is a JRE). `JAVA_HOME=/workspace/scratch/ad4eb857ceea/nocturne-jdk17/jdk-17.0.20.1+1`, `GRADLE_USER_HOME=/workspace/scratch/ad4eb857ceea/gradle-cache`, SDK `/workspace/scratch/ad4eb857ceea/android-sdk`. Gradle requires approved elevated execution for local coordination sockets; existing dependencies are cached and the baseline ran with `--offline`. This host has an 8 GiB memory limit: use the scratch `run-gradle.sh` wrapper (shared flock, 2 GiB heap, two workers) to serialise heavy builds while implementation stays parallel. These paths are host-specific, not project configuration.

## Reproduce
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
SCREENSHOT_TAG=after ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture*'   # renders to app/build/screenshots/after
```

## Continuation prompt
> Continue Nocturne hardening in `Willzy12h/bedjet-android`, branch `claude/nocturne-hardening`. Read `docs/HANDOVER.md`, `docs/superpowers/plans/2026-10-22-nocturne-hardening.md` (global constraints, Tasks5–7 and all lead/carried rulings), `docs/review/register.md`, `docs/review/sdd-ledger.md`, and the Task4/6 implementation reports for runtime/feature APIs. Tasks1–4 and6 are merged, independently reviewed and auto-verified; do not redo them. Use superpowers:subagent-driven-development with GPT-6 Astra at medium effort. Merge the latest published main into the existing clean Task5 branch listed above, preserve both sides of shared-file changes, and resume Task5 for the remaining real feature placements and feedback-policy adoption; do not reset its verified work. Complete its independent review/fix loop, then implement Task7 in a fresh worktree. Each implementer's first shell step in its fresh task worktree is `git reset --hard claude/nocturne-hardening`, then create local.properties. Use test-first implementation, independent task review, fixes/scoped re-review, merge and the full four-task Gradle gate. Use the bounded shared Gradle wrapper and fresh verification checkout on this host; do not trust stale output. Write reports to scratch and copy to the main SDD workspace. Preserve the existing design, UK English, BedJet3 only, truthful pending/failed/unknown, no hardware claims. Update register/ledger/handover and push after each task. CLI Git has no write credentials here: use the authorised connector with identical-tree verification/non-force expected-head updates if necessary. Finish with whole-branch review + one fix wave, complete clock-pinned after screenshots versus baseline (including added feature views), Samsung/BedJet acceptance checklist, draft PR to `claude/lucid-mayer-i1h2gy`, and test-signed release APK/version/SHA-256. Test version is `0.2.0-hardening-test.1` / code2, with throwaway key outside Git. Checkpoint before stopping if budget runs low.

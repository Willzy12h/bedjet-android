# SDD ledger — plan: docs/superpowers/plans/2026-10-22-nocturne-hardening.md
Spec: docs/superpowers/specs/2026-10-21-nocturne-design.md + implementation brief (owner message 2026-10-22). Attached review action plan unavailable — rulings provisional where it would have decided.
Base for Tasks 1–2: e4b0cae

## Pre-flight scan
| Pair / task | Shared file or interface | Finding | Ruling |
|---|---|---|---|
| T1 × T2 | App.kt (T1 bleLink line; T2 maybe Repository ctor) | small textual overlap | lead merges; both allowed |
| T1 × T4 | LinkSessions API (suspendAutoConnect, holdForScreen), link target provider | T4 consumes T1 | T4 starts after T1 integrated |
| T2 × T3 | SlotBackup/NightRecord (T2) vs NightRecord.revisions (T3) | both touch History.kt? T3 owns core/night only; revisions field lives in History.kt (T2-owned) | Ruling: T3 may add `revisions` to NightRecord in History.kt after T2 merged — T3 starts after T2 |
| T2 × T4 | recordOriginalSlot API | T4 consumes T2 | sequential |
| T3 × T4 | ActiveNight v2, NightRunner APIs, NightPreview, AlarmPolicy | T4 consumes T3 | sequential |
| T3 × T5 | Settings.tonightChoice / override; NightPreview | T5 UI consumes | sequential |
| T4 × T5 | NightActions (T4) vs AppViewModel/UI (T5) | T5 owns ui/**; NightActions frozen after T4 | sequential |
| T5 × T6 | Settings UI placement; strings files | T6 writes ui/features + strings_features.xml; T5 wires | parallel OK in separate worktrees; lead wires if T5 finished first |
| T6 × T7 | build.gradle.kts (HC dependency vs minify) | sequential T7 after T6 | ok |
| T1 self | tests need GattPort abstraction | consistent | ok |
| T2 self | Repository suspend migration touches call sites outside ownership | brief allows mechanical call-site edits | ok |
| T3 self | R34 refuses >16h nights vs existing WakeResolver "next occurrence ≥1h" | consistent: refusal message suggests override | ok |
| T4 self | demo FGS type shortService | shortService limit 3 min; ok for demo commands | ok |
| T5–T7 self | consistent | — | ok |

Ruling: Tasks 1 and 2 run in parallel in isolated worktrees — the owner's brief explicitly asks for BLE and persistence in parallel (overrides the skill's no-parallel default); file ownership is disjoint — cost if wrong: merge conflicts in App.kt resolved by lead.
Ruling: subagents use Opus at medium effort (owner's standing instruction) for implementers and reviewers — cost if wrong: some tasks may need extra fix rounds.
Task 1: dispatched (implementer a5da1014e634735f2, worktree, BASE e4b0cae)
Task 2: dispatched (implementer aa97d74b3bfc56bf9, worktree, BASE e4b0cae)
Task 2: implementer DONE_WITH_CONCERNS (6bc0541; core 98, app 50 pass); task review dispatched (a1a2e0d42c62417df)
Task 1: implementer DONE_WITH_CONCERNS (7718d3c; 127 tests pass). Carry to Task 4: adopt rememberedAddress() into Settings.targetDevice at startup; to Task 5: BedJet selection UI. Ruling: in-memory hand-over release acceptable (process death resumes auto-connect) — cost if wrong: another phone may lose the BedJet after app kill.
Task 2: review — Needs fixes (2 Important: reapply read-after-write race; unknown-device originals re-recorded; spec gap: startup marker write on main). Fix round 1 sent to implementer.
Task 2: minor (deferred): crash mid-import leaves mixed state (no journal)
Task 2: minor (deferred): SAF import/export holds the single writer
Task 2: minor (deferred): Notifications.addNotice runCatching swallows CancellationException
Task 2: minor (deferred): schema<=0 gives raw IllegalStateException message
Task 2: minor (deferred): validator skips slot-backup program steps, empty/program consistency, deviceId format, duplicate history ids
Task 2: minor (deferred): SnapshotStore HeaderReader rejects unknown header keys (future fields hide snapshots)
Task 2: minor (deferred): recordOriginalSlot lacks slot range check
Task 2: minor (deferred): addSlotBackup can replace an original — deprecate once Task 4 uses recordOriginalSlot
Task 2: minor (deferred): app tests written before code but first run green (mutation-proven) — TDD process note
Task 2: minor (deferred): snapshot dir not fsynced; stray .tmp not cleaned
Task 2: ⚠ resolved — R19 ordering (backup before write) carried to Task 4 brief; unreadable/dismissUnreadable UI carried to Task 5; editor bounds vs validator: editor phase 5..240 min, ramp 1..8, fan 5..100, temps coerced to mode ranges — within validator; import deleting the running profile covered by R10 (Task 3 snapshot).
Task 1: review — Needs fixes (Important: attach success after session death; R06 stale extra before start write; Forget message untrue). Fix round 1 sent.
Task 1: Ruling: R06 fixed via additive BedJetLink.prepareForStart() called by BedJetController.startProgram after syncClock and before subscribing; Task 1 allowed to edit BedJetController.startProgram — cost if wrong: one extra suspend call per start.
Task 1: Ruling: "Forget this BedJet" (AppViewModel) truthfulness moved to Task 5 (UI owns AppViewModel; forget must clear Settings.targetDevice) — cost if wrong: interim branch shows an untrue message until Task 5.
Task 1: minor (deferred): disconnect() not serialised with in-progress connect (unless fixed in round 1)
Task 1: minor (deferred): unsynchronised RMW on _status between binder and remainder read
Task 1: minor (deferred): any remainder-read failure retires the session; misleading comment GattLink.kt:299
Task 1: minor (deferred): timed-release job and screen-hold reconnect untested
Task 1: minor (deferred): delay(50)+isCompleted negative assertions could pass vacuously
Task 1: minor (deferred): 133 test doesn't assert no scans; SecurityException waiter-leak only structural
Task 1: minor (deferred): FakeRadio non-daemon executor leaks a thread per test
Task 1: minor (deferred): GattLink.shutdown() test hook is public
Task 1: minor (deferred): runCatching in holdForScreen/reconnect catches CancellationException
Task 2: fix round 1/5 implemented (04cba86); scoped re-review dispatched (af3b5fb1b49147f9a, sonnet). Concern carried to Task 4: ensure a target is set before any slot write (no original saved when target null).
Task 2: fix round 1/5 (3 addressed, 0 open; commits 6bc0541..04cba86)
Task 2: complete (commits e4b0cae..04cba86, review clean)
Task 2: minor (deferred): dismissUnreadable may race queued setAsidePending marker write
Task 1: fix round 1/5 implemented (56ab099); re-review dispatched
Task 1: fix round 1/5 (2 addressed, 0 open; commits 7718d3c..56ab099)
Task 1: complete (commits e4b0cae..56ab099, review clean)
Task 1: minor (deferred): end-to-end R06 gap test is a slow regression guard (1.8 s) that doesn't distinguish old/new code
Task 1: minor (deferred): final state after a disconnect racing connect not asserted


## Astra continuation — 22 October 2026

Resumed from 83e6efa69ac46f02ba5e9bd8d542774cece734eb. Tasks 1 and 2 remain complete; their deferred observations remain visible for the final whole-branch review. No hardware verification is claimed.

Ruling: use GPT-6 Astra at medium effort for implementers and all reviewers — the owner explicitly substituted Astra for Opus in this session — cost if wrong: additional fix rounds may be needed; model identity remains truthful.
Ruling: do not append the old Claude Opus co-author or Claude-session URL to new commits; use truthful Astra attribution instead — those trailers describe a different agent/session — cost if wrong: existing commit-message tooling may need adaptation.
Ruling: reset only newly created, isolated task worktrees to the latest integrated claude/nocturne-hardening, then write each local.properties using this host's SDK path — never discard unrelated main-worktree changes — cost if wrong: a stale task base requires rebase/review.
Ruling: execute Task 3, then 4, then Tasks 5 and 6 in parallel isolated worktrees, then 7; lead merges, verifies and pushes each task — explicit owner instruction supersedes earlier phase tables and the skill's default serial implementation — cost if wrong: shared integration changes require explicit reconciliation.

### Continuation preflight

| Task/pair | Shared contract or self-check | Resolution |
|---|---|---|
| T3 self | Models, alarm eligibility, preview, overrides and resume must agree; v1 persistence remains readable | Implement and test in core; retain compatibility only where explicit and observable |
| T3 × T4 | ActiveNight/applied state, expected identity, pending mutation and runner APIs | T4 starts only after reviewed T3 merge; use implementer's interface report |
| T3 × T5 | Temporary selection/override state and applied programme display | UI consumes the reviewed core model; no live-profile reconstruction |
| T3 × T6 | Override, profile resolution and runtime persistence | Platform features consume current contracts; no parallel core-night rewrite |
| T4 self | Local expiry must not connect; uncertain partial writes must persist before mutation | Keep serial state ownership distinct from connection acquisition; regression evidence required |
| T4 × T5 | NightActions public API, command outcomes, setup/device operations | T4 provides documented UI-facing API; T5 requests any new coordinator change from lead |
| T4 × T6 | Service/coordinator actions, scheduling and hand-over suppression | T6 uses reviewed commands and carries identity; any shared runtime edit requires coordination |
| T5 self | Preserve existing design while adding accessibility, recovery and feature controls | Local extensions only; actual-app before/after renders and semantics checks |
| T5 × T6 | Feature UI wiring, Settings persistence, strings and manifest | Isolated worktrees; feature composables/strings_features.xml owned by T6; lead resolves shared Settings/wiring explicitly before launch |
| T5 × T7 | New settings/resources and minified persistence | T7 follows both feature/UI merges; verify minified serializers and components |
| T6 self | Optional automation/provider/permissions cannot alter core control implicitly | Default-off automation; fail gracefully; hardware gates remain pending |
| T6 × T7 | Health Connect dependency, manifest components and R8 | T7 starts after T6 merge and includes new components in release checks |
| T7 self | No production-key fallback; test key is deliberately separate | Produce a test-signed APK without committing keys or publishing a release |

Task 3: dispatched (GPT-6 Astra medium; agent /root/hardening_task3; worktree nocturne-wt-task3; BASE a8cbaba).

Ruling: Task 3 may mechanically update AppViewModel.activePlan and Repository.tonightsProfile to use its new snapshot/temporary-selection helpers, plus dependent selectedProfileId callers — the model must actually govern existing displays and resolution — cost if wrong: compatibility edits need review before Task 4.

Continuation baseline gate: BUILD SUCCESSFUL (1m46s); 99 core passed; app 89 total = 85 passed + 4 screenshot captures skipped; zero failures/errors; debug APK 60,022,556 bytes; lint zero errors and one ObsoleteSdkInt warning. Complete JDK 17 + cached dependencies (--offline); no physical checks.

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

Ruling: legacy Task 3 runner mutators remain interim compatibility adapters only; Task 4 must migrate production callers to typed prepared transactions/current persisted identity and remove unsafe unused adapters — cost if wrong: the core gate alone cannot establish whole-app stale-action safety.
Ruling: Task 4 may make narrow Repository/Settings/History additions for runtime persistence and observability; Tasks5/6 may add their own defaulted feature fields, with Task 6 PlatformSettings in a separate file — existing durable persistence remains the owner; lead resolves Settings and lifecycle wiring — cost if wrong: integration conflicts or missing state migration.
Ruling: F04 uses native charging-constrained JobScheduler with bounded, cancellable job-owned BLE work, no FGS and no manifest ACTION_POWER_CONNECTED receiver. Its user promise is best-effort while charging in the window, with a fresh cancellable60s grace and durable once-per-night suppression. Android's documented restrictions make exact plug-edge execution unavailable; no permanent service is added — cost if wrong: the start window can be missed and must be reported truthfully.
Ruling: Task 4 provides a whole-command hand-over gate and a job-owned operation route before Task 6; release cannot interrupt a multi-packet transaction based on a stale idle check — cost if wrong: a partial upload is UNKNOWN and requires reconciliation.
Ruling: Task 6 may use stable Health Connect1.1.0 with compileSdk36 and AGP8.10.1; retain target35/min31/current Gradle/Kotlin/Compose. Inspected official AAR requires compileSdk36/AGP>=8.9.1; AGP8.10 explicitly supports API36. This narrowly extends dependency-only ownership — cost if wrong: integration build/tooling regression, checked by full gate and release build.
Platform preflight: `docs/review/platform-preflight.md` records official API/source evidence, exact pinned Lull name/UUID/transaction rules, tile launch rules, Health Connect metadata and remaining physical acceptance. SDK36 installed on the build host only.
Publishing: command-line Git lacks write credentials. Lead uses the authorised GitHub connector Git-data APIs, asserts identical local/remote tree hashes and uses an expected-head, non-force branch update. Canonical server commit IDs differ because the connector creates commit metadata; messages retain Local-Implementation-Commit. Verified local source trees are unchanged.

Task 3: implementer DONE at 5e0a072 (134 core passed,85 app passed,4 screenshot skips); independent review by /root/hardening_task3_review found2 Important temporal gaps: Resume could execute after the agreed wake before its15 min offer expired; upload/readback could cross eligibility boundaries before an unconditional Start. Fix round 1/5 dispatched to the same implementer; scoped re-review and full integration gate still required.
Ruling: the fixed-clock identical-step regression proved a DemoBedJet-only confirmation defect (real GattLink already resets cached progress). Task 3 was authorised to add the same prepareForStart reset to Demo, with RED/GREEN evidence. Clock advances solely masking that defect were rejected and reverted.
Task 3 review carry to Task 4: durable pending metadata is insufficient to recover an edited/intended replacement programme; persist the intended snapshot/steps explicitly, never reconstruct from a changed reusable profile. Narrow ActiveNight/NightChanges serialisable-state additions permitted during runtime integration.
Task 3 review carry to Task 5: consume the shared preview for headline/curve and refresh temporary profile selection when noon expiry passes with the screen still open.
Ruling: Task 3 fix round 1 may add a minimal synchronous guard hook in BedJetController.startProgram, checked immediately before the actual start command. Its existing syncClock/prepareForStart calls suspend, so a caller-only post-upload check cannot close the reviewed deadline gap. Guard failure must cancel the confirmation waiter and leave the changed slot outcome UNKNOWN; no transport redesign.
Task 3: fix round 1 implemented at479ca09; 36 focused tests passed. Independent scoped re-review /root/hardening_task3_rereview: both Important findings ADDRESSED, no new Critical/Important breakage. Reports retained under docs/review/task-3-*.md and copied into the SDD workspace. Ready to merge and run the full integration gate.
Task 3: complete after merge95bc135 and full integration gate (52 s): 140 core passed; 85 app passed +4 screenshot skips; zero failures/errors; debug APK60,654,288 bytes; lint0errors,2warnings (OldTargetApi after host SDK36 installation, existing ObsoleteSdkInt). Documentation-only ledger conflict from equivalent connector/local histories resolved by preserving the lead's complete ledger; no source conflicts.
Task 4: next, fresh worktree prepared at nocturne-wt-task4; implementer must reset to the latest canonical branch before local.properties. Clock time-zone responsiveness and the shared theme resolver are explicit integration requirements for the parallel UI/platform tasks.
Task 4: dispatched to /root/hardening_task4 (GPT-6 Astra, medium), isolated worktree nocturne-wt-task4, canonical BASE a5d01c1c83e828421a1420e45516d74bc91a1fde after Task 3's verified push. Narrow mechanical AppViewModel/caller adaptations are permitted to route existing Remote actions through owned APIs without redesign; Task 5 remains the UI implementation owner.
Ruling: removing obsolete runner adapters also permits mechanical migration of their four core test files and RepositoryTest's addSlotBackup fixtures. Preserve behavioural assertions, moving manual/Boost coverage to the runtime owner where necessary; do not retain unsafe production APIs solely for old tests.
Task 5 preflight: `docs/review/ui-preflight.md` maps additions into the existing design and identifies narrow/large-font, snackbar, semantics and specific contrast risks. It is read-only preflight, not implementation or an independent task review.
Task 6 security preflight: the stable Health Connect library merges an exported HealthDataSdkService without a manifest permission. Its methods authenticate the calling UID's ownership of the exact provider package before async dispatch; the public bind action is not authentication. Retain the service for API31–33's provider-token integration, document its package/UID trust boundary and lack of certificate pinning, and do not invent a permission or remove it without evidence. Detailed source inspection is in `docs/review/platform-preflight.md`; no exploit or device verification claimed.
Ruling: Task 4 uncovered and may fix a narrow Task 1 integration omission: AndroidBedJetLink did not delegate prepareForStart to GattLink, silently inheriting the interface's no-op. The lead confirmed the missing delegation. Add the forwarding call and a behavioural regression through the outer adapter; a minimal internal injection seam is permitted. No transport redesign or repeat of Task 1 is authorised.
Task 7 preflight: `docs/review/release-preflight.md` records the available signing/DEX tools, existing signing fallback/publication risks, kotlinx.serialization consumer rules and the lack of an Android runtime on this host. The plan's mapping-aware DEX inspection fallback is feasible; do not label ordinary JVM/Robolectric or a separate classfile shrink as execution of the APK's minified DEX.
Ruling: Task 4's receiver fallback cannot wait for the command mutex behind a long upload. It may persist a narrow, coalesced alarm-change observation via Repository's existing serial writer, outside command ownership, and schedule retry. The coordinator later consumes that signal under its mutex, re-reads the latest alarm, validates night/device/eligibility and prepares a current-identity retime. Clearing by consumed ID must preserve a newer observation. This is a durable submission inbox, not another hardware command owner; interrupted pending uploads still become UNKNOWN and are never blindly replayed. OS retry alone was rejected as insufficient durability for the busy-command case.
Ruling for Tasks5/6: narrow BackupValidator additions/tests for their new settings are permitted, with lead resolution of the shared insertion. Concrete carry to Task5: Task3's tonightChoice/tonightOverride ISO fields currently bypass import validation and can throw in Settings resolution; reject malformed times before restore. Task5 adds its scheduled-theme checks; Task6 validates PlatformSettings ranges. This is schema integration through the existing validator, not a storage redesign.
Task 4 integration rulings: preserve existing device/history assertions while waiting on typed CONFIRMED rather than the early End night UI label; derive migrated timestamp fixtures from their injected clock/zone. Explicit replacement confirmation must carry the dialog's captured identity, and stale-dialog tests are permitted. A universally true confirmation flag was rejected by automatic approval review and was not applied; the implementation uses typed CONFIRMATION_REQUIRED and actual confirmed callbacks instead.
Task 4 parallel-build seam: minimally migrate Widgets.widgetColors and its calls/tests to the shared Settings.isDarkTheme/settings/time API, preserving SYSTEM's dynamic providers and existing palettes. Its exhaustive three-value enum switch would otherwise break Task5's isolated SCHEDULED build before Task6 integration. No richer-widget feature implementation is authorised in Task4.
Task 4 lead caller check found that direct graph.scope UI operations were bounded/serialised but not owned by an FGS. A narrow suspend service handoff is authorised to close the existing R27 requirement: service promotion before finite work, cancellation/token cleanup, no closure replay or second command lock, and captured target/identity validated inside coordinator ownership. Local/demo/job-owned exceptions stay explicit. Add a real service ownership/cancellation regression before review.
Host verification constraint: memory.max is8GiB and one OOM kill was recorded during unconstrained concurrent test/dex/lint work. The bounded retry passed before the additional R27 fix (138 core passes,129 app passes,4 screenshot skips); this is not the final Task4 gate. Future worktrees use the shared scratch run-gradle.sh/flock wrapper with2GiB heap and2workers, serialising Gradle executions while implementation remains parallel. No cache deletion or daemon stop.

Task 4: implementer complete (`dd0c365`; BASE `a5d01c1`), independent task review dispatched to `/root/hardening_task4_review` (GPT-6 Astra medium). Evidence: `docs/review/task-4-implementation.md`; implementer gate 138 core passed, 133 app passed plus four screenshot skips, debug assembled, lint zero errors/two baseline warnings. Not integrated yet. The reviewed API hand-off will govern Tasks 5 and 6.

# Temporary night actions implementation plan

**Goal:** Offer a timed Turbo/Heat/other supported action during a running night, automatically returning to the underlying schedule at its original wake, alongside the existing end-night action.

**Architecture:** One composite on-device programme, issued by NightActions. Optional immutable temporary metadata preserves the base and expanded prefix. No phone resume timer. Captured typed requests cross UI/service/widget/shortcut boundaries.

**Tech stack:** Kotlin, kotlinx.serialization/coroutines, Compose, core JUnit 5, app Robolectric JUnit 4 SDK 34.

**Spec:** `docs/superpowers/specs/2026-10-26-temporary-night-actions-design.md`.

## Global constraints

- Work descends from fixes2 code `e114cc25481731e052a0abc564e74775666ec47e`; publish only `gpt/nocturne-fixes2` with a normal fast-forward preserving concurrent work.
- Do not touch production signing, merge PRs, rebase or force-push.
- Tests first: save actual failing assertions, then passing results. Compilation errors are not behavioural RED evidence.
- All new app text in resources, UK English; pending/failed/unknown/confirmed stay truthful.
- No hardware, Samsung or TalkBack verification claims.
- All Gradle work uses `/workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py`, which serialises the shared build environment and strips signing variables.

## Review focus

Exact target/night ownership; immutable presets and base schedule; no replayed Turbo; old/new wake and clock-step guards; 32-step capacity; cancellation at durable publication boundaries; no background delayed START; receipt/fresh-read confirmation; original alarm and pending observation semantics; clear planned-return wording and accessible choices.

## Task 1 — Core composite planner and durable transaction

- [ ] Add core regressions for temporary prefix plus absolute remaining schedule, repeated action, no wake drift, Turbo/gap handling, limits/capacity/DST, refusal confidence/identity and alarm re-time.
- [ ] Execute them against the old behaviour/API through minimal test scaffolding and record observed RED; do not count a missing-symbol compile failure.
- [ ] Add optional `TemporaryOverride` to ActiveNight, `Block.TEMPORARY`, `ChangeKind.TEMPORARY` and `NightRunner.prepareTemporary(night, expected, preset)`.
- [ ] Keep the base immutable; preserve expanded prefix for re-time; update resume offer handling and all pure-core enum consumers.
- [ ] Add a first-PENDING-publication cancellation regression; move publication inside the guarded transaction and bound request-specific durable cleanup.
- [ ] Run focused core tests to GREEN, then the complete core suite. Commit only owned core files and record exact evidence labels.

## Task 2 — Command owner and service handoff

- [ ] Add app regressions for captured temporary requests, queued stale target/identity, input validation, single confirmed composite, pre/post-upload takeover, cancellation, and pending alarm handoff.
- [ ] Observe RED with a minimal route before implementing dispatch.
- [ ] Extend CommandRequest with optional immutable `actionPreset`; add Command.TEMPORARY and a capture helper, strict identity and slot backup barrier. Honour the same captured preset on the existing End-tonight quick routes; old callers retain their settings-based defaults.
- [ ] Run prepareTemporary and apply under the existing owner; include temporary in confirmed-programme repair and schedule/History settlement without terminal events.
- [ ] Extend BedJetService intent encoding/decoding with fail-closed preset validation; preserve origin and timeout hierarchy.
- [ ] Map new planner/eligibility reasons to string resources. Add a small shared presentation/preset helper for UI and native entry points if needed; avoid duplicating policy.
- [ ] Run meaningful focused owner/service regressions to GREEN; commit only owned runtime/service/helper/resource files.

## Task 3 — Actual UI choice and related entry points

- [ ] Add actual Compose/native dialogue tests for Temporary/End/dismiss, captured presets, eligibility/refusal text and 320 dp/200% scrolling.
- [ ] Observe RED against the current replacement-only prompt.
- [ ] Extend NightGuard without changing unrelated recovery prompts; wire Tonight quick actions, Remote mode/Boost and widgets/shortcuts to typed requests.
- [ ] Provide one-action duration controls and truthful planned return text on the running night. Keep Off immediate, wake Snooze/Extend manual and reconnect UI visible.
- [ ] Add string resources, Block.TEMPORARY curve/text alternatives and appropriate accessibility labels.
- [ ] Run focused UI tests and unchanged U06 Remote regressions to GREEN; commit only owned UI/platform/test/resource files.

## Task 4 — Integrate, independently review and publish

- [ ] Integrate isolated commits, resolve shared resource/enum contracts, and review code against the spec.
- [ ] Add phone-test-guide rows and document BLE behaviour (the composite runs without an added phone wake or connection).
- [ ] Check the current remote branch and preserve concurrent changes before normal publication.
- [ ] Run exact gate: `./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
- [ ] Run the same flags with `:app:assembleRelease`; verify the unsigned output without altering signing.
- [ ] Count XML totals/passes/skips/failures/errors; compare lint IDs/severity/messages/paths; record immutable source hashes through the gates.
- [ ] Update HANDOVER with final code SHA/tree, files, RED/GREEN, protocol citations, BLE before/after, counts, chosen temporary-action defaults and remaining physical verification.
- [ ] Fast-forward only the authorised branch with expected-head protection; report exact published SHA and a device-test readiness verdict based on observed results.

# Independent review fixes implementation plan

> **For agentic workers:** Use test-driven implementation with separate ownership for runtime, persistence, UI and compilation. The primary worker runs Gradle serially, records RED/GREEN evidence and reviews all changes.

**Goal:** Close R1–R6 and adopt the recommended Q1–Q3 behaviour at the exact independently reviewed base, then deliver a verified installation candidate.

**Architecture:** Retain the current single command owner, receipt provenance, durable repository writer and Compose design. Correct the missing recovery shapes, independent fan timeline, omitted control colours and row geometry/drawing order locally. Reconcile eligible status observations across command completion without replaying upload intermediates.

**Tech stack:** Kotlin, coroutines, Compose, Robolectric, JUnit 4/5; existing Gradle and Android dependencies remain.

**Spec:** Independent review of `146501a69814f28d3879ca835bb93d2442b455a1`; approved `docs/redesign/reference/DESIGN-REFERENCE.md` and `RECONCILIATION.md`; user's 29 October instruction to fix all findings and prepare the app.

## Global constraints

- Base: `146501a69814f28d3879ca835bb93d2442b455a1`; fixes branch: `gpt/nocturne-independent-fixes`.
- Preserve the approved design, palette literals, four-tab navigation and existing widget design.
- BedJet 3 only. No physical BedJet, Samsung, TalkBack or overnight validation claims.
- Preserve target/session/night identity, post-ACK freshness, cancellation and truthful pending/unknown/failed/confirmed states.
- UK English; app copy in string resources; core exception text follows existing plain Kotlin convention.
- No PR, merge, tag, release, force-push or production-signing changes.
- Adopt Q1: a wake-only change preserves completed Quiet settling; explicit profile reapply remains distinct.
- Adopt Q2: same-process Activity recreation retains Share; a new repository/process consistently cancels it before opening output.
- Adopt Q3: Demo inherits both palette slots while real preferences and device data remain isolated.

## Review focus

- A fresh contradictory report received during durable publication or at unlock must not be lost.
- Wrong-target, stale and upload-intermediate reports must not become false takeovers.
- Retained valid journals must recover without hiding genuine corruption or replaying quarantined old data.
- Fan-only changes must keep Stay temperature, total duration, limits and the 32-step device boundary.
- Dragging across wrapped rows must land at visible geometry and remain readable, with cancellation and Save/Discard intact.

## Task 1 — persistence and session behaviour (R1, Q2, Q3)

**Files:** Repository.kt, ProfileShareRequest definition, repository/share/Demo regression tests.

**Interfaces:** Keep current writeAllOrNone and Share byte snapshot. Add a nonpersistent repository identity to Share requests with a compatible deserialisation default.

- [x] Adopt the three fault-injected journal reproductions as permanent tests.
- [x] Test Share invalidation at revisions zero and two after repository replacement, same-instance validity and no output open on rejection.
- [x] Test both Demo palette slots and exact real-state restoration.
- [x] Record intended RED failures before production edits.
- [x] Admit exactly `{profiles}` and `{profiles, settings}` journal sets; retain integrity checks.
- [x] Implement the Share identity guard and selective palette inheritance.
- [x] Run focused regressions and existing related tests; inspect source diff.

## Task 2 — fan compilation (R2)

**Files:** PlanCompiler.kt, Profile.kt documentation, IndependentFanTimelineTest.kt.

**Interfaces:** Saved Middle schema unchanged. Temperature shaping remains governed by rampSteps. Fan boundaries can split temperature segments, as existing Quiet already does.

- [x] Test Stay with distinct fan endpoints and a different stored tempEnd, odd/two/one-minute durations, limits, zero Middle and the 32-step boundary.
- [x] Record RED against the base compiler.
- [x] Keep existing multi-step Ease; for Stay use two independent fan segments and cut at fan/temperature boundaries.
- [x] Refuse a nonzero one-minute Ease that cannot represent both speeds; keep an omitted Middle omitted.
- [x] Run new tests and existing PlanCompiler/FanShape tests; inspect compiled durations and temperatures.

## Task 3 — controls and drag behaviour (R3, R4, R5)

**Files:** ProfileEditor.kt, EditorParts.kt, PlatformAutomationSettings.kt and native UI regression tests.

**Interfaces:** Existing helpers provide readable colours. PhaseList supplies measured row geometry to release-time movement. Accessibility moves and repository Save remain unchanged.

- [x] Adopt actual range-handle and enabled automation-switch contrast tests, checking both handles.
- [x] Adopt unequal-height pointer drop; add reverse/cancel and overlapping-card native evidence.
- [x] Record RED before production edits.
- [x] Wire the three omitted colour helper calls.
- [x] Replace grabbed-height division with actual normal-layout row geometry; mutate once at release.
- [x] Raise the entire lifted card's sibling drawing order; retain existing opaque backgrounds.
- [x] Run focused UI regressions and inspect normal/large-text and held-card images.

## Task 4 — confirmation, observation handoff and wake-only edits (R6, Q1)

**Files:** BedJetController.kt, NightRunner.kt, NightActions.kt, related receipt interfaces only if required, runtime/core regression tests.

**Interfaces:** Root owns PlanCompiler; runtime worker calls its existing replan interface. No unsolicited hardware command may result from a contradictory observation.

- [x] Adopt contradictory first-step and persisted Off-during-owner reproductions.
- [x] Add observations at durable-publication and ownership-release boundaries; include stale/target/cancellation controls.
- [x] Test manual wake-only settled Quiet against alarm-replan behaviour while leaving explicit Update tonight separate.
- [x] Record RED before production edits.
- [x] Match applicable first-step fields with current prepared-session/freshness protections intact.
- [x] Retain and reconcile the latest eligible receipt without queueing intermediate upload reports as takeovers; close the publication/unlock race.
- [x] Route wake-only preparation through the existing settling policy with current eligibility, attribution and persistence guarantees.
- [x] Run focused runtime tests and source review.

## Task 5 — complete verification and delivery

- [x] Review all task diffs and resolve newly found defects with a reproducing test.
- [x] Commit implementation and regressions with clear source identity.
- [x] Run exactly `./gradlew --offline --no-daemon --max-workers=3 -I docs/redesign/verification.init.gradle clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
- [x] Run every P8 opt-in capture with `SCREENSHOT_TAG=remediation`; preserve the main gate XML first.
- [x] Independently parse fresh reports, process exits, skips, lint and warnings; inspect changed native images.
- [x] Exercise release assembly/optimisation and existing release checks for the installation candidate where tooling permits.
- [ ] Use the existing permanent TEST signing route if available, without extracting its secrets or replacing the identity. Verify APK package/version/certificate/hash. **BLOCKED:** automatic approval review rejected the remote dry-run push/authentication check before execution; the existing permanent key is not local. Await explicit authorisation for the prepared branch push and existing TEST workflow.
- [x] Correct warning inventory scope and update handover/register with final evidence and physical-device limits.
- [x] Prepare the isolated source branch and complete evidence; explicitly report the delivery/signing blocker.
- [ ] Deliver the permanent TEST installation artifact after its authorised workflow succeeds and its exact source/package/version/certificate/hash are verified. Physical acceptance remains NOT RUN.

## Execution record

The progress/evidence register is `docs/redesign/INDEPENDENT-FIX-REGISTER.md`. Raw verification output is preserved separately and linked from the final handover.

Final tested source: `3fd7f043308f10a8524ab29e75c96f5cd2eea292`; application/test/build SHA-256 `4dfe10c9b415c67c891a4f62c7a8065e05607aec1f36687f66cec1a383322be4`. Final exact gate: core 332 passed, app 1,829 passed plus 41 documented opt-in skips. Separate capture run: all 41 passed, no skips; all 1,003 PNGs independently validate. Final clean/capture/release processes exit 0 with zero failures/errors, lint findings and compiler/build warnings. Release serialization covers 56 models / 130 cases before and after separate R8 JVM shrinking; actual APK DEX runtime is not executed. The follow-up handover commit changes documentation only.

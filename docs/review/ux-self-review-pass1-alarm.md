# UX self-review, pass 1: alarm, core and runtime boundaries

## Scope and evidence status

Reviewed the diff from `367968bddc390e5b2bb12876fc1662ffa07e47c3` to the restored UX checkpoint `0eddd188540df9d918c9c2e69701ae6e72f9a918`, together with the complete current contents of the 47 files listed below. The inventory includes shared Bluetooth/service dependencies and the five legacy test files assigned for fixture correction. Also traced the short-test exclusions through `platform/HistoryCsv.kt` and `platform/SleepOverlay.kt`, and read the relevant alarm, short-test, Bluetooth and Boost string resources. UI presentation, widgets and the whole integration review have separate owners.

The completed restored gate is `ux-full-gate-1-restored`: core 201 tests passed; app 1,180 tests, 37 failures, 0 errors and 38 skipped. Eight of those failures belong to the five test classes corrected in this pass. The other failures are recorded by the integration owner; this report does not reclassify them as passing.

The five authorised fixture corrections and two new regressions were written before the next batch. No production change was made for either new finding. At this report's first publication, `ux-review-pass1-red` has emitted a lint failure (1 error, 25 warnings, 2 hints): `widget/Widgets.kt:104`, `StateFlowValueCalledInComposition`. It has not yet supplied an executed RED or GREEN result for the new regressions. Source and test edits are frozen while the integration owner controls that batch. `git diff --check` passed.

## Concrete findings awaiting RED

### A. A retry warning can retain the superseded queued wake

`NightActions.kt:868` saves the initially prepared wake in `PendingRetime.requestedWakeAt`. `updateSettings` at `:1513` can change source acceptance while that request remains queued. The retry prepares again with current settings at `:932`, but its execution context at `:922` and warning helper at `:1095` still prefer the original saved wake. A null-creator alarm accepted at submission can therefore become a distinct profile fallback when acceptance is switched off; an uncertain write can be described using the earlier alarm time.

The regression is `RetimeNotificationTest.aQueuedSourcePolicyChangeWarnsAboutTheWakeActuallyPreparedForTheRetry` (`service/RetimeNotificationTest.kt:1163`). It queues an accepted null-creator alarm, proves a connection failure retained it, disables unknown-source acceptance, then permits a real upload and fails its programme start. It checks UNKNOWN confidence, unchanged applied programme and identity, the actual pending intended fallback, one terminal notice, and exact agreement between that intended wake, notification, runtime reason and terminal history. The fixture uses a null creator because `acceptUnknownAlarmSource` applies to absent creator metadata, not an arbitrary unlisted package.

The eventual fix must retain exact request ownership and the same context through timeout and cleanup. It must not rewrite unrelated or newer observations, credit a cached status, or alter radio-on/unreachable retry policy. No fix is included before an observed failing assertion.

### B. Saving an idle tonight-only wake incorrectly requires the radio

`AppViewModel.setTonightWake` (`ui/AppViewModel.kt:439`) deliberately treats an override with no captured active night as local work. `NightActions.setWakeOverride` (`NightActions.kt:1465`) still passes `MIN_BUDGET_MS` unconditionally, so the new Bluetooth admission check runs before the local settings branch at `:1471`. With a real target selected and the adapter off, this save returns FAILED instead of saving a local setting.

The regression is `BluetoothOffTest.anIdleTonightWakeOverrideIsSavedLocallyWithBluetoothOff` (`service/BluetoothOffTest.kt:72`). It asserts LOCAL/SETTINGS, the exact saved wake, no active night, no retry or alarm inbox, no service delivery and no retained radio receiver. The captured identity check must continue to reject the idle request if a night appears before admission; this is not permission to bypass radio checks for a tracked-night rewrite.

## Requested hypotheses and boundary review

**A radio refusal does not leave a replayable override queue.** No production `pendingOverride` field or queue exists. Active-night override changes pass the radio/identity checks before their body and save the new setting only after `OutcomeKind.CONFIRMED` (`NightActions.kt:1492–1500`). The separate `wakeSourceChange` journal at `:1504` completes an equal-wake local provenance change; it does not contain a BedJet rewrite. The misleadingly similar `pendingOverrideWithoutAnAlarmStillExplainsThePendingChange` name belongs to an explanation test. Finding B concerns local save eligibility, not replay.

**Background admission has separate bounds, not a proved absolute 115-second total.** `BedJetService.start` creates an undelivered-admission expiry at `BedJetService.kt:229`; `onStartCommand` removes it and cancels that expiry at `:35–36`. Near-deadline delivery can keep the same receiver through a separately bounded owner wait, approximately 115 + 90 seconds under cooperative cancellation. UI `runOwned` retains its outer 115-second wait; service work remains bounded at 100 seconds and owner work at 90 seconds. The stated owner/service/UI ordering is preserved. This review does not claim an absolute 115-second submission-to-admission limit for fire-and-forget background starts, and did not change the contract or add a scheduler. Existing cancellation tests prove receiver cleanup for queued owners and undelivered handoffs, not that combined background limit.

**Retime radio admission is deferred only until genuine hardware work.** Unchanged/filtered observations resolve from metadata before `connected()` admits the radio guard. Actual changed wakes with a radio refusal clear the exact retry, retain the confirmed programme, and do not replay after Bluetooth returns. A radio-on unreachable unit retains the existing bounded retry policy. Manual commands retain immediate refusal and off/on invalidation. The gate already ran all 13 `RadioOffRetimeTest` cases successfully; these checks are host evidence, not physical-radio tests.

**The receipt, cancellation and R38 paths remain intact.** The command mutex, source receipt order, post-send confirmation barrier and confirmed-ending repair are unchanged in their essential ordering. CHECK_BEDJET reconnects for a fresh target report and sends no programme; it does not promote an old uncertain write. Resume generation, expiry, target and captured-night checks remain present. Normal nights keep their one-hour minimum and use the new eighteen-hour maximum; the marked five-minute equipment test has a separate fixed compiler and cannot become a normal short night through an alarm change.

## Legacy fixture corrections

All existing behavior assertions remain. These changes establish the preconditions each test intended to exercise and account for the intentionally richer P7 warning.

| File and test | Observed restored-gate failure | Correction |
| --- | --- | --- |
| `night/CommandOriginsTest.kt:197`, `retimeCommandsDefaultToAlarmAtCaptureAndLegacyServiceDelivery` | Timed out driving the real entry point. An empty ended-night RETRY now correctly returns quietly before emitting an owner result. | Start a confirmed night, mark it UNKNOWN, then deliver the legacy RETRY and retain its exact REFUSED/ALARM assertions. |
| `night/CommandOriginsTest.kt:294`, `widgetEntryCarriesItsOriginAcrossTheServiceHandoff` | Timed out waiting for service delivery. The positive real-target fixture inherited an off adapter. | Explicitly enable the adapter; retain service handoff, refusal and WIDGET-origin assertions. |
| `night/RuntimeDeliveryTest.kt:282`, `connectPermissionRevokedAfterOrdinaryDeliveryStopsWithoutAnFgsObligation` | Initial `assertTrue(BedJetService.start(...))` failed before the intended permission-revocation boundary. | Enable the adapter before delivery; retain revoked permission, no forbidden promotion and ordinary-delivery assertions. |
| `service/RuntimeServiceTest.kt:139,156,171`, `cancelledUiDeliveryCannotExecuteLater`, `deferredUiPinRejectsTargetChangedBeforeDeliveryInsideCoordinator`, `uiOperationWaitsForForegroundOwnershipAndServiceCancellationReachesUpload` | Timed out pumping the main looper because off-radio preflight prevented the intended service handoff. | Enable the adapter in only these positive-delivery fixtures; preserve cancellation, foreground ownership, target refusal, zero-write and UNKNOWN assertions. |
| `night/SlotBackupsUnreadableTest.kt:110`, `aRefusedRetimeRetryIsDroppedWithItsReasonAndTheUserIsTold` | Expected the bare refusal; actual runtime/notice text began `Requested wake 05:30 on Demo BedJet.` | Keep the exact returned refusal assertion. Build the contextual runtime/notification expectation from resources and the fixture alarm instant in the system zone. Preserve no-write, retry removal and notification-title assertions. |
| `night/TruthfulOutcomesTest.kt:133`, `aRetimeWhoseNewWakeUpBeganBeforeItsWriteIsRefusedOnceAndNotRetried` | Expected the bare refusal; actual runtime text began `Requested wake 06:45 on Demo BedJet.` | Format the fixture requested wake in the system zone and use `runtime_retime_request_context`. Keep refusal, unchanged confirmed identity, no-write, no-retry and history-reason assertions. |

No guessed `03:30`/`04:45` local times were substituted. Focused results must be counted only from requested fresh suites and the batch log, not all XML left by the earlier full run.

## Complete-file review register

Paths in the first two tables are relative to `app/src/main/kotlin/io/github/willzy12h/nocturne/` and `core/src/main/kotlin/io/github/willzy12h/nocturne/core/` respectively. “No further issue” means source review found no additional concrete defect in that file; it is not a new test-pass or device claim.

### App production — 14 files

| File | Review result |
| --- | --- |
| `ble/BluetoothAdmission.kt` | Temporary receiver captures an off/on interruption, closes on admission/cancellation and rechecks actual adapter state. No further issue. |
| `ble/BluetoothAvailability.kt` | Resource-backed off/interrupted reasons, demo bypass and permission boundary remain distinct. No further issue. |
| `data/Repository.kt` | Short-test records are excluded from morning check-in/suggestions. Durable publications and original-slot protection remain ordered. No further issue. |
| `night/AlarmExplanation.kt` | Candidate, fallback and applied wake remain separate; manual/pending/unknown/failed states take precedence. Short-test text remains explicit. No further issue. |
| `night/AlarmSource.kt` | Uses Android's single next alarm, nullable creator and the normal 1–18-hour/source policy. No search of later alarms is implied. No further issue. |
| `night/BedJetTargetName.kt` | Target labels use saved names or a resource fallback; old captured targets are not relabelled as the current unit. No further issue. |
| `night/BoostDescription.kt` | Ten-minute Turbo prefix and remaining Heat description match the configured compiled Boost chain, including the 10-minute case. No further issue. |
| `night/LinkSessions.kt` | Status demand is event-driven. A tracked programme does not create an indefinite holder; thirty-second grace and active owner protection remain. No further issue. |
| `night/NightActions.kt` | Findings A/B above. Otherwise reviewed owner admission, all command cases, preparation/write/receipt settlement, local inbox drain, expiry, cancellation repair, restore, override and resume paths. |
| `night/PlanMessages.kt` | Typed duration problems are converted to resources for app-facing refusals/exceptions; generic errors retain their meaningful message. No further issue. |
| `night/RuntimeState.kt` | New request wake and short-test marker have backwards-compatible defaults; feedback remains request/origin scoped. Finding A is in how the saved wake is used. |
| `night/ShortTestPreview.kt` | Captures target, slot and preview time. Owner performs freshness and active-night validation. No further issue. |
| `notify/Notifications.kt` | Terminal re-time warning offers captured-target read-only Check. Stop/Resume confirmation routes remain guarded; Bluetooth notices deduplicate for background origins; expiry ticking stops when no future deadline applies. No further issue. |
| `service/BedJetService.kt` | Ordinary service delivery avoids a premature FGS obligation; real work promotes before ownership. Retime metadata can settle with radio off. Cancellation transfers/cleans guard ownership. Combined-lifetime qualification above remains explicit. |

### Core production — 11 files

| File | Review result |
| --- | --- |
| `night/ActiveNight.kt` | `isTestRun=false` default preserves old data; identity and pending-intention models remain distinct. No further issue. |
| `night/NightChanges.kt` | Test marker survives change/end paths; test stop does not create an ordinary-night resume offer. No further issue. |
| `night/NightRunner.kt` | Separate short-test preparation, fresh timing guards and uncertainty publication remain intact. Retime re-resolves current settings, exposing app warning Finding A. |
| `plan/NightDurationProblem.kt` | Typed window/maximum causes contain no Android presentation dependency. No further issue. |
| `plan/NightPreview.kt` | Preview propagates typed duration problems while preserving wake provenance and fallback. No further issue. |
| `plan/PlanCompiler.kt` | Ordinary 1–18-hour window, phase allocation and final execution checks remain separate from five-minute test compilation. No further issue. |
| `plan/ShortBedJetTest.kt` | Fixed one-minute start, two-minute middle and two-minute wake, no phone alarm or after-wake extension. No further issue. |
| `plan/WakeResolver.kt` | Allowed source/window selection, fallback and tonight override precedence remain consistent. No further issue. |
| `protocol/Model.kt` | Dry limit is 20–31°C (encoded 40–62); other mode ranges and device packet model remain intact. No further issue. |
| `store/History.kt` | Test marker persists for diagnostics with default false; regular record/revision fields remain intact. No further issue. |
| `store/Settings.kt` | Unknown-source handling concerns null metadata; source filtering and override lifecycle remain explicit. No replayable pending override field exists. |

### App tests — 16 files

Paths are relative to `app/src/test/kotlin/io/github/willzy12h/nocturne/`.

| File | Review result |
| --- | --- |
| `night/AlarmWindowExplanationTest.kt` | Too-soon/maximum resource copy and immediate UI recheck while another owner waits use real graph state. No further issue. |
| `night/BoostCommandMappingTest.kt` | All five durations assert actual owner-uploaded segments and the same visible/confirmation description. No further issue. |
| `night/CommandOriginsTest.kt` | Corrections above; attribution assertions remain through actual entry points. |
| `night/NightDurationResourcesTest.kt` | Resource mappings and pre-write refusal checks remain exact. Root has added a separate reminder-preview regression for raw diagnostic text; that work belongs to root. |
| `night/RuntimeDeliveryTest.kt` | Positive radio fixture corrected; scheduling, notice, permission and stale-action assertions retained. |
| `night/ShortBedJetTestRuntimeTest.kt` | Preview is local, confirmed test writes/backups are real, marker survives reload, history/Health/CSV exclusion and override preservation asserted. No further issue. |
| `night/SlotBackupsUnreadableTest.kt` | Exact hardware refusal retained with resource-backed contextual notice expectation. |
| `night/TrackedNightForegroundConnectionTest.kt` | Measures 29,999/30,000ms grace and unchanged active plan plus wake/end alarms. No further issue. |
| `night/TruthfulOutcomesTest.kt` | Refusal/UNKNOWN/CONFIRMED boundaries, no-write guarantees and durable history remain asserted; contextual runtime expectation corrected. |
| `service/BluetoothOffTest.kt` | Immediate and off/on refusal, finite listener cleanup and local Stop tracking covered; new idle-override regression awaits RED. |
| `service/OriginFeedbackTest.kt` | Positive fixtures explicitly enable radio; service exception/timeout identity and absence of duplicate generic notices remain asserted. No further issue. |
| `service/RadioOffRetimeTest.kt` | Thirteen real-target/off-radio cases count every connect/read/write; metadata no-ops, actual refusal and radio-on retry policy are distinguished. No further issue. |
| `service/RecoveryNotificationActionsTest.kt` | Actual cancellation/acceptance flows retain captured-night and physical-mode assertions. No further issue. |
| `service/RetimeNotificationTest.kt` | Complete file reviewed, including partial writes, timeout, persistence failure, newer observation and terminal-notice uniqueness. New policy-toggle case exposes the actual attempted intention. |
| `service/RuntimeServiceTest.kt` | Only required positive-radio fixtures changed; full cancellation and foreground ownership assertions retained. |
| `service/ServiceEntrySafetyTest.kt` | Null/missing handoff, revoked permission, promotion failure and ordinary service delivery remain tested without replacing the owner. No further issue. |

### Core tests — 6 files

Paths are relative to `core/src/test/kotlin/io/github/willzy12h/nocturne/core/`.

| File | Review result |
| --- | --- |
| `night/ShortBedJetTestTest.kt` | Fixed test duration, marking, original backup/write path and fixed-plan refusal assertions match production behavior. |
| `plan/AlarmWindowUxTest.kt` | Boundary and accepted-source policy distinguish normal one-hour minimum from separate short test. |
| `plan/NightDurationProblemTest.kt` | Compiler/preview expose the correct typed cause for outside-window versus too-long plans. |
| `plan/PlanningHardeningTest.kt` | Maximum-duration update retains rejecting invalid/night-too-long cases and existing planning guards. |
| `plan/WakePolicyHardeningTest.kt` | Unknown-source, explicit package and normal-window fallback assertions remain independent. |
| `protocol/DryModeRangeTest.kt` | New Dry limits are asserted directly, including clamping; no unrelated mode range is loosened. |

## Verification limits

This is a source and host-regression review. It establishes no BedJet hardware receipt, Samsung launcher behavior, physical overnight timing, TalkBack sequence or force-stop/reboot delivery. The integration owner will append the actual RED/GREEN and final full-gate results. Production fixes for Findings A/B must follow their observed assertion failures and then run the relevant complete regression suites; no speculative change is included here.

## Post-review focused evidence

`ux-review-pass1-behaviour-red` completed separately after the lint-first attempt. Fresh XML shows all five corrected legacy classes GREEN: `CommandOriginsTest` 24/24, `RuntimeDeliveryTest` 20/20, `RuntimeServiceTest` 10/10, `SlotBackupsUnreadableTest` 3/3 and `TruthfulOutcomesTest` 17/17 — **74 passed, 0 failures/errors/skips**. This does not describe the whole batch as green; root recorded 210 tests with 21 failures across its combined scopes.

Finding B produced the intended RED in `BluetoothOffTest.anIdleTonightWakeOverrideIsSavedLocallyWithBluetoothOff`: **“Choosing tonight's wake without a tracked night is a local settings save expected:<LOCAL> but was:<FAILED>”**, at line 76. The class had 14 tests, 1 failure and no errors/skips. After observing that assertion, the only production fix changes `setWakeOverride` to require the hardware admission budget only when the captured `expected` night is non-null. Its existing exact-identity check still precedes the local settings save. GREEN is pending.

Finding A did **not** produce its intended RED in that batch: the one selected test failed during initial setup at line 1169, **“expected:<FAILED> but was:<LOCAL>”**. Inspection of the cached Robolectric 4.14.1 `ShadowPendingIntent.getCreatorPackage` bytecode proves that `setCreatorPackage(null)` falls back to the application package. The fixture now supplies `AlarmClockInfo(..., showIntent = null)`, as the existing null-source tests do, and explicitly asserts absent creator metadata plus initial source acceptance. No warning-context production change has been made; this corrected regression must run again before its fix.

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


## Merged runtime/core verification update

The corrected Finding A fixture produced its intended RED in `ux-review-pass1-green-sequence-red`:

> A warning must name the exact wake whose write became uncertain, not the superseded queued alarm expected:<Requested wake 0[7:0]0 on Demo BedJet. Th...> but was:<Requested wake 0[8:3]0 on Demo BedJet. Th...>

The actual attempted fallback wake was 07:00; the queued, subsequently disallowed unknown-source alarm was 08:30. Only after that failure, the runtime was changed to carry the actual prepared intention through `RetimeExecution`, the exact matching durable `PendingRetime`, and result/error/timeout warning branches (`NightActions.kt:998,1011,1041,1073,1109`). The originally queued wake remains the context when preparation has not happened.

`ux-temporary-merge-behaviour-red` completed 295 app tests: 274 passed and 21 failed in the combined UI/widget/runtime scope. The following selected, fresh archived XML suites are all GREEN:

| Selected suite | Passed | Failures/errors/skips |
| --- | ---: | --- |
| `ManualCancellationSettlementTest` | 13 | 0/0/0 |
| `RestoreOriginalCaptureTest` | 7 | 0/0/0 |
| `UnchangedAlarmObservationTest` | 18 | 0/0/0 |
| `RetimeNotificationTest` | 64 | 0/0/0 |
| `RuntimeFixRoundTest` | 35 | 0/0/0 |
| `BluetoothOffTest` | 14 | 0/0/0 |
| `TemporaryActionOwnerTest` | 9 | 0/0/0 |
| `TemporaryActionBoundariesTest` | 24 | 0/0/0 |
| `TemporaryActionServiceTest` | 5 | 0/0/0 |
| `TemporaryNativeActionTest` | 6 | 0/0/0 |
| **Selected total** | **195** | **0/0/0** |

Finding A's exact policy-toggle regression passed (1.607 s), within the complete 64-test P7 class. Finding B's idle override regression is GREEN within BluetoothOffTest 14/14; its conditional hardware budget retains the captured-identity check. The five existing legacy fixture corrections were already separately recorded GREEN above and are not added again to this selected total.

The earlier merged core run `ux-temporary-merge-red` passed 240/240. `TemporaryNightTest` passed 34/34, including both new `alarmRetimeCannotRestartExpiredTemporaryTurboAfterAShortUploadDelay` and `alarmRetimeCannotRestartExpiredTemporaryTurboAfterAShortClockSyncDelay` cases; `PreparedPublicationCancellationTest` passed 5/5. The incoming first-temporary-step expiry guard already prevents that restart, so no speculative timing production change was made.

The merged positive shortcut test now invokes actual `BedJetService.onStartCommand` and passed with CONFIRMED, SHORTCUT origin, the original wake and captured temporary preset. It is stronger than the immutable service class's positive intent-only assertions. All of this remains host/simulator evidence, with no hardware or Samsung acceptance claim.

The current P4 widget integration separately recorded its intended eight temporary-action failures (`WidgetConfirmationTest`: 17 tests, 8 failures; the nine existing guard tests passed). UI/widget owners are correcting their observed cases. The full integration gate is still pending. Six new legacy PlatformActionActivity Off/Stop confirmation/identity regressions have been added for the next RED batch; production for that entry-point finding is unchanged.


## Legacy retained widget stop guard — subsequent R38 RED

The `ux-temporary-ui-native-red` run reproduced both retained-entry defects in `LegacyPlatformStopConfirmationTest`: 6 tests, 5 failures, zero errors/skips. The tracked OFF and END_NIGHT cancellation/positive cases each failed the pre-approval connection assertion (`expected:<0> but was:<1>`). `legacyOffApprovalQueuedBehindANewerNightIsRefused` failed `expected:<STALE> but was:<CONFIRMED>`; the corresponding END_NIGHT queued-identity case passed.

Only after those failures, `PlatformActionActivity.kt:40` gained explicit tracked OFF/END_NIGHT confirmation using existing resources. Its approved command at `:86` now calls the existing strict `NightActions.remote` owner with the captured target and night plus a Standby receipt matcher. The owner and replacement policy were not changed, so generic emergency Off retains its existing behavior. Tests were not weakened or edited. Focused GREEN is pending; root also includes the existing untracked-Off positive control.


## Legacy R38 and responsive Temporary follow-up GREEN

Fresh archived `ux-temporary-native-fixture-red-2/app` XML passed `LegacyPlatformStopConfirmationTest` **6/6**, `PlatformActivityTest` **3/3** and `TemporaryNativeActionTest` **6/6**, zero failures/errors/skips. All five legacy failures above are resolved by the Activity-only strict confirmation route; the untracked-Off and existing Temporary native routes remain green. This is a selected **15/15** result, not a claim that the whole integration gate passed.

The same archive passed `WidgetConfirmationTest` **17/17** after the eight new Temporary cases initially failed. The responsive widget now transports the rendered preset, opens the shared Temporary/End dialogue and retains captured identity through WIDGET service ownership. The phone cross-check records current source/test lines and keeps physical-device qualifications.

The second source/test pass by a separate task agent, within this self-review, identified a separate reviewed-Off queue seam in ShortcutActivity and Tonight: both capture identity and approval but eventually call the generic OFF path, which intentionally exempts emergency Off from identity checks. Six real-route/compatibility regressions are in root's `ux-reviewed-off-and-runtime-red` batch; no production change precedes that RED. This new finding is tracked in the pass-2 report and does not reopen the verified legacy Activity repair.

## Reviewed shortcut/Tonight Off — subsequent intended RED and owner correction

Fresh `ux-reviewed-off-and-runtime-red/app` XML records `ShortcutStopConfirmationTest` **6 tests, 2 failures, zero errors/skips**. `shortcutOffApprovalQueuedBehindANewerNightIsRefused` failed **“An old shortcut OFF approval must not stop a newer night expected:<STALE> but was:<CONFIRMED>”**; `confirmedTonightOffQueuedBehindANewerNightIsRefused` failed **“An approved Tonight Off must stay bound to the reviewed night expected:<STALE> but was:<CONFIRMED>”**. The four cancel, positive service/receipt, queued END_NIGHT and unconfirmed emergency-Off controls passed. The combined root batch contained eight tests and four failures because it also reproduced two separately owned Remote runtime-limit cases; those are not included in this six-test runtime result.

Only after the intended failures, `NightActions.kt:105–109` narrowed the OFF identity exemption to requests without visible replacement approval. Reviewed strict requests now use the existing captured-identity comparison at owner admission. CHECK_BEDJET and unconfirmed emergency/notification Off keep their previous exemptions. No test assertion or entry-point payload was weakened. Source is frozen for root's focused GREEN; the complete pass-2 scope, source inventory and remaining device qualifications are recorded in `ux-self-review-pass2-runtime.md`.

## Reviewed shortcut/Tonight Off — focused GREEN

Fresh `ux-temporary-reviewed-green-native-red/app` XML independently confirms **ShortcutStopConfirmationTest 6/6**, including both formerly failing STALE assertions; **LegacyPlatformStopConfirmationTest 6/6**; and the selected **RuntimeOwnershipTest.oldNotificationOffAndFreshOffBothResolveTheSelectedTarget 1/1**. This is **13/13 selected Off/Stop regressions**, zero failures/errors/skips, with the existing emergency-Off behavior retained. Only that one RuntimeOwnership method was requested in this batch.

The same batch passed **WidgetConfirmationTest 17/17**, **TemporaryNativeActionTest 6/6** and the separately owned **RemoteRuntimeLimitsTest 2/2**. These six selected reports total **38/38**. The complete batch was **76 tests: 69 passed, 7 failed**. The two RecoveryNotificationActionsTest timeouts are under root's fixture-pacing correction; no production Resume behavior has changed. Native/UI follow-ups and the final exact full gate remain pending. This entry updates the pending status above without erasing the original RED chronology.

## Native and notification follow-up — complete focused GREEN

The next run, **`ux-native-and-recovery-green`**, completed in **257.74 seconds**. Its fresh archived XML independently totals **15 reports, 76 tests, 0 failures/errors/skips**. `RecoveryNotificationActionsTest` now passes **2/2**, with the main Looper advanced by measured wall-clock elapsed time instead of ten simulated milliseconds per busy spin. Its original five-second wall-clock deadline and all positive/cancel/receipt assertions remain. No production Resume change was made.

Reviewed-Off **6/6**, legacy Off/Stop **6/6**, the selected old-notification emergency-Off compatibility case **1/1**, widget confirmation **17/17**, native Temporary **6/6** and Remote runtime limits **2/2** remain GREEN. This supersedes the pending notification recheck above. The exact full integrated gate and two separately drafted presentation regressions remain pending.

## Local Stop tracking approval — intended RED and focused GREEN

The separate Settings/UI review found a pre-existing captured-identity gap in the actual recovery dialog. `NightRecoveryControls` retained only a Boolean; approval called `model.run(STOP_TRACKING)`, recapturing whichever night was current. A command waiting behind the owner could likewise adopt a newer night. The generic local owner action was not itself changed.

Fresh `ux-last-capture-boundaries-red/app` XML records **StopTrackingCaptureTest: 4 tests, 2 intended assertion failures, zero errors/skips**. `approvalAfterAnotherNightReplacesTheDisplayedNightIsStaleAndPreservesIt` (`:137`) failed **“Approval belongs to the night shown when the dialog opened expected:<STALE> but was:<LOCAL>”**. `approvalQueuedBehindTheOwnerCannotStopAReplacementNight` (`:153`) failed **“Owner admission must recheck the identity captured by the dialog expected:<STALE> but was:<LOCAL>”**. The unchanged-night Bluetooth-OFF positive and cancellation controls already passed. The combined parent batch was 29 tests / 6 failures in 202.86 seconds; its two unrelated idle-UI fixture failures are not product RED for this finding.

Only after those intended failures, `ui/NightRecoveryControls.kt:46,55–56,79–84` stores a complete request on opening, pins the displayed night identity/device and submits the same request on approval. `ui/AppViewModel.kt:376–379` keeps `STOP_TRACKING` local when using the existing `runCaptured` path. No NightActions rule, schema, hardware command or test assertion changed.

Fresh `ux-idle-capture-red-local-stop-green/app` XML passes **all four unchanged StopTrackingCaptureTest cases, 0 failures/errors/skips** (6.854 seconds). Both stale approvals preserve the exact replacement night, History and runtime including its existing Resume offer; zero connect/disconnect/read/write/service assertions remain. The same-night positive still closes only local tracking with one STOPPED entry and leaves the unit's programme running; cancellation submits nothing. The complete parent batch was **9 tests / 7 passed / 2 other idle-UI failures**, 127.21 seconds, so this is focused GREEN rather than a passing full tree. The SDK 34 tests use GraphLifecycle and actual attached Compose controls with native graphics. Physical acceptance and the final integrated gate remain pending.

## Timed Snooze/Extend Turbo limit — intended RED and correction awaiting GREEN

Valid portable settings can retain a 15-minute Turbo preset for Snooze warm/cool or Extend warm/cool. These commands send a single manual run; they do not append Boost's Heat continuation. The prior owner attempted that duration rather than refusing it before work. The backup schema and saved values are intentionally unchanged.

The completed parent run **`ux-timed-policy-red-capture-green`** contains **38 tests / 30 passes / 8 failures / 0 errors/skips**, **200.66 seconds**. Its fresh `TimedPresetLimitTest` report alone is **5 tests / 4 intended failures / 0 errors/skips**, 14.757 seconds. All four `importedFifteenMinuteTurbo{SnoozeWarm,SnoozeCool,ExtendWarm,ExtendCool}IsRefusedBeforeTransportOrMutation` cases failed the exact assertion **“[COMMAND] must refuse Turbo above the unit's ten-minute limit expected:<REFUSED> but was:<ACCEPTED>”**, where COMMAND names the corresponding Snooze/Extend command. The positive `importedTenMinuteTurboStillConfirmsTheExistingTimedManualActionForEveryRole` passed for all four roles, including actual manual receipts, ten-minute runtime and the existing UNKNOWN manual-tracking/Keep tracking semantics. The other four failures are the separately owned Settings role-presentation cases; this was not an all-green batch.

After the intended failures, `night/CapturedActionPreset.kt:28–31` adds `timedPresetRefusal(context,preset)` using the existing `Limits.TURBO_MINUTES` and `runtime_turbo_duration_limit`. `NightActions.kt:111–114,153–156` captures and checks the selected timed preset inside the existing owner, before any connection or generation change. `:301–309` resolves the role once, and both existing `run` callers pass that immutable value across connection suspension into the timed action at `:345–348`. This keeps valid work to the original one connection and one generation advance. It adds no schema/default change, new owner, delayed start, Boost/Temporary policy or receipt exception. The five tests remain unchanged and retain exact ActiveNight/runtime/History/settings/originals/physical-slot preservation plus zero connect/disconnect/read/write assertions for refusal. Settings consumes the shared reason while keeping its separate Quick action eligibility explanation. Focused GREEN and the final full gate are pending.

## Final capture and timed-policy focused GREEN

Parent-run **`ux-final-capture-and-timed-policy-green`** completed in **204.16 seconds**, with **38/38 tests passed, zero failures/errors/skips** across nine selected reports. The archived `selected-suites.json` was checked directly. Root verified **355 non-documentation inputs unchanged** during the run. This is the complete focused batch, not the full app gate.

`TimedPresetLimitTest` passes **5/5** unchanged: all four formerly ACCEPTED over-cap actions now return the exact REFUSED cap reason before transport or state/History mutation, and the ten-minute actual-receipt control still passes for all four Snooze/Extend roles. `ImportedPresetRoleSettingsTest` passes **4/4**, preserving valid timed Turbo summaries and explaining only the Extend preset's distinct Quick restriction. `StopTrackingCaptureTest` remains **4/4**, with both stale-approval and offline/cancel controls. The same run passes `IdleQuickCaptureTest` **2/2**, `ImportedTemporaryDurationTest` **3/3**, `TemporaryNativeActionTest` **7/7**, `TemporaryNativeLayoutTest` **7/7**, `WidgetProviderTest` **4/4** and `RecoveryNotificationActionsTest` **2/2**. These counts are already included in 38 and must not be added again.

The final complete-file reread found no additional concrete defect in the reviewed runtime/core/service/restore/alarm scope. It rechecked the current NightActions, AppViewModel, captured-preset boundary and changed native shortcut/test files, then compared the previously complete core/service/dependency inventory by Git blob. Source hashes and the final post-gate closure remain in the runtime review inventory. The exact full core/app/lint/release gate and physical acceptance remain pending.

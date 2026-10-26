# UX self-review pass 2 — runtime, core, alarm and service

## Scope and result

This is the second source/test pass by a separate task agent, within this self-review, against Step A base `367968bddc390e5b2bb12876fc1662ffa07e47c3`. The reviewed integrated checkpoint is `c77fc1e384b7fd8ff4ee8f5b587c6b7336cbb5ff`, plus the subsequently authorised reviewed-Off owner correction. The 26 changed production files in the assigned BLE/admission, night, service and core subtrees were read in full. The table below records the reviewed working-file blobs, so later UI or integration changes do not silently inherit this review.

The pass found one further concrete R38 defect: reviewed Shortcut and Tonight Off requests lost their captured-night protection at generic owner admission. Its intended RED was observed before the narrow owner correction, and its complete six-case regression class is now GREEN. The earlier retained legacy widget activity defect is also GREEN. No additional concrete defect was identified in the complete runtime/core files; this statement is a source-review result, not a claim that the final integrated gate or physical-device acceptance has passed.

The parent owns all Gradle runs and final integration. No Gradle run, production change beyond the authorised Off condition, speculative timing rewrite, or feature expansion was made during this pass.

## Finding: a reviewed Off must remain bound to the reviewed night

`ui/ShortcutActivity.kt:32,58` captures `requireIdentity=true` and supplies `replacementConfirmed=true` after the positive button. `service/BedJetService.kt:218,84` preserves those fields through the real service route. Tonight follows `TonightScreen.kt:411` → `NightGuard.kt:87` → `AppViewModel.kt:356,366–368`, which also captures strict identity inside the synchronous positive-confirmation scope. The former `NightActions.execute` condition exempted every OFF request from identity checks, including these reviewed requests. A different night admitted before the queued Off was therefore stopped by approval of the older night.

The six new `platform/ShortcutStopConfirmationTest.kt` cases use SDK 34, JUnit 4, `GraphLifecycle`, an observed DemoBedJet and the actual owner queue. Shortcut cases invoke the real Activity and deliver the resulting Intent through `BedJetService.onStartCommand`. The Tonight case invokes the actual AppViewModel positive callback path; it does not claim a rendered Compose click. The stale cases hold the owner, queue approval, publish a newer night revision, release the owner and require STALE with no additional connect/read/write. Positive confirmation requires a matching receipt and the correct origin.

Fresh archived XML at `/tmp/nocturne-gpt11-evidence/ux-reviewed-off-and-runtime-red/app/TEST-io.github.willzy12h.nocturne.platform.ShortcutStopConfirmationTest.xml` records **6 tests, 2 failures, 0 errors, 0 skipped**:

| Test and source line | Observed RED result |
| --- | --- |
| `shortcutOffApprovalQueuedBehindANewerNightIsRefused`, `:144` | `An old shortcut OFF approval must not stop a newer night expected:<STALE> but was:<CONFIRMED>` |
| `confirmedTonightOffQueuedBehindANewerNightIsRefused`, `:185` | `An approved Tonight Off must stay bound to the reviewed night expected:<STALE> but was:<CONFIRMED>` |
| `shortcutOffCancelDoesNotDeliverAServiceOrTouchHardware`, `:110` | Passed: cancel performs no delivery or hardware work. |
| `shortcutApprovedOffConfirmsItsCapturedNightThroughTheService`, `:125` | Passed: actual positive service route, receipt, SHORTCUT origin and one stopped event. |
| `shortcutEndNightApprovalQueuedBehindANewerNightIsRefused`, `:148` | Passed: the existing END_NIGHT identity guard remains effective. |
| `anUnconfirmedEmergencyOffStillUsesTheSelectedTarget`, `:218` | Passed: the deliberately unconfirmed emergency route retains its selected-target semantics. |

The combined root batch had eight tests and four failures because it also reproduced two separately owned Remote runtime-limit defects; those two are not part of this six-test runtime result.

Only after RED, `NightActions.kt:105–109` narrowed OFF's exception to requests without `replacementConfirmed`. Reviewed strict requests now pass through the existing captured-identity comparison at owner admission. The `requireIdentity` condition, CHECK_BEDJET exemption, target validation and generic unconfirmed Off behavior remain unchanged. No test was weakened. The related compatibility assertions are `RuntimeOwnershipTest.oldNotificationOffAndFreshOffBothResolveTheSelectedTarget` (`:174`) and `aPersistedWidgetOffAfterProcessDeathUsesTargetNotOldNightIdentity` (`:189`).

### Focused GREEN after the correction

Fresh archived `ux-temporary-reviewed-green-native-red/app` XML was independently parsed. It records **ShortcutStopConfirmationTest 6/6**, **LegacyPlatformStopConfirmationTest 6/6** and the specifically selected **RuntimeOwnershipTest.oldNotificationOffAndFreshOffBothResolveTheSelectedTarget 1/1**, zero failures/errors/skips: **13/13 selected Off/Stop cases**. Both previously failing reviewed-Off queue cases now pass. The one selected RuntimeOwnership method is not a claim that its whole class was rerun.

The same archive records **WidgetConfirmationTest 17/17**, **TemporaryNativeActionTest 6/6** and the root-owned **RemoteRuntimeLimitsTest 2/2**, also with zero failures/errors/skips. These six selected reports total **38/38**. The whole batch was **76 tests, 69 passed, 7 failed**. Two of those failures are `RecoveryNotificationActionsTest` owner-completion timeouts; root is correcting the fixture's fixed Looper advancement while retaining the five-second wall-clock deadline and behavioral assertions. No production Resume change has been made. Those results and the remaining native/UI failures prevent a full-tree GREEN claim. The exact full gate remains pending.

The subsequent **`ux-native-and-recovery-green`** run completed in **257.74 seconds**. Its fresh archive contains **15 reports, 76 tests, 0 failures/errors/skips**, including **RecoveryNotificationActionsTest 2/2**, **ShortcutStopConfirmationTest 6/6**, **LegacyPlatformStopConfirmationTest 6/6**, the selected old-notification Off compatibility case **1/1**, **WidgetConfirmationTest 17/17**, **TemporaryNativeActionTest 6/6** and **RemoteRuntimeLimitsTest 2/2**. The notification fixture now advances the main Looper by measured `System.nanoTime` elapsed time, retaining its original five-second deadline and all behavior assertions. Production Resume behavior was unchanged. This clears the focused failures above; the exact full gate and the separately drafted presentation regressions remain pending.

## Closed finding: retained legacy widget Activity

The still-declared `PlatformActionActivity` remains reachable from retained widget PendingIntents. Its initial policy exempted Off from replacement confirmation, so tracked OFF and END_NIGHT could connect immediately; queued legacy OFF also bypassed exact identity. `ux-temporary-ui-native-red` produced **LegacyPlatformStopConfirmationTest: 6 tests, 5 failures**. Four tests failed the pre-approval connection assertion (`expected:<0> but was:<1>`); `legacyOffApprovalQueuedBehindANewerNightIsRefused` failed `expected:<STALE> but was:<CONFIRMED>`. The queued END_NIGHT control passed.

The Activity-only correction uses existing resource-backed confirmation at `PlatformActionActivity.kt:40` and the existing strict `NightActions.remote` path at `:86`, with captured target/night and a Standby receipt matcher. Fresh archived `ux-temporary-native-fixture-red-2/app` XML passed **LegacyPlatformStopConfirmationTest 6/6**, **PlatformActivityTest 3/3** and **TemporaryNativeActionTest 6/6**, all with zero failures/errors/skips. The selected total is **15/15**; it is not a full integration-gate result. The same archive passed the separately owned responsive `WidgetConfirmationTest` **17/17**, after its eight intended Temporary failures. Current widget implementation and device qualifications are recorded in `ux-phone-cross-check.md`.

## Boundary review and evidence

| Boundary | Current implementation and reviewed assertion |
| --- | --- |
| F1: cancelled manual receipt wait or confirmed ending publication | `NightActions.kt:570,580,598,630–637` captures the exact `PendingEnd`, then repairs only that matching ending or its own pending request within an eight-second non-cancellable local budget. A confirmed publication is not downgraded and another request is not overwritten. `ManualCancellationSettlementTest` was reread in full; `cancellingAsConfirmedOffEndingPublishesCompletesItBeforeReconciliation` (`:290`) requires the durable STOPPED journal, one Off packet, cleared active/end markers and one stopped event before any later reconcile. The complete class passed **13/13** in the merged runtime batch. |
| F2: captured original versus an import during preparation | `NightActions.kt:1467–1495` captures the exact `SlotBackup`, including provenance, before owner admission. `writeSlotLocked` compares it again after backup preparation (`:1508–1515`). `RestoreOriginalCaptureTest.anImportQueuedDuringTheSlotJournalSaveIsRefusedAfterBackupPreparation` (`:152`) uses a real repository import, asserts replacement actually occurred, and checks zero writes plus unchanged physical slot contents. Both unchanged and newly reviewed imported positive controls remain. The complete class passed **7/7**. |
| F3: unchanged alarm consumed after wake | `NightActions.kt:833,868–870,936–937` distinguishes an unchanged observation from a real pending rewrite. `UnchangedAlarmObservationTest.anUnchangedObservationQueuedBeforeWakeRemainsQuietWhenConsumedAfterWake` (`:204`), its manual/confirmed variants (`:208,212`), and actual-pending-expiry controls retain the separate outcomes. The complete class passed **18/18**. |
| Actual prepared wake in P7 terminal warnings | `RetimeNotificationTest.aQueuedSourcePolicyChangeWarnsAboutTheWakeActuallyPreparedForTheRetry` (`:1163`) first produced the intended 07:00-versus-08:30 RED after an actual uncertain retry upload. `NightActions.kt:1000,1015–1018,1043,1075,1111` now carries the actual prepared wake through execution context, the matching durable pending request and failure/timeout context. The class passed **64/64**, including that exact regression. Unprepared refusals retain the original request context. |
| Radio-off versus radio-on unreachable link | `BluetoothAdmission` observes interruptions while a command waits; normal manual commands check immediately. RETIME/RETRY preserve the guard but check/admit only at `NightActions.connected` (`:241–246`), so unchanged/filtered local metadata does not require Bluetooth. The retry catch distinguishes a `BluetoothCommandException` from a radio-on connection failure, clearing only the refused pending attempt while retaining legitimate unreachable-link retry policy. `RadioOffRetimeTest`, `BluetoothOffTest` and `RuntimeDeliveryTest` were read in full; their source assertions include no reads/writes for local observations, no off/on resurrection, no silent replay and receiver cleanup. |
| Idle wake override and queued settings | `AppViewModel.setTonightWake` captures identity and selects a local route when there is no tracked night. The matching owner uses no hardware admission budget for that local save and still refuses identity changes. The observed idle-override RED is now GREEN within **BluetoothOffTest 14/14**. There is no separate pending-override replay queue; failed hardware attempts do not save a future hidden command. |
| Temporary composition and Turbo cap | `TemporaryProgramme.kt:16,26,41,48` composes one complete on-device programme from the captured original timeline. Turbo expands through the fixed Turbo-then-Heat policy, with at most ten continuous Turbo minutes. The existing `NightRunner.kt:299` execution-time guard is checked before upload, after upload and around clock-sync/start; the two controlled short-delay tests cross the Turbo boundary while the longer temporary interval still exists. Both were GREEN without any speculative protocol change. `TemporaryNightTest` passed **34/34**. Minute-based programme planning is not a second-exact physical return promise. |
| Phone away and physical Off | `TemporaryActionBoundariesTest.theStoredProgrammeReturnsToTheOriginalTimelineWithoutAPhoneCommand` (`:307`) advances the complete simulated programme through return, wake and after-wake without another phone command. The physical-Off simulations before preparation, during upload and after confirmation (`:256,274,336`) require no blind START. The complete boundary class passed **24/24**. There is no later phone return scheduler; physical BedJet acceptance remains untested. |
| Temporary ownership, receipts, cancellation and new alarms | `NightRunner` keeps the exact pending request and confirmed revision; partial writes settle UNKNOWN. Temporary owner tests require a fresh receipt, unchanged original wake and one composite start. Boundary tests at `:163,190,222,482,501,549,808` cover cancellation before/after writes, confirmed publication, old/new alarm observations and a cached matching first step. The merged owner/boundary/service classes passed **9/9**, **24/24**, **5/5**. Existing `isTestRun` and Temporary data coexist in active state/history. |
| Service, origin and lifetime | Complete `BedJetService.kt`, `RuntimeState.kt`, `BluetoothAdmission.kt` and `AppViewModel.kt` were read. Captured target/origin/preset survive delivery; malformed Temporary payloads are rejected before promotion; cancellation closes/transfers admission ownership; `runOwned(hardware=false)` keeps local WIDGET Stop tracking under the owner. UI haptics remain APP-scoped. The service/owner/UI budgets remain **100/90/115 seconds**, respectively. The background combined-lifetime qualification below is retained. |

The ten selected fresh XML classes in `ux-temporary-merge-behaviour-red` passed **195/195**, zero failures/errors/skips: ManualCancellationSettlement 13, RestoreOriginalCapture 7, UnchangedAlarmObservation 18, RetimeNotification 64, RuntimeFixRound 35, BluetoothOff 14, TemporaryActionOwner 9, TemporaryActionBoundaries 24, TemporaryActionService 5 and TemporaryNativeAction 6. The whole app batch was **295 tests, 274 passed, 21 failed**; its other failures belonged to the then-pending UI/widget integration. The earlier merged core run `ux-temporary-merge-red` passed **240/240**, including TemporaryNight 34 and PreparedPublicationCancellation 5. Counts above describe selected archived reports, not every XML file left in a reused Gradle directory.

## Complete changed-production inventory

Paths beginning `app/` below are relative to `app/src/main/kotlin/io/github/willzy12h/nocturne/`; `core/` paths are relative to `core/src/main/kotlin/io/github/willzy12h/nocturne/core/`. Every listed file was read completely, with its diff from the Step A base and relevant callers/tests. Blob prefixes identify the exact reviewed contents.

| File | Lines; blob prefix | Review focus / result |
| --- | --- | --- |
| `app/ble/BluetoothAdmission.kt` | 54; `e8007daed6be` | Registration, interruption memory, check/admit/close and cancellation ownership; no additional defect. |
| `app/ble/BluetoothAvailability.kt` | 35; `8e595944a80b` | Demo/permission/off distinctions and explicit exception reasons; no additional defect. |
| `app/night/AlarmExplanation.kt` | 163; `88aee2b25534` | Applied versus requested wake, short-test marker and refusal resource mapping; no additional defect. |
| `app/night/AlarmSource.kt` | 57; `e88076b365eb` | Accepted/null/filtered creator metadata; actual policy-toggle fixture uses an intentionally absent show intent. |
| `app/night/BedJetTargetName.kt` | 13; `a4ca29a441c0` | Warning target context; no additional defect. |
| `app/night/BoostDescription.kt` | 21; `57259535ac98` | Ten-minute Turbo-only versus longer Turbo+Heat label/description; root owns UI label acceptance. |
| `app/night/CapturedActionPreset.kt` | 38; `7accb29ee38b` | Preset validation and serialization boundary, standalone Turbo cap; no additional defect. |
| `app/night/LinkSessions.kt` | 210; `ee3626d114b5` | Screen/operation leases, idle grace, reconnect and scan cleanup; no additional defect. |
| `app/night/NightActions.kt` | 1807; `0212bec27681` | Full owner, receipt, ending, restore, alarm, Temporary and local recovery paths; reviewed-Off finding/correction above. |
| `app/night/PlanMessages.kt` | 31; `86ca7b8fc8ec` | Typed duration and Temporary reasons retain resource-backed app text; no additional defect. |
| `app/night/RuntimeState.kt` | 124; `f7eddbfc05ad` | Durable intent versus transient request/feedback/context, defaults and exact identity; no additional defect. |
| `app/night/ShortTestPreview.kt` | 6; `46ac16fb3e22` | Captured short-test preview; no additional defect. |
| `app/service/BedJetService.kt` | 304; `5a6ce6740c24` | Complete handoff, promotion, intent decode, radio guard and cancellation paths; no additional defect. |
| `core/night/ActiveNight.kt` | 72; `e4fc07404c2f` | Snapshot identity, `isTestRun` and Temporary coexistence; no additional defect. |
| `core/night/NightChanges.kt` | 94; `18ad00dd7b05` | Prepared change/refusal types, duration and Temporary problems; no additional defect. |
| `core/night/NightRunner.kt` | 324; `6eaaa7d81077` | Full prepare/apply/publication/clock boundary logic; elapsed Turbo hypothesis was already guarded and tested GREEN. |
| `core/night/TemporaryProgramme.kt` | 94; `923eff7e7ee7` | Absolute base timeline, prefix clipping, wake gap and slot-step limits; no additional defect. |
| `core/plan/NightDurationProblem.kt` | 6; `7d9a04fca78f` | Typed normal-night duration failures; no additional defect. |
| `core/plan/NightPreview.kt` | 54; `568d24c38b85` | Preview follows actual selected wake/source and duration failure; no additional defect. |
| `core/plan/PlanCompiler.kt` | 178; `f87b474e5da9` | Normal minimum/maximum preserved, explicit short-test compilation, Turbo expansion; no additional defect. |
| `core/plan/Profile.kt` | 88; `c2ece71223ba` | Phase enum and snapshot plan data; Temporary phase remains explicit. |
| `core/plan/ShortBedJetTest.kt` | 25; `05193eb2e14d` | Bounded explicit test programme with test marker; no normal alarm-window relaxation. |
| `core/plan/WakeResolver.kt` | 37; `f8fe637bb884` | Next-bedtime/wake selection remains separate from start-now eligibility; no additional defect. |
| `core/protocol/Model.kt` | 154; `b5836ac10dec` | Mode limits and Dry range; no additional runtime defect. |
| `core/store/History.kt` | 61; `e679ecb92857` | Test-night marker, temporary event and existing record/event identity; no additional defect. |
| `core/store/Settings.kt` | 108; `7f6ac6f1cf38` | Layout-preference normalization, captured settings and wake override lifecycle; no additional defect. |

The following complete production dependencies/entry files were also reviewed: `night/Receivers.kt`, `night/NightAlarms.kt`, `notify/Notifications.kt`, `platform/PlatformActionActivity.kt`, `ui/ShortcutActivity.kt`, `ui/AppViewModel.kt`, `ui/NightGuard.kt`, `widget/WidgetConfirmationActivity.kt` and the Graph lifecycle in `App.kt`. Responsive widget transport/owner code and the Tonight Off caller were checked at their relevant current branches. UI/widget geometry and the entire UI diff remain with their assigned reviewers; this report does not substitute for those passes.

## Complete changed-test inventory

All 29 changed test files in the assigned night/service/core scope were read completely. They contain **329 declared `@Test` methods** (252 app, 77 core); that is a source inventory, not a new execution count.

| File/class | Declared cases | Main boundary inspected |
| --- | ---: | --- |
| `night/AlarmWindowExplanationTest` | 3 | Short/long normal-night explanations and explicit short test. |
| `night/BoostCommandMappingTest` | 1 | Actual ten-minute Boost message. |
| `night/CommandOriginsTest` | 24 | Capture, UI, service, notification, alarm and queued-origin context. |
| `night/NightDurationResourcesTest` | 7 | Typed duration text through actual UI/native entry points. |
| `night/RuntimeDeliveryTest` | 20 | Ordinary versus foreground delivery, denied/expired obligations and permission loss. |
| `night/ShortBedJetTestRuntimeTest` | 5 | Explicit test start, marker, target/identity and history exclusion. |
| `night/SlotBackupsUnreadableTest` | 3 | Original-loss refusal with exact contextual warning and dropped retry. |
| `night/TemporaryActionBoundariesTest` | 24 | Actual upload/cancellation, return, Off, receipt and alarm races. |
| `night/TemporaryActionOwnerTest` | 9 | Captured preset and identity, local preview, truthful refusal and one start. |
| `night/TrackedNightForegroundConnectionTest` | 1 | Foreground ownership during tracked BLE work. |
| `night/TruthfulOutcomesTest` | 17 | Before-write failures versus uncertain writes and confirmed outcomes. |
| `night/UnchangedAlarmObservationTest` | 18 | Quiet metadata including wake-boundary crossing versus actual expiry. |
| `service/BluetoothOffTest` | 14 | Real-target off/queued off-on, local override/Stop tracking and receiver cleanup. |
| `service/OriginFeedbackTest` | 4 | Owner feedback origin and APP-only behavior. |
| `service/RadioOffRetimeTest` | 13 | Real-target local unchanged/filtered cases and explicit changed-wake refusal. |
| `service/RecoveryNotificationActionsTest` | 2 | Explicit positive/cancel recovery controls. |
| `service/RetimeNotificationTest` | 64 | Full terminal, timeout, persistence, cancellation, retry and policy-toggle behavior. |
| `service/RuntimeServiceTest` | 10 | Foreground ownership, cancellation propagation and receiver fallback. |
| `service/ServiceEntrySafetyTest` | 8 | Invalid/ordinary/service entry and bounded lifetime behavior. |
| `service/TemporaryActionServiceTest` | 5 | Captured preset/origin payload and invalid pre-promotion rejection. |
| `core/night/NightTemporalEligibilityTest` | 12 | Start/re-time/Resume time boundaries. |
| `core/night/PreparedPublicationCancellationTest` | 5 | Exact pending/confirmed publication and cancellation repair. |
| `core/night/ShortBedJetTestTest` | 3 | Explicit short test versus normal compiler limits. |
| `core/night/TemporaryNightTest` | 34 | Complete compositing, re-time, Turbo boundaries and phone-away simulation. |
| `core/plan/AlarmWindowUxTest` | 4 | Actionable normal-window failures. |
| `core/plan/NightDurationProblemTest` | 4 | Typed duration validation. |
| `core/plan/PlanningHardeningTest` | 5 | Existing planning constraints retained. |
| `core/plan/WakePolicyHardeningTest` | 7 | Accepted alarm/override/default policy. |
| `core/protocol/DryModeRangeTest` | 3 | Dry's actual allowed range. |

The complete unchanged Step A `ManualCancellationSettlementTest` and `RestoreOriginalCaptureTest` were reread, together with complete new `LegacyPlatformStopConfirmationTest`, `ShortcutStopConfirmationTest`, `TemporaryNativeActionTest` and `PlatformActivityTest`. The relevant existing emergency-Off compatibility cases in `RuntimeOwnershipTest` were checked separately. Tests require actual changed slot state or upload traffic where claimed, rather than satisfying a mutation check through mocks alone. The temporary preset tests directly save a different Dry 70 setting after capturing Heat; `Repository.updateSettings` does not normalize that preset. Their preview and execution use the captured Heat request, so the later invalid Dry preset does not replace the reviewed action. The earlier long app run progressed between tests and did not establish a deadlock.

## Qualifications retained for handover

- `BedJetService.start` creates a 115-second undelivered-submission expiry (`:243–245`). Delivery cancels that expiry (`:35–38`) before a separately bounded owner wait. A delivery near expiry can therefore retain admission observation for approximately 115 seconds plus the 90-second owner budget, with cooperative cleanup separately bounded. UI `runOwned` retains its outer 115-second wait (`:172`). This review does not claim an absolute 115-second total for the fire-and-forget path or establish a new requirement violation.
- Repository/source checks and Robolectric/DemoBedJet receipt assertions are not hardware observations. Physical phone-away return, physical Off, Bluetooth reconnection, actual programme timing, launcher reboot/force-stop delivery, Samsung idle behavior and TalkBack remain device acceptance tasks.
- R4 Health Connect writing and R5 import preview/blanket queued-restore blocking remain **Missing** in the phone cross-check. The exact captured-original safeguard does not implement either missing R5 behavior.
- Final clean review of changed subsets and the exact integrated core/app/lint/release gate remain parent-owned. A passing selected class is not a passing full tree.

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

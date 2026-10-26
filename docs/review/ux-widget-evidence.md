# P4 widget evidence

Scope: approved `2026-10-26-nocturne-ux` design and plan, P4 only. Base: `367968bddc390e5b2bb12876fc1662ffa07e47c3`. The command owner, receipts, R38 and 90/100/115-second budgets remain the shared runtime contract. Root owns integrated gates and merge.

## Rulings and interface checks

- Ruling: use the cached Glance 1.1.1 `GlanceAppWidget.compose` API and inflate its real RemoteViews in Robolectric SDK 34, with GraphLifecycle. No test dependency is added — the offline cache already provides the production translation API — tests do not prove launcher-specific rendering on physical Samsung hardware.
- Ruling: Night contains Start tonight, Boost and Turbo; Quick contains Cool and Warm; Device contains Off and Stop tracking; profile starts and an explicitly labelled Open app area remain reachable on further pages. Groups paginate to the current one/two/three-action budget, so compact sizes do not hide later controls — unspecified page maps are resolved without adding command semantics — extra taps are the cost on compact widgets.
- Ruling: responsive reference layouts are 200×128dp, 280×180dp and 340×240dp. Chevrons and action buttons retain 56dp touch bounds, all labels 16sp, status/temperature values 20sp. Provider metadata advertises the requested 2×1 default and both-axis resizing — launcher cell geometry varies, so these are tested dp layouts rather than a claim about physical launcher cell measurements.
- Ruling: widget-confirmed Off uses the existing `NightActions.remote(endsNight=true)` route with the captured target/night and a Standby receipt predicate, inside WIDGET attribution and `BedJetService.runOwned`. Generic Off deliberately ignores old night identities; changing that globally would regress R38 — the existing remote owner already checks the captured identity under its mutex — widget confirmation refuses an obsolete night instead of converting a newer one.
- Interface: root owns Bluetooth/service preflight, alarms worker owns any Check BedJet enum additions. Widget changes do not add a command owner, BLE polling or changes to NightActions/service/notifications.

## Verification ledger

Focused P4 implementation is ready for integration. The final whole-tree gate remains with root; no physical launcher or BedJet claim is made.

### Initial actual layout RED

`ux-widget-layout-red-1`: 13 tests, 13 assertion failures. Tests compiled and inflated production RemoteViews.

- `largeDeviceHasTwoLargeActions`: `java.lang.AssertionError: Real controls on device:0 at 340.0.dp x 240.0.dp expected:<[Off, Stop tracking]> but was:<[]>`
- `mediumDeviceHasTwoLargeActions`: `java.lang.AssertionError: Real controls on device:0 at 280.0.dp x 180.0.dp expected:<[Off, Stop tracking]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `largeQuickHasTwoLargeActions`: `java.lang.AssertionError: Real controls on quick:0 at 340.0.dp x 240.0.dp expected:<[Cool, Warm]> but was:<[]>`
- `largeNightHasThreeLargePrimaryActions`: `java.lang.AssertionError: Real controls on night:0 at 340.0.dp x 240.0.dp expected:<[Start tonight, Boost, Turbo]> but was:<[]>`
- `compactCanReachBoost`: `java.lang.AssertionError: Real controls on night:1 at 200.0.dp x 128.0.dp expected:<[Boost]> but was:<[Start tonight, Boost]>`
- `compactCanReachTurbo`: `java.lang.AssertionError: Real controls on night:2 at 200.0.dp x 128.0.dp expected:<[Turbo]> but was:<[Start tonight, Boost]>`
- `widgetRequestCarriesWidgetOriginBeforeTheServiceHandoff`: `java.lang.AssertionError: expected:<WIDGET> but was:<APP>`
- `mediumCanReachTurbo`: `java.lang.AssertionError: Real controls on night:2 at 280.0.dp x 180.0.dp expected:<[Turbo]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `mediumNightHasTwoLargePrimaryActions`: `java.lang.AssertionError: Real controls on night:0 at 280.0.dp x 180.0.dp expected:<[Start tonight, Boost]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `compactDeviceHasOneLargeAction`: `java.lang.AssertionError: Real controls on device:0 at 200.0.dp x 128.0.dp expected:<[Off]> but was:<[Start tonight, Boost]>`
- `compactNightHasOneLargePrimaryAction`: `java.lang.AssertionError: Real controls on night:0 at 200.0.dp x 128.0.dp expected:<[Start tonight]> but was:<[Start tonight, Boost]>`
- `mediumQuickHasTwoLargeActions`: `java.lang.AssertionError: Real controls on quick:0 at 280.0.dp x 180.0.dp expected:<[Cool, Warm]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `compactQuickHasOneLargeAction`: `java.lang.AssertionError: Real controls on quick:0 at 200.0.dp x 128.0.dp expected:<[Cool]> but was:<[Start tonight, Boost]>`

The owner subsequently clarified visible Boost/Turbo duration labels; expected controls are now Turbo 15m (Boost default) and Turbo 10m. The separate label RED is recorded with the next pre-implementation run.

### Entry, cadence, provider and clarified label RED

`ux-widget-entry-cadence-red-2`: 20 tests, 20 assertion failures; only the four requested classes were counted.

- `largeDeviceHasTwoLargeActions`: `java.lang.AssertionError: Real controls on device:0 at 340.0.dp x 240.0.dp expected:<[Off, Stop tracking]> but was:<[]>`
- `mediumDeviceHasTwoLargeActions`: `java.lang.AssertionError: Real controls on device:0 at 280.0.dp x 180.0.dp expected:<[Off, Stop tracking]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `largeQuickHasTwoLargeActions`: `java.lang.AssertionError: Real controls on quick:0 at 340.0.dp x 240.0.dp expected:<[Cool, Warm]> but was:<[]>`
- `largeNightHasThreeLargePrimaryActions`: `java.lang.AssertionError: Real controls on night:0 at 340.0.dp x 240.0.dp expected:<[Start tonight, Turbo 15m, Turbo 10m]> but was:<[]>`
- `compactCanReachBoost`: `java.lang.AssertionError: Real controls on night:1 at 200.0.dp x 128.0.dp expected:<[Turbo 15m]> but was:<[Start tonight, Boost]>`
- `compactCanReachTurbo`: `java.lang.AssertionError: Real controls on night:2 at 200.0.dp x 128.0.dp expected:<[Turbo 10m]> but was:<[Start tonight, Boost]>`
- `widgetRequestCarriesWidgetOriginBeforeTheServiceHandoff`: `java.lang.AssertionError: expected:<WIDGET> but was:<APP>`
- `mediumCanReachTurbo`: `java.lang.AssertionError: Real controls on night:2 at 280.0.dp x 180.0.dp expected:<[Turbo 10m]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `mediumNightHasTwoLargePrimaryActions`: `java.lang.AssertionError: Real controls on night:0 at 280.0.dp x 180.0.dp expected:<[Start tonight, Turbo 15m]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `compactDeviceHasOneLargeAction`: `java.lang.AssertionError: Real controls on device:0 at 200.0.dp x 128.0.dp expected:<[Off]> but was:<[Start tonight, Boost]>`
- `compactNightHasOneLargePrimaryAction`: `java.lang.AssertionError: Real controls on night:0 at 200.0.dp x 128.0.dp expected:<[Start tonight]> but was:<[Start tonight, Boost]>`
- `mediumQuickHasTwoLargeActions`: `java.lang.AssertionError: Real controls on quick:0 at 280.0.dp x 180.0.dp expected:<[Cool, Warm]> but was:<[Start tonight, Boost, Turbo, Cool, Warm, Off]>`
- `compactQuickHasOneLargeAction`: `java.lang.AssertionError: Real controls on quick:0 at 200.0.dp x 128.0.dp expected:<[Cool]> but was:<[Start tonight, Boost]>`
- `startWithNoTrackedNightStaysInPlace`: `java.lang.AssertionError: Start needs no replacement confirmation without a tracked night expected null, but was:<Intent { dat=nocturne://widget/... cmp=io.github.willzy12h.nocturne/.platform.PlatformActionActivity (has extras) }>`
- `anOrdinaryControlSendsACallbackWithoutOpeningAnyActivity`: `java.lang.AssertionError: A confirmation-free widget action must stay on the launcher expected null, but was:<Intent { dat=nocturne://widget/... cmp=io.github.willzy12h.nocturne/.platform.PlatformActionActivity (has extras) }>`
- `offWithNoTrackedNightStaysInPlace`: `java.lang.AssertionError: Off needs no confirmation without a tracked night expected null, but was:<Intent { dat=nocturne://widget/... cmp=io.github.willzy12h.nocturne/.platform.PlatformActionActivity (has extras) }>`
- `onlyTheExplicitOpenAppAreaTargetsMainActivity`: `java.lang.AssertionError: app:0 must expose a real Open app control`
- `singleProviderHasAPreviewAndNoPeriodicWakeup`: `java.lang.AssertionError: expected:<0> but was:<1800000>`
- `quickProviderAdvertisesAResizableTwoByOneWithAPreviewAndNoPeriodicWakeup`: `org.junit.ComparisonFailure: expected:<[2]> but was:<[4]>`
- `anIdleWidgetDoesNotRedrawOnARepeatingMinuteTimer`: `java.lang.AssertionError: No data changed during three idle minutes expected:<1> but was:<4>`

The first implementation cycle removes ordinary activity launches and the minute/provider timers. Confirm-required controls still use the old confirmation route until the next regression cycle replaces it; no final P4 completion is claimed.

### First verification and layout-harness correction

`ux-widget-layout-entry-green-1`: 28 requested widget tests; 21 passed, 7 failed the added control-bounds assertions. Control names, minimum sizes/type, callback routing, provider metadata and no-idle-timer cases passed. The failures had 41dp legacy Robolectric text-line metrics (two extra lines moved a medium action to y146–202; four moved the large action to y228–284). Existing repository layout tests use native graphics for real font metrics. The next run changes only the layout test renderer to `@GraphicsMode(NATIVE)` before deciding whether production geometry needs correction; these failures are not claimed as a successful gate.

- Ruling: confirmation-required controls use a direct Activity PendingIntent, selected from the captured rendered target/night. Other controls use ActionCallback. A newly stale request fails in place; a callback never opens an activity after a changed night — this avoids activity trampolines and preserves explicit approval — a stale control may require another tap after refresh.
- Ruling: Glance broadcasts finish after service handoff. A tracked application-scope delivery job waits for the bounded service result and updates Glance preferences; it is not a second command owner. Its one-shot Pending deadline is cancelled when the result settles. Stored Pending from an older process or past its bound renders Unknown — interruption must never leave an indefinite or false success — a process killed before service acceptance may leave an unknown, unsent request rather than replay it.

### Receipt, confirmation, lifetime and phase-event RED

`ux-widget-truthful-state-red-3`: 64 requested widget tests, 38 passed and 26 failed. There were 25 behavioural assertion failures and one test-host WorkManager initialisation error. No stale XML suites were counted. Native rendering removed the legacy 41dp-metric failures, but also exposed a real mixed-line baseline overflow: the medium Start/Stop button ended at y181 in a 180dp host. The action row now centres children vertically so native LinearLayout baseline alignment cannot move a two-line button below its fixed row.

- `aRefusedStartShowsFailedWithoutClaimingConfirmation`: `java.lang.AssertionError: expected:<FAILED> but was:<null>`
- `aLaterPendingTapCannotBeOverwrittenByAnEarlierResult`: `java.lang.AssertionError: The newer Warm tap is still pending expected:<PENDING> but was:<null>`
- `aMatchingCachedStatusCannotConfirmANewWidgetRequest`: `java.lang.AssertionError: expected:<UNKNOWN> but was:<null>`
- `cancellingWhileAWriteIsUnsettledChangesPendingToUnknown`: `java.lang.AssertionError: Cancellation must settle the widget as well as the owner expected:<UNKNOWN> but was:<null>`
- `anOffTappedBeforeANightStartsCannotEndThatNewNightWhileQueued`: `java.lang.AssertionError: An unconfirmed stale Off must not write expected:<0> but was:<1>`
- `aConfirmedResultForAnotherTargetIsNotShownAsCurrentConfirmation`: `java.lang.AssertionError: expected:<CONFIRMED> but was:<null>`
- `theCallbackReleasesItsReceiverWhileTheOwnedCommandStillWaits`: `java.lang.AssertionError: The broadcast must return after handoff, not wait for the BedJet receipt`
- `aCompletedReceiptLeavesNoLiveDeliveryOrDeadlineChild`: `java.lang.AssertionError: expected:<CONFIRMED> but was:<null>`
- `pendingFromAnEarlierProcessRendersUnknown`: `java.lang.AssertionError: expected:<PENDING> but was:<null>`
- `aNewMatchingReceiptConfirmsInPlaceWithWidgetOrigin`: `java.lang.AssertionError: expected:<CONFIRMED> but was:<null>`
- `pendingIsPublishedBeforeTheFirstBedJetWrite`: `java.lang.AssertionError: Pending must be visible before sending expected:<PENDING> but was:<null>`
- `pendingPastItsBoundRendersUnknownAtTheNextRender`: `java.lang.AssertionError: expected:<PENDING> but was:<null>`
- `confirmedStopTrackingIsLocalAndDoesNotSendOff`: `java.lang.AssertionError: A tracked-night STOP_TRACKING tap must directly request its confirmation activity`
- `trackedOffUsesAnExcludedTranslucentActivityWithoutTheAppTask`: `java.lang.AssertionError: A tracked-night OFF tap must directly request its confirmation activity`
- `aChangedTargetWhileOffConfirmationIsOpenIsRefused`: `java.lang.AssertionError: A tracked-night OFF tap must directly request its confirmation activity`
- `confirmingOffEndsTheCapturedNightAndClosesTheOverlay`: `java.lang.AssertionError: A tracked-night OFF tap must directly request its confirmation activity`
- `cancellingTheOffDialogSendsNothing`: `java.lang.AssertionError: A tracked-night OFF tap must directly request its confirmation activity`
- `replacementStartAndManualControlsKeepTheirConfirmationGuard`: `java.lang.AssertionError: A tracked-night START_NIGHT tap must directly request its confirmation activity`
- `trackedStopUsesTheSameSmallConfirmationOnly`: `java.lang.AssertionError: A tracked-night STOP_TRACKING tap must directly request its confirmation activity`
- `aNightChangedAfterApprovalButBeforeOwnerAdmissionIsAlsoRefused`: `java.lang.AssertionError: A tracked-night OFF tap must directly request its confirmation activity`
- `aChangedNightWhileOffConfirmationIsOpenIsRefused`: `java.lang.AssertionError: A tracked-night OFF tap must directly request its confirmation activity`
- `everySizeKeepsEveryCommandReachableAndPersistsPageChangesWithoutAnActivityOrConnection`: `java.lang.IllegalStateException: WorkManager is not initialized properly.  You have explicitly disabled WorkManagerInitializer in your manifest, have not manually called WorkManager#initialize at this point, and your Application does not implement Configuration.Provider.`
- `mediumDeviceHasTwoLargeActions`: `java.lang.AssertionError: Stop tracking must fit inside 280.0.dp x 180.0.dp: Rect(142, 125 - 272, 181)`
- `boostDescriptionNamesBothPartsOfItsRun`: `java.lang.AssertionError: expected:<Turbo heat for 10 minutes, then Heat for 5 minutes> but was:<null>`
- `mediumNightHasTwoLargePrimaryActions`: `java.lang.AssertionError: Start tonight must fit inside 280.0.dp x 180.0.dp: Rect(8, 125 - 138, 181)`
- `aTrackedPlanRefreshesAtItsPhaseBoundaryWithoutAnIdleMinuteLoop`: `java.lang.AssertionError: The planned phase boundary is an event even with no BLE connection expected:<[Planned phase: Start, Planned phase: Middle]> but was:<[Planned phase: Start]>`

The receipt test froze a matching pre-request report; the queued-Off test observed one actual simulated control write where zero was required. The callback lifetime test held the real owner at a write boundary and failed because the broadcast could not return. Confirmation tests clicked production RemoteViews and required the direct overlay PendingIntent. Widget state preferences and actual rendered Pending/Confirmed/expired-Unknown are tested together. WorkManager auto-initialisation is present in the merged production manifest; the direct Robolectric host lacks that initialiser. The redraw wrapper retains stored state when a host refresh is unavailable, and paging tests additionally read and render the persisted page state.

### Receipt/confirmation GREEN and large-font/truth-label RED

`ux-widget-feedback-green-font-red`: 70 widget tests, 63 passed and 7 failed. New receipt confirmation/refusal/cancellation, queued Off identity, broadcast handoff, actual direct confirmation PendingIntents, phase boundary updates and all nominal layout cases passed. Exact remaining failures:

- `everySizeAndPageKeepsControlsAndTextInsideItsBoundsAtTwoHundredPercentFont`: `java.lang.AssertionError: At 200%, Start tonight must fit in 280.0.dp x 180.0.dp on night:0: Rect(8, 148 - 138, 204)`
- `aFailedWakeIsFailedRatherThanStillPending`: `org.junit.ComparisonFailure: expected:<Next wake [failed]> but was:<Next wake [pending]>`
- `anUnknownWakeIsUnconfirmedRatherThanStillPending`: `org.junit.ComparisonFailure: expected:<Next wake [unconfirmed]> but was:<Next wake [pending]>`
- `aPlannedPhaseCarriesItsAbsoluteTimeSpanForAFrozenHostView`: `java.lang.AssertionError: A cached planned phase must include its date and time range: Planned phase: Start`
- `aCompletedReceiptLeavesNoLiveDeliveryOrDeadlineChild`: `java.lang.AssertionError: Settling the result must cancel its one-shot deadline`
- `trackedOffUsesAnExcludedTranslucentActivityWithoutTheAppTask`: `java.lang.AssertionError: The overlay must not bring the full app task forward expected:<> but was:<null>`
- `cancellingTheOffDialogSendsNothing`: `java.lang.AssertionError` at the immediate `isFinishing` assertion after the native dialog click.

The last three required test-harness corrections: the first counted LinkSessions’ existing 30-second connection-release child instead of the actual WidgetDelivery job; Android parses an empty manifest taskAffinity as null; AlertDialog posts its click handler to the main looper. Tests now join the returned delivery and assert it has no active deadline children, assert no affinity, and drain the dialog click handler. No production lifecycle workaround was added for those harness issues.

The actual large-font fix retains 16sp/20sp and 56dp controls. Above 130% text scaling, pages carry one full-width action. Compact/large-font status uses a symbol with the full state, command and outcome in its accessibility description; a tick is reserved for receipt-confirmed results. The large layout places temperature beside status to keep phase/date span/wake visible, and uses concise wake labels with the full wording in the description. Nominal layouts keep their one/two/three budgets.

`ux-widget-font-truth-green-1` stopped at test compilation because the updated cadence assertion lacked its `assertTrue` import. It ran zero tests and is not behavioural RED or GREEN evidence. The import is corrected before rerunning.

`ux-widget-font-truth-green-slot-red`: 74 tests, 70 passed and 4 failed. All receipt, handoff, deadline-cleanup, confirmation and paging tests passed. UNKNOWN/FAILED night wording and absolute phase span passed. The two newly requested unresolved-slot cases exposed the old confirmed wake label:

- `aPendingSlotWriteForThisNightDoesNotKeepTheOldConfirmedWake`: `org.junit.ComparisonFailure: expected:<Next wake [pending]> but was:<Next wake [Tue 06:30]>`
- `anUnresolvedSlotWriteForThisNightMakesItsWakeAndPhaseUnconfirmed`: `org.junit.ComparisonFailure: expected:<Next wake [unconfirmed]> but was:<Next wake [Tue 06:30]>`
- `everySizeAndPageKeepsControlsAndTextInsideItsBoundsAtTwoHundredPercentFont`: `java.lang.AssertionError: At 200%, Start tonight must fit vertically: lines=71, height=56, padding=18`
- `aConfirmedPlanAndSingleActionFitAtNormalAndTwoHundredPercentText`: `java.lang.AssertionError: BEDJET-DEMO must not be ellipsised at 1.0× in 200.0.dp x 128.0.dp expected:<0> but was:<2>`

The unresolved-slot presentation now applies the existing exact displaced-night confidence rule; an unrelated-night slot journal has a positive control. The native button style explicitly retains sentence case and removes inherited letter spacing, and the compact Single header uses BedJet while its full device name remains accessible. The 200% test now checks actual text layout height and ellipsis, beyond control rectangles alone. Arbitrarily long saved profile names may still ellipsise inside a fixed-size button; their full action name is always its accessibility description. Fixed primary/control labels are required to fit in full.

## Final focused GREEN and handover

`ux-widget-font-native-slot-green`: **34 requested tests passed, zero failures/errors**, `BUILD SUCCESSFUL` (124.39 seconds). Counted only fresh `WidgetResponsiveLayoutTest` (27) and `WidgetTruthLabelsTest` (7), not stale XML from earlier focused runs. This resolves all four behavioural failures from the preceding 74-test run. That preceding run already passed the other 40 widget tests. A final assertion helper recognises the state prefix when the accessibility description also names the device; root’s integrated gate re-runs the complete suite.

- Normal geometry: `compactNightHasOneLargePrimaryAction`, `mediumNightHasTwoLargePrimaryActions`, `largeNightHasThreeLargePrimaryActions`, plus each Quick/Device/Profile/App page and all Single sizes. Primary controls remain Start tonight / Turbo 15m (configured Boost) / Turbo 10m.
- Enlarged text: `everySizeAndPageKeepsControlsAndTextInsideItsBoundsAtTwoHundredPercentFont` traverses every paginated group and overflow page at 200%, checking the actual RemoteViews text/control bounds, text layout height, primary-label ellipsis and button dimensions. `aConfirmedPlanAndSingleActionFitAtNormalAndTwoHundredPercentText` adds a real confirmed plan/report and both widget types at 100%/200%.
- Truth labels: `anUnknownWakeIsUnconfirmedRatherThanStillPending`, `aFailedWakeIsFailedRatherThanStillPending`, `aPendingWakeRetainsItsPendingLabel`, `aPlannedPhaseCarriesItsAbsoluteTimeSpanForAFrozenHostView`, both matching unresolved-slot tests and `anUnresolvedWriteForAnotherNightDoesNotColourThisNight`.
- Receipt/lifetime (12 previously GREEN): `pendingIsPublishedBeforeTheFirstBedJetWrite`, `aNewMatchingReceiptConfirmsInPlaceWithWidgetOrigin`, `aMatchingCachedStatusCannotConfirmANewWidgetRequest`, `aRefusedStartShowsFailedWithoutClaimingConfirmation`, `cancellingWhileAWriteIsUnsettledChangesPendingToUnknown`, `anOffTappedBeforeANightStartsCannotEndThatNewNightWhileQueued`, `aLaterPendingTapCannotBeOverwrittenByAnEarlierResult`, `theCallbackReleasesItsReceiverWhileTheOwnedCommandStillWaits`, `aCompletedReceiptLeavesNoLiveDeliveryOrDeadlineChild`, `pendingFromAnEarlierProcessRendersUnknown`, `pendingPastItsBoundRendersUnknownAtTheNextRender`, `aConfirmedResultForAnotherTargetIsNotShownAsCurrentConfirmation`.
- Confirmation (9 previously GREEN): actual RemoteViews directly start the excluded translucent activity for Off/Stop and guarded replacements; cancellation sends nothing; confirmed Off settles its captured night; Stop is local; changed target, changed open-dialog night and changed queued night are refused.
- Event/paging (previously GREEN): `anIdleWidgetDoesNotRedrawOnARepeatingMinuteTimer`, `aTrackedPlanRefreshesAtItsPhaseBoundaryWithoutAnIdleMinuteLoop`, both `WidgetPagingTest` tests, both provider tests, and five actual click-route tests. The phase observer runs finite plan deadlines only while this process lives; a cached phase includes its absolute date/time span if the host outlives the process.

### Integration-only seams owned by root

1. In `WidgetDelivery`, use the newly added `BedJetService.runOwned(..., hardware = request.command != Command.STOP_TRACKING)` argument. This isolated base predates that overload. Keep `notifyFailures = false`: root’s Bluetooth-off handling still posts its deduplicated WIDGET notice. The UNDISPATCHED handoff reaches runOwned and retains its per-submission admission guard; no retry is added.
2. Replace the duplicate widget Boost description with root’s `night.boostDescription(context, minutes)` helper once integrated; visible mode/duration labels and tested wording already match it.
3. Add exported=false WidgetConfirmationActivity to the release manifest exact allowlist after root’s observed manifest RED. No serializable model or dependency was added.
4. Device remains Off + Stop tracking; Check BedJet is in the requested warning/notification recovery route and is not an extra P4 requirement.

### Practical limits

No physical Samsung launcher, hardware BLE connection or release-install verification was performed here. The automated layout evidence is the native SDK 34 renderer at the documented dp sizes. Arbitrarily long saved profile names can ellipsise within fixed widget bounds, with the full action available as its content description; all fixed primary/device labels are verified in full. The provider has no periodic update interval, and WidgetUpdater has no unconditional timer.

### Source/test map

| Behaviour | Implementation | Regression |
|---|---|---|
| Responsive budgets, paging and type | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/Widgets.kt:81` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetResponsiveLayoutTest.kt:135` |
| Confirmed-plan geometry and both widget types | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/Widgets.kt:170` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetResponsiveLayoutTest.kt:175` |
| Activity-free ordinary callbacks | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetActions.kt:76` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetInPlaceActionTest.kt:47` |
| In-place persisted pages | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetActions.kt:65` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetPagingTest.kt:29` |
| Receipt state and request CAS | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetActions.kt:140` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetCommandFeedbackTest.kt:182` |
| Dead/expired Pending | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetActions.kt:111` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetCommandFeedbackTest.kt:237` |
| Callback handoff and cancellable deadline | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetActions.kt:171` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetCommandFeedbackTest.kt:227` |
| Strict Off identity under the shared owner | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetActions.kt:206` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetCommandFeedbackTest.kt:156` |
| Direct translucent approval | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetConfirmationActivity.kt:24` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetConfirmationTest.kt:111` |
| Approval identity change refusal | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetConfirmationActivity.kt:62` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetConfirmationTest.kt:202` |
| Finite phase events, no idle loop | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetUpdater.kt:53` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetUpdateCadenceTest.kt:48` |
| Night/slot truth labels | `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetPresentation.kt:121` | `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetTruthLabelsTest.kt:46` |

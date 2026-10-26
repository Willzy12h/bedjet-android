# UX self-review pass 1 — UI, themes, navigation and editor

## Scope and evidence boundary

Reviewed the complete contents of the changed UI production files, their diff from `367968bddc390e5b2bb12876fc1662ffa07e47c3`, related changed resources and UI tests. The restored integration checkpoint is `0eddd188540df9d918c9c2e69701ae6e72f9a918`; this review also includes the subsequent shared-workspace test corrections and the peer's passive-curve fix. Line references below describe this review snapshot and may move during remediation.

This is a host source/test review. It does not establish physical BedJet behaviour, Samsung background delivery, launcher sizing, spoken TalkBack output, reboot or force-stop acceptance. The first integrated gate reported core **201 tests, no failures**, and app **1,180 tests, 37 failures, 0 errors, 38 expected capture skips**. Root owns all Gradle invocations and subsequent full gates.

The first focused review invocation, `ux-review-pass1-red`, stopped before test execution because of the widget composition lint error and an invalid explicit `assertDoesNotExist` import in `AppSmokeTest`. The import was removed without changing assertions. **That invocation is not behavioural RED evidence.**

The next root-run batch, `ux-review-pass1-behaviour-red`, completed **210 tests: 189 passed, 21 failed, 0 errors and 0 skips** in 233.58 seconds. The selected fresh XML suites establish the following results; no copied-directory aggregate is used. Logs and XML are retained in `/tmp/nocturne-gpt11-evidence/ux-review-pass1-behaviour-red`.

| Selected suite | Tests | Passed | Failed | Observed result |
|---|---:|---:|---:|---|
| `NightGuardTruthTest` | 8 | 0 | 8 | Each guard exposed the false running claim, including both confidence changes while open. |
| `UiQueuedBluetoothTest` | 2 | 1 | 1 | Interrupted tap wrote once instead of zero times; the same save wait without a radio interruption completed normally. |
| `EditorTrackingTruthTest` | 4 | 0 | 4 | Three states exposed the false running text in editor and dialog; accepting A's old dialog changed replacement B's active state. |
| `MoreReleasePlacementTest` | 3 | 1 | 2 | Both More menus exposed Release; Settings Release→Take back worked. |
| `SaveOrderingTest` | 2 | 2 | 0 | Existing ordinary save/reapply and next-night controls passed unchanged. |
| `AppSmokeTest` | 15 | 15 | 0 | Corrected interaction/label assumptions and strengthened UNKNOWN/manual truth checks passed. |
| `AppearanceTest` | 2 | 2 | 0 | Correctly formatted Boost label and explicit Light confirmation retained persisted appearance assertions. |

The four new suites therefore supplied **17 cases: 15 intended failures and 2 positive-control passes**. Minimal production corrections were applied only after this executed RED.

Root's next batch, `ux-review-pass1-green-sequence-red`, freshly passed all **17/17** cases in those four suites plus **2/2 unchanged SaveOrdering controls**, with no errors or skips in those five selected suites. Their XML timestamps are 07:22:54–07:23:27 UTC on 26 October. The invocation as a whole was **85 tests with 16 failures** from other deliberate/new regressions and a separate fixture issue; it is not an overall GREEN gate. This verifies UI-01 through UI-05 on the checkpoint before the incoming Temporary merge, not the subsequently combined tree.

## Findings and verified remediation before the Temporary merge

| ID | Finding and exact source | Reproduction / regression | Status at handoff |
|---|---|---|---|
| UI-01 | `ui/NightGuard.kt:33–39` formatted a running/wake claim for every non-Resume choice, including UNKNOWN, PENDING, kept-manual and a confidence change while the dialog remained open. | Eight `NightGuardTruthTest` cases failed at the false-claim assertion. Actions, off purpose, captured identity and cancellation invariants remain asserted. | Resource now says the profile is tracked for tonight and names the requested action; no captured-confidence claim. Positive/cancel behaviour unchanged. **8/8 GREEN before merge.** |
| UI-02 | `ui/AppViewModel.kt:401–431` previously waited for `lastSave` before the Bluetooth admission marker existed. An OFF→ON transition during a real durable settings save escaped that marker. | `UiQueuedBluetoothTest.aRadioInterruptionWhileWaitingForTheSettingsSaveDoesNotReplayTheTap` observed one transport write, violating the zero-write requirement. `anUninterruptedTapStillRunsAfterTheSameSettingsSaveCompletes` passed. | Creates admission before the save wait, propagates the same marker through service/owner, bounds the UI submission by the existing 115-second limit, and closes the marker in an outer `finally`, including ViewModel cancellation. No idle receiver or replay. **2/2 GREEN before merge.** |
| UI-03 | `ui/ProfileEditor.kt:99,119,146` used matching `profileId` as evidence that a profile was running, in both editor and save-choice dialog. UNKNOWN, PENDING and kept-manual states matched. | Three `EditorTrackingTruthTest` cases each found two false running claims. Both save choices and next-night-only persistence/state invariants remain asserted. | Both resource strings now describe a profile linked to the tracked night. **All three cases GREEN before merge.** |
| UI-04 | The Update-tonight dialog kept only a Boolean and captured the current REAPPLY request after confirmation. Accepting A's dialog after B replaced it rewrote B. | `EditorTrackingTruthTest.anUpdateTonightDialogCannotRewriteANightThatReplacedItsOriginal` failed its complete replacement-state assertion. It also requires unchanged programme/generation and stale feedback. Existing `SaveOrderingTest` passed unchanged. | `ui/ProfileEditor.kt:102,134–135` stores the complete target/identity `CommandRequest` at opening; `ui/AppViewModel.kt:461–470` saves the edit then executes that captured request. The one-argument API remains for existing callers. **Stale case and 2/2 SaveOrdering controls GREEN before merge.** |
| UI-05 | `ui/NightRecoveryControls.kt:71` omitted `includeRelease = false`, leaving Release/Take back in Tonight's More despite the Settings destination and original handoff requirement (`ux-ui-evidence.md:12,55,92`). | Two More absence cases failed; Settings Release→Take back positive control passed. Request and applicable re-check remain asserted. `AppSmokeTest` retained idle handover reachability through Settings and passed. | More now supplies `includeRelease = false`; Settings handover is unchanged. **3/3 GREEN before merge.** |
| UI-06 | `values/strings_ui.xml:25` described an “End button” despite the Stop night action. | `PhoneControlCopyTest.endingAndItsConfirmationSayStopNight` failed in the same executed batch. | Root corrected the help resource; root owns verification. |

The separate passive-preview defect was found by the UI peer: NightCurve's unconditional input handlers intercepted History card expansion and gave passive Profiles previews phase actions. Five unchanged `HistoryRevisionsTest` failures are the existing RED evidence. The peer's correction defaults `phaseInteraction` to false and enables it only in Tonight/ProfileEditor. Review of the complete corrected file confirms that pointer input, semantic click/custom actions and selected details are gated, while the existing description remains available. `ProfileEditorInteractionTest.profilePreviewsStayPassiveUntilTheirEditorIsOpened` adds a passive Profiles regression. Root owns its verification.

All-state **Stop night visibility is intentional**. A guarded Stop action is not evidence that a programme is confirmed. The UNKNOWN smoke assertion was replaced with direct truth checks: UNKNOWN text, Planned wake, no Waking you at, no Now/current-phase claim, and unchanged identity/state when the Stop dialog is cancelled. Production visibility was not restricted.

## Production file review ledger

Paths in this table are under `app/src/main/kotlin/io/github/willzy12h/nocturne/`. Each row records a complete-file review, including unchanged surrounding code.

| File | Reviewed boundaries and outcome | Relevant test evidence |
|---|---|---|
| `ui/ActiveIndicator.kt` | `:39,68,86`: resumed/screen-interactive gate, animator-scale observer, disposed receivers/observers, clipped viewport visibility and finite 600ms + 600ms pulse. No idle animation loop. | `ActiveIndicatorTest`; `TonightLayoutTest.reducedMotionDoesNotInventACompletionPercentageForBusyWork`. |
| `ui/AppViewModel.kt` | `:124,211,268,401,461`: source-bound visible status; sensor-only throttling; visible Bluetooth listener cleanup; fresh re-check published before delivery; serialized saves and captured request identity. UI-02 now observes radio during the save wait with cancellation cleanup; UI-04 accepts the dialog's complete request. Both passed their selected pre-merge regressions. Test history is filtered; no Health writer added. | `BluetoothLifecycleTest`, `UiQueuedBluetoothTest`, `AlarmWindowExplanationTest`, `HapticsTest`, `SaveOrderingTest`, `EditorTrackingTruthTest`. |
| `ui/BluetoothBanner.kt` | `:35`: explicit Android permission→enable flow; adapter refresh on result; resource-backed failure, 48dp control. No automatic command replay. | `BluetoothBannerTest` (both screens, real adapter state, enable intent, permission revocation). |
| `ui/EditorPhaseSelection.kt` | `:14`: maps compiled Turbo fragments and front-trimmed Wake back to source indices; Middle explicit. | All four `EditorPhaseSelectionTest` mappings use the real compiler. |
| `ui/MainActivity.kt` | `:69,135,142,165,205`: actual five-tab navigation, theme/system-bar binding, serialized parked drafts, safe phase selection only for an unchanged draft, Settings alarm/backups destinations, view-bound haptics. | `TonightPhaseNavigationTest`, `AlarmSettingsNavigationTest`, `AppSmokeTest.largeTextNavigationKeepsFiveTabsReachable`, draft tests, `HapticsTest`. |
| `ui/ModeLabel.kt` | `:13`: separate mode name and resource-backed maximum from `Mode.temperatureRange`, selected temperature unit. | `RemoteModeLimitsTest`; `ProfileEditorInteractionTest.modeChoicesShowModelMaximumsWithoutReplacingTheirNames`. |
| `ui/NightCurve.kt` | `:67,94,247,298`: reviewed peer's opt-in phase interaction, immutable plan selection, latest edit callback, retained manual-end descriptions, current-phase gating and editable timeline touch height. History/Profiles remain passive after the peer correction. | `ProfileEditorInteractionTest`, unchanged `HistoryRevisionsTest`, `NightCurveAlternativeTest`, `EditorPhaseSelectionTest`, manual smoke coverage. |
| `ui/NightGuard.kt` | `:27–54`: positive action still enters captured-night scope; Resume has distinct confirmation text. UI-01 uses neutral tracking copy after eight executed RED cases; eight pre-merge GREEN. | `NightGuardTruthTest`; existing stale Stop smoke case; `RecoveryAndFanUiTest`. |
| `ui/NightRecoveryControls.kt` | `:37–80`: offer target/generation/expiry eligibility, Resume distinction, kept-manual recovery, local Stop-tracking wording and re-check path. UI-05 now excludes Release/Take back from More; three pre-merge GREEN. | `RecoveryAndFanUiTest`, `MoreReleasePlacementTest`, smoke resume-expiry checks. |
| `ui/ProfileEditor.kt` | `:88,99,102,134`: compact/expanded source rows, explicit editable controls, add/remove focus, time/unit/limit controls, draft/save/discard flow. 56dp phase rows and 48dp mode/add controls. UI-03/UI-04 now use neutral text and an immutable dialog request; four pre-merge GREEN. Raw source-phase times describe editable phases; compiled-curve selection correctly handles trimming. | `ProfileEditorInteractionTest`, `EditorPhaseSelectionTest`, `U06ControlSemanticsTest`, `PickerInitialTimeTest`, `EditorTrackingTruthTest`, unchanged `SaveOrderingTest`. |
| `ui/ProfilesScreen.kt` | `:62,94,148`: passive preview call, immutable pin preview/target/programme, stale/expiry/active-night refusal and resumed-only five-second preview clock. Typed duration refusal uses Android resources. | `PinPreviewLifecycleTest`, pin smoke cases, passive Profiles regression, `NightDurationResourcesTest`. |
| `ui/QuickActivity.kt` | `:20`: reported Quick marker requires READY status; ordinary tracked phases cannot claim Boost; Boost additionally needs matching target, confirmed start journal and compiled programme; ambiguous presets remain unmarked. | `ActiveIndicatorTest` real actions and ambiguity/configuration cases. |
| `ui/RemoteScreen.kt` | `:45,78,98`: target/status gate, truthful reported values, guarded mode changes, slider receipt checks, silent confirmed Fan feedback only, dynamic Boost description. | `RecoveryAndFanUiTest`, `BoostAccessibilityTest`, `RemoteModeLimitsTest`, `U06ControlSemanticsTest`. |
| `ui/SettingsScreen.kt` | `:91,105,154,211,242`: Light/System-light confirmation and dismissal, saved appearance controls, owner-backed Settings handover, alarm-source persistence and fresh result, explicit short-test preview. Root separately owns the ten-minute Boost helper mismatch. | `LightThemeConfirmationTest`, corrected `AppearanceTest`, `MoreReleasePlacementTest`, `AlarmSettingsNavigationTest`, `AlarmAndShortTestSettingsTest`, `SettingsBoostCopyTest`. |
| `ui/SetupChecks.kt` | `:45,75`: resume refresh and the shared Nearby-device permission list; explicit system setup destinations. | `GapsCoverageTest.grantingNearbyPermissionWhilePausedRemovesItsMissingSetupCheckOnResume`, U06 setup rows. |
| `ui/ShortTestControls.kt` | `:25`: captured preview target/slot, explicit positive start, scrollable explanation, busy/active/no-target restrictions, cancellation does not submit. | `AlarmAndShortTestSettingsTest`, `ShortTestTonightIntegrationTest`. |
| `ui/ShortcutActivity.kt` | `:20,26`: exported requests always show a confirmation; captured notification identity/target retained; Resume and Stop labels are distinct. No extra can waive confirmation. | `RecoveryNotificationActionsTest`, `ServiceEntrySafetyTest`, `PhoneControlCopyTest`. |
| `ui/Theme.kt` | `:90,96,171,195`: only normal Daylight heating colours changed; Simple Daylight/Midnight retained; text/control palette roles unchanged; scheduled clock uses boundary sleep, resume and clock-change events. | `DaylightHeatPaletteTest`, `ContrastTest`, existing scheduled-theme tests. |
| `ui/TonightScreen.kt` | `:103,125,158,226,282,356`: foreground-only alarm refresh, fixed truthful wake, Quick-first layout, all-state guarded Stop, no current-phase claim without confirmation, reserved busy geometry and actual reported Quick marker. | `TonightLayoutTest`, U06 primary/capture tests, corrected `AppSmokeTest`, alarm tests, `NightGuardTruthTest`. |
| `ui/TonightSections.kt` | `:47,58,73`: normalized persisted IDs, real drag placement, Move up/down buttons/custom actions, 48dp headers, no timer or visual reorder animation loop. | `TonightLayoutTest` persistence, real drag, malformed order and accessible move cases. |
| `ui/features/FeatureSections.kt` | `:34,102`: single toggle rows, owner-backed release/take-back, captured target and cancellation propagation. Settings path correct; More now supplies the existing Release exclusion parameter for UI-05. Health permission remains read-only. | `LightThemeConfirmationTest`, `MoreReleasePlacementTest`, U06 platform rows. |
| `ui/features/HistoryFeatures.kt` | `:31,61`: CSV delegates to the filtered exporter; marked tests return before constructing a Health reader; overlay remains read-only and lifecycle/event refreshed. | Short-test boundary tests; existing History/Health tests. No Health write claim. |

## Resource and XML review ledger

Paths are under `app/src/main/res/`. All listed files were read in full. New UI wording is resource-backed; protocol identifiers, test data and punctuation are not treated as prose resources.

| File | Review result |
|---|---|
| `values/strings_alarm_sources.xml` | Source names, one/18-hour reasons and confidence labels remain distinct; no promotion of manual state over pending/unknown. |
| `values/strings_alarm_ux.xml` | Duration refusals, next-alarm-only limitation, fresh re-check and contextual Check-BedJet text have explicit resources. Root is covering a missed caller that exposed the typed core enum. |
| `values/strings_bluetooth.xml` | Off/interrupted/expired reasons distinguish retrying an action from automatic replay. |
| `values/strings_boost.xml` | Ten-minute Turbo-only and longer Turbo→Heat descriptions are separate resources. |
| `values/strings_fixes2_settings.xml` | Unknown-source setting explains the 1–18-hour window without changing its default. |
| `values/strings_runtime.xml` | Resume/Stop action names and re-time terminal/current-programme messages checked. Root's non-duration Boost label follow-up is recorded separately. |
| `values/strings_short_test.xml` | Explicit equipment-test preview, target/slot, durations, off route and History/Health comparison exclusion. No hardware-success assertion before owner result. |
| `values/strings_ui.xml` | Shared units, recovery/editor copy, quiet-help text, descriptions and format placeholders reviewed. UI-01/UI-03 now use neutral tracked-night wording; root corrected UI-06. The guard/editor cases passed before merge; root owns quiet-help verification. Root also covers misleading ten-minute Settings Boost copy. |
| `values/strings_ux_ui.xml` | Reorder, expansion, curve details, light warning, mode maxima and release help all have resources. |
| `values/strings_widgets.xml` | Unknown/pending/confirmed/local/last-report labels distinguish saved information from current confirmation; widget implementation is the runtime peer's review scope. |
| `values/themes.xml` | Widget confirmation remains translucent; Glance button casing/letter spacing override has no application palette changes. |
| `layout/quick_controls_widget_preview.xml` | Static preview uses resource text, 16/20sp labels and 56dp controls against the actual light `background` colour; it is not live status evidence. |
| `layout/single_action_widget_preview.xml` | Same static-preview constraints; no runtime command path. |
| `xml/quick_controls_widget.xml` | 2×1 target, 200×128dp minimum, resizing and updatePeriodMillis=0 verified from source; Samsung launcher acceptance remains device-only. |
| `xml/single_action_widget.xml` | Same bounds/cadence plus explicit optional configuration activity; runtime peer owns behaviour tests. |

## Related test-file review ledger

Paths are under `app/src/test/kotlin/io/github/willzy12h/nocturne/`. This ledger records inspected coverage; it does not turn a pending invocation into a passing result.

| File | Preserved or added assertion boundary |
|---|---|
| `AppSmokeTest.kt` | Label/interaction proxies updated to resources and dialog-scoped confirmation; original state, history, stale identity and resume assertions retained. UNKNOWN now has direct truth checks. Manual-plan negative assertions are scoped to the plan and paired with a positive manual-end assertion. Idle handover remains reachable through Settings. |
| `AppearanceTest.kt` | Corrected formatted Turbo button; Light must remain unsaved until Switch is accepted; persisted Light and Dark assertions retained. |
| `IdleProcessTimersTest.kt` | Actual default clock callbacks prove idle notification/demo timers stop; running hidden demo completion still works. |
| `ScreenshotCapture.kt` | Host-native capture contract retained; running capture now explicitly waits for confirmed state rather than inferring it from Stop visibility. Capture skip condition unchanged. |
| `ui/ActiveIndicatorTest.kt` | Finite pulse geometry, remove animations, screen/lifecycle stop, genuine Boost journal and custom/ambiguous Quick presets. |
| `ui/AlarmAndShortTestSettingsTest.kt` | Fresh source/fallback on repeated re-check, cancel-only preview, owner-confirmed marked test and absent sleep history. |
| `ui/AlarmExplanationActionTest.kt` | Real callbacks for unknown-source/profile-opt-out/changed saved wake; no implied profile update. |
| `ui/AlarmExplanationLayoutTest.kt` | Complete actionable line at 320dp/200% for all four looks. |
| `ui/AlarmExplanationTest.kt` | Full scenario matrix including pending/manual/unknown precedence, exact duration boundaries, diagnostics parity and actual native picker route. |
| `ui/AlarmSettingsNavigationTest.kt` | Actual Settings destination, persisted toggle both ways and exported reason. |
| `ui/BluetoothBannerTest.kt` | Adapter truth, both screens, permission-before-enable and actual Android intent. |
| `ui/BluetoothLifecycleTest.kt` | One visible listener; forged state extras do not change adapter truth; hidden/cleared cleanup. |
| `ui/BoostAccessibilityTest.kt` | Remote's spoken programme description matches Turbo then Heat. |
| `ui/DaylightHeatPaletteTest.kt` | Amber/orange-red family with other mode colours and Simple palette preserved. |
| `ui/EditorPhaseSelectionTest.kt` | Four real compiler mappings, including Turbo continuation and trimmed phases. |
| `ui/EditorTrackingTruthTest.kt` | New neutral-copy and captured-dialog identity regressions described above; ordinary SaveOrdering control remains unchanged. |
| `ui/GapsCoverageTest.kt` | Permission resume, target validation, schedule expiry, wake override precedence and resource-backed refusal remain asserted. |
| `ui/HapticsTest.kt` | APP view/request-bound admission and confirmation; local/other origins silent; cancellation/recreation do not adopt old requests. |
| `ui/LightThemeConfirmationTest.kt` | Switch/Cancel/outside/Back, System currently dark/light, saved theme, Settings Release owner path. |
| `ui/MoreReleasePlacementTest.kt` | New location regressions preserve Request/re-check and Settings Release/Take back. |
| `ui/NightGuardTruthTest.kt` | Eight UNKNOWN/PENDING/manual/stale-confidence dialog cases; cancellation preserves state. |
| `ui/PhoneControlCopyTest.kt` | Actual compiled Boost duration/modes, Resume/Stop labels; root adds shortcut/help consistency regressions. |
| `ui/PickerInitialTimeTest.kt` | Actual native picker fields and positive acceptance; existing choice, planned wake, empty-profile fallback and edited saved fields retained. |
| `ui/PinPreviewLifecycleTest.kt` | A stopped preview does not continue five-second clock reads; test clock restored. |
| `ui/ProfileEditorInteractionTest.kt` | Actual phase gestures, add-row save boundary, repeated focus and mode maxima; peer adds passive Profiles test. |
| `ui/RecoveryAndFanUiTest.kt` | Real Resume/Stop confirmation effects; cancellation retains physical mode; confirmed Fan bubble suppressed while failures/unknown remain visible. |
| `ui/RemoteModeLimitsTest.kt` | Every Remote mode names the protocol maximum, including Dry. |
| `ui/ShortTestTonightIntegrationTest.kt` | Marked test name/fixed timing and guarded Stop cancellation. |
| `ui/TonightLayoutTest.kt` | Real drag/save/order, fixed wake geometry, no busy shift, reduced-motion indeterminate semantics and stopped alarm refresh. |
| `ui/TonightPhaseNavigationTest.kt` | Actual MainActivity route opens and scrolls the source phase. |
| `ui/U06CaptureTest.kt` | Separate complete Quick capture respects new order; unchanged host-only metadata and optional capture skips. |
| `ui/U06ControlSemanticsTest.kt` | All actual sliders/range thumbs and switch rows at 320dp/200%; compact phase controls are opened before existing complete assertions. |
| `ui/U06PrimaryLayoutTest.kt` | Primary/recovery/settings controls, full labels, ≥48dp targets and 200% busy geometry in all looks; no assertion weakening. |
| `ui/UiQueuedBluetoothTest.kt` | Actual durable save wait + service handoff, zero writes/interrupted result, and unchanged-radio positive control. |

Existing `ContrastTest` was also read: it measures actual text roles against all used surfaces (4.5:1), after-wake curve tints (3:1), and the shared Quick boundary factory (3:1). Those arithmetic checks and the U06 semantics/layout suite are host evidence, not a claim that TalkBack speech or every OEM visual surface was observed.

## Deferred cross-review concern

The U6 caller correction exposes a small menu-availability edge: `NightRecoveryControls.kt:47` still renders More unconditionally, while its remaining items require either a tracked night (re-check) or `settings.platform.lullHandover` (Request). With neither, the menu is empty. This is source inspection after the Release exclusion, not an executed test result. Proposed narrow follow-up: a default idle case asserting no empty More trigger, paired with the existing tracked/Lull tests retaining their useful actions. It was reported to root without further source edits during the verification freeze.

The previously recorded source-policy-toggle warning context concern remains in `ux-phone-cross-check.md`: a queued `PendingRetime.requestedWakeAt` can outlive a change to unknown-source acceptance; retry resolves with current settings while warning context prefers the stored time. Review-time anchors are `night/NightActions.kt:885,922,1095` and `night/RuntimeState.kt:40`. Root ran `RetimeNotificationTest.aQueuedSourcePolicyChangeWarnsAboutTheWakeActuallyPreparedForTheRetry` in the same batch, but it failed earlier with expected FAILED versus actual LOCAL, before establishing a warning-text mismatch. The runtime reviewer owns fixture diagnosis and verification; this result alone is not proof of the proposed warning-context defect. No alarm policy or runtime changes were made during this UI review.

No additional demonstrated defect was found in the reviewed palette, touch-target, fixed-header, phase-mapping, finite-motion or lifecycle boundaries. The selected pass-1 corrections are verified before merge. The combined tree still needs its own gate. A separate immutable review of incoming Temporary commit `655993d2ea3d5021a2fab75ecf7db5f77d699f7e` and its UI merge boundaries is retained at `/tmp/nocturne-gpt11-tools/temporary-ui-review.md`.

## Combined Temporary regression cycle

The first `ux-temporary-merge-red` invocation stopped at app compilation because `ProfileEditor.PhaseList` lacked the new `Block.TEMPORARY` branch. No app behavior was tested in that invocation. A mechanical null start-time branch was added before rerunning.

The subsequent `ux-temporary-merge-behaviour-red` executed **295 tests: 274 passed, 21 failed, no errors or skips**, as recorded in `/tmp/nocturne-gpt11-evidence/ux-temporary-merge-behaviour-red/selected-suite-summary.json`. Selected fresh XML confirms these intended failures:

| Finding | Executed RED | Minimal correction awaiting GREEN |
|---|---|---|
| Temporary wording repeated a running/wake claim for uncertain or manual tracking, including an open confidence change. | `TemporaryTrackingTruthTest` **4/4 failed** and `TemporaryNativeTruthTest` **4/4 failed** at their false-claim assertions. | Shared resource names the tracked profile only; both Compose and native calls pass that name. Eligibility, target/night capture, ending, cancellation and zero-effect assertions remain unchanged. |
| A valid imported Turbo Warm preset retained 15 request minutes behind a slider capped at 10. | `ImportedTemporaryDurationTest.importedTurboWarmStartsWithAnExplicitTenMinuteTemporaryPreviewAndDoesNotBecomeBoost` failed because its explicit 10-minute preview was absent. | Initial Temporary minutes are bounded by that choice's existing maximum. The saved preset and separately captured ending request retain their original minutes; Boost retains its separate 30-minute maximum. The test still checks actual confirmed Turbo-only steps, original wake/record identity and unchanged settings. |
| A Temporary interval invented reusable profile phase index 0. | `EditorPhaseSelectionTest.aTemporaryIntervalHasNoReusableProfilePhaseToEdit` expected null but received `EditorPhaseSelection(TEMPORARY, 0)`; four original mapping controls passed. | Helper returns null for Temporary. Tonight falls back to the generic profile editor; original source phase mappings remain asserted with explicit non-null checks. |
| Default idle Tonight exposed an empty More menu after Release moved to Settings. | `MoreReleasePlacementTest.idleWithoutHandoverDoesNotOfferAnEmptyMoreMenu` failed; three retained reachability/Settings controls passed. | More appears only when a tracked night or enabled Lull handover supplies an entry. Request, re-check and Settings Release/Take back behavior are retained. |

These five suites supplied **18 cases: 11 intended failures and seven retained passes**. The other two UI failures were incoming `TemporaryActionDispatchTest` fixtures: the Boost tap still searched for the obsolete simple label, and the ending-body expectation still supplied the old three resource arguments. The selector now uses the visible `boostLabel`; the body expectation supplies profile plus requested action. All actual mode, duration, night-ending, preset-capture, Off and planned-return checks remain intact.

Before any label or geometry production fix, the next cycle adds one dedicated `TemporaryTrackingTruthTest.theTemporaryAlternativeNamesStopNightAndStillRequiresItsGuardedConfirmation` assertion and three `TemporaryNativeLayoutTest` cases. The resource deliberately still says “End tonight” until that label assertion has executed. The layout cases open actual Shortcut and rendered Quick/Single widget dialog windows at 320dp/200%, checking complete scrollable native text, 48dp/56dp targets, widget button sizing, non-overlap and cancellation without writes. Neither the new label case nor the native layout cases has executed yet. Root owns the next test invocation; no GREEN claim is made for these corrections.

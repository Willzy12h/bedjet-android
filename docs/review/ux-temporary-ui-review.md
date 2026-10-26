# Temporary feature — immutable UI integration review

Reviewed commit `655993d2ea3d5021a2fab75ecf7db5f77d699f7e`, whose code is parent `a4411b1cb7fc2769d2e11d84685e3748b3f371d8`, against common base `367968bddc390e5b2bb12876fc1662ffa07e47c3`. Sources were read with `git show`; no build was run for this review. Line anchors in the findings refer to the immutable incoming tree, not the subsequent merge resolution.

## Findings and required integration work

| ID | Source and finding | Evidence boundary / regression proposal |
|---|---|---|
| T-UI-01 | `ui/NightGuard.kt:61` formats `temporary_running` from captured state even when UNKNOWN/manual becomes current; choosing Temporary while ineligible still renders this claim. `ui/TemporaryNativeDialog.kt:36–39` always renders the same running/wake sentence from the current night once, including UNKNOWN, PENDING and manual. The native sentence does not refresh while the dialog remains open. | Direct source defect matching the already reproduced guard-truth class of issue. Add actual Compose Temporary and native shortcut/widget cases for UNKNOWN/PENDING/manual and CONFIRMED→UNKNOWN while open. Preserve disabled/refused Temporary, available ending/cancel, target/identity and zero writes on dismissal. Use neutral tracked/planned text after observing RED. The existing eight `NightGuardTruthTest` passes do not cover this new resource. |
| T-UI-02 | `values/strings_temporary_ui.xml:7` labels the alternate action “End tonight”; runtime refusals also say “end tonight”. The current UX contract calls the night-ending action Stop night and has a distinct Resume confirmation. Incoming `NightGuard` lacks the current `resume` API; incoming Shortcut uses the older generic recovery labels. | Integration requirement: retain the approved Resume API/body/buttons and Stop confirmation semantics. Parent/P1 worker owns the coordinated button-copy regression and resource changes; this review does not waive RED. |
| T-UI-03 | `TemporaryActions.kt:27–42` accepts a saved Warm/Cool preset, computes maximum 10 for a Turbo mode, but retains its original minutes. Both `NightGuard.kt:48,64` and native `:25,47` clamp only the displayed slider position, not the request. `BackupValidator.kt:140–148` allows an imported Turbo action preset. `validCapturedPreset(TEMPORARY)` accepts Turbo up to 30, and `TemporaryProgramme.prepare` splits that into Turbo then Heat. An imported 15–30-minute Turbo Warm preset can therefore offer a request longer than its displayed 10-minute control maximum. | Source candidate requiring executed regression. Import/store a valid backup preset `ActionPreset(TURBO,86,100,15)`, open Warm, and require that the enabled Temporary request agrees with the 10-minute Turbo-only limit. Preserve the original saved preset, nominal Turbo maximum 10, and the positive Boost Turbo→Heat case. Do not silently report this as a reproduced transport defect before the regression runs. |
| T-UI-04 | `EditorPhaseSelection.kt` is introduced by UX and has an exhaustive `when(Block)`; incoming `Block.TEMPORARY` adds a missing branch. A Temporary interval also has no reusable profile phase index. | Definite compile/integration gap, reported to root. Use the generic profile-editor route for Temporary, retain the safe source selection for genuine Start/Middle/Wake/After-wake, and add a narrow route assertion. No false source index should be invented for Temporary. |
| T-UI-05 | Native Temporary geometry is not covered at 320dp/200% by the six `TemporaryNativeActionTest` cases: they use SDK34 with default qualifiers/font scale. The body is scrollable and SeekBar declares 48dp minimum height, but list choices and three native action buttons have no geometry assertions. | Coverage gap, not a demonstrated clipping or TalkBack defect. Add actual native window layout/scroll/target assertions or supervised device acceptance before claiming that boundary. Existing four-look 320dp/200% tests cover Compose only. |

## Boundaries that are preserved in incoming code

- `TemporaryChoice.forMinutes` copies only duration; target, night/revision and preset fields remain captured. `CapturedQuickAction` preserves a separate ending request, so changing the Temporary slider does not change the original ending action's duration.
- Compose passes the captured Temporary request to `runCaptured`; ending still enters the captured-night scope. Owner execution revalidates identity, target, limits and programme eligibility. Native confirmation passes the captured request through the service. A stale native preview can remain enabled until another interaction, but the owner still rejects its old identity; this review found no stale-write bypass.
- `remoteCaptured` additionally requires reported programme step 0 before confirming that a tracked night ended. Its two real owner tests retain the UNKNOWN/no-STOPPED-event negative case and true manual step-0 control.
- Off is excluded from `captureQuickAction` and from Remote's Temporary branch. It retains a guarded physical ending action and never gains a delayed return command. Stop tracking remains local.
- Nominal TURBO and Remote Turbo begin at 10 minutes and offer maximum 10. Boost has a separate 30-minute maximum; actual compiled Temporary prefix details show Turbo and any Heat continuation. The imported-preset case above is separate from those correct nominal routes.
- `runCaptured` is APP-origin and uses the existing view/request-bound haptic capture. Widget/native shortcut requests retain WIDGET/SHORTCUT/NOTIFICATION origin through service `entryOrigin`, and do not capture an app-view haptic request. The native test suite asserts WIDGET and SHORTCUT outcomes; no new physical haptic claim is made.
- Retained Remote status is a display cache only. Incoming `AppViewModel` stops live collection when hidden, clears freshness, clears the retained snapshot on target change, and leaves raw owner receipts unchanged. Remote requires READY plus `hasLiveStatus` before enabling its controls; “Last reported” labels the retained card.
- The feature introduces no phone resume timer or idle polling loop. The only new native listeners are dialog choice/SeekBar callbacks. Compose state is owned by its composition. Existing Activity cancellation/navigation routes remain; no new background coroutine is added by the dialog helper itself.
- New production prose is in resource XML. ProfileEditor’s incoming change is the compatible `enabled=true` parameter on `SwitchRow`; it does not replace UX’s full captured save request, phase interactions, or save/discard flow.

## Complete incoming UI test-file review

These are source/coverage observations. The incoming documentation reports its own source-specific passing runs; this review has not re-executed them on the combined UX tree.

| Test file | Complete inspected coverage |
|---|---|
| `ui/TemporaryActionDialogTest.kt` | Four actual Tonight looks, 320dp/200% via U06 fixture, selected Temporary/end choices, touch/layout bounds, complete return/schedule text, confirmation/cancel availability and unchanged active state on dismissal. |
| `ui/TemporaryActionDispatchTest.kt` | Ten cases: captured preset plus one-action duration; saved-preset changes while open; ending original preset/duration; UNKNOWN refusal; stale revision; actual Remote Temporary; mode-only Remote ending; split Boost and guarded Off; profile-limit refusal; UNKNOWN/manual return-line suppression. |
| `platform/TemporaryNativeActionTest.kt` | Six actual widget/shortcut cases: both choices, cancel/Open Tonight, native Turbo maximum, saved-preset capture, WIDGET ending and temporary outcomes, stale ending, SHORTCUT service payload and origin. No native 320dp/200% or changing-confidence wording case. |
| `ui/RemoteCapturedEndTest.kt` | Two real ViewModel/owner cases: same mode with programme step 1 is UNKNOWN and does not create STOPPED; manual step 0 confirms ending. |
| `ui/RemoteReconnectPresentationTest.kt` | Three real screen cases: retained last report with disabled controls after app switching, READY without fresh receipt remains disabled, and cached accessory state cannot confirm a fresh owner command. |
| `ui/ForegroundStatusTest.kt` | Eleven cases spanning hidden collection, retained snapshot, reconnect/disconnect freshness, target changes, one-second sensor cadence, immediate control/accessory fields, and raw receipt independence. |
| `ui/NightCurveAlternativeTest.kt` | Six semantic cases, including named Temporary curve/timeline intervals while retaining the underlying schedule, units and non-temperature mode semantics. |
| `service/TemporaryActionServiceTest.kt` | Five additional dispatch-boundary cases inspected: captured payload/origin and ending payload, missing/malformed/out-of-range input refused before promotion or writes. |

The changed `AlarmExplanationTest` hunk was also inspected; its saved-override/no-alarm case must be retained alongside the UX one/18-hour and fresh-diagnostics matrix.

## Merge resolution notes

After the immutable review, root assigned the actual four UI conflicts. They were resolved and staged, without changing the new Temporary truth/duration resources or P1 labels:

- `NightGuard` combines the typed Temporary descriptor with the existing Resume API/body/buttons, neutral non-Temporary guard copy and captured identity scope.
- `ShortcutActivity` retains the native Temporary route and origin handling plus existing Stop/Resume-specific confirmation copy.
- `RemoteScreen` retains last-reported display and disabled stale controls, Bluetooth banner, mode-limit labels, Boost spoken description, and quiet confirmed Fan feedback.
- `TonightScreen` retains the full fixed/reorderable UX layout, all-state guarded Stop, phase editing, test-run restrictions and finite markers. It adds Temporary pending/return presentation and captured Quick routes. Its marker input now also requires `hasLiveStatus`, preventing the newly retained display cache from lighting a stale active marker.
- Read-only checks of automatic merges confirmed the pre-save admission/115-second cleanup, full editor `CommandRequest`, passive curve default, P5 mode labels and `SwitchRow` disable support survive.

The incoming dispatch fixture formats `guard_running_waking` with the historical three arguments. The merged resource has two; update that expectation to name the action rather than the obsolete wake argument while preserving its actual mode/duration/ending assertions. This is a fixture compatibility correction, not removal of a behavioral check.

## Empty More regression plan

The earlier approved U6 correction removes Release/Take back from Tonight's More but leaves More visible with no entries when no night is tracked and Lull handover is off. Add a default-idle regression asserting no empty More trigger and retain the existing tracked/Lull tests proving Request and re-check are still available, plus the Settings Release→Take back positive control. Observe RED before hiding the empty trigger. No menu production change was made during the immutable review or merge resolution.

Physical BedJet return timing, firmware behaviour after physical controls, Samsung lifecycle/launcher behaviour, actual TalkBack speech, battery use, reboot/force-stop and release runtime remain unverified here.

## Read-only preparation for the second whole-diff UI review

After the merge conflict resolutions, the complete current contents of `AppViewModel`, `ProfileEditor`, `EditorPhaseSelection`, `NightGuard`, `RemoteScreen`, `TonightScreen`, `NightCurve`, `ShortcutActivity`, `TemporaryActions`, `TemporaryNativeDialog`, `HistoryScreen` and `NightRecoveryControls` were read again. The changed Temporary/reconnect resource files were also read in full. The diff from checkpoint `b1c3a81` contains no new MainActivity, SettingsScreen, ActiveIndicator or Theme production change at this preparation point. P1/widget work continues separately; this is not the final second-pass sign-off.

The earlier fixes remain present: the same bounded Bluetooth admission marker surrounds the UI save wait and owner call; editor consent carries its complete captured `CommandRequest`; Resume has its own guard body; passive History/Profile curves do not install phase handlers; Quick active markers require a live displayed status; Remote retains Last reported text with disabled stale controls; and confirmed Fan feedback remains quiet. No additional concrete production defect was found in these merge-specific reads beyond T-UI-01/03/04 and the empty-More finding already recorded above.

The first combined Temporary invocation, `ux-temporary-merge-red`, ended before app tests because `ProfileEditor.PhaseList` lacked the new enum branch. Root reported exit 1 after 61.19 seconds for both app compile variants. The only follow-up production edit was the mechanical `Block.MIDDLE, Block.TEMPORARY -> null` branch. This invocation is **not** behavioral RED evidence.

Prepared source regressions awaiting an executed result are four `TemporaryTrackingTruthTest` cases, four `TemporaryNativeTruthTest` cases, one `ImportedTemporaryDurationTest` case, `MoreReleasePlacementTest.idleWithoutHandoverDoesNotOfferAnEmptyMoreMenu`, and `EditorPhaseSelectionTest.aTemporaryIntervalHasNoReusableProfilePhaseToEdit`. The native truth class exercises ShortcutActivity and the existing PlatformActionActivity route; the forthcoming geometry fixture uses the actual rendered Glance-to-WidgetConfirmationActivity route.

### Native geometry draft and acceptance boundary

`/tmp/nocturne-gpt11-tools/TemporaryNativeLayoutTest.kt.draft` contains three unexecuted cases: actual Shortcut confirmation, actual rendered Quick widget confirmation, and actual rendered Single widget confirmation. It sets 320dp/640dp and 200% font before creating each Activity, opens the real dialog window, and does not replace it with an artificially large test measure. The assertions cover both Temporary/Stop choices, scroll reachability of every rendered text line, no ellipsis/lost characters, full visible 48dp targets for the shortcut and 56dp targets for widget controls, at least 16sp widget buttons, non-overlapping positive/Cancel buttons, and cancellation with unchanged night/status and zero writes or service submission. Widget routes also inspect dark/light Settings variants. The draft stays outside the repository until the P4 shared native helper is integrated and root authorizes its test cycle.

The final second pass should read the finished P4/helper and P1 labels together with these files, reconcile every selected fresh suite result, inspect the actual layout evidence, and then repeat the original full-diff UI/theme/navigation/editor ledger against `367968b`. Host geometry assertions must remain distinct from physical Samsung/TalkBack/launcher acceptance.

## Integrated execution and follow-up — 26 October

The findings and draft descriptions above retain their original review-time meaning. The following evidence records the later combined-tree work without rewriting that history.

`ux-temporary-merge-behaviour-red` executed **295 cases: 274 passed, 21 failed, no errors or skips**. Fresh selected XML established the eleven intended regressions: eight Temporary confidence-copy failures, the imported Turbo duration mismatch, the invented Temporary source phase, and empty More. The retained source-mapping and More/Settings controls passed. Initial corrections used neutral tracked-state wording, bounded only the Temporary preset, returned no source phase for Temporary, and hid More when it had no entries. The original ending request and saved settings were retained.

`ux-temporary-ui-native-red` executed **73 cases with 23 failures**, but several new checks did not reach their intended behavior. Seven Compose cases failed in bitmap/vector drawing because their classes lacked native graphics configuration. Four native truth cases passed their wording checks, then failed complete status equality solely because Graph's demo ticker changed `actualTemperature` (45→44, or 45→46 during manual heat). The Quick widget fixture looked for Warm on page 0, although at the declared 200% text size it is on page 1. Geometry also mixed screen and window coordinates; the partial contrast collector initially omitted bitmap/nine-patch backgrounds. These were fixture corrections, not production defect fixes or valid new behavioral RED claims. Complete status assertions were retained with an unticked injected Demo link, and contrast now samples the actual native drawable backgrounds.

After those corrections, `ux-temporary-native-fixture-red-2` executed **82 cases: 76 passed, six failed, no errors or skips**. Its selected fresh XML shows:

| Suite or case | Executed result and meaning |
|---|---|
| Four existing `TemporaryTrackingTruthTest` cases | **4/4 passed**; UNKNOWN/PENDING/manual/open-confidence wording and full cancellation state checks now pass. |
| `TemporaryNativeTruthTest` | **4/4 passed**, retaining complete status and identity assertions. |
| `ImportedTemporaryDurationTest.importedTurboWarmStartsWithAnExplicitTenMinuteTemporaryPreviewAndDoesNotBecomeBoost` | **Passed**; the real action confirms ten Turbo-only minutes, retains the original wake/record and leaves imported settings unchanged. |
| `TemporaryTrackingTruthTest.theTemporaryAlternativeNamesStopNightAndStillRequiresItsGuardedConfirmation` | **Valid RED**: the selected control's actual text was `End tonight`, expected `Stop night`. |
| `ImportedTemporaryDurationTest.anUnsupportedImportedTurboEndingIsExplainedAndDisabledWithoutAdvertisingFifteenMinutes` | **Valid RED**: the actual ending confirmation remained enabled. `validCapturedPreset(WARM/COOL, Turbo)` rejects that original action, so the UI must not advertise it as executable. |
| `TemporaryNativeLayoutTest` | Four failures remain assigned to the native UI worker for geometry/contrast analysis. They are not counted as a successful layout or accessibility acceptance. |

The proven Stop option now uses the approved label. `TemporaryChoice.canEnd` captures validation of the **original ending command and preset**, before Temporary duration normalization. The shared `temporaryEndRefusal` uses the existing invalid-action resource; `requestedEndDetail` omits unsupported ending details. Compose disables and guards the positive callback for an ineligible ending while leaving the eligible ten-minute Temporary choice available. The native worker consumes the same immutable eligibility. Owner validation and original ending snapshots are unchanged. Verification of this ending correction is pending.

The existing Stop-label test now also aggregates three related copy checks: `runtime_temporary_too_close`, `runtime_temporary_too_many_steps`, and `temporary_native_end` must use Stop action language. Those three resources intentionally retain their old wording until the new assertion has executed. Incoming native/Compose fixtures were updated for the proven Stop label and distinguish the selected choice from the positive button; mode, duration, capture, Off, ending and cancellation checks remain intact.

### Ending eligibility GREEN and explanatory-copy RED

`ux-temporary-reviewed-green-native-red` executed **76 cases: 69 passed, seven failed, no errors or skips**. Selected fresh XML records:

| Suite | Tests / failures | Interpretation |
|---|---:|---|
| `ImportedTemporaryDurationTest` | **2 / 0** | Both the actual ten-minute Turbo-only Temporary action and unsupported-ending refusal/disablement pass, including unchanged saved settings and zero ending effect. |
| `TemporaryActionDispatchTest` | **10 / 0** | Captured presets/durations, original ending action, stale identity, Remote ending, Boost continuation, guarded Off and return truth all remain intact. |
| `TemporaryActionDialogTest` | **4 / 0** | All four Compose looks retain the Temporary/Stop choices, layout and cancellation checks. |
| `TemporaryNativeTruthTest` | **4 / 0** | All native confidence/manual wording and complete cancellation-state checks pass. |
| `TemporaryTrackingTruthTest` | **5 / 1** | The four original truth cases pass. The Stop-label case passes the actual option-label assertion, then reaches the new aggregate wording assertion. |
| `TemporaryNativeActionTest` | **6 / 0** | Existing native capture, effect, cancellation and entry-origin controls pass. |
| `WidgetConfirmationTest` | **17 / 0** | Actual widget confirmation and delivery controls pass. |

The aggregate failure explicitly lists all three old explanatory strings, establishing valid RED for this final copy correction. `strings_temporary_runtime.xml:10–11` now offers a shorter/other duration or “stop the night.” `strings_temporary_ui.xml:17` now states: “This will stop the night and run the action. The night will not resume automatically.” These are resource-only changes; the native helper, eligibility and owner behavior are unchanged. The corrected wording still awaits its next executed GREEN.

The other six failures in that invocation were four native geometry/minimum checks assigned to the native UI worker and two `RecoveryNotificationActionsTest` timing-fixture checks assigned to root. This invocation is not a full GREEN gate. Native device, Samsung, TalkBack and lifecycle acceptance remain separate from these host results.

### Native layout, saved-preset and idle-capture follow-up

`ux-native-and-recovery-green` then executed **76/76 passing cases, no failures/errors/skips, 257.74 seconds**. The fresh selected app reports include native layout **5/5**, Temporary tracking/aggregate Stop copy **5/5**, imported Temporary duration/ending **2/2**, Compose dialog **4/4**, dispatch **10/10**, native truth **4/4**, native action **6/6**, widget confirmation **17/17**, and recovery notification **2/2**. The native worker inspected the actual-window captures; this closes the host minimum-size, scrolling, complete-text and contrast failures recorded above, without claiming physical launcher or TalkBack acceptance.

The complete-file review then found native fallback clipping, night-qualified static preview contrast, a misleading imported-preset summary, and idle action capture loss. Their executed sequence remains distinct from fixture repairs:

| Run | Fresh selected result | Behavioral evidence |
|---|---:|---|
| `ux-final-presentation-red` | **9 total, 6 passed, 3 failed**, 92.61 seconds | Native fallback title/action text clipping and night preview contrast **1.074:1** are valid RED. The new Settings case stopped at an incorrect normal-width tab selector, so this invocation provides no Settings behavior RED. |
| `ux-last-capture-boundaries-red` | **29 total, 23 passed, 6 failed**, 202.86 seconds | Corrected native layout **7/7**, static widget previews **4/4**, and recovery notification **2/2** pass. The Settings case reaches its unsupported-reason assertion after the explicit Heat repair control passes. Actual idle Shortcut approval reviewed Turbo10 + Heat5 but programmed Turbo10 + Heat20 after a Settings change. Two local Stop-tracking identity cases also fail as intended. The two app idle Quick cases stop in fixture waits before their behavioral assertions. |
| `ux-idle-capture-red-local-stop-green` | **9 total, 7 passed, 2 failed**, 127.21 seconds | Imported Settings/Temporary **3/3** and local Stop tracking **4/4** pass. Idle imported Warm reaches valid RED because packets were sent before refusal. Queued app Boost times out after admission and does not supply an actual-duration RED. |
| `ux-timed-policy-red-capture-green` | **38 total, 30 passed, 8 failed**, 200.66 seconds | Idle app capture **2/2** and actual native action **7/7** now pass, including the compiled Turbo10 + Heat5 result. Imported Settings/Temporary **3/3**, Stop tracking **4/4**, native layout **7/7**, widget previews **4/4**, and recovery notification **2/2** remain GREEN. Four Settings role cases and four timed-cap cases reach intended RED; the timed Turbo10 positive control passes. |

The app idle fixture first had to establish Demo mode before `Repository.restore`, which intentionally retains the current hardware selection. Its queued-Boost waiter also advanced the paused Android main looper by only one millisecond per polling interval, starving the existing 1.5-second programme refusal grace. It now advances that looper by measured elapsed time. Neither fixture timeout is described as a captured-duration RED. The coherent capture correction was authorized by the actual imported-Warm zero-write failure and native Boost duration failure: `captureQuickAction` keeps the original preset in its idle ending request, and Shortcut uses that same reviewed request for its approval and service submission. All actual mode, duration, one-start, saved-setting, origin, cancellation, status and history assertions remain intact.

The first Settings correction used the Quick WARM validator for every saved-action editor. `ImportedPresetRoleSettingsTest` proves why that was too broad: for each Snooze Warm/Cool and Extend Warm/Cool role it first executes the actual timed Turbo10 command successfully, then checks the real Settings presentation. All four cases reach the final aggregate assertion: the timed summary is missing and the whole supported action is incorrectly called unsupported. The two Extend cases additionally report that the separate Quick-button restriction is absent. Each case also retains unchanged saved-preset, generation, history and active-night assertions.

The subsequent minimal presentation correction gives only the Extend editors their shared Quick command. Every editor uses `timedPresetRefusal(context, preset)` for the manual Turbo cap; this formats `runtime_turbo_duration_limit` from `Limits.TURBO_MINUTES`, with no UI copy of the limit. An over-cap action keeps the existing unsupported explanation plus the precise cap reason and omits the executable summary. Supported Turbo10 retains its ordinary timed summary; Extend additionally uses the resource-backed Quick-only explanation, while Snooze inherits no Quick warning. No saved preset is normalized on viewing. The existing imported Turbo15 test and its explicit Heat repair control are unchanged.

Complete current reads of `SettingsScreen`, the shared capture/helper changes and the new regression fixtures found no additional concrete presentation defect in this correction. The new explanatory text is in `res/values`, has no line limit or fixed-height container, and remains within the existing vertically scrollable Settings layout. The four role and timed-cap production fixes await the next executed GREEN at the time of this entry. The final full integrated gate is separate and remains owned by root.

### Captured actions and role-aware Settings GREEN

`ux-final-capture-and-timed-policy-green` completed **38/38 passing cases, zero failures/errors/skips, 204.16 seconds**. The nine fresh selected suites are `IdleQuickCaptureTest` **2/2**, `ImportedTemporaryDurationTest` **3/3**, `ImportedPresetRoleSettingsTest` **4/4**, `TimedPresetLimitTest` **5/5**, `TemporaryNativeActionTest` **7/7**, `StopTrackingCaptureTest` **4/4**, `TemporaryNativeLayoutTest` **7/7**, `WidgetProviderTest` **4/4** and `RecoveryNotificationActionsTest` **2/2**. Root verified that all **355 non-document source hashes** remained unchanged through that run.

The four Settings role assertions now pass after their actual timed Turbo10 controls, while the imported Turbo15 explanation, unchanged saved data and explicit Heat repair controls remain GREEN. Actual app/native captured Boost delivery remains Turbo10 + Heat5 after a later Boost30 setting; invalid idle Warm performs no write. The timed owner refuses over-cap Turbo before transport or state mutation and preserves its supported ten-minute behavior. This closes the focused presentation/capture findings above; final full-gate and physical acceptance claims remain separate. All repository edits are frozen after this evidence entry for root's checkpoint publication.

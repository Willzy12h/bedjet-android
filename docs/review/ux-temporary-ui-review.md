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

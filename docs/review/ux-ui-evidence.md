# UX UI implementation and verification

Base: `367968bddc390e5b2bb12876fc1662ffa07e47c3`. This evidence covers the isolated UI patch. The integration worker owns the complete gate and the final APK.

## Scope and shared interfaces

| Work | Shared consumer | Decision |
| --- | --- | --- |
| U1 Tonight collapse/order | Core Settings and existing AppViewModel.updateSettings | Backward-compatible settings fields; normalise duplicate, unknown and missing section IDs before rendering. No new command or transport owner. |
| U2 fixed wake | AlarmExplanation, runtime alarm worker | Preserve the existing explanation/target callback, pending/unknown precedence, and manual-run end wording. |
| U4 fan feedback | Root RemoteScreen/AppViewModel work | Root owns successful fan-set feedback suppression, retaining failures/unknown outcomes and haptics. |
| U6 Settings release | Root NightRecoveryControls work | Reuse the existing handover operation, capture target, and result reporting. Root removes the release item from More while retaining other handover actions. |
| U7 mode maximums | Root protocol/Remote work | Read Mode.temperatureRange; never duplicate Dry or other protocol limits in the UI. |
| P8/P9 themes | Runtime worker Settings test subsection | UI changes stay in Appearance and BedJet handover. Runtime owns Alarms and the short-test subsection. |

## Design decisions

- Quick controls remain outside the reorderable section list. Their collapse choice is saved.
- The wake header stays outside the Tonight scroll container, with the honest source/status explanation still available.
- Drag handles support real drag/drop. A compact reorder view makes long sections practical to move; Move up/down provides an accessible alternative.
- Phase controls are disclosed from compact rows using the Simple timeline vocabulary. Adding a phase expands its draft controls; the repository is unchanged until Save.
- Active markers use a brief finite pulse, then remain static. Motion is cancelled when hidden, paused, screen-off, or system animations are disabled.
- Only normal Daylight heating tints change. Simple view and Midnight Ink retain their existing mode colours; contrast remains a release gate.

## Observed verification

Each run uses `/workspace/scratch/93c42f112bf4/run_gradle.py`, the isolated `ux-ui` checkout, the GPT-11 offline cache and shared serial lock. Results live in `evidence/<label>/gradle.log`, `result.json` and `app/`. Only requested suites from that run count: copied result directories can contain stale suites. No hardware acceptance is claimed.

- `ux-ui-red-1`: test compilation stopped before execution (two test geometry accesses used unsupported DpRect conveniences). Corrected the harness to use left/right edges. This is not counted as assertion RED.
- `ux-ui-motion-red`: invalid aborted harness run. Freezing Compose's clock before `performScrollTo()` prevented that operation settling. The saved thread dump established the wait, and only this checkout's test worker was stopped. The harness now scrolls before freezing and restores auto-advance after each test. No completed test counts are claimed for this run.
- `ux-ui-red-2`: 23 executed, 22 failed, one passed. Valid missing-behaviour failures covered Quick placement/collapse, real reordering, fixed wake, shifting queued-work UI, active markers and Daylight heat colour. Four editor cases used a compact-navigation selector at normal text size; seven Settings cases had malformed resource qualifiers; the paused-screen assertion queried a hierarchy no longer registered while paused. Those harness failures are excluded from behaviour evidence.
- `ux-ui-red-3`: 23 executed, 13 failed, ten passed. Missing confirmation/Settings release (six), missing compact editor/curve interaction/max labels (four), hidden Tonight alarm polling (one), and malformed saved section order (one) were observed before those changes. One pulse assertion used untransformed layout bounds; the geometry measurement was corrected to the marker's transformed semantic bounds.
- `ux-ui-green-1`: 78 executed, 12 failed, 66 passed. Contrast (five), curve text alternatives (four), Tonight completeness (six), and both palette cases passed. Six existing Simple Quick queries now also matched newly editable timeline rows, so they were scoped to the Quick section without changing layout assertions. The new tests exposed six harness issues: queued broadcast delivery with a frozen clock, the wrong outside-touch event, Resources not updated before a system-configuration callback, a fixture-zone clock expectation, two identical mode chips, and an async settings save asserted before persistence. The exact parameterised U06 editor method filter selected no cases; later runs use a trailing wildcard and require the suite to be present.
- `ux-ui-green-2`: 105 executed, eight failed, 97 passed. All 40 U06 primary cases and all eight requested U06 editor cases passed, as did the four existing timeline geometry cases. New valid RED covered four ambiguous/configured Quick-marker cases and repeated long-press of the same source phase. Two existing normal-theme wake-label cases exposed the omitted source label; it is restored in the plan card. The remaining system-dark harness case also needed Android's view-hierarchy configuration dispatch, not just updated Resources and an Activity callback. Outside, Back, Cancel and the screen-off marker assertions passed.
- `ux-ui-green-3`: 115 executed, two failed, 113 passed. The two newly added regressions observed RED: changing the configured Boost duration kept the old programme marked active, and a curve long-press retained the previous profile callback when the curve itself was unchanged. All previous failures passed, including current System mode, source labels, repeated phase focus, all active-marker ambiguity cases, and the four real-compiler source mappings. All 44 U06 primary cases and eight U06 editor cases passed. The four new long-owner-label cases retained complete visible text (no ellipsis or lost characters), full semantics and unchanged Quick geometry at 320 dp / 200% text.
- `ux-ui-green-4`: 40 executed, ten failed, 30 passed. All nine active-marker cases passed, including duration changes. The new reduced-motion owner-progress assertion observed RED because the static fallback announced a made-up 50%; it now draws a centred static segment with indeterminate semantics. The profile callback case still failed: the unchanged local function reference also kept the upstream callback stale, so the caller now supplies a lambda with its actual captures. Eight opt-in capture cases still aligned the final Quick row under the old below-plan layout; their setup now scrolls the entire Quick section into view, retaining every assertion that all four buttons must be completely visible in the same screenshot.
- `ux-ui-green-5`: **50 executed, zero failures, zero skipped**, Gradle exit 0 in 96.79 seconds. Fresh requested suites: ActiveIndicatorTest 9, TonightLayoutTest 9, ProfileEditorInteractionTest 6, EditorPhaseSelectionTest 4, NightCurveAlternativeTest 4, TonightCompletenessTest 6, long-owner U06PrimaryLayoutTest 4, U06NormalCaptureTest 4 and U06LargeTextCaptureTest 4. This verifies the latest-profile callback, changed Boost duration and reduced-motion indeterminate-progress fixes, plus all four looks in the normal and 200% capture cases. Taking the latest result per requested testcase from green-3 and green-5 yields **124 distinct passing cases**, with no remaining failed/skipped case; this is combined focused evidence, not a claim that one run executed 124 tests.

Native graphics captures from the final run are under `app/build/screenshots/ux-ui-final-verified/u06/`. Visual inspection covered normal Daylight Tonight, 200% Daylight Quick controls, the expanded Midnight phase editor, and the 200% Simple Daylight longest owner/queue message. All four Quick controls fit together in their dedicated view; the owner/queue message is complete; the expanded editor exposes timeframe, model-backed limits and sliders. These isolated screenshots still use the base Boost wording and Dry limit; the root P1/Dry changes supply those integration values. The reserved owner area intentionally stays present when idle so command progress cannot move the controls. Source review and `git diff --check` found no remaining defect in the owned patch. Root review and the full integrated gate remain separate.

Additional source mapping tests use the real PlanCompiler, including split Turbo phases, front-trimmed Wake, end-trimmed Start and adjacent phases with the same mode. A revised running Start block opens the ordinary profile editor, because its original phase index cannot be proven from the remaining programme alone (including a retime within the same minute).

Quick activity is deliberately conservative. A ready report must match the configured manual preset's mode, target, fan and possible remaining duration; identical matching presets do not identify a unique action. A tracked programme uses its night-phase marker. Quick Boost requires retained confirmed `startRequested` slot metadata for this target and matching live programme progress. IR-06 can clear that journal after a displaced night ends, so the Boost marker then stays static. No journal is retained solely for animation, and these markers do not manufacture a command receipt.

## Source and regression map

Paths below are relative to the repository. Line references describe this isolated patch before integration.

| Requirement | Source | Executed regression coverage |
| --- | --- | --- |
| U1 saved Quick collapse and section order | `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/Settings.kt:59`; `ui/TonightScreen.kt:349`; `ui/TonightSections.kt:47` | `TonightLayoutTest` collapse/persistence, actual long-press drag, Move up/down and duplicate/unknown IDs. |
| U2 fixed wake and truthful explanation | `ui/TonightScreen.kt:88,158,318` | Fixed bounds while scrolling; existing eight `TonightWakeLabelLayoutTest` cases, four timeline geometry cases and 44 U06 primary cases. Pending/manual/unknown text remains tied to existing runtime confidence. |
| U3 compact editor, add, curve details and phase entry | `ui/ProfileEditor.kt:83,236`; `ui/NightCurve.kt:67,247`; `ui/EditorPhaseSelection.kt:13` | Six `ProfileEditorInteractionTest` cases, four real-compiler source mapping cases, latest-profile gesture regression, all eight U06 editor cases. MainActivity's source-selection handoff is an integration task below. |
| U5 stable owner progress | `ui/TonightScreen.kt:271` | Queued work keeps Quick coordinates; longest real owner label plus queued count has no lost characters or ellipsis at 200% in all four looks; reduced-motion state stays indeterminate. |
| U6 Settings handover | `ui/SettingsScreen.kt:152`; `ui/features/FeatureSections.kt:102` | Real Settings Release/Take back path tested; More removal belongs to root integration. |
| U7 model-backed maximums | `ui/ModeLabel.kt:13`; `ui/ProfileEditor.kt:332` | Heat40, Turbo43, Gentle33.5, Cool31 remain separate from the names; 18 editor sliders retain exact C/F states and accessible values. Root owns Dry's model correction and Remote labels. |
| U8 finite visible motion and idle cadence | `ui/ActiveIndicator.kt:39,68,86`; `ui/QuickActivity.kt:20`; `ui/TonightScreen.kt:101` | Nine real active-marker cases cover finite settling, remove animations, screen-off/paused lifecycle, configured presets, ambiguous manual/programme modes and changed Boost duration. Paused Tonight does not poll alarms and refreshes on return. |
| P8 light-theme warning/cancellation | `ui/SettingsScreen.kt:83,91,105` | Seven `LightThemeConfirmationTest` cases exercise Switch, Cancel, outside touch, Back, current System light/dark and Settings Release. |
| P9 Daylight heat colours only | `ui/Theme.kt:97,103,179` | Two palette cases plus all five contrast tests; Simple retains original heat colours, normal Daylight uses amber/orange-red, Cool/Dry and Midnight Ink remain unchanged. |

`ui/` above means `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/`; all UI test classes are under the corresponding app test package.

## Integration contract

Root owns MainActivity. Add a backward-compatible nullable selection to its existing saved draft, preserve parked edits, and pass the selection to the editor:

```kotlin
@Serializable
private data class ProfileDraft(
    val original: Profile,
    val edited: Profile,
    val selection: EditorPhaseSelection? = null,
)

// Immediately after the existing `edit` lambda:
val editPhase: (Profile, EditorPhaseSelection) -> Unit = { profile, selection ->
    edit(profile)
    draftJson = draftJson?.let { encoded ->
        val saved = Json.decodeFromString<ProfileDraft>(encoded)
        Json.encodeToString(saved.copy(selection = selection.takeIf { saved.edited == profile }))
    }
}

// Add to the existing calls:
ProfileEditor(/* existing arguments */, initialPhase = draft.selection)
TonightScreen(/* existing arguments */, onEditPhase = editPhase)
```

Further root integration points:

- Keep the Bluetooth banner above Quick, and preserve the root Stop night label/guard wording. This layout already keeps guarded Stop night outside the confirmed-only current-step text.
- Call `PhoneHandoverMenuItems(..., includeRelease = false)` in More; Settings already uses `asSettings = true` and the same owner/service handover.
- Add the root Boost description semantics to the Quick Boost button. Its visible label already uses `remote_boost`; the root resource/helper supplies the accurate Turbo wording.
- Preserve the alarm worker's Settings Alarms/test subsection. Prefer `runtime.retime?.requestedWakeAt` before its older `alarmAt` fallback when merging the requested-wake display.
- The running heading falls back to the applied snapshot's profile name, so the generated short test's supplied name appears even though it is absent from the profile repository.
- Root adds descriptor fixtures for both new Settings fields, `EditorPhaseSelection`, and MainActivity's nullable draft selection, then runs the actual navigation RED/GREEN and the full integration gate. This patch does not change shared release fixtures or the protocol.

## Device checks still required

Real phone drag ergonomics, TalkBack announcements, system remove-animations behaviour, brightness perception and BedJet handover remain device checks. JVM/Compose evidence establishes the implemented paths and geometry, not physical BLE or OEM behaviour.

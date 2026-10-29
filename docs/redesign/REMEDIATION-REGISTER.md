# Nocturne redesign remediation register

> **Current correction round:** see [INDEPENDENT-FIX-REGISTER.md](INDEPENDENT-FIX-REGISTER.md) for the independent findings, fixes and new verification. The results and capture files below record the original `146501a69814f28d3879ca835bb93d2442b455a1` review base (application source `c9704ee`). They are historical evidence, not verification of the new fixes or a hardware sign-off.

Starting branch: `gpt/nocturne-redesign-final-review`.
Starting SHA: `e4be250fb9f340800fa89aea8e358d5bddbf5054` (remote fetched and verified).
Working branch: `gpt/nocturne-redesign-remediation`.

This is the completed remediation record, closed by the exact clean gate and separate native captures below. It is the implementer's evidence and self-review, not independent sign-off.

## Baseline and environment

| ID | Severity/type | Feature/file | Reproduction | Cause | Action/commit | Verification |
|---|---|---|---|---|---|---|
| ENV-01 | Environment | Local Gradle/Git execution | Initial Git fetch and Gradle bootstrap | Restricted process cannot open the sockets needed by Git and Gradle workers | Authorised commands run with reviewed socket access; repository settings unchanged | `git fetch origin --prune` exited 0; remote start remains e4be250 |
| ENV-02 | Environment | Copied Gradle transform cache | Exact gate before tests: unreadable transform metadata at `7fddc756b2f9c6a981b6731e7e469949` | A concurrent environment cache-copy process modified Gradle transform files during execution | Use an isolated `/tmp` Gradle cache outside that synchronised workspace | Fresh baseline reached all 1,708 app cases |

## Assumptions

- The approved design supersedes pre-redesign control placement. Obsolete selectors move to current entry points; behavioural and accessibility coverage remains.
- The owner's explicit whole-app ownership and work-alone instruction supersedes older Claude-only files and skill suggestions to delegate.
- Physical BedJet, Samsung, installed widgets, device battery behaviour and actual TalkBack remain untested unless subsequently performed.

## Findings and verification

The complete baseline has been reproduced and all 207 failures classified. Final closure is tied to the clean gate and individual case reconciliation, not merely a focused retry.

### ENV-03 — verification process memory (high; environment)

The clean baseline compiled successfully, but the Gradle daemon disappeared as the test workers started. The 8 GiB cgroup recorded two OOM kills; the separate Kotlin daemon retained 2.3 GiB with a 4 GiB heap, alongside Gradle and three test workers. No test result is inferred from this aborted run. The dedicated scratch Gradle user home now limits the Gradle heap to 2 GiB and runs Kotlin compilation in process; the requested command and all test cases remain unchanged. Evidence: `baseline-clean-cache.log` and its exit-1 receipt.

## Reproduced source baseline

The exact requested command at the starting SHA exited **1**: core **313/313** passed; app **1,708 total, 1,460 passed, 207 failed, 0 errors, 41 opt-in capture skips**. Lint was run separately because the app test failure stopped the gate; it exited **0** with **22 warnings and 4 hints**, with the existing broad resource suppression still present. The complete list of 207 failing cases is [baseline-failures.csv](evidence/remediation/baseline-failures.csv). All 207 cases now match executed GREEN cases in the final clean gate; historical counts were not used as the baseline.

The first migrated test compile exposed a helper typed as `ComposeContentTestRule`, incompatible with `createEmptyComposeRule`. It was corrected to the shared `ComposeTestRule`; this compile failure is not regression RED evidence.

### DEP-01 — API 31/33 Robolectric cache (high; test infrastructure)

Six legacy health containment/security cases could not start because their instrumented Android dependencies were missing. The official `12-robolectric-7732740-i7` and `13-robolectric-9030017-i7` JARs and POMs were downloaded and verified against Maven Central SHA-512 files, then installed in the local Maven cache. Neither SDK coverage nor cases were removed. `prepare-offline-verification.py` and `robolectric-sdk-pins.json` reproduce the provisioning in commit `06ee117`. The retained six API 31/33 cases passed in `tooling-trusted`; the expanded alarm identity suite passed all 24 API 31/33/34/36 cases in `focused-render-harness-green`.

## Regression RED before production changes

All runs in this section used the original production sources at e4be250 with migrated selectors or new regression tests. Receipts include the working source-tree digest where available. Test harness changes dismiss the actual approved floating feedback surface before tapping a covered control; they neither sleep nor change production feedback behaviour. The `DemoImportPreviewInvalidationTest` is untouched and all four of its baseline cases passed.

| ID | Severity | Feature / affected source | Reproduction and root cause | Intended remediation | RED evidence |
|---|---|---|---|---|---|
| UI-01 | High | Tonight state / TonightScreen, TonightParts | Saved unknown or manual night was announced as running; disconnected quick tiles lacked disabled semantics | Derive running name and enabled state from confirmed owner/device evidence | RemediationStateSafetyTest, focused-red-compiled (3 failed) |
| UI-02 | Medium | Quick command feedback / TonightParts, AppViewModel | UI can miss brief busy emissions; unrelated 15-second fallback retains Sending after completion, and can expire while queued | Clear pending by identity when its own submitted operation finishes; retain existing command owner | QuickPendingStateTest completedPresetClearsSending, pending-red (1 failed); ActiveIndicatorTest (6 failed) |
| UI-03 | High | Editor / EditorParts, ProfileEditor | Two-neighbour drag reorders during the gesture and loses the intended target; new numeric steppers lost range/unit semantics | Commit the measured row displacement once on release; restore adjustable semantics and full spoken mode names | ProfileEditorInteractionTest and AccessibilitySemanticsTest baseline / focused-red-compiled |
| UI-04 | High | Large text / EditorParts, ProfileEditor, ProfilesScreen | Fixed one-row layouts clip modes, middle temperature, profile actions; Add step body has no scrolling | Adapt columns and stack value controls at large text, allow full labels and sheet scrolling | EditorLargeTextReachabilityTest, editor-large-red (6 failed); ProfilesEditHitTest focused-red-compiled |
| UI-05 | High | Rendered contrast / TonightParts, EditorParts | Overcast caption 3.185:1; small Start text 3.318:1; lifted editor row 1.373:1 | Use legible palette ink and approved button typography; use a light lifted surface in Overcast | RedesignTextContrastTest focused-red-compiled and focused-red-harness, actual rendered text/layout and native View.draw |
| UI-06 | Medium | Navigation / MainActivity | Setup request replayed on activity recreation after being consumed | Save the handled request counter alongside the navigation state | P8ConsumedSettingsRequestTest setup recreation, focused-red-harness |
| UI-07 | High | Remote / RemoteScreen | Reconnect/Bluetooth presentation removed from Remote | Restore existing status and Bluetooth command entry points | BluetoothBannerTest / RemoteReconnectPresentationTest original baseline |
| UI-08 | Medium | Profiles preview / ProfilesScreen, EditorParts | Compact preview removed full spoken wake/fan path; short-test profile identity absent from compact card | Preserve visual design and restore complete accessible description and test identity | TonightAcceptanceTest, QuietMiddleDescriptionTest, P8MiddleSummaryTest, ShortTestTonightIntegrationTest baseline / focused-red-compiled |

These rows preserve the original RED evidence. Their production fixes, commits and final GREEN closure appear below. Test migration retains behaviour (saved drafts, alarm fallback, target capture, wake edits, limits, confirmation and persistence) through the current four tabs, Tonight Change and Remote link. Old section-collapse/reorder and persistent global banner expectations are obsolete under the approved compact design.

## Review findings after the first remediation pass

These are implementer self-review findings, not Claude's independent sign-off. Exact raw logs and XML are retained in the delivery evidence. A focused GREEN is provisional until the final complete gate.

| ID | Severity | Feature / files | Reproduction / root cause | Remediation / verification so far |
|---|---|---|---|---|
| DATA-01 | High | Profiles Import; Repository, ImportPreview, AppViewModel | `ProfileFileFlowTest` (two RED cases in review-red): importing a shared profile used whole-backup restore, deleting unrelated profiles and replacing personal settings/history/originals | Keep the existing schema and import admission/fingerprint/transaction owners; Profiles merges by ID and writes only profiles after a pre-import snapshot. Settings still offers full restore. Five file-flow cases GREEN in tooling-trusted |
| DATA-02 | High | Share profile; ProfilesScreen, ProfileShareRequest, Repository | Three RED file-flow cases: a later edit substituted the selected profile, Activity recreation lost the pending selection, and an old result crossed a demo round trip | Save the captured encoded profile with the document request; validate the real-session revision at delivery. No later profile lookup; cancellation propagates. All three GREEN in tooling-trusted |
| UI-09 | Medium | AppViewModel busy lifetime | `QuickPendingStateTest`: the first completion cleared busy while a second request was still queued/owned | Count admitted UI requests, decrement on completion including cancellation; keep each preset's own completion identity. All three pending tests GREEN in tooling-trusted |
| UI-10 | High | Home mode labels, editor values, Middle plot | tooling-trusted RED: Overcast large Heat value 2.505:1, small reported/pending Heat 2.167:1; two large-text Middle end-time labels lost lines | Preserve palette literals; use ink fallback for insufficient text contrast. Keep proportional bars and put complete boundary-time/temperature labels below narrow bars. GREEN in `self-review-green-manifest`; all contrast and editor layout regressions also passed in `controls-middle-green` |
| UI-11 | Medium | Profiles weekday dots | review-red and tooling-trusted: a 28dp fixed dot clipped a 29dp rendered glyph at 200% | Allow minimum rather than fixed dot dimensions, keep compact line height and full spoken weekday/on-off meaning. GREEN in tooling-trusted |
| UI-12 | High | History cards / check-in dialogs | Self-review found fixed one-row profile/time and comfort/issue layouts with maxLines=1; rendered regression tests prepared before production changes | Six `HistoryLargeTextReachabilityTest` cases failed in `self-review-red-compiled`; flow layouts, complete names and scrollable dialogs made all six GREEN in `self-review-green-manifest`. |
| UI-13 | Medium | Remembered BedJet row / SetupFlow | Two stacked 10% tint layers produce 19% rather than the approved 10% | Actual two-layer pixel RED in `manifest-tint-red-compiled`; remove the redundant background. All four tint-strength tests, each checking seven palettes, GREEN in `focused-render-harness-green` |
| UI-14 | High | Settings connection summary | READY transport alone is labelled Connected, unlike Tonight's receipt-backed state | RED in `self-review-red-compiled`; reuse receipt-backed `headerConnection`. GREEN in `self-review-green-manifest` |
| UI-15 | Medium | Tonight status / large-text control position | Four U06PrimaryLayoutTest RED cases in full-remediation-first: replacing a wrapped status with short Sending moves the quick controls at 200% | Preserve the status line's measured space during owner progress; all four cases GREEN in `self-review-green-manifest` |
| UI-16 | Medium | Turbo phase accessibility | P8LegacyTurboDisplayTest and P4EditorBehaviourTest RED: row's spoken summary omits fixed effective temperature because Turbo is not an editable-temperature mode | Add the effective fixed temperature to the resource-backed spoken description, preserving the approved row, stored legacy phase and read-only sheet; GREEN in `self-review-green-manifest` |
| TEST-02 | Test harness | Navigation, colour consent, receivers, feedback | The broad suite exposes ambiguous unscoped Tonight matches, uppercase heading selectors, wrong Turbo suffix, hidden light-theme consent and tests dismissing the feedback they then inspect | Keep state/persistence/accessibility assertions through scoped current entry points and deterministic delivery; all current-case mappings GREEN in the final gate |
| WARN-01 | Build / lint | Gradle, Android resources, adapters, test shadows | Original baseline: 99 compiler warnings and 10 Gradle diagnostic instances; unsuppressed lint: 60 warnings + 3 hints | Remove broad lint.xml; modern APIs/plurals/state, unused resource removal, current compatible AGP/Robolectric and JVM 17 compilerOptions. Individual inventory in warnings.csv; final lint/compiler/build status clean, with every narrow exception documented |

The initial navigation contrast readings were made while the Activity correctly retained the previous dark palette behind light-theme confirmation. Those readings are not valid Overcast RED evidence. The corrected test completes the actual consent flow before measuring the rendered palette and passes in the final complete gate.

All API 31/33 legacy security cases passed in tooling-trusted. Navigation restoration passed on both API 34 and the added API 36 runtime. `DemoImportPreviewInvalidationTest` remains unchanged and enabled, with all four cases passing in the focused run.

## Test classification and retained assertions

- **TEST-01 — obsolete entry points/selectors (medium; tests):** four-tab navigation, the Tonight Change chooser, whole profile cards, tagged More/New actions, current sheet controls and resource-backed captions replace removed buttons/text nodes. The CSV identifies each old expectation and the retained state, identity, persistence, alarm, cancellation or accessibility assertion. Fixes are in `4c2dc19` and `fbb2a6a`; no removed UI was reintroduced solely for a selector.
- **TEST-02 — deterministic fixture ownership and interaction (medium; tests):** GraphLifecycle owns graph shutdown; native palette readings complete the real consent flow; pointer tests dismiss an actual overlapping feedback bubble or scroll a current control into view. A test that specifically examines feedback retains it. These are test/harness changes, not production delays or disabled assertions. Fixes are in `fbb2a6a`, with the merged-manifest support in `06ee117`.
- **TEST-03 — approved intentional design changes (medium; tests):** removed section collapse/reorder and fixed global headers no longer define the UI. Updated cases verify that legacy stored preferences cannot hide current card/wake/quick/Remote actions, the compact order and detail expansion are usable, and all six approved quick presets remain reachable. Remote is tested through its Tonight route. Stored compatibility fields remain. Fixes are in `4c2dc19` and `fbb2a6a`.

Every reproduced baseline case keeps its original identifier and has a current-case mapping in the CSV. The final full gate matches all 207 cases to executed GREEN assertions. An obsolete label alone is not classified as a functional defect; genuine layout, state or accessibility defects exposed after entry-point migration remain separate UI register items.

## Second self-review: rendered states and complete manifest

| ID | Severity | Feature / files | RED and root cause | Fix / GREEN evidence |
|---|---|---|---|---|
| DEP-02 | High (verification fidelity) | AGP / app unit-test manifest | `PlatformManifestTest` failed on API 31, 33, 34 and 36 in `manifest-tint-red-compiled`; Robolectric's parsed test manifest lacked the five app receivers even though the packaged application manifest contained them. `AutoStartDeliveryTest` also could not receive the manifest-declared clock broadcast. | Feed the final application merged manifest through AGP's public generated-manifest source API, stripping only the root package attribute. This keeps variant security overlays and avoids replaying source-only merger directives. Eight manifest cases and auto-start delivery GREEN in `self-review-green-manifest`; eight manifest cases GREEN again in `focused-render-harness-green`. |
| UI-17 | High (accessibility) | Navigation, History and active quick markers | Correctly accepted Overcast consent reproduces unselected navigation at 3.185:1, active Turbo marker at 2.333:1 and History issue text at 3.308:1 (`self-review-red-compiled`). Earlier measurements behind light-theme confirmation were invalid harness readings. | Use existing legible ink for navigation; choose a legible foreground against the actual History/active fill without changing protected palette literals. Navigation/History GREEN; quick marker verification also exposed UI-18. |
| UI-18 | Medium | Quick evidence after Boost / QuickDefaultsSection | `focused-render-harness-green` leaves a CONFIRMED Boost slot journal for recovery. After Off, a fresh Standby receipt arrives with no active owner/night, but the old `slotWrite == null` guard hides Off's marker. | Allow a settled CONFIRMED journal alongside fresh manual receipts; preserve recovery data and reject PENDING/FAILED/UNKNOWN journals. Added explicit Boost → Off → Heat regression and uncertain-journal assertions. All 17 selected cases GREEN in `marker-menu-green`, including the confirmed-journal and uncertainty cases. |
| CLEAN-01 | Low (maintenance) | Unreachable pre-redesign UI | Self-review found TonightSections, the superseded StatusHeader UI, unused ModeQuickButtons and TempSlider with no application callers; old tests exercised an obsolete quick-control implementation. | Remove dead implementations and their unused resources; retain backup fields/schema. Move coverage to the actual six approved quick tiles, current status mapper and meaningful selected indicators. No tests removed. |

The first native stripe tests used a floating-point reference over an unsampled base; native premultiplied surfaces introduced rounding that made those harness expectations invalid. The corrected reference samples the rendered base, then independently computes one approved tint layer with at most one RGB channel unit of rounding. The actual double-tint defect was separately reproduced in `manifest-tint-red-compiled` (expected `ff222e3f`, actual `ff283c54`). All seven palettes and all four tint strengths pass in `focused-render-harness-green`.

`marker-green` stopped at a capture-helper compile error (`navTab` already performs the click and returns Unit); it is not regression RED evidence. The helper is corrected. A warning audit also found the generated-manifest bridge's deprecated AGP `unitTest` getter in a prior Kotlin-DSL compilation; `hostTests[HostTestBuilder.UNIT_TEST_TYPE]` replaced it and the script was recompiled successfully in `marker-menu-green` without that diagnostic.

## Capture harness diagnosis

`captures-remediation` executed all 41 opt-in cases (29 passed, 12 failed, zero skipped). Eight failures were obsolete descendant matchers on the merged wake control; the assertion now checks the same complete formatted wake value on the merged node. Three failures followed the fixture's real/demo flag change: SessionApp correctly resets navigation, so the capture explicitly opens Profiles again. The subsequent `capture-harness-diagnosis` run additionally found Settings Back outside the scrolled viewport; it now scrolls the actual Back control into view before tapping it. None of these changes alters production behaviour.

### DEP-03 — wide native dialog measurement (high; test infrastructure)

`EditorTypedDurationTest` adds the complete Tonight Change → editor → Middle shape/fan → phase → Type time → save route. Both 411dp/xxhdpi and 411dp/xhdpi configurations fail at the initial field assertion with an AppNotIdleException, as does the isolated TypeTimeDialog (three RED cases in `typed-duration-isolation`, exit 1). The same dialog already passes at 320dp/200% text. This matches [Robolectric issue 8460](https://github.com/robolectric/robolectric/issues/8460).

The pinned 4.16.1 `ShadowWindowManagerGlobal.configureWindowFrames` explicitly ignores requested window sizes by default for compatibility. Its `robolectric.windowManager.useRequestedSize` setting enables Android-like requested-size handling and gravity. That test-only setting passed all three unchanged regressions in `typed-duration-window-sizing` (3 tests, zero failures/errors/skips, exit 0; no warnings). No dialog width, production behaviour, idling policy, timeout or case selection was changed. The complete captures and ordinary gate also exercise this configuration.

## Final visual-review regressions

| ID | Severity | Feature / files | RED and root cause | Fix / verification |
|---|---|---|---|---|
| UI-19 | High (accessibility) | Shared Material inputs and indicators / NocturneControls, editor, Remote, Tonight, Settings | Native captures exposed the focused Overcast Hours label using the bright primary fill. Five new `RedesignTextContrastTest` cases failed in `controls-contrast-red`: Midnight unfocused field boundary 1.228:1; Overcast slider thumb 2.186:1, selected radio and small focused label 2.526:1, checked cooldown choice 2.363:1. | Shared control colours choose existing readable palette ink, and input boundaries use ink3. Protected palette literals, decorative card borders and the approved Start fill remain unchanged. All five GREEN in `controls-contrast-green-middle-red`; the final focused run also checks all previous contrast regressions. |
| UI-20 | Medium (layout) | Middle temperature steppers / EditorParts | `middleTemperatureValuesKeepTheirUnitOnOneLineAtNormalSize` failed before the layout change in `controls-contrast-green-middle-red`: 22°C occupied two lines at 411dp/100%. The 172dp inline threshold did not leave enough room between two 48dp targets. | Raise the font-scaled minimum inline width to 216dp, stacking the complete value above the buttons when needed. Large text already uses this arrangement; the approved controls and editing behaviour remain. All 29 app contrast, typed-duration and large-text cases passed in `controls-middle-green`. Its exit 1 was the six test-only lint errors recorded below. |

`controls-contrast-green-middle-red` exited 1 with 23 app cases, 22 passed and the single new UI-20 regression failed. Its source digest was stable (`a1f11a5d5ab8c8e284a316d5bf79029b751a5bd9c33de711f8da38f61d06dd8b`). This is explicit RED evidence, not a failed contrast fix.

Visual evidence also exposed stale large-text Settings top captures: the raw drawing preceded the click's recomposition. `U06Fixture.capture` now uses its existing deterministic frame/idle helper, and each Settings capture asserts and scrolls to the actual destination Back control. No production navigation behaviour or timing policy changes. The final 41-case capture run passed with zero skips, and its refreshed destination images were inspected.

### WARN-02 — new test composition state reads

`controls-middle-green` passed all 29 selected app cases but exited 1 because lint found six `StateFlowValueCalledInComposition` errors in the new standalone time-dialog and contrast test hosts. Their themes now observe settings with `collectAsState`, like the existing test hosts. No production change or lint exception is involved. The six diagnostics are retained individually as WARN-205–210 in `warnings.csv`; the subsequent capture/lint and complete gate provide GREEN evidence.

## Implementation commit map

- `4c2dc197d60629adb0ee3908660e35f407ab0d1b`: initial failing regression evidence and approved-UI selector migrations.
- `06ee117`: DEP-01/02/03, API compatibility/security coverage, build toolchain, broad lint configuration removal and reproducible offline SDK preparation.
- `fbb2a6acab0f029325e254541868286c81b6f501`: UI-01–20, DATA-01/02, CLEAN-01, remaining warning fixes, new native regressions, deterministic test migrations and expanded capture harness. WARN-02 is a test-host correction in this commit.
- `5fdc42a`: UI-21/22 native regression tests, readable selected controls and non-overlapping History actions. The 118-case focused run passed before this commit.
- `c9704ee91b4d5642a90f7cf0b7f9d22b9ce619dd`: UI-23 fresh-ViewModel setup request restoration, with two API regressions and 38-case GREEN.
- ENV-01–04 concern local verification provisioning or execution, not application changes.

The application and test changes through UI-20 are committed. The later selection and History findings below were discovered during the refreshed visual review, before final delivery.

## Selection-state review before final gate

The first exact clean-gate attempt from `fbb2a6a` was deliberately stopped with actual exit **143**, before app test results, when refreshed native images exposed selection contrast requiring investigation. Its unchanged source digest and raw log are preserved as `interrupted-full-gate-selection-review`. It is not a successful gate or test RED evidence. The preceding 41-case capture run and lint both passed, but successful screenshot generation alone does not establish visual correctness.

### UI-21 — selection-state contrast (high; accessibility)

The refreshed Overcast Middle sheet shows a selected shape outline using the same blue that had failed the control-indicator check. Add-step tiles use a similarly unadjusted mode-colour selection border, and selected Material FilterChips use a surface2 fill very close to the surrounding sheet. Three native regression tests now inspect the actual selected outlines/fills against their rendered neighbours across all palettes before any production changes. `selection-contrast-red` exited 1: all three new tests failed against unchanged production code. Actual ratios were 1.2085:1 for the Midnight selected Middle mode, 2.0511:1 for the selected Midnight Turbo Add-step border against its fill, and 2.3916:1 for the Overcast selected shape border against its fill. The required minimum is 3:1. The source digest was stable (`da2989d3fdd84037a366c469cbc1a745e6fe1058cdf2c999ab4c1d43a2f999c5`). The fix preserves the mode/tint colours and uses readable ink for insufficient selected borders; FilterChips use the existing ink/ground treatment and their secondary labels inherit the selected foreground. All three regressions (looping all palettes, and all Add-step choices) are GREEN in `selection-history-green`: 118 app cases passed, zero failures/errors/skips; lint has zero issues; actual exit 0; stable digest `9df9191fe6c379206a674d4e0387a8a5f93de93e25f01f60c035f2c5b612290c`. Its external Kotlin daemon-session warning is preserved and explained in WARNING-AUDIT.

### UI-22 — large-text History suggestion actions overlap (high; accessibility)

The refreshed `gaps-compact-light-06-checkin-suggestion-window1.png` and dark equivalent show the Keep-profile text overlapping the Apply button at 320dp/200%. The Material AlertDialog action flow measures the multi-line, 20sp primary button and text action with overlapping bounds. A new two-palette native regression checks complete text, 48dp touch targets and non-overlapping action bounds, then uses actual touch input to keep the original profile and separately accept the reviewed fan change. The test was added before the production layout correction. `history-actions-red` exited 1 with both cases failing: Apply bounds `(48,919)–(592,1101)` and Keep bounds `(90,1050)–(592,1157)` overlap by 51 pixels. The unchanged source digest was `dbaf933a82cd9b83d73453d14667a66b12e71b0f5e3bf48a4bd5b304a20d1acb`. The actions now use an explicit vertical column with 8dp spacing; behaviour and resource copy remain unchanged. Both palette cases and the original normal-size CheckInSuggestionTest are GREEN in the 118-case `selection-history-green` run. The touch test confirms Keep preserves the original middle and Apply changes fan endpoints from 50/25 to 60/35 while retaining temperature.

### UI-23 — setup request counter after fresh ViewModel restoration (medium; navigation)

The repeated lifecycle review identified a mismatch introduced by UI-06: the consumed setup counter survives in saved Compose state, while a fresh AppViewModel starts its request counter at zero. A later first request can be ignored because it equals the old saved counter. The new `NavigationRestoreTest.newSetupRequestStillOpensAfterFreshViewModelRestoration` failed on both API 34 and 36 in `setup-counter-red` (2 cases, actual exit 1, stable digest `8551a31c24bd384fd860694235f113eb85ea57d40faa15383c2ff103d0bf3666`): the setup checklist was absent after the second request. The consumer now consumes the zero reset without navigating, then handles the next distinct positive request. The retained-ViewModel recreation case remains unchanged and must still prevent replay. All 38 navigation/consumed-request cases are GREEN in `setup-counter-green`, including both API 34/36 fresh-model regressions and the retained-model recreation case. Lint has zero issues, no warning lines occurred, actual exit 0, and the stable source digest is `dbe37daa47686e6ff20c17d58191d5e7aca3738eb52cbd9e4457ed2834a8c4e8`. Fix and test committed as `c9704ee91b4d5642a90f7cf0b7f9d22b9ce619dd`; the final complete gate passed from that exact source.


### ENV-04 — final-gate execution transport interruption (environment)

The exact gate started from `c9704ee` compiled and reached `:app:testDebugUnitTest`, then the execution service disconnected. Polling returned `exec-server transport disconnected` and an unknown session identifier. A process inventory showed no runner, Gradle daemon or test worker; no process-completion receipt was written. The daemon diagnostic ends with `Shutdown in progress` while starting a test worker. The partial log is preserved as `interrupted-full-gate-transport.log`, with a separate observation record whose exit code is explicitly unknown. This is neither a passing gate nor evidence of a failing test. Source digest `dbe37daa47686e6ff20c17d58191d5e7aca3738eb52cbd9e4457ed2834a8c4e8` remained unchanged. The exact clean command was restarted after that diagnosis; no source, timeouts, assertions or reporting were changed.


## Final closure — 29 October 2026

The exact clean command recorded in FINAL-REVIEW ran from `c9704ee91b4d5642a90f7cf0b7f9d22b9ce619dd` from **04:19:21 to 04:41:38 UTC**, actual process exit **0**, with unchanged source digest `dbe37daa47686e6ff20c17d58191d5e7aca3738eb52cbd9e4457ed2834a8c4e8`. All 66 actionable Gradle tasks reported by the result were executed.

- Core: **313 passed; 0 failures, errors or skips**.
- App: **1,805 cases; 1,764 passed; 0 failures or errors; 41 documented opt-in capture skips**.
- Separate final captures: **41 passed; 0 failures, errors or skips** from the same source digest.
- Lint: **0 errors, warnings or hints**. Compiler/build: **0 project-owned warnings and 0 external warning diagnostics in the final gate**. The historical external Kotlin message remains documented, not filtered.
- Every one of the **207** original failed cases has a direct executed GREEN mapping in `baseline-failures.csv`. The renamed Overcast-menu case now checks exact opaque fill, ink and composited border across all seven palettes; its original identifier remains BASE-091.
- Critical final cases include unchanged DemoImportPreviewInvalidation **4**, retained API31/33 HealthLegacyContainmentSecurity **6**, API31/33/34/36 UatAlarmIdentity **24**, PlatformManifest **8**, API34/36 NavigationRestore **36**, and RedesignTextContrast **22** (native tests loop the relevant palettes/controls).

| Register scope | Fix commits / resolution | Final verification |
|---|---|---|
| UI-01–20, DATA-01/02, CLEAN-01 | `fbb2a6a`, with initial RED/migration in `4c2dc19` | All corresponding core/app cases pass in the final clean gate; native captures and layout/pixel assertions pass |
| UI-21/22 | `5fdc42a` | Three all-palette selection regressions and two History touch/layout cases pass; refreshed native views inspected |
| UI-23 | `c9704ee` | Fresh API34/36 ViewModel restoration and retained-ViewModel no-replay cases pass |
| DEP-01/02/03 | `06ee117` | Official SDK security cases, merged-manifest receivers and unchanged native typed-duration regressions pass |
| TEST-01/02/03 | `4c2dc19`, `fbb2a6a`, manifest support `06ee117` | No deleted test files or added skip/reporting suppression; all 207 baseline mappings are GREEN |
| WARN-01/02 | `06ee117`, `fbb2a6a` | 210 diagnostic entries reconciled; 38 explicit narrow exceptions justified; final clean log/lint show no warnings |
| ENV-01–04 | Isolated/provisioned verification environment and diagnosed restart after transport loss | Final process completed successfully; aborted attempts remain separately recorded |

No known actionable source defect, test failure or project-owned warning remains. The final delivery SHA is the documentation/evidence descendant of the verified application SHA and is recorded exactly in the accompanying delivery manifest and handoff message. No production/test/build input changed after this successful gate. Physical hardware validation and Claude's independent review remain unperformed and are not implied by this closure.

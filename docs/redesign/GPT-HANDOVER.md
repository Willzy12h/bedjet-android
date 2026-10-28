# Nocturne redesign handover

Completed. Base: `claude/nocturne-redesign-foundation` at
`42e6ba2aa26d4de8b26441d961eed7ae664f7e47`. Branch: `gpt/nocturne-redesign`.

## Phase checklist

- [x] P2: Theme wiring and Appearance — `283719032dda55f414e437205c258f79ecb0ba3d`
- [x] P3: Side-stripe tiles, Home / Tonight and Profiles — `a5933a9446b65ab5f91d18deaff68cfe3c934f67`
- [x] P4: Profile editor, step editor and fan shapes — `9c1f682d25a20be48b0333048a0daf8213b1c03c`
- [x] P5: Cooldown — `0dcf66c9abb2867933736c1e2e1a222696853d2a`
- [x] P6: Guided setup, Help and about, Settings and History — `4a661f2c7c4dea53bd288886698366e65ce8cf75`
- [x] P7: Widgets v2 — `bc6882ce908563a2a58b536c0fc0ffd9c39e82f7`
- [x] P8: Polish and audit — `99111097a88effab237e57c417de427122280aef`
- [x] Final: clean verification, debug APK and handover — `49ec2bd75e8b686830fc3f59780f3547de1a4172`

## Assumptions

- The user supplied the full design export on 27 October 2026. Its published HTML checksum
  matches `02654f2672f634a81a155ff5e11f8bb48cea57b2ad7a293b64410e96a8f23fde`.
  The HTML and accompanying reference/reconciliation documents are in `reference/`.
  Palette-applied Overcast screenshots are derived renders, not separately approved screens.
- Where the export's recommendations conflict with explicit BUILD-PACKETS instructions,
  the packets govern: retain HeatScale boundaries and PaletteTokens (R1, R2, R7–R10),
  wrapping subtitles (R3), approximately 2dp curve corners (R4), no gradients (R5),
  seven choices with ticks in both palette rows (R6), fan sliders and packet defaults (R11),
  “Source code on GitHub” (R13), widget side-stripe rows with all existing actions (R14),
  fixed Turbo controls (R15), “Long heat” (R16), and the packet's Cooldown offer (R17).
  These are reviewable decisions, not changes to the foundation or the brief.
- Design-only actions without an existing implementation must not be presented as working.
  Existing confirmation, error, history detail and large-text behaviours remain authoritative
  where the static design is unspecified (R12, R19–R21).
- Use the existing Kotlin / Compose / Glance architecture and existing command owners.
  Each phase must pass its gate before the next phase starts.
- Each slot may contain any palette. Consent and native/system-bar appearance therefore follow
  the displayed palette's darkness, rather than the slot's name. While a light transition waits
  for consent, use the other dark choice, or Midnight Ink if both choices are light. The existing
  schedule/hold times remain unchanged. Legacy holds for the default pair are migrated; ephemeral
  approvals without a palette identity are not reused.

- Filled primary buttons use a consistent 20sp bold label so the required Overcast sky-blue/white
  pair has large text. Small action/error labels use ink instead of low-contrast accent colours.
  These can be restyled during review while retaining the rendered contrast checks.
- The design omits the functioning Remote tab and the saved Tonight section order/collapse
  controls. They remain available. P8 places Quick controls immediately after the Night section,
  following the design while preserving the saved order of Night, Wake and Connection. The existing immediate Start action keeps its truthful “Start night now”
  label; the mock's future-start wording is not mapped to an immediate command. Review can
  change these layout choices separately from the command and schedule behaviour.
- Git CLI publication has no interactive credentials in this environment. Phase commits are
  published through the connected GitHub service with the expected parent SHA and no force.
  Each resulting source tree is compared with the checked local tree before advancing the
  local branch to that same commit. No signing or release configuration changes are needed.
- Existing saved Turbo chains keep their stored duration and temperature data when opened;
  P8 displays their effective compiled Turbo temperature of 43°C / 109°F.
  Selecting Turbo for another phase sets 10 minutes / 43°C and exposes no sliders. This avoids
  silently shortening a previously saved chain. The existing chain policy and compiler remain
  intact. Review can introduce an explicit migration if legacy chains should change.
- The applied plan carries no fan-shape metadata. Quiet middle timeline labels use the captured
  active-night profile, never a later editable profile, and show only speeds present in the plan.
  Equal or clamped speeds can make stage identity ambiguous: those entries name Quiet middle
  and show the actual fan path without guessing settle/asleep/rise. Legacy plans without a
  captured shape retain a neutral actual path. Profile summaries and check-in proposals have
  the full model and describe all three speeds.
- Choosing Ease down can temporarily retain equal endpoints; the second slider stays available
  during editing. The protected model derives Steady when those equal endpoints are saved and
  reopened. No new persisted shape field or backup schema change is introduced.
- New drafts created from presets receive the specified 22°C Cooldown; copying an existing
  profile preserves its after-wake content. Import and demo seed profiles are not mutated.
  The offer applies to future plans; it does not alter a running night's captured plan.
  To give copies the default too, change the Copy callback in `ProfilesScreen.kt` explicitly.
- History's generic `ERROR` events include uncertain writes and unconfirmed outcomes.
  Its warning chip therefore says “1 issue” / “N issues” and retains the original detail,
  rather than claiming a command did not send. A future “didn't send” label needs a
  structured event that proves no write occurred. The supplied History design itself
  asks that its suggestion be checked against the app's recorded evidence.
- P6 retains the existing setup steps and Settings features. The design's additional
  profile-creation wizard and update-check service are not existing working flows;
  no simulated controls or status are added. The existing project link becomes the
  explicitly requested “Source code on GitHub” action at the same destination.
- Widgets retain their existing one/two/three-action size budgets and overflow pages,
  including profile starts, Stop tracking and Open app. The mock's four-action shortlist
  is not used to remove working controls. Each existing action gains a side-stripe row;
  its stripe describes the captured preset, with a neutral Off stripe or accent for
  actions without a preset. It does not indicate a confirmed BedJet state. The existing
  status/receipt labels and refresh cadence remain authoritative.

- P8 uses the supplied Figtree/Bricolage font assets, with Latin/Latin Extended glyphs and
  platform fallback for other scripts. To change this, replace the font resources and the
  role assignments in `Theme.kt`; retain the attribution notices and contrast/layout checks.
- Settings subtitles describe the available functions instead of claiming live connection or
  permission state. The eight destinations follow the design while grouping existing controls.
  Review can change `SettingsHome` and the page groups in `SettingsScreen.kt` without changing
  their command owners.

## Verification and APK

The clean full check passed on 28 October 2026 from committed source
`99111097a88effab237e57c417de427122280aef`. There were no source changes after P8;
Final commits only complete this handover and the review log. No signing changes.

Required task sequence:

```sh
./gradlew clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

This environment used `--no-daemon --max-workers=3 -I docs/redesign/verification.init.gradle`
for the documented three-worker, fresh-JVM-per-class isolation, with the installed toolchain
and offline dependency cache. No test-class filter or screenshot opt-in was set for this run.
All 62 Gradle tasks executed after `clean`.

- Core: 311 tests, zero failures/errors/skips.
- App: 1,704 cases, zero failures/errors and 41 opt-in capture skips: 1,663 executed tests.
- Total executed tests: 1,974. Every JUnit report was readable.
- The 41 opt-in capture cases passed separately on the same source, with zero skips.
- Lint: zero errors, 24 warnings. The warning categories are OldTargetApi (1),
  SpecifyJobSchedulerIdRange (1), ModifierParameter (1), DiscouragedApi (2),
  PluralsCandidate (1), ObsoleteSdkInt (1), UnusedResources (6), and UseKtx (11).
- Debug APK builds. No hardware, Samsung, TalkBack or on-device verification is claimed.

```text
:core:test — 311 tests, 0 failed, 0 skipped
:app:testDebugUnitTest — 1704 tests, 0 failed, 41 skipped
:app:assembleDebug
:app:lintDebug — 0 errors, 24 warnings
BUILD SUCCESSFUL in 23m 16s
62 actionable tasks: 62 executed
```

The test-count and lint lines above summarise the readable JUnit/lint reports; the final two
lines are copied from Gradle's console. The full task command completed with exit code 0.

APK:

- Build path: `app/build/outputs/apk/debug/app-debug.apk`.
- Delivered directly as `nocturne-redesign-debug.apk`; no draft PR was needed or opened.
- Size: **68,168,175 bytes** (65.01 MiB).
- SHA-256: `9e1aa74f54f41c0b5aebf997401014d9ad9ba45966dccca9280f46f85d49e37e`.
- The APK is not committed. The delivered copy matches the build output byte for byte.

If Android reports a package/signature conflict, export a backup in Settings first, then
uninstall the current test app and install this APK. Production signing was not changed.

### P2 evidence

- RED: `RedesignThemeTest.chosenPalettesReachMaterialAndModeColoursInEveryThemeMode`
  showed the old fixed background; `RedesignPalettePickerTest` showed the missing swatches.
  Both pass after token wiring and selectors. Backup export/import already retained both choices.
- RED: `RedesignCurveColoursTest` captured the rendered curve and found the missing Ember
  colours for different temperatures. The curve now uses the foundation `stepColor` directly,
  including after-wake; timeline dots and the current-step marker also use the known temperature.
- RED: `RedesignPaletteConsentTest` exposed missing confirmation for a light night palette,
  incorrect native-dialog darkness for a dark day palette, and no light-transition hold when
  the scheduled slots have opposite palette darkness. The existing consent flow is being
  adapted to actual palette darkness; schedule times and the foundation resolver stay intact.
- Existing `DaylightHeatPaletteTest` expectations intentionally change from the old amber
  palette to shared Ember, including Simple view, as explicitly required by P2.
- Existing `ContrastTest` expectations intentionally change with the approved tokens:
  decorative outlines are each palette's hairline, and translucent surfaces are composited
  over their ground before measurement. Ink body/container text retains its 4.5:1 checks;
  the approved Overcast Start pair uses the foundation's 3:1 token floor. Decorative
  Ember bars no longer use the old after-wake alpha dimming; their meaning is also given
  in text, and ink labels/subtitles are checked against every temperature tint.
- The required Overcast primary, accent and error tokens do not all meet 4.5:1 as small text
  on every surface. Additional rendered tests found profile actions at 2.63:1, Start at
  3.32:1 with 16sp medium text, invalid-preset errors at 3.82:1, and wake labels at 3.11:1.
  Small action/error text now uses ink; Overcast wake text uses ink/ink2 while its markers
  retain accent. Shared Material button wrappers preserve callbacks, enabled states and
  layout parameters; filled primary buttons use 20sp bold labels with the required colours.
  `RedesignTextContrastTest` checks actual laid-out text across all seven palettes and uses
  a conservative large-text threshold (24sp regular or 19sp bold). Future explicit text
  styles inside filled buttons must preserve that threshold or meet 4.5:1.
- `ContrastTest` retains five checks: all-palette composited body/container text; separate
  Midnight and Overcast hairline expectations; ink/ink2 on every mode/temperature tint;
  and the existing meaningful quick-control boundary test. The old literal-colour checks
  change only where the approved tokens change them. Rendered bar colour is checked by
  `RedesignCurveColoursTest`; existing curve/timeline descriptions retain text equivalents.
- Existing `RemoteAccessorySwitchTest` exposed a real layout regression after adding the
  palette rows: confirmation feedback shrank the viewport and hid the interacted switch.
  Settings now keeps that switch in view on viewport shrink, cancelling when the scroll
  offset changes. Commands, matching-bit confirmation and tests are unchanged. A temporary
  layout diagnostic was removed; no arbitrary timer remains in the production fix.
- GREEN: 37 focused tests passed (0 failed, 0 skipped), including five rendered-text contrast
  checks, unchanged accessory switches, legacy hold migration, opaque editor rendering,
  native palette consent, held-open schedule and demo isolation.
  The full P2 phase gate passed: 308 core tests; 1,616 app cases, 0 failures and 38 existing
  opt-in skips; lint 0 errors and 21 warnings. `BUILD SUCCESSFUL in 11m 40s`; 43 actionable
  tasks: 3 executed, 40 up-to-date. Two app test workers ran the unchanged complete suite.
  The worker setting is local build configuration, not a repository or release-workflow change.
- The first completed full gate passed 308 core tests but failed 18 of 1,610 app tests
  (38 existing opt-in cases skipped). Four accessory visibility failures reproduced;
  nine selected foundation cases passed, with only those four failing on the redesign.
  The other earlier full-suite failures did not recur in the passing gate. The new theme
  test now renders a visible surface and settles snapshot/Android frames after changes,
  following the existing fixture pattern; every original assertion remains. Its exact
  timeout cause was not established. Interrupted runs and a baseline build-cache lock failure are not reported as
  test results. Build invocations are serialised in this environment.

### P2 changed files

- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/CustomRunDuration.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/DemoModePanel.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/DeviceControls.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/HistoryScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ImportPreviewDialog.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightCurve.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightGuard.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightRecoveryControls.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NocturneButtons.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/PalettePicker.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditor.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfilesScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/QuickDefaultsSection.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RecoveryNotices.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SetupChecklistCard.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SetupFlow.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ShortTestControls.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Theme.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ThemeConsent.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightSections.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/features/FeatureSections.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/features/HistoryFeatures.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/features/PlatformAutomationSettings.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/SingleActionConfigActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ContrastTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/DaylightHeatPaletteTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignCurveColoursTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignEditorGroundTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignPaletteConsentTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignPalettePickerTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignTextContrastTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignThemeTest.kt`
- `docs/redesign/GPT-HANDOVER.md`
- `docs/redesign/reference/DESIGN-REFERENCE.md`
- `docs/redesign/reference/RECONCILIATION.md`
- `docs/redesign/reference/nocturne-final-design.published.html`

### P3 evidence

- RED: `RedesignStripeTilesTest` found the absent rendered Heat stripe;
  `RedesignHomeTest` found the missing live-temperature/context nodes;
  `RedesignProfilesStyleTest` found the absent card entry point and incorrect Overcast
  popup background. The Start token and complete-subtitle assertions already passed and
  remain as regression checks.
- A read-only review prompted `RedesignProfileChooserLargeTextTest`: the larger preset
  rows had no scrolling ancestor and lower choices were unreachable at 320dp / 200% text.
  The dialog list now scrolls; all five presets pass full visibility, touch-size and draft
  identity checks. The test scopes the choice to the dialog rather than the underlying card.
- `NightCurveAlternativeTest.timelineEmphasisesCurrentStepAndWakeInDarkPalette` observed
  the shared tile dropping the previous semibold current/wake labels. Explicit title style
  slots restore that emphasis and preserve the palette-specific wake colour.
- The Home reading compares the exact report exposed by `AppViewModel.status`, which
  intentionally throttles raw sensor changes. Reading the raw demo link in the first test
  could observe a later receipt than the displayed report; the fixture now uses the same
  displayed-report source without relaxing the exact value assertion. Stale reports hide
  the current-temperature visual.
- Shared tiles use the foundation's stripe and tint over the surface, 12dp clipping,
  wrapping full-width subtitles and labelled targets. Mode icons are decorative beside
  their text; active markers still require the existing receipt-evidence predicate.
  Profile actions, preview identity, expiry checks, confirmation callbacks and timeline
  tap/long-press mappings are retained. Curve corners are 2dp.
- The first full P3 gate passed 308 core tests and lint (0 errors, 21 warnings), but found
  34 failures among 1,630 app cases (38 existing opt-in skips). Ten failures used the
  former single preset label “Summer Cool (summer)”; the title and subtitle now have
  separate rows, so `NavigationRestoreTest`, `UiFinalisationTest` and `WakeFeedbackTest`
  select the same preset within the dialog. Every draft/save/restore assertion remains.
  Two failures exposed the clickable profile parent absorbing the passive timeline;
  only the labelled 48dp card header now opens the editor. The timeline and action row
  remain separate, preserving the original passive-preview tests.
- The same gate found four large-text wake-explanation failures: the new hero displaced
  a source explanation that must remain visible without scrolling. At font scales of
  1.5 or above the explanation precedes the hero; the normal-size design keeps the hero
  first. Sixteen quick-control layout failures came from repeating full preset details
  in narrow tiles. Tiles now show the mode and concise temperature/duration subtitle,
  with full details retained in their accessible description. Two “Off” lookups became
  ambiguous with the Home status; confirmed standby is now labelled “Idle”, as drawn,
  while the existing Off action remains unchanged.
- Clean verification of the corrections passed those 140 selected cases. Five further
  assertions in `BoostSequenceLayoutTest` and `UatLayoutAndSetupTest` expected the old
  combined “Turbo 10m” title. They now check the separate mode and duration text, retaining
  all description, touch-target, viewport and two-column geometry checks and adding a
  rendered subtitle-overflow assertion.
- `AppearanceTest` had the same combined-title expectation, found at the start of the next
  full run. Its existing visible Turbo control check now also asserts the separate mode
  and ten-minute subtitle. That failing run was stopped early and is not a passing gate.
- Two intervening runs were invalidated by stale Android test resources after adding strings:
  the compiled `temp_celsius` ID resolved to `template_percent` in the old linked resource
  package, producing format exceptions. A clean app rebuild regenerated matching IDs and
  values; no production formatting or Gradle configuration was changed to hide the failure.
- A later full run executed all 1,630 app cases with one missing History check-in node in
  `AppSmokeTest`. The failure did not reproduce in a diagnostic run of all 15 smoke tests.
  That test now waits for History's expected button using its existing bounded looper/service
  helper before clicking; every behaviour assertion remains. The underlying timing cause
  is not proven and no app logic changed. That full run also failed to write 35 XML reports,
  so its partial parsed report totals are not a verification pass. The follow-up full gate
  includes stack traces and preserves binary reports on failure for diagnosis.
- The follow-up executed all 1,630 app cases with zero failures and 38 existing skips;
  core's 308 cases and lint also passed. Gradle still failed while embedding captured test
  output into 12 XML reports (`TestOutputStore` EOF / Kryo buffer underflow). The installed
  Gradle 8.14.3 API and writer were inspected: test cases, outcomes and failure details are
  independent of console-output embedding. The local external init script now omits
  stdout/stderr sections from app JUnit XML and disables optional app HTML reports.
  Every test and assertion still runs; repository Gradle/signing/release files are unchanged.
  A fresh complete gate verified this reporting workaround with every XML report readable.
- Full P3 gate passed: 308 core tests; 1,630 app cases, zero failures and 38 existing opt-in
  screenshot skips; lint zero errors and 20 warnings. `BUILD SUCCESSFUL in 11m 7s`;
  43 actionable tasks: 2 executed, 41 up-to-date. Both Gradle's execution summary and
  the complete JUnit XML agree on the test counts. Two app test workers were used.

### P3 changed files

- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/StripeTile.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/QuickDefaultsSection.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfilesScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightCurve.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignHomeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignProfilesStyleTest.kt`
  (also contains the five-case `RedesignProfileChooserLargeTextTest`)
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignStripeTilesTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/NightCurveAlternativeTest.kt`
  (one additional emphasis check; original assertions unchanged)
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/NavigationRestoreTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UiFinalisationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/WakeFeedbackTest.kt`
  (preset selectors only, for the intentionally split title/subtitle)
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/BoostSequenceLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UatLayoutAndSetupTest.kt`
  (the intentional separate quick-tile title/subtitle expectations described above)
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppearanceTest.kt`
  (the same separate-title/subtitle expectation, with the visibility check retained)
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppSmokeTest.kt`
  (bounded wait for History's check-in button; all assertions retained)
- `docs/redesign/GPT-HANDOVER.md`
- `docs/redesign/BUG-LOG.md`

### P4 evidence

- Initial RED observed missing fan controls, the old five-minute minimum, Turbo retaining the
  prior duration, enabled Save for an invalid zero-minute phase, missing actual middle fan
  speed in the timeline, old History wording, old mode wording and the old Hot Sleeper shape.
- `P4EditorBehaviourTest` covers all three shapes, independent temperature shaping, Quiet
  compatibility endpoints, speed/minute bounds, Turbo text-only controls, accepted 1/720-minute
  steps and rejected 0/721-minute drafts. A review added RED tests for non-multiple imported
  limits and changing limits after choosing Quiet middle. Editor speeds now use the legal 5%
  increments within the limits; explicit limit edits reconcile all three speeds without changing
  timings. Opening a saved profile alone does not rewrite it.
- `QuietMiddleDescriptionTest`, `QuietMiddleTimelineDescriptionTest` and
  `QuietMiddleEdgeDescriptionTest` cover profile summaries, retained actual fan paths, captured
  shortened stages, legacy unknown shape, ambiguous equal speeds and neutral nonmonotonic
  descriptions. The profile test initially matched a timeline rather than the summary; its
  selector was corrected and a genuine summary failure observed before the summary changed.
- `QuietMiddleCheckInSuggestionTest` verifies all three proposed speeds, no mutation before
  confirmation, and application through the existing `shiftFan` path.
- `RedesignLongHeatLabelTest`, `RedesignCapturedLongHeatTest` and `LongHeatMessageTest` cover
  shared UI labels, captured confirmations, typed duration errors, the known compiler refusal,
  unchanged arbitrary text and editor preview error projection. Core enum names, diagnostic
  strings and `Mode.displayName` remain unchanged. The compiler's exact canonical refusal is
  mapped only at known plan-message boundaries; arbitrary text is never globally replaced.
- `PresetsTest.hotSleeperUsesQuietMiddleWithCompatibleEndpoints` checks the specified
  50%/30-minute, 25%, 45%/40-minute Quiet shape. The existing all-presets test still verifies
  compilation within 32 steps, and the foundation fan-shape tests remain unchanged.
- Existing `WidgetModeNameTest`, `RemoteModeLimitsTest`, `ProfileEditorInteractionTest` and
  `U06PrimaryLayoutTest` change only the intended Gentle heat → Long heat label/resource
  expectations. Their original mode limits, semantics and layout assertions remain.
- `EditorPhaseSelection.kt` already maps source phases correctly. `CustomRunDuration.kt`
  governs Remote manual runs, whose safety limits are separate from profile steps; neither
  file needs to change to support 1–720-minute profile phases.
- Full P4 gate passed: 309 core tests; 1,652 app cases, zero failures and 38 existing opt-in
  screenshot skips; lint zero errors and 21 warnings (the additional warning is the now-unused
  `mode_gentle_heat` resource, to be removed in P8). All JUnit XML is readable.
  `BUILD SUCCESSFUL in 13m 8s`; 43 actionable tasks: 5 executed, 38 up-to-date.
  This complete gate used two app workers recycled after 50 classes and the existing external
  report-format workaround. No tests were removed or disabled.
- Focused verification passed 12 core cases and 83 of 84 app cases with zero lint errors;
  the sole expected RED was the newly added editor-preview wording test, run with its mapping
  deliberately held back. The mapping was then restored for the full check. All nine editor
  behaviour tests, timeline/proposal cases and existing selected layout checks passed.
- A four-worker full run stopped advancing after partial output and was interrupted; it is
  not a passing result. The complete gate was rerun with the established two-worker
  configuration. An external per-test event log now supplements Gradle's buffered output;
  no repository build configuration or test assertions changed for this diagnostic.
- The subsequent full run executed 309 core cases successfully and all 1,652 app cases,
  with 11 failures and 38 existing skips. Ten failures were Compose idling timeouts in
  editor and stripe-tile tests; one was the existing paused-Tonight alarm refresh check.
  This run had no fresh lint result or complete Gradle footer and is not a passing gate.
  The new preview-message test was missing ViewModel/Graph lifecycle cleanup; it now uses
  the same cleanup as `NightDurationResourcesTest`. The timeout cause is not established.
  Verification subsequently passed with two workers recycled after 50 classes to bound shared
  Robolectric state. Every case and assertion remains enabled; this configuration changes
  process sharing. The pass does not establish stability with long-lived workers or prove the
  cause of the earlier timeouts. The separate paused-Tonight alarm refresh test also passed.

### P4 changed files

- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/CapturedActionPreset.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/PlanMessages.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/Receivers.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Format.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/HistoryScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightCurve.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditor.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfilesScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/night/LongHeatMessageTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/night/RedesignCapturedLongHeatTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P4EditorBehaviourTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditorInteractionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/QuietMiddleCheckInSuggestionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/QuietMiddleDescriptionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/QuietMiddleEdgeDescriptionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignLongHeatLabelTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RemoteModeLimitsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06PrimaryLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/WidgetModeNameTest.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/plan/Presets.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/plan/PresetsTest.kt`
- `docs/redesign/BUG-LOG.md`
- `docs/redesign/GPT-HANDOVER.md`

### P5 evidence

- `CooldownOfferSettingsTest` observed RED for both the missing default and strict import of
  the new field. The added default is false; the backup schema and parser remain unchanged.
- The behaviour RED ran against a refusing repository stub and absent UI: eight of nine app
  cases failed, while the stale-demo negative case already passed. A separate restore test
  failed with the session revision increment held back, then passed after it was restored.
  An imported profile retaining its ID and after-wake content cannot reuse an earlier offer.
- `CooldownOfferTest` covers default selections, explicit replacement, decline, persistence and
  the new-profile draft. `CooldownOfferLayoutTest` checks the offer's targets at large text.
  `RepositoryCooldownTest` covers selected updates, stale selections and failed writes;
  `RepositoryCooldownSessionTest` covers session changes and unreadable-data guards. Selected
  changes and the answer are saved together; stale or unsafe requests leave the offer open.
- The 167-case profile regression run reported 18 failures after the offer was inserted.
  The offer moves existing profile controls below the initial viewport.
  `NavigationRestoreTest`, `ProfileEditorInteractionTest` and `WakeFeedbackTest` now scroll to
  the intended profile controls by ID rather than assuming a visible Edit action. Their existing
  draft, save, navigation and running-night assertions remain. All 167 follow-up cases pass,
  with zero lint errors and 21 warnings.
- The first full P5 gate ran 311 core cases successfully and all 1,663 app cases, with
  14 failures and 38 existing skips. Seven failures exposed the same initial-viewport
  assumption in `AppSmokeTest`, `SaveOrderingTest`, `AccessibilitySemanticsTest` and
  `EditorTrackingTruthTest`; these now scroll to the intended profile ID before opening it.
  Their original save, command, unit-label and tracked-night truth assertions remain.
  Seven `P4EditorBehaviourTest` cases reported Compose idling timeouts. No fresh lint result
  or success footer was produced; this is not a passing gate. The idling cause is being
  investigated separately from the navigation corrections. All 33 focused cases then passed,
  including all nine unchanged editor cases, with zero lint errors and 21 warnings; the
  timeout did not reproduce in that smaller run. An experimental bounded frame-settling
  helper did not resolve the full-suite timeouts and was removed; the P4 editor test is
  unchanged from its committed P4 version.
- The second full gate executed all 311 core and 1,663 app cases: seven app failures,
  38 existing skips, and no fresh lint result. Six failures were Compose idling timeouts
  in editor and stripe-tile tests. The other exposed an existing backup/demo fixture whose
  dispatcher held whichever graph coroutine happened to dispatch next. It now targets the
  staged import Job, preserving every admission, stale-review and real-data assertion.
- A 597-case UI run then passed all nine P4 editor, all seven profile interaction and all
  four backup/demo invalidation cases. It had three different existing failures: two
  theme-confirmation idling timeouts and a paused-Tonight alarm-refresh timeout; 24 opt-in
  captures were skipped, and lint reported zero errors and 21 warnings. Temporary diagnostic
  rules were removed. The full gate was repeated with a fresh JVM per app test class
  to eliminate state shared between classes. No cases, assertions or timeouts are disabled
  or relaxed. The exact cause of the shared-worker timeouts remains unproven.
- The complete isolated run passed all 311 core and 1,663 app cases (zero failures,
  38 existing screenshot skips) in 1,443.7 seconds. Gradle exited zero, but its captured
  log omitted the final lint task/footer. The identical full task command was then rerun
  using the portable init script below: tests were up-to-date with those passing results,
  lint reported zero errors and 21 warnings, and all JUnit XML was readable.
  `BUILD SUCCESSFUL in 12s`; 43 actionable tasks: 1 executed, 42 up-to-date.
- `docs/redesign/verification.init.gradle` records the checked app-test settings: two
  workers, a fresh JVM per class, JUnit XML without embedded captured output, and no HTML
  test report. It does not filter cases or change assertions/timeouts. Its configuration
  was checked and the complete task command above used it successfully. Reproduce a phase
  gate with `./gradlew --no-daemon --max-workers=2 -I docs/redesign/verification.init.gradle
  :core:test :app:testDebugUnitTest :app:lintDebug`. The local runner also used a 2 GB Gradle
  heap, in-process Kotlin compilation and cached dependencies; these are environment settings,
  not changes to signing or release configuration.

### P5 changed files

- `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/CooldownOfferCard.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfilesScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppSmokeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/data/SaveOrderingTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/data/RepositoryCooldownSessionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/data/RepositoryCooldownTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/CooldownOfferLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/CooldownOfferTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AccessibilitySemanticsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/EditorTrackingTruthTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/DemoImportPreviewInvalidationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/NavigationRestoreTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditorInteractionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/WakeFeedbackTest.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/Settings.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/store/CooldownOfferSettingsTest.kt`
- `docs/redesign/verification.init.gradle`
- `docs/redesign/BUG-LOG.md` and `docs/redesign/GPT-HANDOVER.md`

### P6 evidence

- Initial RED ran eight new cases: five failures for the absent GitHub row, absent History
  warning chip, missing setup stripe and the two stock Material dialog/sheet surfaces.
  Setup order/demo behaviour, truthful unknown-history detail and the missing-browser
  refusal control already passed. A separate RED checked that the refusal also shows
  “Could not open a browser.” The link now uses `ACTION_VIEW` and the exact repository URL.
- `P6SetupStyleTest` verifies rendered stripes in Midnight Ink and Overcast and retains
  setup order/demo action coverage. `P6SettingsHistoryTest` verifies the actual link,
  both missing/blocked-browser exceptions, warning colour and uncertain-event wording.
  `P6SheetPaletteTest` captures actual Compose dialog/sheet pixels and text colours.
- Native RED found the stock Forest shortcut surface and Midnight time-picker surface.
  The picker fixture first required a real layout traversal before capture; a zero-sized
  bitmap failure was not treated as a palette RED. The picker then failed on sheet colour.
  `P6NativePaletteTest` also checks the picker callback and cancellation without a service.
- Review caught a new native styling mistake: assigning one button text colour removed
  its disabled state. `P6NativeButtonStateTest` reproduced it through the actual Temporary
  shortcut's End → unavailable Temporary → End choices. The helper now uses stateful colours.
  The first native regression run also found four existing 200%-text clipping failures
  after replacing Android's background drawable. The correction retains its native
  drawable geometry/padding and applies the sheet colour. All native palette, state,
  layout, contrast and command cases passed in the next focused run.
- Existing `FinalSettingsCleanupTest` and `U06ControlSemanticsTest` change only the expected
  GitHub label to the wording required by P6. Their intent, attribution and target assertions
  remain. New test fixtures use unmerged chip semantics and dispatch native callbacks before
  checking their effects; the existing native layout and command tests are unchanged.
- That 141-case run passed 133 cases and found two new Settings layout regressions in
  each of four display variants: the slot chips clipped after the card padding narrowed
  their row, and the Quiet night status description became taller than the viewport.
  Slot chips now wrap in a `FlowRow`; the shared single-toggle `SwitchRow` keeps its title
  and switch together and gives its full description the width below. Text, state,
  callbacks and existing layout assertions remain. Lint had zero errors and 25 warnings.
- The follow-up passed all 47 selected cases with no skips, including the two regressions
  in every display variant, the remaining control-semantics checks and all eleven P6 cases.
  Lint reported zero errors and 25 warnings; `BUILD SUCCESSFUL in 3m 26s`, 41 tasks:
  12 executed and 29 up-to-date.
- The complete P6 gate passed: 311 core tests; 1,674 app cases with zero failures,
  zero errors and 38 existing opt-in screenshot skips. Lint reported zero errors and
  25 warnings. `BUILD SUCCESSFUL in 24m 15s`; 43 actionable tasks: 2 executed,
  41 up-to-date. All JUnit XML was readable. The committed verification init script
  ran the full unfiltered app suite with a fresh JVM per class and two workers.
- Independent static review found no additional concrete status, callback or lifecycle
  regression. Native styling adds no recurring work and preserves command capture/confirmation.
  This is code review, not a device or battery measurement.

### P6 changed files

- `app/src/main/kotlin/io/github/willzy12h/nocturne/platform/AndroidSleepSource.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/platform/PlatformActionActivity.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/HistoryScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditor.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SetupChecklistCard.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SetupChecks.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SetupFlow.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ShortcutActivity.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SourceCodeLink.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TemporaryNativeDialog.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Theme.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetConfirmationActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/platform/P6NativeButtonStateTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/FinalSettingsCleanupTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P6NativePaletteTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P6SettingsHistoryTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P6SetupStyleTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P6SheetPaletteTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06ControlSemanticsTest.kt`
- `docs/redesign/BUG-LOG.md`
- `docs/redesign/GPT-HANDOVER.md`

### P7 evidence

- `P7WidgetPaletteTest` observed RED in fixed, System and Scheduled modes: custom Forest
  still resolved to the old fixed Midnight Ink pair. It checks actual Glance colour
  providers for ground, surface, ink, primary and on-primary, including reversed slots.
- `P7WidgetStripeTest` observed RED for the missing 4dp stripe and adjacent flat tint.
  It renders actual RemoteViews for both widget types with Warm, Turbo and Off in
  Midnight Ink, Forest and Overcast, retaining labelled-target checks. The row tint uses
  the protected `tokens.tint()` over the palette's surface and ground; no gradient is added.
- Widget actions retain the same captured request for both their stripe and click. Fixed
  and scheduled colours use `Settings.palette()`; System keeps Glance's separate day/night
  providers. Each render shares its settings/time with action rows. Existing action capture,
  confirmation, paging and update mechanisms are unchanged.
- The 97-case widget regression run passed 96 cases and observed the additional expected
  contrast RED: Overcast's small chevron text on primary blue was below 4.5:1. Navigation
  and Open app now use surface/ink colours while retaining their 16sp labels and 56dp targets.
  The test samples actual rendered button fill and text.
  Lint reported zero errors and 25 warnings in that regression run.
- Focused GREEN passed all 97 widget cases with no skips, including action, confirmation,
  paging, freshness, update-cadence and 200%-text checks. Lint reported zero errors and
  25 warnings. `BUILD SUCCESSFUL in 4m 42s`; 41 tasks: 12 executed, 29 up-to-date.
- The complete P7 gate passed 311 core tests and all 1,679 app cases with zero failures,
  zero errors and 38 existing opt-in skips. The fresh suite finished in 1,162.35 seconds;
  its captured console omitted the final lint task/footer. Repeating the same full task
  command retained those verified up-to-date results, observed lint (zero errors,
  25 warnings), and produced `BUILD SUCCESSFUL in 14s`; 43 tasks: 1 executed,
  42 up-to-date. All JUnit XML was readable.
- P7 uses three workers with the same fresh-JVM-per-class isolation. This environment
  has an eight-core CPU quota and 8 GiB memory; the portable verification init now records
  the passing three-worker setting. No test case, assertion or timeout changed. Reproduce
  the current gate with `./gradlew --no-daemon --max-workers=3 -I
  docs/redesign/verification.init.gradle :core:test :app:testDebugUnitTest :app:lintDebug`.

### P7 changed files

- `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/Widgets.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/P7WidgetPaletteTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/widget/P7WidgetStripeTest.kt`
- `docs/redesign/verification.init.gradle`
- `docs/redesign/BUG-LOG.md` and `docs/redesign/GPT-HANDOVER.md`

## Not done / limits

- No requested implementation phase remains outstanding. The 24 lint warnings are recorded above.
- No on-device, BedJet hardware, Samsung or TalkBack verification.
- The backup schema/strict-parser compatibility policy remains for Claude’s review, as the
  brief requires. Older backups load with defaults in the new app; older app versions may
  refuse newer backup fields. No schema or parser change is included.
- User-reported observation on 28 October 2026: the installed build used 0.1% battery
  overnight, recorded 23 minutes of background activity and worked as expected. The
  installed build/version was not specified. This is useful evidence for preserving the
  existing battery behaviour, not verification of the unfinished redesign APK.
- Direct sign-in access to the Claude artefact is unnecessary; its supplied source and captures
  are available for comparison.

## Foundation changes

No protected foundation changes: `QuietFan`, `FanShape`, `shiftFan`, compiler splitting,
`HeatScale`, the `Palette` enum, `Settings.palette()` and `ui/Palettes.kt` are unchanged.
The backup schema, codec and strict parser are unchanged. The required Hot Sleeper preset
and defaulted Cooldown-answer setting are recorded in P4 and P5; neither rewrites those
protected implementations.


## P8: polish and audit

- Settings now has the design’s eight destinations in four cards. Existing device, alarm,
  notification, backup, setup, Health Connect and diagnostic controls remain on those pages.
  Page selection survives recreation; alarm/backup navigation requests are consumed after
  routing so an old request cannot reopen a page when returning to Settings.
- Home places Quick controls immediately after the Night section while preserving saved
  section order/collapse and its truthful immediate Start action. The live reading uses
  the design’s bold display style, with a smaller large-text size to remain readable.
- Profile editing opens phase and Through the night controls in palette-coloured sheets.
  The phase overview comes before the name, season, timing and limit controls. Existing
  sliders, limits, draft, discard, Save, current-night confirmation and Turbo chaining remain.
  Legacy Turbo displays its effective 43°C / 109°F without changing stored data.
- Figtree and Bricolage Grotesque are bundled as static Android fonts from the supplied
  reference. `fonts/README.md` records provenance, conversion, coverage and checksums;
  exact upstream OFL notices are available through Settings → Help and about → Licences.
  Exact attribution text is packaged in `strings.xml` as a non-translatable resource;
  `P8TypographyTest` verifies it matches the two upstream OFL notices byte for byte.
- Long feedback uses a bounded, scrollable area and a labelled 48dp dismiss control;
  shorter messages retain Material’s compact layout. Full text and the host’s polite live
  region remain available. This fixes alarm feedback taking the whole small-screen viewport.
- The obsolete fixed Daylight schedule wording and unused Gentle heat resource are removed.
  Old Daylight scheme aliases are replaced by direct Overcast token references in tests.
  Source-audit dispositions and visual-review status are tracked in `P8-AUDIT.md`.
- New profile creation moves to the Profiles header, preserving the preset chooser and
  eliminating the floating button’s overlap with list content. At large text, long unavailable
  temperature labels get the header’s full width; short live readings keep the compact row.

### P8 test evidence and navigation changes

Observed RED before implementation: missing editor stripe, legacy Turbo displaying its stored
30°C rather than effective 43°C; missing Settings destinations/deep links; default Material
fonts and Home ordering/type size; inline editor controls and wrong overview order; missing
licence entry and obsolete schedule wording; replayed Settings navigation; missing Middle
summary values; mid-word large-header wrapping; New profile button below the offer.

The new tests are `P8LegacyTurboDisplayTest`, `P8EditorStripeTest`, `P8SettingsNavigationTest`,
`P8TypographyTest`, `P8HomeLayoutTest`, `P8EditorSheetsTest`, `P8ConsumedSettingsRequestTest`,
`P8MiddleSummaryTest`, `P8StatusHeaderLargeTextTest`, `P8ProfilesHeaderTest` and
`P8SnackbarLayoutTest`. The latter observed the underlying Recheck action becoming unreachable
while a long message was visible; existing U06 checks also caught the short-message wrapping
regression during the fix.
`P8CaptureTest` adds opt-in host captures for editor sheets, setup, all Settings pages,
populated History and actual Glance-rendered widget views in both palettes.

The existing navigation tests now enter the relevant Settings page and editor sheet before
checking the same feature. U06’s simultaneous inline slider count is checked by sheet instead:
three controls for each of three phases, five Middle controls and four range thumbs; every
original numeric value, spoken unit and adjustment assertion remains. Profile Edit still uses
real pointer injection and checks that it does not open the New profile chooser.

A temporary diagnostic compared real touch with semantic actions under both Robolectric
renderers. With bundled fonts, legacy rendering produced incorrect text bounds and missed the
sheet’s real touch; native rendering correctly handled that same touch. The affected editor
and alarm tests now use native graphics, retaining the interaction assertions. The diagnostic
was removed after use. This is host test evidence, not an on-device result.

The corrected native captures cover all eight requested areas in both palettes; see
`P8-AUDIT.md` for the reviewed states and limitations. The repository uses opt-in capture
tests, not approved pixel goldens; no golden baseline was weakened or replaced.
The full P8 gate passed; all 41 opt-in captures also passed on the same tested source.

### P8 changed files and test-class inventory

All P8 source, resource, test and documentation changes at the phase gate:

- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditor.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfilesScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/StatusHeader.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Theme.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightSections.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/features/FeatureSections.kt`
- `app/src/main/res/font/bricolage_bold.ttf`
- `app/src/main/res/font/figtree_bold.ttf`
- `app/src/main/res/font/figtree_medium.ttf`
- `app/src/main/res/font/figtree_regular.ttf`
- `app/src/main/res/font/figtree_semibold.ttf`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/strings_ui.xml`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppSmokeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppearanceTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/platform/FeatureUiTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/platform/HealthReadDemoBoundaryTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AccessibilitySemanticsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ActiveIndicatorTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AlarmAndShortTestSettingsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AlarmSettingsNavigationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/BackupOriginalsSettingsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/DaylightHeatPaletteTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/DiagnosticsDisclosureTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/FinalRemoteLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/FinalSettingsCleanupTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/FinalThemeAcceptanceTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ImportPreviewUiTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ImportedPresetRoleSettingsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ImportedTemporaryDurationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/LightThemeConfirmationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/MoreReleasePlacementTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/NightTimelineLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P4EditorBehaviourTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P6SettingsHistoryTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8CaptureTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8ConsumedSettingsRequestTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8EditorSheetsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8EditorStripeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8HomeLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8LegacyTurboDisplayTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8MiddleSummaryTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8ProfilesHeaderTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8SettingsNavigationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8SnackbarLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8StatusHeaderLargeTextTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/P8TypographyTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditorInteractionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ProfilesEditHitTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignPaletteConsentTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignPalettePickerTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RedesignProfilesStyleTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RemoteAccessorySwitchTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RemoteReconnectPresentationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RestoreOriginalDialogLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ScheduledThemeHeldOpenTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/SettingsBoostCopyTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/SettingsControlsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/SettingsTestNavigation.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/SetupRouteTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ThemeDemoIsolationTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/TonightLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06ControlSemanticsTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06PrimaryLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06RecoveryLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UatLayoutAndSetupTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UncertainOriginalsSettingsTest.kt`
- `docs/redesign/BUG-LOG.md`
- `docs/redesign/GPT-HANDOVER.md`
- `docs/redesign/P8-AUDIT.md`
- `docs/redesign/fonts/BRICOLAGE-OFL.txt`
- `docs/redesign/fonts/FIGTREE-OFL.txt`
- `docs/redesign/fonts/README.md`

Existing test-class changes listed above retain their feature assertions. In addition to the
Settings and sheet routes, `TonightLayoutTest` changes Quick’s ordering expectation,
`ProfilesEditHitTest` checks the fixed header instead of the former floating action, and
`DaylightHeatPaletteTest`, `FinalThemeAcceptanceTest`, `NightTimelineLayoutTest`,
`ScheduledThemeHeldOpenTest` and `ThemeDemoIsolationTest` resolve Overcast tokens directly.
`ScreenshotCapture` scrolls the real lazy-list key and scopes More to its card before capture.
`FinalSettingsCleanupTest`, `RestoreOriginalDialogLayoutTest` and `ImportPreviewUiTest`
enter About, BedJet and Backups respectively before their original feature checks.

The first full P8 gate completed 311 core tests and 1,704 app cases, with 21 app
failures and 41 opt-in capture skips; lint had zero errors and 24 warnings. It exposed
remaining test migrations: `HealthReadDemoBoundaryTest` and `FeatureUiTest` render the
new Health section explicitly; `RemoteAccessorySwitchTest` and
`RemoteReconnectPresentationTest` enter BedJet; `U06RecoveryLayoutTest` enters Backups.
`RedesignProfileChooserLargeTextTest` selects the editable profile name, distinct from
the new overview heading. `ActiveIndicatorTest` uses the existing Night collapse control to keep Quick’s marker
visible when feedback enters, before measuring its finite pulse and lifecycle gates.
All original command, receipt, permission, recovery, value and motion assertions remain.

The 42-case follow-up passed 39 cases and retained three marker-visibility failures.
An attempted scroll with the animation clock frozen stalled the host UI dispatcher; that
run was interrupted and is not a pass. The final fixture keeps the marker visible through
the existing Night collapse UI before freezing the clock. All nine `ActiveIndicatorTest`
cases then passed, including reduced motion, finite pulse, screen-off and paused lifecycle.
The full unfiltered P8 gate passed with these changes.

### P8 full gate

`./gradlew :core:test :app:testDebugUnitTest :app:lintDebug` passed using the committed
three-worker/fresh-JVM verification init: 311 core tests; 1,704 app cases, zero failures,
zero errors and 41 opt-in capture skips (the original 38 plus P8’s three). All JUnit
XML was readable. Lint reported zero errors and 24 warnings.

```text
BUILD SUCCESSFUL in 21m 1s
43 actionable tasks: 5 executed, 38 up-to-date
```

All 41 opt-in capture cases then passed on the same production/test source with
`SCREENSHOT_TAG=p8-complete-captures`: `ScreenshotCapture` (14),
`U06NormalCaptureTest` (12), `U06LargeTextCaptureTest` (12), and `P8CaptureTest` (3).
Zero failures/errors/skips; `BUILD SUCCESSFUL in 1m 12s`; 31 tasks: 1 executed,
30 up-to-date. Captures and the scoped visual audit are described in `P8-AUDIT.md`.

Independent review of the refreshed captures found no obvious text clipping, overlap or
unreadable enabled text in the inspected normal and 320dp/200% viewports in Midnight Ink
and Overcast. This is bounded host capture review, not full-scroll, device or TalkBack evidence.

## Final phase files and review

- Changed `docs/redesign/GPT-HANDOVER.md` and `docs/redesign/BUG-LOG.md` only.
- No new behaviour, tests, production source, resources, signing or release configuration
  changed in Final. No new RED test was needed for documentation and verification.
- An independent bounded review compared the full committed diff from foundation
  `42e6ba2aa26d4de8b26441d961eed7ae664f7e47` to P8
  `99111097a88effab237e57c417de427122280aef`, covering receipt truth, navigation, editor/widget
  callbacks, accessibility and protected foundations. No additional concrete regression
  was established. The clean full check then passed as recorded above.
- A final metadata-only commit records the Final verification commit hash in this file;
  neither documentation commit changes the tested app or its APK.

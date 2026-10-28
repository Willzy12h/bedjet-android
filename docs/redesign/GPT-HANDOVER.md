# Nocturne redesign handover

Work in progress. Base: `claude/nocturne-redesign-foundation` at
`42e6ba2aa26d4de8b26441d961eed7ae664f7e47`. Branch: `gpt/nocturne-redesign`.

## Phase checklist

- [x] P2: Theme wiring and Appearance — `283719032dda55f414e437205c258f79ecb0ba3d`
- [x] P3: Side-stripe tiles, Home / Tonight and Profiles — published hash recorded in the next phase entry
- [ ] P4: Profile editor, step editor and fan shapes
- [ ] P5: Cooldown
- [ ] P6: Guided setup, Help and about, Settings and History
- [ ] P7: Widgets v2
- [ ] P8: Polish and audit
- [ ] Final: clean verification, debug APK and handover

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
  controls. They remain available. Quick controls retain their established position above the
  reorderable sections. The existing immediate Start action keeps its truthful “Start night now”
  label; the mock's future-start wording is not mapped to an immediate command. Review can
  change these layout choices separately from the command and schedule behaviour.
- Git CLI publication has no interactive credentials in this environment. Phase commits are
  published through the connected GitHub service with the expected parent SHA and no force.
  Each resulting source tree is compared with the checked local tree before advancing the
  local branch to that same commit. No signing or release configuration changes are needed.

## Verification and APK

Pending. Toolchain restored into temporary build storage; no signing changes.

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

## Not done / limits

- All unticked phases remain outstanding.
- No on-device, BedJet hardware, Samsung or TalkBack verification.
- User-reported observation on 28 October 2026: the installed build used 0.1% battery
  overnight, recorded 23 minutes of background activity and worked as expected. The
  installed build/version was not specified. This is useful evidence for preserving the
  existing battery behaviour, not verification of the unfinished redesign APK.
- Direct sign-in access to the Claude artefact is unnecessary; its supplied source and captures
  are available for comparison.

## Foundation changes

None.

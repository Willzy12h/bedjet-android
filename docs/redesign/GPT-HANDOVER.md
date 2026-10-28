# Nocturne redesign handover

Work in progress. Base: `claude/nocturne-redesign-foundation` at
`42e6ba2aa26d4de8b26441d961eed7ae664f7e47`. Branch: `gpt/nocturne-redesign`.

## Phase checklist

- [x] P2: Theme wiring and Appearance — commit recorded in the next phase entry
- [ ] P3: Side-stripe tiles, Home / Tonight and Profiles
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

## Verification and APK

Pending. Toolchain restored into temporary build storage; no signing changes.

### P2 evidence (in progress)

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

## Not done / limits

- All unticked phases remain outstanding.
- No on-device, BedJet hardware, Samsung or TalkBack verification.
- Direct sign-in access to the Claude artefact is unnecessary; its supplied source and captures
  are available for comparison.

## Foundation changes

None.

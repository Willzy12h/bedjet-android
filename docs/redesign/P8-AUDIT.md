# P8 redesign audit

## Source audit

- `ui/Palettes.kt` contains the foundation palette and Ember literals. These are protected design tokens and remain unchanged.
- `ui/Theme.kt` retains the existing dark and light connected greens, as required by P2. `ui/StatusHeader.kt` retains explicit dark and light connection-state colours for status contrast; the state also has a text label.
- `ui/Theme.kt` uses black/white for computed contrasting on-colours and black for Material’s scrim; these are semantic contrast/masking roles, not replacement palette fills.
- `ui/TemporaryNativeDialog.kt` uses `GradientDrawable` with a solid `setColor` for the sheet background and rounded corners; it does not draw a gradient. The other gradient hit in `Palettes.kt` is a comment.
- The old Daylight theme aliases have been removed. The unused Gentle heat resource and outdated scheduled-theme wording have been corrected. The `Daylight` mention in the wake-label comment is not visible UI.
- Context-free `Format` and `NightCurve` helpers retain English fallbacks; UI callers supply resource-backed `Words`. The snapshot restore message helper is used only by unit tests, with no UI caller. `BEDJET-DEMO` is a device identifier, not localisable copy. The AppViewModel preview fallback now uses a string resource. New UI copy is in `strings.xml`. History and notification headings interpolate stored names, dates and titles with punctuation; they add no hard-coded English label.
- The bundled Figtree and Bricolage Grotesque font copyright notices and OFL text are in `app/src/main/res/values/strings.xml` (`font_licences_text`, non-translatable).

## Screen and controls review

Native Robolectric captures were inspected against the supplied design in Midnight Ink and Overcast. Checked items indicate visual inspection of the captured states, not device or TalkBack verification:

- [x] Home / Tonight
- [x] Profiles and presets
- [x] Profile and step editor
- [x] Through the night
- [x] Settings and Help and about
- [x] History and dialogs
- [x] Guided setup
- [x] Widgets

The captured Home retains profile selection and section reordering, wake-source explanations, demo state and truthful unavailable readings; these occupy more space than the compact mock. The Profiles offer and existing timeline remain visible with the New profile action in a fixed header. The editor retains mode limits, phase actions and the separate fan chooser in sheets; the widgets retain paging, Open app, and explicit last-report/unknown status. These are functional adaptations of the mock. The screenshots do not establish real-device interaction or TalkBack behaviour.

The corrected standalone capture host uses the palette ground, matching the main Activity. Populated History and its check-in dialog are legible in both palettes. Settings retains the existing detailed controls and explanations behind the new grouped index; setup preserves the real phone checks and BedJet selection flow rather than the mock’s sample steps. The captured setup state is demo mode; it does not verify pairing or platform permission behaviour.

## Current verification notes

The complete P8 gate passed: 311 core tests and 1,704 app tests, with 41 opt-in captures skipped in the ordinary gate; lint reported 0 errors and 24 warnings. A separate opt-in capture run passed all 41 cases. Refreshed `p8-complete-captures` Home, Profiles, editor, Settings index and populated History were re-inspected in both palettes; no obvious clipping appeared in those captured viewports.

The sheet Legacy graphics diagnostic was resolved using Native graphics; the actual touch
assertions were preserved. Large-text status-header and Settings request-consumption
corrections pass focused tests and the complete P8 gate. A bounded static review
found no additional concrete regression in these changes. Compared with the foundation,
protected Profile, PlanCompiler, HeatScale and Palettes files have no changes; Settings
has only the P5 cooldown-offer field.

## Independent typography and large-text capture review

Reviewed `p8-final-captures` 320dp / 200% Tonight, Profiles, History and Settings
initial viewports. The unavailable-temperature header wraps between its two words;
the New profile action no longer overlaps the Cooldown text. Visible text in those
viewports has no obvious clipping. This is a visual inspection of captured viewports,
not a complete scroll or device accessibility audit.

Reviewed the normal-size populated History captures and the Midnight Quick and
Overcast Single widget captures. The original Midnight History capture used the wrong host background; the corrected capture uses the palette ground and renders the content legibly.

## Reproducing captures

The opt-in tests write PNGs under `app/build/screenshots/<SCREENSHOT_TAG>/`.
`P8CaptureTest` covers the editor sheets, Settings pages, populated History, demo setup
and actual Glance-composed RemoteViews. `ScreenshotCapture` and `U06NormalCaptureTest`
provide the existing main-screen, menu and dialog states; `U06LargeTextCaptureTest` captures additional 320dp / 200% states. These are native
Robolectric captures, not approved pixel-diff goldens or installed-widget evidence.

```sh
SCREENSHOT_TAG=review ./gradlew --no-daemon --max-workers=3 \
  -I docs/redesign/verification.init.gradle :app:testDebugUnitTest \
  --tests "*P8CaptureTest" --tests "*ScreenshotCapture" --tests "*U06NormalCaptureTest" \
  --tests "*U06LargeTextCaptureTest"
```


## Claude review follow-up — pending rendered verification

The preceding captures/counts predate Claude's review and do not verify its visual changes.
The `SCREENSHOT_TAG=review` capture command was attempted after merging e6b1431; Gradle
bootstrap failed with Network is unreachable because the distribution/cache were pruned.
No new screenshots were reviewed. Opacity, Overcast edge borders, four tint strengths and
Forest/Graphite/Plum #171A2C sheets remain to be checked in actual renders. Source values
match the review decisions; no further production visual change was made. Bespoke sheet
colours remain a request to design. Independent sign-off is still outstanding.

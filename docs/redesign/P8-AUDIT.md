# P8 remediation audit and visual verification

Date: 29 October 2026. Branch: `gpt/nocturne-redesign-remediation`.
Application/test source: **c9704ee91b4d5642a90f7cf0b7f9d22b9ce619dd**. Final automated verification passed; independent review remains.

This replaces the earlier P8 counts, warning budget and pre-Claude capture claims. The starting report is preserved verbatim in `evidence/remediation/historical/P8-AUDIT.md`. The owner's current acceptance criteria apply: no failing tests, no actionable lint/compiler warnings, only documented capture skips in the ordinary gate, and all opted-in captures passing separately.

## Capture command

The complete opt-in command documented in the previous audit remains the capture scope; the requested tag is now `remediation`:

```sh
SCREENSHOT_TAG=remediation ./gradlew --offline --no-daemon --max-workers=3 \
  -I docs/redesign/verification.init.gradle :app:testDebugUnitTest \
  --tests "*P8CaptureTest" --tests "*ScreenshotCapture" --tests "*U06NormalCaptureTest" \
  --tests "*U06LargeTextCaptureTest"
```

The final capture run also requests `:app:lintDebug` after those tasks. There are **41** opt-in cases: ScreenshotCapture 14, U06NormalCaptureTest 12, U06LargeTextCaptureTest 12 and P8CaptureTest 3. Every case must execute with zero skips. In an ordinary gate those same 41 cases are the only permitted skipped tests.

The tests write full-resolution PNGs under `app/build/screenshots/remediation/`. The native window host uses the requested Android window size, resolving the diagnosed Robolectric wide-dialog measure loop. It does not change production dialog widths or test timeouts. GraphLifecycle, injected clocks and deterministic frame/idle helpers remain the fixture conventions. Sending is captured by holding the real operation owner, not by pretending a delayed display is a pending operation.

## Final results and preserved evidence

- Opt-in result: **41 passed, 0 failures, 0 errors, 0 skips; exit 0**.
- Ordinary gate: core **313 passed, 0 failures, 0 errors, 0 skips**; app **1,805 total; 1,764 passed, 0 failures, 0 errors, 41 opt-in skips**; **41, exclusively the four documented opt-in capture classes**.
- Lint: **0 errors, 0 warnings, 0 hints**.
- Project compiler/build warnings: **0**.
- External final-gate diagnostics: **0 warning diagnostics**.
- 1,003 PNGs (536 unique), 45 contact sheets, 5 lossless capture ZIPs, 10 reference-pairing sheets and 10 final-change review sheets.

`evidence/remediation/visual/capture-manifest.csv` maps every unchanged PNG to its SHA-256, dimensions, archive and contact-sheet cell. The numbered ZIPs preserve all captured windows, including repeated backgrounds; contact sheets show each unique image once. `capture-summary.json` records the capture command, actual exit, unchanged source hash, exact JUnit counts and old output files explicitly excluded by run timestamps. Nothing is counted from an earlier run merely because it remains in a build directory.

`build-capture-evidence.py` verifies the successful receipt and 41 tests / 0 failures / 0 errors / 0 skips JUnit counts before packaging. The reference pairing table in [DESIGN-CAPTURE-PAIRING.md](DESIGN-CAPTURE-PAIRING.md) covers every one of the 59 published images and the approved `reference/nocturne-final-design.published.html`. Ten paired contact sheets place a published reference beside a representative native capture. Use full-resolution PNGs to judge details; contact sheets are navigation thumbnails.

## Screen and interaction coverage

| Screen / state | Normal captures | 320dp /200% evidence | Review scope |
|---|---|---|---|
| Tonight | Idle/unavailable, confirmed running, held Sending, offline, wake picker/summary, quick controls, chooser and feedback bubble | Midnight Ink/Overcast with regular/simple views; running/idle, wake controls and quick defaults | Truthful state, full wake explanation, stable control position, touch targets, foreground contrast and scrolling |
| Profiles | Current cards, New chooser, overflow, Pin, Delete, profile import preview/details and quick presets | Header/actions, names/days, chooser and import/overflow routes through actual current UI | Whole-card Edit, reachable actions, opaque menus, preserved snapshot/import safeguards |
| Editor | Grouped phases, drag state, Middle endpoints/shapes, quiet fan, Heat/Long heat/Turbo, Add step and Type time | Midnight Ink/Overcast editor and interactive sheets, including top/bottom viewports | Measured drag, light Overcast surface, complete units, full mode/limit text, sliders/steppers and scroll reachability |
| Settings | All eight grouped destinations; palette rows, notification presets, backups, Night/alarm, permissions, BedJet, Health and About | Midnight Ink/Overcast destination top and control reachability | Real controls, receipt-backed status, readable selected chips and deep-link/request behaviour |
| History | Seeded records, details, issue/comfort states, check-in and suggestion dialogs | Midnight Ink/Overcast check-in choices and suggestion actions | Full labels, scrollable explanations, distinct Keep/Apply touch targets and persisted outcome |
| Setup and recovery | Existing phone checklist, remembered BedJet, recovery menu, loss confirmation | Checklist, loss explanation/confirmation and action paths | Approved tint once, real existing setup routes, full loss explanation and reachable actions |
| Running-night confirmation | Temporary/stop choice, duration, return explanation, cancel/accept | Separate top/bottom views at large text | Selected-state contrast and retained owner/target safety, not a fake UI-only command |
| Widgets | Actual Glance-composed Quick and Single RemoteViews across seven palettes | Existing responsive layout tests supplement captures | Stripe rows, paging/Open app, unknown/last-report wording; no installed-launcher claim |

All seven palette appearances are covered: Midnight Ink, Overcast, Forest, Graphite OLED, Plum, Autumn Rust and Winter Storm. Day/night selection rows continue to expose all seven choices. Palette override hosts may show an Appearance setting label from the seeded model while rendering the selected capture palette; this deliberate test-host difference is not an app setting claim.

## Palette, contrast and surface checks

1. **Opaque floating surfaces:** menus use their opaque palette surface, with dark menu `#232740`. Feedback bubbles, dialogs and sheets are independently captured; expected grey pixels outside a dialog are the platform window/scrim, not transparency inside the surface.
2. **Overcast:** the `#AAB0BA` decorative edges remain visible, with the approved 12% hairline hierarchy. The lifted editor row uses the light `surface2` background; it no longer puts dark text on `#262A45`. Existing sky-blue Start fill is retained with 20sp bold white text.
3. **Four tint strengths:** `.10` tile, `.24` selected/on, `.09` step and `.08` profile. Native pixel tests sample the real base and compute a single independent tint layer, allowing one RGB-unit rounding. The remembered-device double tint was reproduced and removed.
4. **Forest, Graphite and Plum:** their sheet background is `#171A2C`, as agreed. Their main grounds/cards remain their own palette colours. Menus and sheets are different roles.
5. **Text and meaningful indicators:** foregrounds are measured from production TextLayoutResult or rendered pixels. Text meets the applicable 4.5:1/3:1 threshold; selected borders, radios/checkboxes and slider indicators meet 3:1 against the relevant adjacent surface. Decorative outlines are not misrepresented as the sole control cue. All palette literals and mode tints remain intact.
6. **Selected chips and Add-step/shape selection:** raw mode colour failed against some actual fills. Readable palette ink is used where required, with a distinct selected chip fill and inherited readable limit text. Selection semantics and editing callbacks remain unchanged.

Visual review itself found defects after an earlier 41-case capture run passed. UI-19 records control contrast, UI-20 normal-size Middle units, UI-21 selected-state contrast and UI-22 History action overlap. Each was reproduced before production correction. The subsequent evidence is the source for final visual claims; earlier images are retained only as diagnostic context.

## Accepted differences from the published mock

- Captures use deterministic demo profiles, clock/receipt states and seeded History; names, times, fan values and record counts differ.
- Start is the functioning immediate action and says “Start night now”. Alarm-source, pending/unknown state, mode-limit and safety explanations retain the real semantics.
- The current compact four-tab Tonight/Profiles/History/Settings structure is authoritative. Remote is a Tonight subpage. Removed collapse/reorder/global-banner UI is not restored for obsolete tests; compatible stored fields remain.
- The profile/phase editor uses the approved existing sheets and fan sliders. Proportional plots retain complete narrow/large-text labels beneath the bars. Long heat and legacy Turbo explanations may occupy more space than illustrative copy.
- Large text uses extra wrapping, stacked values and vertical scrolling. Paired top/bottom screenshots show the real viewports; they do not pretend all content fits simultaneously. The native glyph/layout tests and touch checks verify controls independently of a thumbnail.
- Setup keeps the existing functioning checklist and BedJet chooser. A design-only multi-stage wizard is explicitly marked as having no direct production counterpart in the pairing table.
- Widgets retain final R14 stripe rows, page navigation, Open app and truthful unknown/last-report labels. The illustrative four-tile mock does not justify removing working actions.
- Platform chrome, keyboard/insets and native component metrics are not pixel-diff goldens. No pixel-perfect parity is claimed.

## Source, resources and warnings

New visible text remains in resources with UK English. Dynamic names/dates/device identifiers are data. Context-free core formatting fallbacks are not new hard-coded screen copy; UI supplies resource-backed words. Bundled fonts keep their licence notices. Solid rounded `GradientDrawable` backgrounds are not colour gradients.

Unused resources and unreachable old UI were removed. No broad `app/lint.xml` remains. [WARNING-AUDIT.md](WARNING-AUDIT.md) records all observed diagnostics and the 38 remaining narrow platform/test exceptions; zero actionable warnings does not mean those exceptions are concealed. The source review and final logs are part of the evidence.

## Limits and independent review

These are native Robolectric captures and host tests. Physical BedJet/Samsung/TalkBack validation, installed widget/Quick Settings behaviour, overnight battery restrictions, physical display/keyboard/insets and real platform-provider/alarm delivery were not performed. Review of captures is the implementer's self-review. Claude's independent review and device validation remain separate activities.

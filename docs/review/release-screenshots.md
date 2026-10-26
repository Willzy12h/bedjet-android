# Release screenshot comparison

## Scope and capture contract

Review base: `4fe524375fa2652ff3b97f3c1e942b35bf280155` on `gpt/nocturne-release`.
These are real Compose renders produced by Robolectric native graphics with the
demo BedJet. They are not photographs, generated mockups, physical-phone captures,
Bluetooth evidence or runtime checks of the minified release APK. No production UI
or resource edits are part of this release work.

The after capture completed against release implementation
`b0e4e1b093c738ae5dde9d7106250c0c39ef8bc7`. All **six screenshot tests passed**
with no failures, errors or skips. The comparison helper found **38 of 38 expected
captures, 28 historical counterparts, ten unbaselined views and no missing
historical baseline**; every capture passed its fixture checks. All 38 after PNGs
were visually inspected. This is a completed capture and review, with the specific
coverage limits and visible overlaps below; it is not a blanket accessibility or
physical-device pass.

The test-only fixture
`app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt` pins
`Graph.clock` **before the Activity is created** to
`2026-10-22T22:30:00Z` in `Europe/London` (23:30 local). Each PNG asserts the actual
clock, resource width and font scale and writes those values into an adjacent JSON
record. The opt-in check executes before launching the Activity when screenshots
are not requested.

| Look | Width / font | Captures | Historical counterparts |
| --- | --- | ---: | ---: |
| Dark | 411 dp / 100% | 7 | 7 |
| Light | 411 dp / 100% | 7 | 7 |
| Simple Dark | 411 dp / 100% | 7 | 7 |
| Simple Light | 411 dp / 100% | 7 | 7 |
| Combined narrow and large text | 320 dp / 200% | 7 | 0 |
| Additional views: underlying Tonight, platform settings, History export entry | 411 dp / 100%, Dark | 3 | 0 |
| **Total** | API 34; 891 dp height; xxhdpi | **38** | **28** |

The seven main screens are Tonight, Tonight scrolled to lower controls, Profiles,
Remote, History, Settings and Tonight with a demo night running. The combined
320 dp / 200% case exercises both constraints simultaneously. There are no
historical combined-stress or feature-view PNGs in `docs/screenshots/baseline`;
the report marks those ten captures **UNBASELINED**. No baseline is invented or
replaced.

## Commands and output

```bash
SCREENSHOT_TAG=after ./gradlew :app:testDebugUnitTest \
  --tests '*ScreenshotCapture*' --rerun-tasks
python3 -B .github/workflows/release_screenshots.py \
  --baseline docs/screenshots/baseline \
  --after app/build/screenshots/after \
  --output app/build/reports/release-screenshots
```

The comparison helper uses Pillow 12.3.0. It checks the exact 38-file capture set
and the exact 28 historical baseline filenames. A deleted historical baseline is
an error (`MISSING_BASELINE`), not an additional unbaselined view. It also checks
per-image metadata, tag `after`, pinned clock, theme, Simple setting, demo mode,
width/font/density and image dimensions. It fails incomplete or inconsistent
evidence. The raw after PNGs and sidecar records remain in
`app/build/screenshots/after`. The comparison directory contains JSON and Markdown
reports plus `index.html` with before, after and amplified-difference views.

Observed raw dimensions are **1233 × 2673 px** for the 411 dp views and
**960 × 2673 px** for the combined 320 dp / 200% views. The recorded density is
480 dpi and height is 891 dp. Every sidecar records `tag=after`, the
same fixed instant/zone, and the required theme, Simple setting and demo mode.
The JUnit evidence is
`app/build/test-results/testDebugUnitTest/TEST-io.github.willzy12h.nocturne.ScreenshotCapture.xml`
(six tests, 27.232 seconds). The comparison report is
`app/build/reports/release-screenshots/report.json`, SHA-256
`4d3d95ac3e2d9441c3a4414550b4291e2fde6b238919990f4cffa52b7117922d`.
Its per-image records include the raw after and historical baseline SHA-256 values.

`python3 -B .github/workflows/test_release_screenshots.py`: **8 tests passed**.
The negative checks cover a deleted historical baseline while every after image
is present, a fabricated new historical baseline name, missing/stale after
captures, wrong clock/zone/tag, incorrect combined width/font/demo values, and a
PNG whose dimensions disagree with the required viewport. These helper tests do
not create or modify any real baseline image.

## Meaning of a pixel difference

The 28 committed baseline images are each **617 × 1337 px**. Their early-morning
clock was not pinned; this is recorded in `docs/review/ui-preflight.md` and is
visible in the Simple Tonight image (06:34 start). They also predate the integrated
hardening and platform changes. They are therefore historical comparisons rather
than a same-state golden master for this release-only patch.

The helper downsamples review copies of the after renders to the historical
baseline dimensions using LANCZOS. It reports changed-pixel percentage, percentage
whose largest RGB-channel difference exceeds 8/255, mean absolute RGB difference
and a difference bounding box. Pixel differences include application changes,
fixture/clock/state changes and resampling. No arbitrary threshold converts those
metrics into a claim that the UI passed visual review.

The baseline's Dark/Light colours, layouts and text are the comparison material.
The reviewer must describe concrete visible differences and distinguish
off-screen scrollable content from clipping. The narrow/large-font images need
their own inspection because no valid before image exists.

## Observations

### Historical comparisons: Dark, Light, Simple Dark and Simple Light

The Dark and Light appearances retain their recognisable backgrounds, cards,
colour roles and five-tab navigation. Dark uses the large wake-time headline;
Light retains paired start/wake cards. Simple replaces the Tonight curve with the
step timeline in both themes. The appearance selection and Simple switch states
match their capture metadata. These observations concern the rendered pixels;
this release patch did not redesign the UI or replace a baseline.

The historical PNGs predate the integrated UI and platform work already present
at the review base. The differences below must therefore not be attributed solely
to this release patch. The pinned evening clock, revised content and different
scroll positions also contribute to the pixel counts.

| Screen / capture suffix | Concrete visible comparison |
| --- | --- |
| Tonight, `01-tonight` | The setup-permission card and demo/disconnected state remain visible. Dark still shows 07:00 as its large wake time. Light's Starts card now reads **23:30**, versus **06:34** in the historical image. Simple's visible timeline begins at 23:30 and continues at 00:00; the setup card still occupies much of the first viewport. |
| Lower Tonight, `01b-tonight-lower` | **Set tonight-only wake** and **More** appear below Start night now. The four Quick actions are arranged in **two columns and two rows**, versus the historical single row of four. All four labels and Boost's duration are visible above the navigation bar in each 411 dp lower capture. The curve begins at 23:30. In Simple, the middle step reads **6 h 40 min**, replacing the historical **23 h 36 min** early-morning preview; the next heat step is 06:40 and wake is 07:00. |
| Profiles, `02-profiles` | Summer Cool and Winter Warm retain their cards and programme curves. The visible action row is **Use tonight / Edit / More**, replacing **Use tonight / Edit / Copy / Delete**. The Dark historical example starts its profile preview at 22:30; the after preview starts at 23:30. The floating New profile button overlaps part of the next card in both the historical and after 411 dp images; that overlap is not introduced by this release patch. |
| Remote, `03-remote` | The demo/disconnected chip and connection instruction remain. No connected-device sliders or hardware state are shown. This is the smallest measured difference group; it does not test connected Remote operation. |
| History, `04-history` | **Export history CSV** is visible above the existing empty-history explanation, shifting that explanation down. No populated history, CSV options or document-picker UI is pictured. |
| Settings, `05-settings` | **Scheduled** is the fourth theme chip. It fits on the same row at 411 dp. The Simple switch is on only in the Simple captures. Demo mode, slot selection, Fahrenheit option, Forget this BedJet and the visible wake-up action chips remain recognisable. Scheduled-theme configuration is not opened. |
| Running Tonight, `06-tonight-running` | The **End night** button and running Cool/fan 70% state remain visible. The night curve or Simple timeline uses the pinned evening schedule. The historical inline Re-check alarm row is absent from these after viewports; the after layout instead shows the wake override and, where the viewport reaches it, More. The Simple Dark after image still contains a start-confirmation snackbar; the Dark, Light and Simple Light after images do not. The historical running images also contain that snackbar, so this transient-state difference contributes to the comparison. |

The Simple Dark running snackbar sits above the bottom navigation and the next
wake-override control is only partly visible above it. The capture does not prove
that every lower control can be exposed while that snackbar is present. The
fixture's six-second looper advance is not evidence that every transient UI state
has disappeared. Likewise, the floating New profile overlap is visible evidence,
not a claim that the whole card is unusable: further scrolling was not captured
for that state.

### Measured differences

Across the 28 comparisons, **3.19–74.53%** of normalised pixels differ at all. The
following table reports the percentage whose largest RGB-channel difference is
**greater than 8/255**. It is descriptive and is not a pass/fail tolerance. The
largest differences are the lower Tonight views, where controls, timing and
scroll positions changed together.

| Screen | Dark | Light | Simple Dark | Simple Light |
| --- | ---: | ---: | ---: | ---: |
| Tonight | 4.37% | 4.75% | 4.75% | 4.93% |
| Lower Tonight | 62.64% | 71.10% | 70.20% | 69.75% |
| Profiles | 4.69% | 5.10% | 4.69% | 5.10% |
| Remote | 1.13% | 1.19% | 1.13% | 1.19% |
| History | 2.27% | 2.17% | 2.27% | 2.17% |
| Settings | 4.83% | 5.04% | 4.89% | 5.08% |
| Running Tonight | 35.22% | 38.82% | 17.85% | 32.42% |

The complete per-image metrics, hashes and amplified-difference images remain in
the generated report. Resampling means that even visually similar text and icons
can contribute nonzero pixel differences.

### Combined 320 dp / 200% font: seven unbaselined views

These seven captures are **UNBASELINED**. They establish the observed layout at
both constraints simultaneously; they do not provide a before/after result or
separate 320 dp-only and 200%-only comparisons. The metadata confirms 320 dp,
font scale 2.0 and the expected raw image dimensions.

| Capture suffix | Observed layout and limits |
| --- | --- |
| `01-tonight` | The demo/disconnected chip wraps onto two lines. Setup explanations wrap heavily beside their Fix buttons. The third setup item continues below the viewport, before the wake card is reached. This is vertical content in a scrollable screen, not evidence of a horizontally clipped label. |
| `01b-tonight-lower` | Start night now fits within its button. Set tonight-only wake wraps onto two lines within its outline. The chart's Wake 07:00 label wraps cleanly. Boost, Warm, Cool and Off are all visible in a two-column layout; Boost's 15 min detail is visible. No horizontal text truncation was observed in these controls. |
| `02-profiles` | The first card's description wraps, the chart's wake label uses two lines and More moves onto a new action row. The floating New profile button overlaps text in the partly visible Winter Warm card below. That overlap requires a scroll/interaction check; the screenshot alone does not prove full card access. |
| `03-remote` | The disconnected chip and the three-line connection instruction fit within the viewport. No connected Remote controls are present. |
| `04-history` | Export history CSV fits on one line; the empty-state explanation wraps across four lines without observed horizontal clipping. |
| `05-settings` | Theme chips wrap into two rows. Simple and Demo explanations wrap beside switches that remain within the screen width. The slot section begins at the bottom and continues below the viewport; lower settings are not reviewed at this size. |
| `06-tonight-running` | The phase text wraps onto two lines. End night, the chart, the two-line wake override, More and Stop tracking remain readable within their controls. The Quick heading begins below these controls; the running state's lower Quick controls are not visible in this particular capture. |

All seven views use the existing **icon-only bottom navigation**. The fixture
successfully navigates using the tab content descriptions at this size. That
checks the selectors' existence and operation in this host test; it is not a
TalkBack traversal or physical touch-target measurement. The visible first-card,
primary-action and Quick labels above show useful wrapping, but these seven
viewports do not justify a general claim of no clipping anywhere in the app.

### Additional unbaselined views: what the PNGs actually show

| Capture | Status and visible evidence |
| --- | --- |
| `features-01-idle-handover.png` | **UNBASELINED.** The PNG shows the underlying idle Tonight screen with Start night now, Set tonight-only wake and More. It does **not** show a handover menu item. The fixture clicks More, but draws only the Activity's `decorView`; `NightRecoveryControls.kt` opens a `DropdownMenu`. This PNG must not be counted as a visual review of that popup or of handover confirmation/operation. |
| `features-02-platform-settings.png` | **UNBASELINED.** The viewport shows no configured alarm, Phone setup, the real-device selection card with Demo on, Find BedJet 3 units and the off Bedtime reminder switch at the bottom. Charging and Health Connect sections below this viewport are not pictured. |
| `features-03-history-export.png` | **UNBASELINED.** Empty History and the Export history CSV entry are visible. The fixture does not open the export options or a document picker and does not demonstrate a written CSV file. |

Coverage and fixture integrity therefore **PASS**, and the visual differences are
recorded above. The ten images without historical counterparts remain explicitly
unbaselined. No missing historical image was waived, no baseline was fabricated,
and no UI or resource edit was made to change these observations.

## Limits

The fixture captures representative viewport states, not every scroll position or
dialog. It does not establish physical touch-target behaviour, TalkBack traversal,
Samsung font rendering, haptics, widgets on a real launcher, Health Connect data,
notification delivery or BedJet communication. Those remain supervised device
acceptance work. A successful capture/metadata gate is separate from the visual
findings recorded above.

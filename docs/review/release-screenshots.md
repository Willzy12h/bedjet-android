# Release screenshot comparison

## Scope and capture contract

Review base: `4fe524375fa2652ff3b97f3c1e942b35bf280155` on `gpt/nocturne-release`.
These are real Compose renders produced by Robolectric native graphics with the
demo BedJet. They are not photographs, generated mockups, physical-phone captures,
Bluetooth evidence or runtime checks of the minified release APK. No production UI
or resource edits are part of this release work.

The after capture is pending the integrated host gate. The test-only fixture
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
| Feature views: idle handover, platform settings, history export | 411 dp / 100%, Dark | 3 | 0 |
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

Pending after capture and visual inspection. Initial baseline inspection confirms
the setup-permission card on Tonight, an early-morning Simple schedule, a
disconnected Remote state, and the older theme/settings controls. These
observations do not predict the actual after pixels or count as a completed
comparison.

## Limits

The fixture captures representative viewport states, not every scroll position or
dialog. It does not establish physical touch-target behaviour, TalkBack traversal,
Samsung font rendering, haptics, widgets on a real launcher, Health Connect data,
notification delivery or BedJet communication. Those remain supervised device
acceptance work. A successful capture/metadata gate is separate from the visual
findings recorded above.

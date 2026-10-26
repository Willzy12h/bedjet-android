# UX screenshot gate correction

Base UI commit: `a95f8e2c11eda7e101e9c26c4797bea771c9c738`.

## Scope

The release inventory previously required only the 38 legacy after captures. `ScreenshotCapture` also has eight existing `captureGaps` cases, each producing eight PNGs. The gate now requires the complete **102-file inventory**. The **28 historical baselines remain unchanged**; the ten previously unbaselined views and 64 gap views are reported as **74 unbaselined views**. No PNG, baseline, build configuration or production UI is changed by this correction.

The gap looks are `dark`, `light`, `simple-dark`, `simple-light`, `compact-dark`, `compact-light`, `compact-simple-dark` and `compact-simple-light`. Compact captures use 320 dp / 200%; the others use 411 dp / 100%. All retain SDK 34, 480 dpi, 891 dp height, the fixed instant `2026-10-22T22:30:00Z`, Europe/London, native graphics and the `after` tag.

| Existing gap scene | Captured window | Demo mode |
| --- | --- | --- |
| `01-slot-pin` | Dialog | On |
| `02-wake-expiry` | Activity | On |
| `03-wake-picker` | Dialog | On |
| `04-resume` | Activity | On |
| `05-checkin-dialog` | Dialog | On |
| `06-checkin-suggestion` | Dialog | On |
| `07-setup-chooser` | Activity | Off |
| `08-unreadable-banner` | Activity | Off |

Dialog captures use their own decor view, whose content height can differ from the activity. Their metadata must match the real PNG dimensions; their width must match the full configured screen, and height must be positive and no greater than the screen. All legacy and gap activity views retain exact full-screen dimensions. The existing renderer, SDK, tag, clock, width, height, density, font, theme, Simple-view and baseline validation remains enforced.

## Observed test evidence

- `evidence/ux-ui-screenshot-inventory-red.log`: **14 test methods executed**, failing with 336 subtest assertions before implementation. Failures covered the missing 64-file inventory, all required gap views, fabricated historical gap entries, look/scene metadata and the report omitting the gap rows.
- `evidence/ux-ui-screenshot-inventory-green.log`: **14 passed**, zero failed, in 6.434 seconds. The report test copies real archived gap PNGs into a temporary test directory and supplies test-only metadata; these files are not release evidence or historical baselines. It checks all 64 rows are `UNBASELINED`, all 38 deliberately absent legacy views still fail the gate, and every actual historical baseline hash remains unchanged.
- `evidence/ux-ui-screenshot-stop-red/`: with `SCREENSHOT_TAG=after`, **one executed capture, one failure, zero skipped**. `ScreenshotCapture.dark` saved the six earlier views, then failed with `ComposeTimeoutException: Condition still not satisfied after 15000 ms` at the literal `End night` wait. The test took 24.711 seconds; Gradle exited 1 after 101.4 seconds. Those six PNGs and their sidecars are preserved under this run's `captures/` directory. The only capture-helper change replaces the literal with the existing `R.string.tonight_end_night` resource.
- `evidence/ux-ui-screenshot-stop-green/`: the identical opt-in command and temporary resource fixture passed, **one executed, zero failed/skipped**, Gradle exit 0 after 53.02 seconds. All seven Dark PNGs and their sidecars are preserved under `captures/`. Inspection of the newly generated running-night filename exposed a separate fixture timing defect: it still showed a pending night, because Stop is now available before confirmation. This run proves the renamed-selector correction, not a confirmed running view.
- `evidence/ux-ui-screenshot-running-red/`: a new explicit assertion reproduced that observed defect: **one executed, one failed, zero skipped**, `Running capture needs a confirmed night expected:<CONFIRMED> but was:<PENDING>`. The test took 9.751 seconds; Gradle exited 1 after 60.72 seconds. The six earlier captures are preserved. The wait now requires the active night and displayed night status to be confirmed and the command owner to be idle, and the explicit assertions remain before the image is saved.
- `evidence/ux-ui-screenshot-running-green/`: **one executed, zero failed/skipped**, Gradle exit 0 after 53.54 seconds. All seven Dark captures and sidecars are preserved under `captures/`; the running view now passes the explicit confirmed-state assertions. This is the final focused capture verification, not the full 14-case integrated matrix.

The regression suite retains the previous checks for deleted historical baselines, invented historical counterparts, missing/stale after files, incorrect fixed clock/zone/tag, wrong compact width/font and inconsistent PNG dimensions. New checks exercise all 64 gap look/scene combinations, reject incorrect theme/Simple/Demo/width/font/pixel fields, and reject invalid activity/dialog bounds.

## Temporary renamed-label fixture

The isolated UI checkout predates the root P2 wording change. For its opt-in capture regression, only the root `strings_runtime.xml` and `strings_ui.xml` were copied temporarily. The former retained its one legacy `runtime_boost` entry solely because the isolated base still references that resource. Original, root and temporary resource bytes and SHA-256 values are preserved outside the checkout in `evidence/ux-ui-screenshot-temporary-resources/`. Both files were restored byte-for-byte after GREEN and are excluded from this commit.

Source references: `app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt:248` (confirmed-state wait and resource selector), `.github/workflows/release_screenshots.py:23` (matrix), `:48` (metadata), and `.github/workflows/test_release_screenshots.py:47` (inventory), `:112` (full metadata matrix), `:142` (unbaselined report and baseline integrity). `git diff --check` passed.

The full current integrated opt-in capture and final screenshot evidence gate remain root-owned. Archived gap PNGs are regression fixtures, not new candidate captures or new before/after comparisons. No physical-device or visual approval is inferred from these script checks.

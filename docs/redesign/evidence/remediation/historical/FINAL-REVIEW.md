# Nocturne parity final review

Date: 29 October 2026
Claude base: `8a249bbee2a8dff05c35b7a8ee9604e2b9720d91`
Completion branch: `gpt/nocturne-redesign` at `8869a7ea1b686a80bd7c4f1cd109949e43517c24`
Review branch: `gpt/nocturne-redesign-final-review`

This is a self-review for Claude to continue. It is not the independent final sign-off.

## Verified

- The app compiles and `:app:assembleDebug` succeeds. Standalone lint passes with zero errors, 22 warnings and 4 hints.
- Core passed in the exact clean gate.
- `DemoImportPreviewInvalidationTest` passed five fresh runs (20 executions).
- All 41 opt-in capture tests passed and produced 270 PNGs.
- Midnight Ink and Overcast screenshots were reviewed. Menus and floating surfaces are opaque; Overcast card edges remain visible; tint strengths are distinct; Forest, Graphite and Plum sheets use `#171A2C`.
- Settings, History, setup and widget renders match the approved hierarchy while retaining real app functions. History's redesigned step bar now has an accessible description.

## Open test and behavior findings

The exact gate currently reports 238 failed app tests out of 1,708, with 41 capture skips. These findings predate the final-review branch and must be resolved before release sign-off.

### High priority: Claude-owned screen regressions or unmigrated assertions

- **Tonight section behavior:** collapse/reopen, saved order/reorder actions, fixed wake positioning and several wake-source summaries no longer satisfy the existing acceptance tests (`TonightLayoutTest`, `TonightAcceptanceTest`, `GapsCoverageTest`, `TonightWakeLabelLayoutTest`). Confirm which behaviors remain required, restore them where lost, and migrate only selectors that changed visually.
- **Profile editor behavior:** phase control expansion, add-step entry, slider semantics, picker initial values and parked draft restoration fail across `ProfileEditorInteractionTest`, `P4EditorBehaviourTest`, `PickerInitialTimeTest`, `NavigationRestoreTest`, `U06ControlSemanticsTest` and related tests.
- **Profiles behavior:** preset chooser reachability at 200% text, overflow actions, cooldown offer interactions and new-profile header reachability fail in the redesigned screen.
- **Status and Remote presentation:** several old status-header assumptions, Remote accessory interactions and reconnect presentation checks fail. Some are selector migrations; retain the underlying command/confirmation behavior.
- **Alarm link from Tonight:** the ignored-alarm explanation is no longer visible/clickable, breaking the deep link into Night settings and its consumed-request regression tests.

### Medium priority: layout/accessibility

- Large-text reachability still fails in parts of Tonight, editor, setup/recovery and settings. The U06 fixture now correctly accepts tap-to-dismiss short feedback bubbles, exposing the real later failures.
- The Overcast filled Start label reports 3.32:1 in `RedesignTextContrastTest`; confirm the intended large-text classification or adjust the approved token/style.
- `TonightScreen.kt` has an always-true condition at line 186.
- The lifted editor drag card still uses hard-coded `#262A45`; review its Overcast drag-state appearance against tokenized surfaces.

### Environment-only verification blocker

Six `HealthLegacyContainmentSecurityTest` cases request Robolectric API 31 and 33 instrumented Android jars. This environment has API 34/35 cached and cannot reach Robolectric's Maven host under the current network allowlist. Do not disable these tests; run them in CI or an environment with those two artifacts cached.

## Accepted differences

- Widgets retain R14 stripe rows, paging and all existing actions rather than the mock's four tiles.
- Settings subpages keep real controls; design-only update-check and invented flows were not added.
- Setup keeps the functioning pairing/checklist flow rather than inventing a seven-stage wizard.
- Running Tonight keeps real controls and six quick presets.
- Captures use deterministic demo data, so text differs from static mock sample names and times.

## Required next gate

```sh
./gradlew --offline --no-daemon --max-workers=3 -I docs/redesign/verification.init.gradle clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Release readiness requires zero test failures, lint at zero errors and no more than 24 warnings.

## Debug APK candidate

A debug APK was assembled from the same source tree for review and device testing. It is a debug-signed candidate, not a release artifact.

- Package: `io.github.willzy12h.nocturne`
- Version: `0.1.0` (`versionCode 1`)
- Minimum SDK: 31; target SDK: 35
- Size: 70,855,068 bytes
- SHA-256: `3b2317aea7a74334475f4cc45e1c1ecd18bc339f45c1a3835ed18204eb41a8d4`
- Debug certificate SHA-256: `ebef59843edabb07ce8b112cac8cecc2edd411a929ff370f7d1b8d64a18f45bb`

Do not treat this candidate as release-ready while the full app suite is red.

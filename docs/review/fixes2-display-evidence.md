# fixes2 — responsive controls with bounded sensor updates

## Historical provenance

This document is preserved from remote commit `367968bddc390e5b2bb12876fc1662ffa07e47c3` (tree `f1d7b8638693d1d161891ae331c8e68d868f0dff`). Its runs and source hashes describe the separate continuation in `/workspace/scratch/93c42f112bf4`; they do not verify the local `e114cc25481731e052a0abc564e74775666ec47e` tree or the later union.

The historical hidden/status-loss behaviour below predates the user's retained-card amendment. The current local implementation retains the selected target's last reported card while hidden or disconnected, invalidates live control status, and clears the cache when the selected target changes. The union preserves that behaviour and adapts the old-target sensor test accordingly. The recorded RED/GREEN results below remain historical evidence, including their original expectations.

## Regression and root cause

The Step A integration gate exposed eight presentation regressions: all four look variants of `U06ControlSemanticsTest.remoteRowsAreSingleToggleableTargets` retained an old Lights switch state, and all four variants of `U06PrimaryLayoutTest.remoteModeTimerAndBoostControlsAreReachableAboveFeedback` could not find the timer controls after the unit reported Heat.

The raw report had already changed in those tests. `AppViewModel.status` applied its one-second delay to the entire `DeviceStatus`, including the mode, chosen temperature, fan, countdown and accessory flags. It therefore delayed the control state as well as the frequently changing sensor readings. A first valid report after an initial null value was also held behind that delay.

Inspected integration evidence: `/workspace/scratch/93c42f112bf4/evidence/stepa-full-gate/app/`. The other Step A gate failures were outside this display fix.

## Implemented behaviour

Only `actualTemperature` and `ambientTemperature` use the one-second presentation cadence. They remain a pair from the same received status. The rest of the latest selected-target status is combined with that sampled pair without an artificial delay.

- Mode, target temperature, fan, remaining time, programme progress, Lights, Beeps and notification details remain live.
- Sensor-only bursts do not publish equal presentation values or release samples early when a control changes.
- Hiding the screen cancels both presentation subscriptions and clears status.
- Foreground return and target changes create a new sensor stream, allowing its first valid snapshot immediately.
- A new target starts with no displayed status until a valid report is available. A missing or mismatched-target raw report clears controls immediately; a delayed sensor emission cannot bring it back.
- Raw `SourcedStatus` objects and receipt orders are unchanged. The copies combine fields for presentation only.
- This introduces no polling loop, transport operation, protocol change or new hardware owner.

Implementation: [AppViewModel](../../app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt). Regression cases: [ForegroundStatusTest](../../app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ForegroundStatusTest.kt).

## Observed RED

Baseline snapshot: `333a118a0fbe245a4e45b1ea0dc288dc49b79206`. The six additional tests were written before changing production code.

Run: `stepa-display-red-isolated`.

| Suite | Tests | Assertion failures | Errors | Skips |
|---|---:|---:|---:|---:|
| ForegroundStatusTest | 9 | 5 | 0 | 0 |

The five failing expectations were:

| Boundary | Expected | Old presentation |
|---|---|---|
| Mode change | Cool immediately | Heat |
| Accessory/progress change | New Lights, Beeps, step and notification fields | Previous fields |
| Target temperature, fan and timer change | New control values before the sensor deadline | Previous chosen temperature |
| Status loss | Null immediately | Previous complete status |
| First valid new-target report | Its temperature snapshot immediately | Null until the old one-second delay ended |

The existing hidden-screen, foreground-return and raw-receipt/cadence cases passed. The new sensor-only emission-count control also passed. The RED run exited 1 after 100.8 seconds, with the intended assertions rather than compilation or fixture errors.

Command selection through the shared runner:

```sh
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/stepa-display-review \
  --label stepa-display-red-isolated -- \
  :app:testDebugUnitTest \
  --tests io.github.willzy12h.nocturne.ui.ForegroundStatusTest
```

An earlier runner labelled `stepa-display-red` was stopped while waiting for a shared lock. It executed no tests and is excluded from evidence. The recorded runs used the isolated GPT11 Gradle cache and lock.

## Verified GREEN

Run: `stepa-display-green`. The same nine foreground tests were retained unchanged for GREEN. The previously failing U06 methods and the existing accessory-confirmation tests were also selected; none of their assertions were weakened.

| Selected suite or method | Passed | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| ForegroundStatusTest | 9 | 0 | 0 | 0 |
| RemoteAccessorySwitchTest | 4 | 0 | 0 | 0 |
| U06ControlSemanticsTest.remoteRowsAreSingleToggleableTargets — four looks | 4 | 0 | 0 | 0 |
| U06PrimaryLayoutTest.remoteModeTimerAndBoostControlsAreReachableAboveFeedback — four looks | 4 | 0 | 0 | 0 |
| **Total** | **21** | **0** | **0** | **0** |

The runner exited 0 after 99.65 seconds. The existing deprecated API and Gradle warnings remain in the log; there was no compilation error.

```sh
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/stepa-display-review \
  --label stepa-display-green -- \
  :app:testDebugUnitTest \
  --tests io.github.willzy12h.nocturne.ui.ForegroundStatusTest \
  --tests '*U06ControlSemanticsTest.remoteRowsAreSingleToggleableTargets*' \
  --tests '*U06PrimaryLayoutTest.remoteModeTimerAndBoostControlsAreReachableAboveFeedback*' \
  --tests '*RemoteAccessorySwitchTest*'
```

Raw evidence is in `/workspace/scratch/93c42f112bf4/evidence/stepa-display-red-isolated/` and `/workspace/scratch/93c42f112bf4/evidence/stepa-display-green/`. Each has `gradle.log`, `result.json`, fresh app JUnit XML and `inputs.json`; GREEN also has `summary.json`. The production and test SHA-256 hashes were checked again after GREEN and still matched the recorded inputs:

| File | SHA-256 at GREEN |
|---|---|
| AppViewModel.kt | `2140e3b035ab87ffe016748e026dc35bead054da0d8dd1f9cd16085c7d435ca4` |
| ForegroundStatusTest.kt | `5995e7d630be7eabfc675fdbba43583785e079ad440a17dbdaccc7ab573fe28a` |

An independent read-only review of the actual implementation and tests found no concrete defect. The full integrated test, lint and build gate remains separate from this focused result. Physical BedJet, Samsung, battery and TalkBack checks were not run.

# GPT-11 integration evidence

Base: `367968bddc390e5b2bb12876fc1662ffa07e47c3`, the final pushed `gpt/nocturne-fixes2` commit. Work continues on `gpt/nocturne-ux`. These are host regression results, not device acceptance.

## Integrated feature patches

The root checkout combines the isolated alarm (`0398344`), duration-resource (`cb91fff`), UI (`a95f8e2`), widget (`622f496`) and screenshot-gate (`1f83832`) patches with the root control/Bluetooth work. Shared files were merged deliberately; their original agent commit IDs are recorded in the ledgers. The published integration commit and full-gate results are recorded below after execution.

## Integration regressions

| Case | Observed RED | Change and focused result |
| --- | --- | --- |
| Timeline phase entry | `TonightPhaseNavigationTest`: a long press opened the editor but left the requested phase collapsed. | Carry a nullable `EditorPhaseSelection` in the existing saved `ProfileDraft`, preserving a parked edited draft. The test passes, together with all 15 `NavigationRestoreTest` cases. |
| Marked five-minute run | `ShortTestTonightIntegrationTest`: a marked equipment test still offered normal wake-time editing. | Exclude `isTestRun` from Tonight's wake override control. The test passes. |
| Core duration text | Duration-resource RED and GREEN are detailed in `ux-alarm-evidence.md`. | Core reports a typed reason; Android chooses the resource string. The final isolated duration run passed 97 core cases and 79 app cases. |
| Release descriptor coverage | `ux-release-fixtures-red`: two release fixture tests ran, one failed because `ActiveNight.isTestRun` was missing from the descriptor fixture. The inventory also exposed the saved editor selection/draft. | Added non-default fixtures for the two Settings fields, marked active/history/end records, requested re-time, editor selection and saved draft. `ux-release-fixtures-green-remote-limits-red`: both release fixture tests passed; the exact compiled inventory matched all 50 models. |
| Remote mode maximums | `RemoteModeLimitsTest`: the real Remote had no Heat/max 40 °C label. | Remote chips now reuse the model-backed `ModeLabel`, including Dry's 31 °C limit. `ux-radio-remote-limits-green`: one test passed with zero failures, errors or skips. |
| Private widget confirmation | `ux-manifest-red`: the actual release manifest contained the new activity without an exact policy entry. | Added the private captured-target confirmation activity and its reason to the manifest inventory policy. `ux-manifest-green` passed all 40 manifest components. |
| Screenshot evidence | Existing renamed action selector timed out; a new running-state assertion then found PENDING where the fixture required CONFIRMED. Python inventory/metadata cases also observed RED. | The fixture waits for the resource-backed Stop label, a receipt-confirmed active night and an idle owner. The final Dark capture passed. The Python helper passes all 14 methods and requires all 102 captures; historical baselines are unchanged. See `ux-screenshot-gate-evidence.md`. |
| Radio-off local re-time and refused replay | `ux-radio-retime-integration-red-2`: 26 tests, 13 passed and 13 assertion failures. Unchanged/filtered owner, service and UI paths incorrectly failed; changed requests retained replayable retries; an ended-night retry posted a radio warning. | `ux-radio-retime-integration-green` passed all 124 fresh selected cases: RadioOffRetime 13, BluetoothOff 13, RetimeNotification 63, RetimeExpiryBoundary 5, UnchangedAlarmObservation 18, OriginFeedback 4 and ServiceEntrySafety 8. Re-time metadata defers the radio check until connection work is actually needed; a refused request clears only its pending attempt and cannot replay. The first invocation had a stale compilation race and ran zero tests, so it is not behavioural RED. |

`ux-integrated-navigation-green-stale-radio-red` ran 32 requested cases: 31 passed and the new ended-night/radio-off case failed. Recovery notification actions passed after their fixture used the same short confirmation delay as the existing service test harness; the production receipt and identity checks were retained.

Release helper regression scripts also passed: `test_release_artifacts.py` 21 methods, `release-serialization-test.py` 35 methods and the integrated `test_release_screenshots.py` 14 methods. These script results are separate from core/app Gradle counts.

## Gate and review status

The exact integrated full gate, full-diff self-review, final gate and APK provenance are pending. Focused task directories can retain older XML; focused counts above use only the fresh selected suites and their Gradle summary. Before a full gate, older test-result directories are moved to an evidence archive so all reported suite totals come from that full invocation.

## Workspace interruption and recovery

The original workspace disappeared while `ux-full-gate-1` was running. There is no retained completion result for that invocation and no full-gate pass or count is claimed for it. The exact staged tree had already been uploaded to GitHub: `45fedf4deeb5d9c8376be6337fad1f59e9f31ce3`. It was immediately secured on `gpt/nocturne-ux` as `0eddd188540df9d918c9c2e69701ae6e72f9a918`, then cloned back and checked against that tree. All 134 integrated source/test/evidence files were preserved.

The written focused RED/GREEN ledgers survived in that tree; their original raw XML, logs and isolated worktrees did not survive the workspace clearing. Those historical runs remain observed results, but the old scratch paths are not current downloadable evidence. The complete gate is rerun as `ux-full-gate-1-restored`, with fresh output directories, and final evidence uses completed reruns. The JDK, SDK, dependency cache and existing Android Debug certificate survived. No test certificate or production signing setting was replaced.

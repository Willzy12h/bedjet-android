# Nocturne fixes2 — integration and verification handover

**fixes2 checkpoint handover — 26 October 2026. Full gate and release PASSED; publication PENDING_FINAL_VERIFICATION.** The original fixes and reconnect scope, including all targeted follow-ups, is integrated and has passed the exact requested gate and separate release assembly. The later temporary Turbo/Heat return request is outside this checkpoint, as recorded below. Samsung, BedJet, TalkBack, installation, physical haptics, battery measurements and minified-APK runtime checks are **NOT RUN**.

## Source and scope

| Reference | Value / status |
|---|---|
| Requested branch | `gpt/nocturne-fixes2` |
| Exact requested base | `gpt/nocturne-final-fixes` at `1c67c0a28084238377c7675e6dc25d0444fe2c22` |
| Integrated ancestor | `claude/nocturne-final-candidate` at `c9befe66f0b78fff4fe9da15d217895ca4b48eee` |
| Intermediate local checkpoint | `28225f7fec7fb196eade839a3dd56cd511f4fea9` — the initial five-commit checkpoint, before later review corrections and the reconnect amendment. |
| Separately preserved checkpoint | `ea9d6c6191ff0eda27b50a0601e6b67b465acb58` — supplied for coordination as the equivalent five-commit tree with different commit metadata. This is not the final source or a claim about who published it. |
| Local code SHA tested by the final gate | **`e114cc25481731e052a0abc564e74775666ec47e`** — the original fixes/reconnect scope and targeted follow-ups, including sensor-only temperature sampling. Full gate exit 0. |
| Source commit tree | **`41a0b7ccba8393b459e26377299f6355d0ceb433`** |
| Gate input manifest | `fixes2-evidence/final-inputs-before.json` records the code/tree above and SHA-256 hashes for **259 tracked non-documentation inputs**. All 259 remained unchanged after the gate and release assembly. |
| Prepared GitHub code commit | **`173edc81ccdb97b262aff6a91dfb6d536f1784b6`**, with the **exact same tree** `41a0b7ccba8393b459e26377299f6355d0ceb433`. This is a prepared immutable object, not evidence of a completed branch publication. |
| Code publication status | **PENDING_FINAL_VERIFICATION:** publication of this checkpoint is not completed. The remote branch has advanced independently; its latest ancestry must be reviewed and preserved before any eventual update. |
| Final branch head | Report the exact delivered branch head separately after publication. It may include the subsequent temporary-mode feature and will need its own verification; this document pins only the original fixes/reconnect checkpoint and does not promise its own commit SHA. |
| Changed-file inventory | **52 paths**, listed below: source, tests, resources, build configuration and documentation, including this new record. |

The scope is F1–F5, lights/beeps protocol verification, alarm-source explanations and diagnostics, and foreground/background BLE behaviour, including the user's later instruction to keep the Remote UI visible during an app-switch reconnect. Production signing is outside this work. The [implementation plan](../superpowers/plans/2026-10-25-nocturne-fixes2.md) retains the requested sequencing and constraints.

**Subsequent request outside this tested checkpoint:** the user also requested temporary Turbo or Heat followed by automatic return to the running night, as a distinct choice from ending the night. Design and implementation work for that feature is proceeding in a separate worktree. It is not included in the code/tree above and is not covered by these gate counts or the prepared GitHub commit. Any eventual combined publication needs separate verification of that later source.

The [completion record](nocturne-completion.md) remains authoritative for IR-01–IR-32. Earlier results in [final-fixes verification](final-fixes-verification.md), [docs2 re-pin](docs2-repin.md) and the register describe their pinned sources; they are not a fixes2 integration gate. In particular, F3's quiet unchanged observation does not remove IR-07's deliberate terminal notice for an actual pending re-time that expires.

## Changes and affected files

| Item | Behaviour and principal files |
|---|---|
| F1 — cancellation settlement | `night/NightActions.kt` guards the whole manual transaction, including pending publication, ending intention, write, confirmation, fallback and final publication. After a possible write, bounded eight-second `NonCancellable` cleanup settles the matching pending request to `UNKNOWN`, clears obsolete pending metadata, preserves a valid Off intention and rethrows the original cancellation. Before a possible write it restores the captured prior tracking. Exact request/identity checks protect another request or an already published confirmation. `ManualCancellationSettlementTest` asserts durable and in-memory state before reconciliation. |
| F1 — review follow-ups | Snooze/Extend publish uncertain manual tracking before the later timed-plan save, retaining the valid Resume offer. If this call has already confirmed Off and durably journalled the matching `STOPPED` ending, cleanup completes that local ending rather than downgrading it; it does not resend Off. The cancellation regression checks one packet and one terminal event. |
| F2 — captured original | `ui/AppViewModel.kt` passes the immutable original shown in the preview to `NightActions.restoreOriginal`. After owner admission/preparation, and again after the last backup suspension, the owner refuses a different stored record. The resource-backed message asks the user to review the changed original. The payload remains the captured record. `RestoreOriginalCaptureTest` covers owner blocking, import of B while restore A is queued, preparation-time import, the slot-journal boundary, absence and unchanged controls. |
| F3 — unchanged alarm observation | `NightActions` compares the resolved wake before unknown/manual refusals and preserves `LOCAL` through alarm-inbox consumption. The same-wake cases produce no command, terminal History or notice. The additional confirmed/manual follow-up checks equality before observation-only expiry, so consuming a queued observation across wake or submitting it again after wake preserves the existing reason. The 82-test GREEN includes the four new regressions and genuine pending-expiry controls; a real matching `PendingRetime` retains its History, sole notice and cancellation repair. |
| F4 — restore preview | `ui/SettingsScreen.kt` scrolls the dialogue body while retaining the title and action slots. `RestoreOriginalDialogLayoutTest` reaches all 32 steps, the full uncertainty warning and both buttons at 320 dp / 200% text in all four looks. |
| F5 — curve alternative | `ui/NightCurve.kt` uses `usesTemperature`, then `usesFan`, then a mode-and-duration-only resource. Off and Pause omit a fan value; Turbo retains it. `NightCurveAlternativeTest` checks Celsius/Fahrenheit alternatives and temperature controls. Text is in `res/values/strings_gaps_visual.xml`; the canvas design is unchanged. |
| Lights and beeps | The existing codec/decoder direction was correct. `LightsAndBeepsProtocolTest` checks literal commands and status bits; `RemoteAccessorySwitchTest` exercises the actual Remote screen, ViewModel and owner through all four switch directions, including a non-matching report before confirmation. No protocol reversal was needed. |
| Alarm explanation | `night/AlarmExplanation.kt`, `night/AlarmSource.kt`, `ui/AlarmExplanationLine.kt`, `ui/TonightScreen.kt`, `ui/MainActivity.kt`, `ui/SettingsScreen.kt`, `ui/AppViewModel.kt`, `NightActions.diagnostics()` and `res/values/strings_alarm_sources.xml` provide the source line, relevant navigation, setting refresh and diagnostic reason. The completed 55-test alarm run covers policy, confidence, callbacks, source-line layout and compatibility with the existing saved-override presentation. The separate integrated Settings/diagnostics run passed 18 tests. |
| BLE and reconnect | `night/LinkSessions.kt`, `ble/GattLink.kt`, `ble/AndroidBedJetLink.kt`, `core/link/BedJetLink.kt`, `platform/PlatformRuntime.kt` and `ui/AppViewModel.kt` implement demand and presentation boundaries. The reconnect amendment also affects `ui/RemoteScreen.kt`, the shared `SwitchRow` enabled parameter in `ui/ProfileEditor.kt`, `ConnectionLine` in `ui/TonightScreen.kt` and `res/values/strings_reconnect.xml`. `ForegroundStatusTest` and the actual-activity `RemoteReconnectPresentationTest` establish retained presentation and immediate control-freshness invalidation; the reconnect run has 23 passing tests. The later 22-test sampling GREEN confirms that only actual/ambient sensor temperatures wait for the one-second sample; mode, setpoint, fan, timer, accessories, programme progress and limits update immediately. |

Paths in the table are relative to `app/src/main/kotlin/io/github/willzy12h/nocturne/` unless a core, resource or test path is stated. Test files live in their matching module/package. The complete inventory below also includes build configuration and documentation changes.

### Changed-file inventory

This inventory was read from the exact base `1c67c0a28084238377c7675e6dc25d0444fe2c22` to source HEAD `e114cc25481731e052a0abc564e74775666ec47e` plus the final documentation worktree. It contains **51 tracked changed paths plus this new `docs/review/fixes2-verification.md`**, **52 paths total**. The non-documentation gate inputs are separately pinned by the 259-file manifest above, so completion of the handover does not alter the source being tested.

```text
app/build.gradle.kts
app/src/main/kotlin/io/github/willzy12h/nocturne/ble/AndroidBedJetLink.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ble/GattLink.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/night/AlarmExplanation.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/night/AlarmSource.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/night/LinkSessions.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/platform/PlatformRuntime.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AlarmExplanationLine.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightCurve.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditor.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RemoteScreen.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt
app/src/main/res/values/strings_alarm_sources.xml
app/src/main/res/values/strings_fixes2_runtime.xml
app/src/main/res/values/strings_fixes2_settings.xml
app/src/main/res/values/strings_gaps_visual.xml
app/src/main/res/values/strings_reconnect.xml
app/src/main/res/values/strings_ui.xml
app/src/test/kotlin/io/github/willzy12h/nocturne/ble/IdleStatusDemandTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/AlarmExplanationRuntimeTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/ForegroundConnectionTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/ManualCancellationSettlementTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/NightActionsTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/RestoreOriginalCaptureTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/UnchangedAlarmObservationTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/platform/IdleTelemetryTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AlarmExplanationActionTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AlarmExplanationLayoutTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AlarmExplanationTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AlarmSettingsNavigationTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ForegroundStatusTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/NightCurveAlternativeTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RemoteAccessorySwitchTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RemoteReconnectPresentationTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/ui/RestoreOriginalDialogLayoutTest.kt
core/src/main/kotlin/io/github/willzy12h/nocturne/core/link/BedJetLink.kt
core/src/test/kotlin/io/github/willzy12h/nocturne/core/protocol/LightsAndBeepsProtocolTest.kt
docs/HANDOVER.md
docs/review/fixes2-alarm-evidence.md
docs/review/fixes2-ble-behaviour.md
docs/review/fixes2-runtime-evidence.md
docs/review/fixes2-ui-protocol-evidence.md
docs/review/fixes2-verification.md
docs/review/known-limitations.md
docs/review/nocturne-completion.md
docs/review/phone-test-guide.md
docs/review/register.md
docs/superpowers/plans/2026-10-25-nocturne-fixes2.md
```

## Executed RED / GREEN evidence

The evidence directories are under `/workspace/scratch/6cc8f67848d1/fixes2-evidence/`. Each completed run has a `result.json` containing the exact Gradle command, project and exit code, plus a log and copied JUnit XML. Counts below were checked against fresh XML and the corresponding execution outcome. Repeated focused suites overlap and **must not be added together as a full-suite count**. All listed behavioural REDs have assertion failures, not compilation errors.

| Item | Observed RED | Completed GREEN / remaining boundary |
|---|---|---|
| F1 original transaction | `runtime-f1-red`: **10 tests, 7 failures, 0 errors, 0 skipped**. Cancellation after work returned, during confirmation and during the fresh fallback left `PENDING`; pre-write cases also exposed incorrect restoration. | `runtime-f1-green`: **78 passed, 0 failures/errors/skips**, exit 0. Includes cancellation settlement, end-request, send-confirmation and timeout feedback suites. |
| F1 timed publication | `runtime-f1-timed-red`: **2 tests, 2 failures**, 0 errors/skips. Snooze/Extend cancellation left the old programme `CONFIRMED`. | `runtime-f1-timed-f2-green`: **97 passed, 0 failures/errors/skips**, exit 0, including 12 cancellation and 10 manual-tracking cases. |
| F2 captured original | `runtime-f2-red-valid`: **6 tests, 4 failures**, 0 errors/skips. Queued/imported, captured-preview, missing-record and fresh-preparation cases failed; two controls passed. | The same `runtime-f1-timed-f2-green` run includes **6 captured-original, 10 imported-original, 50 ownership and 9 admission** cases. |
| F3 unchanged wake | `runtime-f3-red`: **13 tests, 7 failures**, 0 errors/skips. Same resolved wake incorrectly refused or fell through the alarm inbox for unknown/manual, fallback and override/filter cases. | `runtime-f3-green`: **97 passed, 0 failures/errors/skips**, exit 0: 13 unchanged observations, 20 runtime-delivery, 5 expiry-boundary and 59 notification cases. |
| F1–F3 review boundaries | `runtime-review-boundaries-red-resumed`: **3 tests, 3 failures**, 0 errors/skips: confirmed Off journal, import during slot-journal persistence and unchanged observation crossing wake. For F2 only, the existing final comparison was temporarily removed to establish that guard's necessity, then restored. | `runtime-review-boundaries-green`: **104 passed, 0 failures/errors/skips**, exit 0: 13 cancellation, 7 captured-original, 14 unchanged-observation, 28 end-request, 37 send-confirmation and 5 expiry cases. This is targeted evidence, not the final integration gate. |
| F3 additional wake-boundary follow-up | `runtime-confirmed-observation-red`: **4 tests, 4 assertion failures, 0 errors/skips**, exit 1, 72.03 seconds. Consuming queued or repeated post-wake observations invented an expiry reason for confirmed nights and replaced a manual night's existing reason. | `runtime-confirmed-observation-green`: **82 passed, 0 failures/errors/skips**, exit 0, 221.71 seconds: 18 unchanged-observation, 5 expiry-boundary and 59 notification cases. The preceding 104-test run predates this follow-up; the 82-test run adds its four regressions and checks genuine expiry controls. |
| F4 / F5 | `ui-f4-f5-red`: **8 tests, 6 failures**, 0 errors/skips: four inaccessible final-step cases and two incorrect fan alternatives. | `ui-f4-f5-green`: **14 passed, 0 failures/errors/skips**, exit 0: 4 restore-layout, 4 curve and 6 existing uncertainty-warning cases. |
| Lights / beeps | Production matched the public source before changes; no production-failure RED is claimed. | `ui-protocol-verification`: **12 core + 4 app tests passed**, 0 failures/errors/skips, exit 0. |
| Alarm source line | `alarm-red`: **19 tests, 19 failures**, 0 errors/skips. Existing wake-policy assertions passed before each missing-explanation assertion. | `alarm-complete-green`: **55 passed, 0 failures/errors/skips**, exit 0, 114.30 seconds: 22 explanation/diagnostic, 9 runtime-state, 3 action, 4 layout, 8 wake-title, 1 START-attribution and 8 existing wake-feedback cases. |
| Alarm pending and confidence | `alarm-pending-red`: **24 tests, 2 failures**, 0 errors/skips, for overly specific START/MANUAL pending wording. `alarm-manual-policy-red`: **9 tests, 3 failures**, 0 errors/skips, for uncertainty hidden by manual tracking and lost saved-override policy. | The relevant source, runtime and existing wake-feedback cases pass in the same **55-test `alarm-complete-green`** run. |
| Alarm saved-override compatibility | `alarm-reviewed-green-resume` had **54 tests, 1 failure**, 0 errors/skips: the existing override control rejected a no-alarm suffix beside the saved override. Before correction, `alarm-override-red` ran **22 tests, 1 assertion failure**, 0 errors/skips; the new `SAVED_OVERRIDE_NO_ALARM` case required the retained `Saved tonight-only wake 07:00` wording. | `alarm-complete-green` passes both the new UI/diagnostic/picker control and all eight existing wake-feedback cases. No existing wake-feedback assertion was weakened. The line remains present and clickable, with diagnostic reason `OVERRIDE`. |
| Alarm integration | `alarm-integration-red-2`: **3 tests, 3 failures**, 0 errors/skips, for missing Settings navigation/control and exported diagnostics. | `alarm-integration-green`: **18 passed, 0 failures/errors/skips**, exit 0, 107.82 seconds, on the root integration worktree: 3 Settings navigation/exported-diagnostic, 3 action, 4 layout and 8 existing wake-feedback cases. |
| Foreground / BLE demand | `ble-red`: **10 tests, 6 failures**, 0 errors/skips, for old idle timing, hidden presentation collection, unbounded UI updates and idle remainder reads. | `ble-green-2`: **73 passed, 0 failures/errors/skips**, exit 0. Includes demand, connection lifecycle, raw GATT, owner admission and fresh-read boundaries. |
| Idle observation persistence | `ble-telemetry-red`: **2 tests, 1 failure**, 0 errors/skips: idle reports still changed durable platform observations; the owner-positive control passed. | `ble-telemetry-green-2`: **14 passed, 0 failures/errors/skips**, exit 0. The owner/remainder control requires a distinguishable later step and the same original receipt. |
| App-switch reconnect amendment | `reconnect-ui-red`: **6 tests, 3 failures**, 0 errors/skips: hiding, returning during reconnect and a visible disconnect each removed the previous target's snapshot. Target-change, hidden/no-prior-snapshot and rate-limit controls passed. After the cache-only change, `reconnect-screen-red`: **9 tests, 2 failures**, 0 errors/skips: six ViewModel cases passed, but the actual Remote card disappeared while reconnecting and cached controls were enabled at `READY` without a new session report. The owner cached-report safeguard passed. | `reconnect-complete-green`: **23 passed, 0 failures/errors/skips**, exit 0, 102.82 seconds: 6 foreground-status, 3 rendered Remote/owner, 4 accessory-switch, 4 displayed-status, 4 connection-lifecycle and 2 raw-receipt cases. The earlier hidden-value-null expectation is superseded by the user's retained-UI requirement. |
| Existing lifecycle expectation | `lifecycle-expectation-red`: **9 tests, 3 assertion failures, 0 errors/skips**, exit 1, 19.46 seconds. Three older `NightActionsTest` cases expected `SEARCHING` after six seconds but correctly remained `READY` under the requested 30-second grace; six controls passed. | `lifecycle-expectation-green`: **18 passed, 0 failures/errors/skips**, exit 0, 62.09 seconds: 9 `NightActionsTest`, 4 foreground-connection, 3 idle-demand and 2 idle-telemetry cases. The corrected tests establish the complete 30-second reuse window. This is a test expectation follow-up, not a new production regression. |
| Sensor-only temperature sampling / immediate control feedback | `ui-command-display-red`: **8 tests, 8 assertion failures, 0 errors/skips**, exit 1, 82.38 seconds. The unchanged U06 tests failed in all four looks because whole-status sampling delayed mode/timer content and the confirmed Lights switch state. `ui-control-status-red`: **7 tests, 1 assertion failure, 0 errors/skips**, exit 1, 46.96 seconds; the added immediate-control case expected `COOL` and still displayed `HEAT`, while six existing controls passed. | `ui-control-status-green`: **22 passed, 0 failures/errors/skips**, exit 0, 81.48 seconds: 7 foreground-status, 3 reconnect, 4 primary mode/timer, 4 switch-semantics and 4 slider-semantics cases. Only actual/ambient sensor temperatures are sampled; control/progress/limit fields remain immediate. Cache, `hasLiveStatus` and raw-receipt safeguards remain intact; no existing U06 assertion was weakened. |

More detailed accounts: [runtime evidence](fixes2-runtime-evidence.md), [UI/protocol evidence](fixes2-ui-protocol-evidence.md), [alarm evidence](fixes2-alarm-evidence.md) and [BLE before/after](fixes2-ble-behaviour.md). All targeted follow-ups have passed their named runs and are integrated. Their overlapping counts do not replace the final full gate.

### Attempts excluded from a successful or behavioural result

| Attempt | Why it is excluded or qualified |
|---|---|
| `runtime-f2-red` | The new fixture exceeded the programme-name limit. The six fixture failures and copied older passing XML are not the captured-original behavioural RED. Use `runtime-f2-red-valid`. |
| `runtime-review-boundaries-red` | A fixture referred to `SlotWrite.program` instead of `SlotWrite.intended`; compilation failed before test execution. Older copied XML is stale. |
| `runtime-review-boundaries-red-valid`, `alarm-reviewed-green` | Interrupted attempts with no completed `result.json`; not passing evidence. |
| `ble-green`, `alarm-integration-red` | Kotlin type-inference compilation failure; copied previous XML is not a new test run. |
| `alarm-green` | **35 tests, 32 passed, 3 failed**: callback fixtures did not isolate UTC. Fresh XML and a preserved daemon-output excerpt establish execution despite the truncated client log. The fixtures were corrected; this attempt is not all-green. |
| `alarm-final-green` | Despite its label, **45 tests, 2 failures** exposed manual confidence wording. It is a genuine review RED, not a GREEN. |
| `alarm-reviewed-green-resume` | Despite its label, **54 tests, 1 failure** exposed saved-override presentation compatibility. The subsequent explicit regression and correction pass in `alarm-complete-green`; the earlier attempt remains a failed run. |
| `ui-protocol-initial` | Four app fixture selectors used compact-tab semantics at ordinary width and failed before sending commands. The core task did not execute. This does not show reversed device protocol. |
| `ble-telemetry-green` | **14 tests, 1 failure**: the idle fixture hid the activity before the suspending acquisition completed, so cancellation of the unfinished connection was expected. The fixture now awaits acquisition before testing grace. No production fix was attributed to that fixture race. |

## Lights and beeps: public-source comparison

The immutable Lull [command encoder](https://github.com/rkanjani/lull/blob/f98821a6f53cbe80c00fc1ba11396a9433b322f0/BedJetKit/Sources/BedJetKit/Codec.swift#L33-L34) supplies the command directions. Its [status decoder](https://github.com/rkanjani/lull/blob/f98821a6f53cbe80c00fc1ba11396a9433b322f0/BedJetKit/Sources/BedJetKit/Codec.swift#L137-L142) supplies the byte-27 flags; [ESPHome's BedJet status layout](https://github.com/esphome/esphome/blob/06bd92c3880c575a366f7b8e6fb77f74fd200937/esphome/components/bedjet/bedjet_codec.h#L103-L115) independently corroborates those bits.

| Visible request | Codec command byte | Expected status | Remote representation |
|---|---|---|---|
| Lights on | `setLed(true)` → `0x46` | `b[27] & 0x10 != 0` | Lights checked. |
| Lights off | `setLed(false)` → `0x47` | `b[27] & 0x10 == 0` | Lights unchecked. |
| Beeps on | `setMuted(false)` → `0x49` | `b[27] & 0x01 == 0` | `!beepsMuted`, checked. |
| Beeps off | `setMuted(true)` → `0x48` | `b[27] & 0x01 != 0` | `!beepsMuted`, unchecked. |

The command tests assert full two-byte packets with the leading `0x01`. The decoder matrix checks all four relevant flag combinations and unrelated high bits. App tests require a matching report, rather than changing the switch on write completion alone. Public protocol comparison and scripted reports do not establish the physical unit's response. The [phone-test guide](phone-test-guide.md) includes lights/beeps toggles and requires both the physical change and the confirmed switch state; those rows remain **NOT RUN**.

## Alarm: explanation without changing source policy

Android's [`AlarmManager.getNextAlarmClock()`](https://developer.android.com/reference/android/app/AlarmManager#getNextAlarmClock()) returns the one next upcoming alarm-clock event, or null. It is **not a multi-alarm list**. Nocturne cannot use that API to discard an earlier rejected event and inspect a later Samsung alarm. [`PendingIntent.getCreatorPackage()`](https://developer.android.com/reference/android/app/PendingIntent#getCreatorPackage()) may return null, and the alarm's show intent is nullable. These contracts do not diagnose the owner's Samsung phone.

The existing persisted unknown-source choice defaults to **true** and remains unchanged. A null creator is refused only when that choice is **off**. Samsung Clock's known package remains in the existing allow-list. The existing inclusive one-to-sixteen-hour window also remains unchanged; the initial start anchors it to the start time, and a running night's re-time uses its original start. Profile alarm opt-out, an explicit tonight-only override, an unapproved source, no exposed alarm and an out-of-window event are distinct code paths that can explain why the wake remains at 07:00. None is claimed to have been reproduced on the user's phone.

The small, resource-backed line always describes the source or decision, for example:

- `Next alarm 06:30 · Samsung Clock — used`
- `Alarm 06:30 ignored: source unknown — tap to change`
- `No alarm set — profile wake 07:00`

The explanation uses current saved settings rather than trusting an old cached acceptance flag. A running night names its stored wake separately from the current candidate; an eligible candidate does not prove a BedJet change was applied. Pending, failed and unknown confidence takes precedence over a manual flag. A stored tonight-only override retains its policy, including after an uncertain manual attempt. When no current alarm is exposed beside a saved override, the compatibility correction returns the saved-override/state description without an irrelevant no-alarm suffix. The line stays clickable, its diagnostic reason remains `OVERRIDE`, and its action opens the existing Tonight picker. The 55-test GREEN includes the added UI/diagnostic/action control and the unchanged existing wake-feedback assertion.

Unknown/disallowed sources open the Alarms setting; profile opt-out/no alarm opens the profile editor; an editable outside-window or override case opens the existing Tonight wake picker. Existing owner restrictions still prevent unsafe edits. Settings exposes the already-existing unknown-source choice instead of silently relaxing it. The diagnostic object includes the decision/ignore reason, explanation, wake state and origin, current alarm time/nullable creator, source acceptance, window bounds, pending requested wake, matching-time flag, destination and `onlyNextAlarmExposed`. Completed History still has no permanent creator-package field; that limitation is unchanged.

## BLE: baseline, implemented demand, and the reconnect amendment

These statements describe code and host tests, not measured radio frequency or battery consumption. The [BLE record](fixes2-ble-behaviour.md) documents the baseline before implementation.

| Boundary | Baseline at `1c67c0a` | fixes2 behaviour / verification boundary |
|---|---|---|
| Visible activity | Activity lifecycle holds the selected connection; every selected report can reach the eagerly collected UI. | Visible activity holds the connection and gets an immediate initial snapshot. Only actual/ambient sensor temperatures are sampled at most once per second; mode, setpoint, fan, timer, accessory state, programme progress and limits update immediately. Raw owner receipts remain unthrottled. |
| Hidden activity | ViewModel continues collecting full status; the last connection holder starts a five-second idle grace. | Stop the UI subscription and routine idle platform-observation persistence. Disable new split-status remainder reads when no activity/owner requires them. Last-holder grace is about 30 seconds. |
| Return from app switch | Connection may need to reconnect. Clearing status removes the Remote content. | Retain the same target's last-known Remote card across hide/reconnect, keep the actual connection indicator visible and identify retained data as **Last reported**. Controls require `READY` and a current target report. Target change clears the retained snapshot. Hidden snapshots do not keep live collection running. The 23-test reconnect GREEN covers the actual Remote screen, lifecycle, accessory controls and raw-receipt safeguards. |
| Owner/background work | Command-owner jobs hold the connection independently; scheduled work connects when hardware is needed. | Active owner work preserves full demand and prevents idle disconnect. Its final release starts grace only when no other holder remains. No new idle reconnect loop or polling service is added. |
| Confirmation and fresh read | Raw sourced reports, receipt order and owner-held disconnect/reconnect fallback establish confirmation. | Unchanged. Retained UI data cannot satisfy confirmation. Split-read enrichment retains the head receipt; a late Standby can still settle a valid Off intention during grace. |

Notifications drive status; the unit/stack controls their physical rate, which is unmeasured. Split notifications can trigger a remainder read when the operation queue is free and more than two seconds has elapsed since the previous remainder read. An already-started read may finish after demand disappears; cancelling an in-flight exchange would retire the transport. Raw `NightActions.observeStatus` remains available during grace for late Off settlement, while routine `PlatformRuntime.observe` persistence is demand-gated.

Wake/end delivery, alarm observations/re-time, eligible charger starts, explicit commands and pending recovery keep their existing scheduling/owner boundaries. Re-time retry spacing is at most ten minutes and bounded by the wake deadline; enabled charger checks are periodic at fifteen minutes with eligibility/grace checks. A running programme stays on the BedJet without requiring a permanent phone connection. Optional Lull handover scanning requires a ready, verified compatible connection and permission; it does not establish one itself. No exact Android scheduling or physical reconnect-time guarantee is made.

The earlier per-stream hidden-state test intentionally required a null presentation value. The latest user amendment supersedes only that presentation-clearing rule; it retains visible-only live collection and the demand/receipt safeguards. Null raw status invalidates control freshness immediately. At `READY` without a current report, the connection indicator says **Connected** and the Remote card remains **Last reported**; cached temperatures are not presented as current connection data. The first current-session report supplies live content again. The timeout order remains **UI 115 s > service 100 s > owner 90 s**.

The first BLE implementation sampled the entire `DeviceStatus`. Existing U06 regressions exposed the resulting delay to mode and accessory confirmation feedback. The narrow follow-up displays actual/ambient sensor temperatures at most once per second and forwards the control/progress/limit fields immediately, while retaining the cache and freshness boundaries. Its separate **22-test GREEN** includes the unchanged U06 mode/timer, switch and slider assertions and the actual Remote reconnect cases; the earlier 23-test reconnect result is not reused as evidence for that later correction.

## Executed full gate and release assembly status

The exact gate ran on the fully integrated local source `e114cc25481731e052a0abc564e74775666ec47e`, under evidence label **`fixes2-full-gate`**: **exit 0, 848.80 seconds**. The result, fresh XML and lint comparison are summarised in `/workspace/scratch/6cc8f67848d1/fixes2-evidence/fixes2-gate-summary.json`; the named run directory retains the command, Gradle log, XML and report evidence.

```sh
./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The separate requested release assembly ran under label **`fixes2-release`**, with the same constraints: **exit 0, 157.22 seconds**. Gradle reported **BUILD SUCCESSFUL in 2m 36s**, with **51 actionable tasks: 48 executed, 3 up-to-date**:

```sh
./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease
```

The supplied historical baseline is **core 175 tests; app 889 tests, 38 skipped; lint 0 errors / 14 warnings + 1 hint**. The current full gate has **187 core tests passed** and **995 app tests: 957 passed, 38 skipped**, with no failures or errors. Lint remains **0 errors / 14 warnings + 1 hint**, with no new or removed findings when compared by ID, severity, message and path. Shared-runner evidence uses Java 17, the prepared Android SDK and offline Gradle/Maven caches. Production signing is outside this work; the successful unsigned release assembly does not establish installation or minified-runtime behaviour.

| Final check | Exact integrated result |
|---|---|
| Code/tree tested by the final gate | Code `e114cc25481731e052a0abc564e74775666ec47e`; tree `41a0b7ccba8393b459e26377299f6355d0ceb433`; evidence label `fixes2-full-gate`; **exit 0, 848.80 seconds**. |
| `:core:test` total / passed / failed / errors / skipped | **187 / 187 / 0 / 0 / 0**; 25 fresh XML suite files. |
| `:app:testDebugUnitTest` total / passed / failed / errors / skipped | **995 / 957 / 0 / 0 / 38**; 103 fresh XML suite files. |
| `:app:assembleDebug` | **PASSED** in the full gate. |
| `:app:lintDebug` errors / warnings / hints | **0 / 14 / 1**. |
| Comparison with baseline lint findings | **No new or removed findings**, compared by issue ID, severity, message and path. |
| `:app:assembleRelease` and signing scope | **PASSED**, separate `fixes2-release` run, **exit 0, 157.22 seconds**. Generated unsigned APK; no production-signing change is included. |
| Focused reconnect amendment, before the final gate | **23 passed, 0 failures/errors/skips** in `reconnect-complete-green`, exit 0. This does not replace the integrated gate above. |
| Focused sensor sampling / immediate control feedback | **22 passed, 0 failures/errors/skips** in `ui-control-status-green`, exit 0; integrated in the code above. |
| Final gate/release input-integrity check | **All 259 non-documentation input hashes unchanged** from the before-gate manifest, including the check after release assembly. |
| Code publication | **PENDING_FINAL_VERIFICATION:** GitHub code object `173edc81ccdb97b262aff6a91dfb6d536f1784b6` has the exact tested tree; branch publication is not completed. Preserve the independently advanced remote ancestry and report the eventual delivered head with its applicable verification. |

### Unsigned release output

- Path within the build worktree: `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Size: **4,523,115 bytes**.
- SHA-256: **`ef6ac3fe25d2ee98fd53bba6ba93b680f9088ab5cbb963f14676e8d1f43f4307`**.
- Android build-tools 35.0.0 `apksigner verify --verbose` reports `DOES NOT VERIFY` / `Missing META-INF/MANIFEST.MF`, as expected for this unsigned output. This signature check is separate from the successful release assembly; no release signing, installation or device run was performed.

## Remaining work and decisions

Retain this immutable checkpoint as completed host verification: the full gate, separate release assembly, lint comparison and 259-input integrity check have passed. Branch publication is not completed. The authoritative IR/register, BLE record, phone guide and known-limitations wording have been reconciled for this original fixes/reconnect scope. Before eventual publication, review and preserve the independently advanced remote history; the prepared immutable object does not establish a branch update and does not authorise replacing later work or force-pushing. The subsequent temporary Turbo/Heat return feature is being developed separately. A combined publication needs its own final-source verification, and these 187-core/995-app results must remain attributed to the exact checkpoint above. Report the eventual delivered branch head separately rather than putting a self-referential SHA in this document.

No choice is required to change the alarm default: the existing default and saved policy are preserved. A phone check should inspect the explanation and diagnostics to identify the actual reason the owner's wake remains at 07:00. Physical acceptance must also exercise lights/beeps, app-switch reconnect and last-reported presentation, background owner completion, the 30-second grace, native picker readability and TalkBack. Earlier KL-01 original-slot inventory/read-back, U-B5 and other recorded limitations remain open at their existing scope.

**Checkpoint readiness: PENDING_FINAL_VERIFICATION for branch publication only.** The exact full gate and separate release assembly have passed. The later temporary Turbo/Heat return request is outside this verdict. No hardware, Samsung, TalkBack, signed installation or minified-APK runtime result is claimed.

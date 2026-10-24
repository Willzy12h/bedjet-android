# Nocturne F1 corrections — 24 October 2026

Branch: `gpt/nocturne-f1`.

Base: `c59e974fd8dfeb4d29ed58f8567514ac6b7b345d`, fetched from `origin/claude/nocturne-final-candidate` before branching.

This review covers the owner's six F1 requests. The complete F1 gate and release-journal compatibility check passed; earlier candidate results remain historical evidence.

## Corrections

| Request | Implementation |
|---|---|
| 1. Silent final background re-time failures | `retryRetimeLocked` reports terminal partial-write, permanent-refusal and exception outcomes. Background timeouts have one service-owned notice even when routine notifications are suppressed. Routine retryable failures retain their alarm without a notice. |
| 2. Off contract | The Task 4 contract now names Off as the night/revision-identity exception, including old notification/widget/dialog requests. Captured-target validation remains mandatory. The Off identity/target validation and three inverted regression tests are unchanged. |
| 3. Wrong delayed-end history label | The end marker retains the label of the attempted manual command; a later qualifying stopped-state report records that label instead of always recording “BedJet off.” |
| 4. Reflective capability lookup | `ManualTrackingTest` accesses the public `hasPlannedManualEnd` property directly. Its behavioural assertions are retained. |
| 5. Missing report timestamp guard | The marker also retains the request time. Reports received before that instant cannot complete it. Equal instants, including different zone representations, are accepted. |
| 6. Unreachable BedJet with Off pending | Completion documentation and the register explicitly retain the UNKNOWN night beyond `endsAt` until a qualifying selected-target stopped report or **Stop tracking**. There is no automatic completion time limit for an unresolved Off. |

## Notification ownership

The receivers continue to suppress routine service failure notifications. The re-time coordinator reports a final dropped failure, and `CommandOutcome.notificationHandled` prevents the service from adding a generic second notice. This flag is transient outcome metadata, not part of the saved runtime journal.

| Outcome | Notice |
|---|---|
| `FAILED`, same confirmed night and retry still armed | None; retain the pending retry. |
| Permanent `REFUSED` or terminal `FAILED` after pending work is dropped | One problem notice carrying the actual reason. |
| Partial write or exception leaving the night `UNKNOWN` | One problem notice explicitly describing the unknown state; it does not claim the BedJet was not restarted. |
| Fresh-status takeover before/during upload | Keep the existing single takeover information notice. |
| Expired pending re-time | Keep the existing single atomic expiry notice. |
| Unchanged `LOCAL` or `STALE` | None. |
| Re-time operation timeout | One service-owned timeout notice; ordinary lifecycle cancellation keeps its existing behaviour. |

An alarm observation can refuse a change before `retryRetimeLocked` runs. That path also owns its final notice when it consumes pending work, and saves the refusal reason instead of retaining a promise to retry.

## Manual-end journal compatibility

`RuntimeState` keeps the existing `endRequested` identity and adds nullable `endRequestedLabel` and `endRequestedAt`. Completion, replacement and stale-marker reconciliation clear all three values together. Retaining the same night after a refused replacement retains its ending intention.

Older identity-only journals remain readable. Because their command cannot be reconstructed truthfully, their completed history event uses the neutral resource “Night stopped.” New and changed user copy is confined to `res/values/strings_runtime.xml`; this branch does not perform Claude's string migration.

Boost already uses a separate slot-write/start-confirmation path and does not create this manual-end marker. F1 preserves that path. The label correction covers every command that actually creates the marker, including Off/End, Turbo, Warm, Cool and Remote mode commands.

## Test-first evidence

Every production correction was preceded by a failing behavioural assertion. No test was disabled or assertion weakened.

| Cycle | RED | GREEN |
|---|---|---|
| Receiver final failures, timeout and truthful unknown state | 24 re-time tests: 6 expected assertion failures, 0 errors/skips | Initial combined re-time/expiry/takeover/service-feedback run: 48 passed, 0 failures/errors/skips. |
| Existing takeover notices and observation-driven refusal | 3 tests: all 3 failed, exposing duplicate generic errors and a missing final notice | Included in the 48-test combined run. |
| Persisted reason after a refused observation | 1 test failed because runtime still promised another retry after dropping it | Included in subsequent re-time and complete integration runs. |
| Refusal of the first pending alarm observation | 1 test failed because no notice was posted before a prepared re-time existed | Included in the observation and complete integration runs. |
| Command label and timestamp | 27 tests: 11 expected assertion failures, 0 errors/skips | Final focused manual-end run: 52 passed, 0 failures/errors/skips. |
| Marker metadata cleanup | 3 strengthened checks failed with cleanup withheld, 0 errors/skips | Included in the final 52-test manual-end run. |

The receiver regressions call `NightAlarmReceiver` and deliver its captured intent to the real Robolectric service. They assert the published night, pending work, scheduled retry, additional writes and persisted notice counts, so a replacement at the same Android notification ID cannot conceal a duplicate notice. Every new Graph-building fixture uses `GraphLifecycle`.

## Baseline test-fixture correction

The first complete app baseline stalled in `RuntimeRegressionTest.oldFinishCannotEraseAReplacementNight`. A thread dump captured Robolectric Main parked inside `runBlocking` while dispatcher workers were idle. The fixture retained the application's Main-thread runtime observers; the likely starvation point was a coordinator continuation awaiting the paused Handler queue. The dump did not identify the particular mutex owner.

The fixture now finishes the application graph through `GraphLifecycle.fresh`, injects an explicit demo graph and binds it to `App.graph`. All five test bodies and assertions are unchanged. The isolated class passed **5/5**, with the previously stalled test completing in **3.170 seconds**. This changes test isolation only.

## Final integrated verification

**PASS — code commit `7725b442999e28ec13d1dc24021409147be3cd69`.** The subsequent hand-off commit changes documentation only. After clearing the two test tasks' outputs, the required gate completed successfully:

```sh
./gradlew :core:cleanTest :app:cleanTestDebugUnitTest --offline --max-workers=2 --console=plain
export SCREENSHOT_TAG=f1
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --max-workers=2 --console=plain
```

The retained JUnit XML reports these exact counts:

| Scope | Suites | Tests | Passed | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|---:|
| Core | 20 | 156 | 156 | 0 | 0 | 0 |
| App debug | 60 | 504 | 504 | 0 | 0 | 0 |
| **Required gate total** | **80** | **660** | **660** | **0** | **0** | **0** |

All six existing opt-in screenshot tests ran, producing 38 capture PNGs. **Debug assembly passed. Lint passed with 0 Error, 0 Fatal, 14 Warning and 1 Hint**: 11 `UseKtx`, one each of `ObsoleteSdkInt`, `OldTargetApi` and `SpecifyJobSchedulerIdRange`, and one `AutoboxingStateCreation` hint. The gate took **8 minutes**, with **59 tasks: 41 executed and 18 up-to-date**.

The debug APK is **66,181,662 bytes**, SHA-256 `863d0d3880309b8b97f6ea628443aa849ca35de79a87fd8bdf7768abb3b90c0c`.

The additional release-variant serialisation check also passed:

```sh
./gradlew -I .github/workflows/release-serialization.init.gradle :app:testReleaseUnitTest --tests io.github.willzy12h.nocturne.releasegate.ReleaseSerializationTest :app:exportReleaseSerializationInputs --offline --max-workers=2 --console=plain
```

`ReleaseSerializationTest` passed **2/2**, with **0 failures, errors or skips**; release serialisation inputs were exported successfully. The fixtures cover the populated new end-marker fields and a legacy identity-only marker. This is a JVM release-variant check, not a new minified-APK or installation verification.

The session evidence is retained under `/workspace/scratch/1b34905af893/f1-evidence/`: `required-gate-summary.json`, `required-gate.log`, `required-gate-xml/`, `release-serialization.log` and `release-serialization-xml/`. The test-first counts above describe separate focused runs and are not added to the required-gate total.

## Independent code review

A separate read-only review of `c59e974..7725b44` found no Critical or Important correctness issues. Its documentation-completion note is addressed in this final evidence update. Claude's independent review and merge remain separate.

The timeout regression injects a real `TimeoutCancellationException` using a short nested timeout after a write. It verifies the receiver-to-service timeout-handling branch and UNKNOWN outcome; it does not measure expiry of the full `SERVICE_MS` wall-clock budget.

Physical BedJet 3 behaviour, Android phone delivery and installation remain untested by this work. The automated tests and any Robolectric captures are not hardware evidence. No production release or merge is part of F1.

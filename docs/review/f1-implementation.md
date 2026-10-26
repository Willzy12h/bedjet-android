# Nocturne F1 corrections — 24 October 2026

Branch: `gpt/nocturne-f1`.

Required hand-over base: `262c52fc7a2416f6b5d93a7abafb11079ec29d76` from `claude/nocturne-final-candidate`. The original branching base, `c59e974fd8dfeb4d29ed58f8567514ac6b7b345d`, remains part of the historical F1 evidence.

The owner's later instruction authorised a normal candidate-to-F1 merge. Local merge commit `1cc689d60da3e38e62f5ed219c6ac27b92811a48` has parents `90deefd681e49a0a482fa6be04cdc01461eeca7d` and the required base `262c52fc7a2416f6b5d93a7abafb11079ec29d76`. No rebase was performed.

The GitHub merged-code commit is `6d3773d0bb9ae2fabeb6fc38493dd250df6bf617`, with parents `4fdb0cfb953c590014b63bede743bb34f407ba66` and `262c52fc7a2416f6b5d93a7abafb11079ec29d76`. Its tree, `3e687391b648cbebe8f0c50f048643f5f5703702`, exactly matches the verified local merge. The first-parent hand-off commits also have identical trees. Publication through the GitHub API changes commit metadata while preserving file trees and normal merge topology; it does not rebase or force-push the work. The delivery adds a documentation-only hand-over commit after this verified code. The final hand-over records the exact delivery SHA.

This review covers the owner's six F1 requests. **The required gate for the merged sources and the additional release-journal check passed.** The earlier 156-core/504-app gate and additional 2-test release-journal check remain historical pre-merge evidence, preserved below.

The merge preserves all 35 upstream UI/non-runtime-string files and all 152 P6 runtime resources. F1 adds five runtime resources. P6's U11/F14 wording and status remain unchanged in the register.

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

## Per-item RED → GREEN evidence

Requests 1, 3 and 5 change production behaviour and have observed failing assertions before their corrections. Requests 2 and 6 are documentation-only, and request 4 replaces reflective test access with the existing public property; their behavioural RED is **N/A**, not an invented failure. No test was disabled or assertion weakened.

The GREEN suite counts in this table were confirmed in the **merged-source gate**, retained under `merged-required-gate-xml/app/`. Earlier focused GREEN runs and the original RED evidence are also retained. XML paths are relative to `/workspace/scratch/1b34905af893/f1-evidence/`; shared suites are not additional tests to add to a gate total.

| Request | Observed RED | Merged GREEN / verification |
|---|---|---|
| 1. Final background re-time failures | `retime-red-xml/` records six failed assertions, including `receiverPartialWriteLeavesUnknownAndPostsOneNotice`, `receiverPermanentPreWriteRefusalPostsOneNotice`, both catch-path cases and `receiverTimeoutAfterWritePostsOneTruthfulNotice`. Follow-up RED XML records duplicate takeover notices, a dropped refusal retaining its retry promise and a first observation with no final notice. | `RetimeNotificationTest` **28/28**; focused re-time/expiry/takeover/service-feedback coverage and its intermediate cycles are retained below. |
| 2. Off contract | **N/A — documentation-only.** The identity-free, captured-target-bound implementation and its three inverted regressions were preserved. | `RuntimeOwnershipTest` **49/49**, including the three unchanged Off cases; those cases also passed **3/3** in `manual/green-final-xml/`. The old notification, recreated widget and wrong-target boundaries retain their assertions. |
| 3. Delayed-end history label | `manual/red-xml/` records **eight label assertion failures**, including `aLateCoolEndKeepsTheCoolingLabel`, `takeBackUsesTheSavedCommandLabel`, `reconcileUsesTheSavedCommandLabelAfterRestart` and the neutral legacy-marker case. They expected the requested action or “Night stopped.” and received “BedJet off.” | `ManualEndRequestTest` **18/18**; command labels, restart, Take back, legacy markers and metadata cleanup pass. The earlier focused GREEN is retained in `manual/green-final-xml/`. |
| 4. Direct manual-end property access | **N/A — direct-property test cleanup with no behavioural change.** `ManualTrackingTest` now reads `hasPlannedManualEnd` directly; its assertions were retained. | `ManualTrackingTest` **10/10**; source inspection confirms direct property access. |
| 5. Report timestamp guard | `manual/red-xml/` records **three timestamp assertion failures**: `aStandbyReceivedBeforeTheRequestCannotFinishItButAnEqualTimeCan`, `restartingRetainsTheRequestTimeAndLabel` and `aNewManualEndReplacesBothTheOldLabelAndRequestTime`. Each showed an older report incorrectly closing the current request. | The same `ManualEndRequestTest` **18/18** GREEN covers old/equal report instants, zone-equivalent instants, restart and replacement of request metadata. The combined label/time RED had 11 failures, not two separate runs. |
| 6. Unreachable BedJet 3 with Off pending | **N/A — documentation-only.** Existing `endRequested` expiry behaviour was verified by source inspection and documented explicitly. | `SendConfirmationTest` **21/21**, including `anUnconfirmedOffDoesNotExpireAsAnEndedProgramme` and late-report/restart boundaries. The completion review and register describe UNKNOWN tracking past `endsAt` until a qualifying report or **Stop tracking**. |

### Historical focused cycles

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

## Current merged verification

**PASS — required gate against local merge `1cc689d60da3e38e62f5ed219c6ac27b92811a48`, identical tree to GitHub merged-code commit `6d3773d0bb9ae2fabeb6fc38493dd250df6bf617`.** Only documentation changes follow this code. The test outputs were cleared before the complete run:

```sh
./gradlew :core:cleanTest :app:cleanTestDebugUnitTest --offline --max-workers=2 --console=plain
export SCREENSHOT_TAG=f1
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --max-workers=2 --console=plain
```

The fresh merged-source JUnit XML reports:

| Scope | Suites | Tests | Passed | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|---:|
| Core | 20 | 156 | 156 | 0 | 0 | 0 |
| App debug | 60 | 504 | 504 | 0 | 0 | 0 |
| **Required gate total** | **80** | **660** | **660** | **0** | **0** | **0** |

All six opt-in screenshot tests ran. **Debug assembly passed. Lint passed with 0 Error, 0 Fatal, 14 Warning and 1 Hint.** The required gate completed with exit 0 in **8 min 22 s**, with **59 tasks: 27 executed and 32 up-to-date**. The debug APK is **68,038,781 bytes**, SHA-256 `3947a09575d140281ff03a1344e8e9c28de3a5eae079ea33fe78bc3e6d8afc62`.

**The additional merged-source release-journal check passed 2/2, with 0 failures, errors or skips.** Release serialisation inputs were exported successfully. The successful run completed with exit 0 in **50 seconds**, with **34 tasks: 3 executed and 31 up-to-date**. The populated new marker fields and a legacy identity-only marker are covered; this is a JVM release-variant check, not new minified-APK or installation verification.

The first attempt lost the Gradle daemon during `compileReleaseUnitTestKotlin`, before producing test results. The environment's memory controller records out-of-memory kills, but the log does not prove the particular daemon's cause. This is retained as a build-infrastructure failure, not regression RED. The unchanged check passed on retry with one worker, a fresh 1,536 MiB JVM and Kotlin compilation in that process:

```sh
./gradlew --no-daemon -I .github/workflows/release-serialization.init.gradle :app:testReleaseUnitTest --tests io.github.willzy12h.nocturne.releasegate.ReleaseSerializationTest :app:exportReleaseSerializationInputs --offline --max-workers=1 --console=plain '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process
```

Current evidence is retained under `/workspace/scratch/1b34905af893/f1-evidence/`: `merged-required-gate-summary.json`, `merged-pre-gate-clean.log`, `merged-required-gate.log`, `merged-required-gate-xml/`, `merged-release-serialization.log`, `merged-release-serialization-retry.log` and `merged-release-serialization-xml/`. These are separate from the pre-merge evidence below.

## Historical pre-merge integrated verification

**PASS before the new candidate merge — code commit `7725b442999e28ec13d1dc24021409147be3cd69`.** The following hand-off commit changed documentation only. These results do not establish the current merged-source gate. After clearing the two test tasks' outputs, that earlier required gate completed successfully:

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

## Files changed against the required base

The F1 delta against `262c52fc7a2416f6b5d93a7abafb11079ec29d76` contains these 14 files:

```text
.github/workflows/release-serialization-fixtures.json
app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/night/RuntimeState.kt
app/src/main/kotlin/io/github/willzy12h/nocturne/service/BedJetService.kt
app/src/main/res/values/strings_runtime.xml
app/src/test/kotlin/io/github/willzy12h/nocturne/night/ManualEndRequestTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/ManualTrackingTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/night/RuntimeRegressionTest.kt
app/src/test/kotlin/io/github/willzy12h/nocturne/service/RetimeNotificationTest.kt
docs/review/f1-implementation.md
docs/review/nocturne-completion.md
docs/review/register.md
docs/review/task-4-implementation.md
docs/superpowers/plans/2026-10-24-nocturne-f1.md
```

`RuntimeState.kt` contains the additional persisted end-marker fields and transient notice-ownership field. The release fixture checks the persisted fields and legacy compatibility. `RuntimeRegressionTest.kt` changes fixture setup only, preserving its five test bodies and assertions. `Receivers.kt` and `Notifications.kt` need no F1 edits and match the required base, as do `ui/**` and every other `strings*.xml` resource.

## Independent code review

A separate read-only review of `c59e974..7725b44` found no Critical or Important correctness issues. A further review of the normal merge confirmed that the F1 production and test files match that reviewed version, and that the upstream UI/string migration is preserved. It found no Critical or Important code findings; the remaining base/gate-status documentation note is addressed here. Claude retains independent review and final merge authority.

The timeout regression injects a real `TimeoutCancellationException` using a short nested timeout after a write. It verifies the receiver-to-service timeout-handling branch and UNKNOWN outcome; it does not measure expiry of the full `SERVICE_MS` wall-clock budget.

Physical BedJet 3 behaviour, Android phone delivery and installation remain untested by this work. The automated tests and any Robolectric captures are not hardware evidence. The authorised candidate-to-F1 merge is recorded above; no production release or merge into a `claude/*` branch is part of this hand-off.

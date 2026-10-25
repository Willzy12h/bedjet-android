# Fixes2 runtime evidence

Base: `1c67c0a28084238377c7675e6dc25d0444fe2c22`. This work covers F1–F3 in the fixes2 plan. Tests use Robolectric, JUnit 4, SDK 34 and `GraphLifecycle`. These are executed host results; Samsung, BedJet hardware, installation, TalkBack and physical notification delivery remain **NOT RUN**. The parent integration run owns the complete build, lint and release gate.

All Gradle commands use the shared serialising runner, offline dependencies and the existing JDK/SDK. The runner removes production signing environment variables. The owner, service and UI budgets remain 90, 100 and 115 seconds respectively. Confirmation receipt ordering is unchanged.

## F1 — settle interrupted manual transactions

[`NightActions.manualChange`](../../app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt) now guards pending publication, ending-intention persistence, the write, the matching-report wait, the fresh fallback and final publication together. An interruption after a possible write saves the same pending request as `UNKNOWN` and clears its `PendingChange`. A known pre-write failure restores the captured earlier night. Cleanup checks the exact request ID, identity and `PENDING` confidence, so it cannot downgrade a published confirmation or replace another request. It retains a valid Off intention and its receipt boundary. Cleanup runs within an eight-second `NonCancellable` limit; the original cancellation is then rethrown, with any cleanup failure suppressed on it.

[`ManualCancellationSettlementTest`](../../app/src/test/kotlin/io/github/willzy12h/nocturne/night/ManualCancellationSettlementTest.kt) asserts in-memory and on-disk state **before reconciliation**. Its cases cover cancellation after the callback returns during report confirmation; a caller timeout in that wait; cancellation during fallback reconnect and during the fresh-report wait; Off intention followed by a later Standby; intention-save failure; cancellation as pending state is durably published; confirmation-publication cancellation; another published request; and normal confirmed controls.

### Observed RED

Run label: `runtime-f1-red`. Command:

```sh
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py \
  --project /workspace/scratch/6cc8f67848d1/nocturne-runtime-work \
  --label runtime-f1-red -- :app:testDebugUnitTest \
  --tests '*ManualCancellationSettlementTest*'
```

Result: **10 tests, 7 failures, 0 errors, 0 skips**, exit 1. The report-wait cancellation, caller timeout, fallback reconnect, fallback report-wait and Off cases each failed with:

> An interrupted sent request must already be UNKNOWN before reconciliation expected:&lt;UNKNOWN&gt; but was:&lt;PENDING&gt;

`cancellationAsPendingPublishesBeforeWritingRestoresThePriorNight` expected the earlier `CONFIRMED` night with no pending change and found the new `PENDING` manual request. `anIntentSaveFailureRestoresThePriorTrackingWithoutSending` expected `UNKNOWN` and received `FAILED`, because the prior unknown tracking had been replaced with pending state. The published-confirmation, other-request and normal-control cases passed on the base.

### Observed GREEN

Run label: `runtime-f1-green`. Command:

```sh
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py \
  --project /workspace/scratch/6cc8f67848d1/nocturne-runtime-work \
  --label runtime-f1-green -- :app:testDebugUnitTest \
  --tests '*ManualCancellationSettlementTest*' \
  --tests '*RuntimeTimeoutFeedbackTest*' \
  --tests '*SendConfirmationTest*' \
  --tests '*ManualEndRequestTest*'
```

Fresh JUnit XML: **78 passed, 0 failures, 0 errors, 0 skips**, exit 0.

| Suite | Passed |
|---|---:|
| `ManualCancellationSettlementTest` | 10 |
| `RuntimeTimeoutFeedbackTest` | 3 |
| `SendConfirmationTest` | 37 |
| `ManualEndRequestTest` | 28 |

Evidence is retained under `/workspace/scratch/6cc8f67848d1/fixes2-evidence/runtime-f1-red/` and `runtime-f1-green/`: each contains the exact command/result, Gradle log and copied JUnit XML. Existing deprecation and missing-platform-tools warnings were emitted; no lint or physical-device claim is made by these focused runs.

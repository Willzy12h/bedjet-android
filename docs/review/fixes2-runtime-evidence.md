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


## Continuation of the interrupted runtime work

The continuation starts from `b2d3ee58fbf1f4a98a03f8f0d04016da06d96a73`, the F1 commit documented above, and preserves the earlier uncommitted F1 timed-control, F2 and F3 work. The original checkout remains untouched. New runs use `/workspace/scratch/93c42f112bf4/run_gradle.py`, the same serial build lock, offline JDK/SDK/dependency caches and unsigned configuration. New evidence lives in `/workspace/scratch/93c42f112bf4/evidence/<run-label>/`; each completed run retains the command/result JSON, Gradle log and copied JUnit XML.

The earlier `runtime-review-boundaries-red` **was not a valid assertion RED**: test compilation stopped on an unresolved `SlotWrite.program` reference. Its copied XML belonged to the preceding successful run and is excluded from the counts below. The fixture now reads the actual `SlotWrite.intended` field. The continuation's first `runtime-resume-boundaries-red` also executed no tests: Gradle could not initialise its local socket under the default sandbox. Authorised offline execution with the socket available then reached the assertions. `runtime-resume-boundaries-red-2` reproduced all three failures; `runtime-resume-boundaries-red-3` repeated them after the inherited tracked edits were verified in the isolated checkout.

### F1 — timed manual controls and confirmed ending publication

A timed Snooze or Extend could return from the manual confirmation path with the old programme still `CONFIRMED`, then be cancelled while saving its manual plan. The timed path now enters the existing programme-affecting settlement, so it durably marks manual tracking `UNKNOWN` and preserves the original programme's Resume offer before the later plan save.

Retained RED `runtime-f1-timed-red`: **2 tests, 2 assertion failures, 0 errors or skips**. Both `cancellingSnoozeBeforeItsPlanPublishesCannotRestoreAConfirmedProgramme` and `cancellingExtendBeforeItsPlanPublishesCannotRestoreAConfirmedProgramme` failed with:

> An interrupted sent request must already be UNKNOWN before reconciliation expected:&lt;UNKNOWN&gt; but was:&lt;CONFIRMED&gt;

Retained `runtime-f1-timed-f2-green` then passed **97 tests**: 12 manual-cancellation, 10 manual-tracking, 6 restore-capture, 10 imported-original, 50 runtime-ownership and 9 admission tests, with no failures, errors or skips. This historical run predates the additional boundaries below.

The confirmed-Off boundary was a separate gap: the STOPPED ending journal could be durable while prompt cancellation left its local completion pending. Generic interruption cleanup then changed the active request to `UNKNOWN`. The continuation retains the exact `PendingEnd` created after report confirmation and completes that durable journal during the bounded cleanup only when it still matches and the active request is the same pending request, or has already been removed. A newer request and a published confirmation retain the existing protection. Completion is local and sends no additional Off packet.

Fresh RED `runtime-resume-boundaries-red-3`: `cancellingAsConfirmedOffEndingPublishesCompletesItBeforeReconciliation` failed with:

> A durably confirmed Off must finish locally before reconciliation expected null, but was: ActiveNight(... confidence=UNKNOWN, pending=null ...)

The test first verifies that the matching STOPPED journal exists on disk, then checks the active-night file, runtime journal and single terminal History event before reconciliation. The full failing assertion, including its concrete night fixture, remains in the XML.

### F2 — bind a restore to the reviewed original throughout preparation

The ViewModel now passes its captured `SlotBackup`, and the slot-only entry captures the current target and original before waiting for the owner. A missing captured original remains missing for that request. Full-record comparisons include the payload and provenance. They run after owner admission, fresh status, generation persistence and the original-backup preparation. A changed record produces the resource-backed review refusal, sends no packet and removes the new pending slot-write journal while retaining any earlier unresolved journal.

Retained RED `runtime-f2-red-valid`: **6 tests, 4 assertion failures, 0 errors or skips**. The queued slot restore, queued ViewModel preview, fresh-status replacement and missing-original cases each wrongly returned `CONFIRMED` instead of `REFUSED`. The unchanged original and newly reviewed imported original controls passed. The earlier run named `runtime-f2-red` is not substituted for this valid result.

The final preparation test uses the real repository import policy after accepted original loss. It queues an import on the serial repository writer while the pending slot intention is being saved; import publication therefore occurs before the backup preparation finishes. This exercised the missing final comparison even though all earlier comparisons had passed.

Fresh RED `runtime-resume-boundaries-red-3`: `anImportQueuedDuringTheSlotJournalSaveIsRefusedAfterBackupPreparation` failed with:

> A changed original requires a new review expected:&lt;REFUSED&gt; but was:&lt;CONFIRMED&gt;

The test also checks that the imported record really replaced the uncertain original, no packet reached the demo hardware, its slots stayed unchanged and no slot write remained pending.

### F3 — unchanged observations remain quiet, including across wake-up start

The existing wake resolver now decides whether an observation asks for a changed wake before uncertain/manual-state refusals. A quiet local observation is returned directly instead of falling through into a retry refusal or executing an older request. Filtered alarms and overrides keep their quiet behaviour. Genuine changed alarms retain terminal refusal feedback, History evidence and one problem notice.

Retained RED `runtime-f3-red`: **13 tests, 7 assertion failures, 0 errors or skips**. Unchanged accepted alarms and fallback defaults on unknown/manual nights, an out-of-window alarm resolving to the same default, a filtered alarm and an override wrongly returned `REFUSED` instead of `LOCAL`. Retained `runtime-f3-green` passed **97 tests**: 13 unchanged-alarm, 20 runtime-delivery, 59 retime-notification and 5 retime-expiry-boundary tests, with no failures, errors or skips.

A queued observation also needs the same distinction after the wake-up boundary. When no prepared re-time exists, an unchanged resolved wake now consumes the observation without inventing an expiry. A real pending request continues through the existing expiry owner and notice path.

Fresh RED `runtime-resume-boundaries-red-3`: `anUnchangedObservationQueuedBeforeWakeRemainsQuietWhenConsumedAfterWake` failed with:

> A queued unchanged observation must remain quiet across wake-up start expected:&lt;LOCAL&gt; but was:&lt;REFUSED&gt;

A second fresh RED, `runtime-resume-alarm-reason-red`, ran the unknown, manual and confirmed variants together: **3 tests, 3 assertion failures, 0 errors or skips**. The unknown variant repeated that outcome failure. `anUnchangedManualObservationQueuedBeforeWakeKeepsItsTrackingReason` found `The wake-up change expired.` instead of `Existing tracking reason`. `anUnchangedConfirmedObservationQueuedBeforeWakeCannotInventAnExpiryReason` found the same expiry text instead of `null`. Each case checks unchanged active state, reason and History, no connection or write, an empty inbox, and no problem notification or logged notice.

### Fresh boundary RED command

```sh
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/runtime-work \
  --label runtime-resume-boundaries-red-3 -- :app:testDebugUnitTest \
  --tests '*ManualCancellationSettlementTest.cancellingAsConfirmedOffEnding*' \
  --tests '*RestoreOriginalCaptureTest.anImportQueuedDuringTheSlotJournalSave*' \
  --tests '*UnchangedAlarmObservationTest.anUnchangedObservationQueuedBeforeWake*'
```

Result: **3 tests, 3 assertion failures, 0 errors or skips**, exit 1. The additional alarm reason RED uses the same runner and project with label `runtime-resume-alarm-reason-red` and filter `*UnchangedAlarmObservationTest.*QueuedBeforeWake*`.


### Initial continuation GREEN

`runtime-resume-focused-green` completed with **36 passed, 0 failures, 0 errors and 0 skips**, exit 0: 13 `ManualCancellationSettlementTest`, 7 `RestoreOriginalCaptureTest` and 16 `UnchangedAlarmObservationTest` cases. This run precedes the repeated-broadcast regression and the final verification below. It used the new serial runner with `:app:testDebugUnitTest` and the three full suite filters.


### Repeated-broadcast RED and actual F2 mutation

After the 36-test GREEN, the final original comparison after `backup(target, slot)` was deliberately removed, leaving the owner-admission, fresh-status and generation checks intact. The exact correct source was retained, and `mutation.patch` plus `mutation.json` record the removed block and SHA-256 values. Run `runtime-resume-mutation-and-repeated-red` executed:

```sh
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/runtime-work \
  --label runtime-resume-mutation-and-repeated-red -- :app:testDebugUnitTest \
  --tests '*RestoreOriginalCaptureTest.anImportQueuedDuringTheSlotJournalSave*' \
  --tests '*UnchangedAlarmObservationTest.aRepeatedPostWakeObservation*'
```

The restore mutation was **caught by an assertion failure**: `anImportQueuedDuringTheSlotJournalSaveIsRefusedAfterBackupPreparation` again returned `CONFIRMED` instead of the required `REFUSED`. This demonstrates that the test specifically requires the comparison after the last repository preparation boundary. The complete correct file was restored and its SHA-256 equality verified before the next production change.

The two newly added alarm tests were also RED in this run. `aRepeatedPostWakeObservationPreservesAManualTrackingReason` found `The wake-up change expired.` instead of `Existing tracking reason`; `aRepeatedPostWakeObservationCannotInventAnExpiryForAConfirmedNight` found that expiry text instead of `null`. A second broadcast after wake was consuming the earlier observation through `expireRetimeFor`, bypassing the retry consumer's unchanged distinction. Both routes now share `unchangedAlarmObservation`; only an observation without a prepared request can take the quiet path. The existing real-pending expiry condition remains first and unchanged.

Combined result: **3 tests, 3 assertion failures, 0 errors or skips**, exit 1: one deliberate mutation failure and two new behaviour failures. The final verification below runs with the correct restore guard present.


### Final continuation GREEN

Run `runtime-resume-final-green` completed in **227.9 seconds**, exit 0. Fresh XML reports **113 passed, 0 failures, 0 errors and 0 skips**, including all 38 direct F1–F3 regressions and the existing behaviour most affected by these changes:

| Suite | Passed |
|---|---:|
| `ImportedOriginalRestoreTest` | 10 |
| `ManualCancellationSettlementTest` | 13 |
| `ManualEndRequestTest` | 28 |
| `ManualTrackingTest` | 10 |
| `RestoreOriginalCaptureTest` | 7 |
| `RuntimeDeliveryTest` | 20 |
| `UnchangedAlarmObservationTest` | 18 |
| `RetimeExpiryBoundaryTest` | 5 |
| `RetimeNotificationTest` | 2 |

The two `RetimeNotificationTest` controls are `aNewAlarmObservationCannotSwallowThePendingRetimeExpiryNotice` and `anExpiryMessageCannotHideAnUnknownNightReconciliationRefusal`. The final run used:

```sh
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/runtime-work \
  --label runtime-resume-final-green -- :app:testDebugUnitTest \
  --tests '*ManualCancellationSettlementTest*' \
  --tests '*RestoreOriginalCaptureTest*' \
  --tests '*UnchangedAlarmObservationTest*' \
  --tests '*ManualTrackingTest*' \
  --tests '*ManualEndRequestTest*' \
  --tests '*ImportedOriginalRestoreTest*' \
  --tests '*RuntimeDeliveryTest*' \
  --tests '*RetimeExpiryBoundaryTest*' \
  --tests '*RetimeNotificationTest.aNewAlarmObservationCannotSwallowThePendingRetimeExpiryNotice' \
  --tests '*RetimeNotificationTest.anExpiryMessageCannotHideAnUnknownNightReconciliationRefusal'
```

The correct original guard is present in this final run. `inputs.sha256` records the six tested source/resource/test files. `git diff --check` passes. Independent read-only source review found no concrete defect; a separate evidence audit reproduced the mutation hashes and confirmed the real import fixture and recorded assertion counts.

The focused builds emitted the existing deprecation and missing-platform-tools warnings. This continuation makes no new lint, release, Samsung, installation, TalkBack, hardware-control or physical-notification claim. The root integration run owns the full project test/lint/build gate and unsigned release assembly. The 90-second owner, 100-second service and 115-second UI budgets, single command mutex and report receipt ordering remain unchanged.

# Nocturne fixes2 — Step A completion evidence

Original base: `1c67c0a28084238377c7675e6dc25d0444fe2c22`.
Branch: `gpt/nocturne-fixes2`.

## Recovery and preservation

The interrupted local tip `28225f7fec7fb196eade839a3dd56cd511f4fea9` and both unfinished runtime/alarm worktrees were present. The original checkouts remain intact. The initial origin pointed at another local checkout; forwarding to GitHub through the shell failed because shell Git credentials were unavailable.

The connected GitHub API preserved the five commits in their original order and with the same messages and exact file trees. Its commit endpoint assigns author/committer metadata, so the preserved remote checkpoint is `ea9d6c6191ff0eda27b50a0601e6b67b465acb58`, rather than the original local hash. Both tips have the exact tree `98ba06e49ff7e90d789d88b484c32c719d9d4e0a`. The original Git history was additionally retained in a verified bundle and a local recovery branch. No force push, rebase, reset, clean or worktree deletion was used.

## Completed continuation items

| Item | Implementation and evidence |
|---|---|
| F1: cancellation through confirmation and fresh fallback | The whole manual transaction is guarded. Bounded `NonCancellable` cleanup settles only the matching request. Valid Off intent and receipt boundaries survive. See `ManualCancellationSettlementTest` and [runtime evidence](fixes2-runtime-evidence.md). |
| F1: confirmed Off ending publication | A matching durable `PendingEnd` is completed locally on interruption, with one STOPPED event and no second Off write. Regression: `cancellingAsConfirmedOffEndingPublishesCompletesItBeforeReconciliation`. |
| F1: timed manual publication | Snooze/Extend preserve manual uncertainty and the programme resume offer before later revision persistence. Cancellation cannot restore false confirmed programme tracking. |
| F2: captured restore original | The owner retains the exact previewed original, checks it after admission and preparation, and checks it again after backup persistence before any destructive write. `RestoreOriginalCaptureTest` covers queued direct/ViewModel calls, fresh preparation and the final slot-journal race. |
| F2: actual mutation | Removing only the final post-backup comparison caused the preparation-race regression to return CONFIRMED instead of REFUSED. The correct source was restored and its checksum checked before the final GREEN. |
| F3: queued unchanged observation crossing wake | An observation without a changed resolved wake stays quiet and preserves tracking reason, History and hardware state. The same guard handles a repeated post-wake broadcast. Real pending changes retain their expiry and terminal-feedback behaviour. See `UnchangedAlarmObservationTest` and the existing expiry/notification controls. |
| F4/F5 | The 32-step uncertain restore preview scrolls at 320 dp/200% text; curve descriptions omit unavailable Off/Pause controls. See [UI/protocol evidence](fixes2-ui-protocol-evidence.md). |
| Lights/beeps | Existing mappings were retained and tested against literal packets/status flags and actual Remote switches. No new physical claim is made; the user separately reported passing these phone checks. |
| Alarm confidence | PENDING/FAILED/UNKNOWN precede manual-tracking wording; a saved override retains its origin. Fresh alarm RED: 35 tests/9 expected failures; GREEN: 51 passed. See [alarm evidence](fixes2-alarm-evidence.md). |
| Navigation, saved setting, diagnostics export | `AlarmSettingsNavigationTest` exercises actual Tonight-to-Settings navigation, both saved unknown-source choices and exported diagnostic reason/text. Previous valid integration RED had 3 assertion failures. The integrated GREEN ran 45 tests with no failures/errors/skips, including the complete explanation matrix. |
| BLE visibility/throttling/grace | Foreground actual/ambient sensor temperatures are limited to one update per second; control fields stay immediate. Hidden presentation is cancelled; idle remainder reads and routine platform observation saves stop. Connection grace is 30 seconds, cancelled by foreground return; command holders retain their connection and raw receipts. See [BLE evidence](fixes2-ble-behaviour.md). |

The final runtime-focused GREEN executed **113 tests, 0 failures, 0 errors, 0 skipped**: manual cancellation 13; restore capture 7; unchanged observations 18; manual ending 28; manual tracking 10; imported originals 10; runtime delivery 20; expiry boundaries 5; targeted terminal-notification controls 2.

## Full gate

The first integrated full gate ran all core/app tests and exposed twelve failures: three old tests still expected the former five-second grace; eight U06 cases exposed delayed controls under the whole-status throttle; one saved-override case showed irrelevant no-alarm wording. None was skipped or weakened.

- Grace assertions now check READY at 29,999 ms and release at 30,000 ms, retaining duplicate-holder protection. The corrected tests plus `ForegroundConnectionTest` passed 7/7.
- The presentation throttle now samples only measured temperatures. A fresh RED had 5 assertion failures across 9 tests; GREEN passed 21, including all eight existing U06 failures. See [display evidence](fixes2-display-evidence.md).
- A saved override without a current alarm retains its confidence-specific prefix without appending the fallback explanation. RED had 4 expected failures/17 tests; GREEN passed 56, retaining the existing `WakeFeedbackTest` assertion in all looks. See [alarm evidence](fixes2-alarm-evidence.md).

First gate counts: core 187 passed; app 992 total, 942 passed, 12 failed, 38 skipped, 0 errors. The final gate below supersedes that failed run.

Final run `stepa-final-gate` succeeded in **922.48 seconds (15 minutes 22 seconds)** with the exact requested offline flags, all four gate tasks and `:app:assembleRelease` in one invocation.

| Check | Final result |
|---|---|
| Core | 187 tests, 187 passed, 0 failures/errors/skips |
| App | 1,001 tests, 963 passed, 0 failures/errors, 38 expected opt-in capture skips |
| Debug assembly | Succeeded |
| Debug lint | 0 errors, 14 warnings, 1 hint |
| New lint findings against `1c67c0a` | 0, comparing issue identity, file and source snippet |
| Release assembly | Succeeded; unsigned release APK produced without production signing credentials |

Machine-readable counts and lint identities are committed in [fixes2-gate-results.json](fixes2-gate-results.json). Raw command, output and copied JUnit XML remain under `evidence/stepa-final-gate` in the continuation workspace. All remaining Step A implementation and integration items are complete; physical-device acceptance remains outside this host gate.

The baseline lint comparison was freshly run at `1c67c0a`: **0 errors, 14 warnings and 1 hint**. Existing warning IDs were OldTargetApi (1), SpecifyJobSchedulerIdRange (1), ObsoleteSdkInt (1), and UseKtx (11); the hint was AutoboxingStateCreation. Comparison uses issue identity, source file and source snippet, rather than counts alone.

## Review and limits

A read-only review of the preserved BLE changes and another of the final F1–F3 changes found no additional concrete defect in those inspected scopes. A separate F2 evidence check verified the exact one-guard mutation, expected assertion, real repository import and restored source. The integrated runtime cross-check reviewed the complete NightActions diff and surrounding receipt, mutex and cancellation paths; the three direct regression files still matched the 113-test GREEN inputs. The integrated alarm cross-check reviewed source policy, confidence ordering, navigation, write-before-publish settings, refresh and exported JSON; its central files matched the 56-test GREEN inputs. Root also reviewed the BLE/display, curve/resource, settings and navigation diffs. No additional concrete defect was found in these final Step A checks. These reviews do not constitute independent project sign-off.

Step A deliberately retains the existing alarm-source default and inclusive 1–16 hour normal-night policy. The next authorised UX work addresses explanations, the requested Settings override/test option, and other phone-test findings. The short-alarm filter is a duration rule, not a delayed AlarmManager read.

The preserved original history bundle was re-verified after integration and records the complete original history, including `28225f7` and the runtime/alarm worktree refs.

The command owner remains `NightActions` through `BedJetService.runOwned`; owner/service/UI budgets remain 90/100/115 seconds. Production signing and its four environment variables are unchanged. There is no hardware, Samsung, TalkBack, on-device installation or battery-measurement claim. Release assembly is a host build result only.

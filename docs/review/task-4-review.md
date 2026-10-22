### Spec Compliance

- ❌ Issues found: R26/R28 restart safety is incomplete for Boost; R30/R31 can lose wake/end delivery while the command owner is busy; F01 cannot always clear an override; F16 can leave a confirmed wake notification displayed after uncertainty/takeover; F02 does not post the resume notification after a programme replacement. Five Important findings below.
- ⚠️ Cannot verify from this task diff: Task 5's actual Keep tracking/Stop tracking/Resume presentation and use of typed outcomes, and Task 6's widget identity capture, charger eligibility, job cancellation and hand-over callers. Root should verify these against the frozen runtime contracts during integration. These are separate cross-task checks, not presumed defects in absent work.
- ⚠️ Physical BedJet 3 timing, Android/Samsung background eligibility, Doze delivery and two-phone transfer remain unverified. The report correctly distinguishes automated evidence from hardware validation.

### Strengths

- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:65-91`: whole-operation ownership uses an owner token, includes queue waiting in the deadline and releases ownership in `finally`; submission-target validation happens under the mutex. `service/BedJetService.kt:80-103` adds a bounded service handoff with cancellation cleanup rather than a second command lock.
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:132-140,175-179`: the prepared transaction is durably published and original-slot recording precedes mutation. `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightChanges.kt:17-21` now carries the immutable intended snapshot through a restart.
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:268-306`: the alarm observation inbox can be persisted without waiting behind an upload and conditionally clears by observation ID. `app/src/test/kotlin/io/github/willzy12h/nocturne/service/RuntimeServiceTest.kt:113-129` exercises the actual receiver during held hardware work.
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ShortcutActivity.kt:17-26` and `ui/NightGuard.kt:22-40`: external shortcuts require a visible confirmation; the running identity shown in a dialog is retained rather than recaptured at acceptance. `app/src/test/kotlin/io/github/willzy12h/nocturne/AppSmokeTest.kt:65-80` checks a changed night while that dialog is open.
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:599-607`: diagnostics explicitly serialise settings and bounded events/notices, without the separate secret token; `night/RuntimeOwnershipTest.kt:191-197` checks the token exclusion. The production clock change in `App.kt:42-47` preserves intentionally injected clocks while following the system zone.

### Issues

#### Critical (Must Fix)

- None identified.

#### Important (Should Fix)

1. **An interrupted Boost can leave the displaced night eligible for automatic replay.** `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:222-226,475-494,401-406` and `night/RuntimeState.kt:20-21`.

   Boost saves only a `SlotWrite` before replacing the working slot and starting it. The old active night remains CONFIRMED until `finishLocked` after success. The catch path marks the active night uncertain, but a process death does not execute that catch. On restart, `reconcile` changes the pending slot journal to UNKNOWN without changing the old confirmed active night. `queueRetimeLocked`/`retryRetimeLocked` at lines 308-340 check the active night, not the uncertain slot journal. A pending alarm observation can therefore rewrite and restart the old night after an uncertain Boost upload/start. There is also a crash window after SlotWrite becomes CONFIRMED but before the old night is finished.

   Persist the relationship between the replacement operation and its previous night, including the intent to start, before the first hardware mutation. Reconciliation must either complete a known local finish or mark the displaced active night UNKNOWN and suppress automatic rewrites. Add restart regressions seeded at both the pending-upload and confirmed-slot-before-finish windows, with an alarm observation present; assert no replay and truthful active confidence.

2. **Wake and night-end broadcasts are discarded when another command owns the mutex for more than 8.5 seconds.** `app/src/main/kotlin/io/github/willzy12h/nocturne/night/Receivers.kt:16-17,42-51`; `night/NightActions.kt:260-265,417-423`.

   Both one-shot alarm paths enter the shared receiver timeout and then wait for the command mutex. A slow connect, Remote operation or upload may legitimately own that mutex for up to 90 seconds. At 8.5 seconds the pending wake/end operation is cancelled and the receiver finishes after showing an error. Unlike the retime path, neither action is durably submitted nor rescheduled. If the owning operation does not itself reschedule the night, the wake notification is missed or tracking remains active after its end until a later app-open/reconciliation event. The existing end-versus-extend test calls `expire` directly and therefore does not cover this receiver deadline.

   Keep the receiver short, but durably retain or safely re-arm an identity-bound local wake/end request when ownership is unavailable. Consume it under the same coordinator mutex and retain stale-revision rejection. Add an actual receiver regression holding ownership beyond the broadcast budget, then releasing it and verifying one local delivery, with no BLE acquisition and no duplicated wake notice.

3. **Clearing tonight's override is refused when the fallback wake time is identical.** `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:508-523`; `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt:63-68`.

   `setWakeOverride(null)` prepares a retime before clearing settings. The core correctly returns `Preparation.Refused("Wake-up unchanged.")` when the alarm/default resolves to the current wake instant, and the runtime immediately returns that refusal without clearing the override or updating the active night's origin. For example, a user chooses 07:00 as an override, their phone alarm is also 07:00, and then chooses to follow their alarm again. Reset does nothing; a subsequent move to 07:30 remains ignored because the active night still has OVERRIDE origin.

   Treat this case as a local source/override transition under ownership, preserving the existing applied programme and wake instant while durably clearing the override and updating attribution. Add a regression that resets an equal-time override and then changes the alarm, proving the night follows it.

4. **The quiet notification can continue claiming a confirmed wake after takeover or a failed Remote command.** `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:185-195,443-447,579-584`; `notify/Notifications.kt:78-88`.

   Quiet notifications are cancelled for the prepared programme transaction and on local finish, but the manual-command pending/unknown path and both takeover paths do not cancel or refresh them. With quiet status enabled, turning the BedJet off using its physical remote changes the active night to UNKNOWN and posts a separate information notification while the ongoing notification still reads “Waking at hh:mm”. A Remote failure has the same stale display. `showQuiet` checks confidence only when invoked; there is no reactive notification reconciliation in the diff.

   Reconcile/cancel the quiet notification on every transition away from a confirmed programmed night, including manual tracking, and on the setting changing. Add NotificationManager-shadow coverage that starts with a visible quiet notification and verifies it no longer asserts the old wake after takeover and uncertainty.

5. **Programme replacement creates the resume offer but never posts its notification action.** `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:141-158,232-237`; `notify/Notifications.kt:91-98`.

   The successful replacement branch saves `resumeOffer`, but only `finishLocked` calls `Notifications.showResume`. Replacing one night with another goes through `apply`, not `finishLocked`, so the required “Resume the rest of the night” notification is absent. The replacement-offer case is distinct from the tested Stop tracking/Off flow.

   After a successful replacement with a valid offer, post the resume notification using the current captured identity. Preserve confirmation when accepting that offer would replace the new night; do not silently grant replacement confirmation. Add a notification-shadow regression for confirmed Start-over-Start replacement, and coordinate the confirmation presentation with Task 5.

#### Minor (Nice to Have)

1. **Verification output is successful but not pristine.** `/workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/task4-verified-gate.log:80-86` reports deprecated Gradle features incompatible with Gradle 9. The existing lint report identifies `app/build.gradle.kts:15` (OldTargetApi) and `app/src/main/res/mipmap-anydpi-v26` (ObsoleteSdkInt), ending at `app/build/reports/lint-results-debug.txt:34` with 0 errors and 2 warnings. These are reported baseline/tooling issues, not a newly introduced runtime regression; retain them in the lead's release/toolchain follow-up rather than describe the output as warning-free.

### Checks and Evidence

- Reviewed the supplied immutable diff package for `a5d01c1c83e828421a1420e45516d74bc91a1fde..dd0c365f779fe6b9090943e84d846315090e73eb`, including all production, test and register hunks. Used sequential chunks; recovered tool-output truncation with narrow excerpts. No git commands, checkout/index/branch changes, implementation edits or subagents.
- Read the task brief including appended rulings, global continuation overrides, reviewer rubric and implementer report. The report's final full gate is supported by `task4-verified-gate.log:86-87` (“BUILD SUCCESSFUL”, 59 actionable tasks). The reported 138 core and 137 app test totals were not independently regenerated; no suite was rerun.
- Named outside-diff check: the reset-override and retry-replay risks required the intact core `NightRunner.prepareRetime`/`prepareRewrite`/`apply` contracts, which the supplied removal hunks omit. Read `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt:39-190`; confirmed equal-wake refusal and that no slot-journal guard exists in that core contract. No broader codebase crawl. A guessed `NightTransitions.kt` path did not exist; no conclusion relies on that missing file.
- Named evidence check: inspected only the stated final build log and generated lint summary to distinguish actual build success from warning-free output. The findings above follow directly from deterministic control flow and missing durable transitions, so no additional test execution was needed; focused regressions are specified with each finding.

### Cross-task Verification Questions for Root

- `night/RuntimeState.kt:23-24` and `service/BedJetService.kt:132-140`: do Task 6 widgets capture strict night/revision identity when rendered, rather than reconstructing a fresh identity only at tap time? The new notification PendingIntent route does capture identity, but the widget action plumbing is outside this runtime diff.
- `night/NightActions.kt:54-62,566-589`: do Task 6 charger and hand-over callers use the job-owned/whole-operation routes and supply fresh eligibility without raw session suspension? Verify job cancellation and the chosen Start-only use at integration.
- `night/NightActions.kt:364-389` and `night/RuntimeState.kt:32-42`: do Task 5 recovery controls expose Keep tracking/Stop tracking and Resume with a real captured-identity confirmation where replacement is required? This report does not count their absence from the runtime-only diff as a Task 4 presentation defect.

### Assessment

**Task quality: Needs fixes.**

**Reasoning:** The durable prepared transactions, target-bound service delivery and alarm inbox are substantial improvements with useful behavioural regression coverage. The remaining restart and receiver-delivery holes can violate the central no-blind-replay and truthful-tracking guarantees; the override and notification gaps are concrete missing behaviours rather than optional polish.

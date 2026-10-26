# Task 3: night-state and planning core

Status: DONE (Task 3 core scope; Task 4 runtime integration explicitly remains). Worktree `/workspace/scratch/ad4eb857ceea/nocturne-wt-task3`, branch `astra/nocturne-task3`, initial reset to `a8cbaba`. No hardware validation. Implemented by GPT-6 Astra.

## Authorised scope adaptations

Lead explicitly approved mechanical adaptations to `AppViewModel.activePlan`, `Repository.tonightsProfile` and selectedProfileId UI callers, while preserving the current UI. `NightRecord.revisions` is authorised by the ownership ledger. Legacy runner mutators are explicitly retained as interim compatibility adapters by lead instruction; Task 4 must migrate all production callers to the authoritative identity/transaction API before claiming whole-app stale protection. NightActions public surface is unchanged.

## Progress and decisions

- First shell action was the authorised isolated-worktree reset, followed by local.properties for the supplied Android SDK.
- Applied the test-driven-development skill. Initial eight baseline regression assertions all failed for the expected behaviours, then passed after snapshot/state and planning fixes.
- New policy API contracts were introduced as no-behaviour scaffolds, then seven executable tests failed before implementing alarm attribution/window, persisted override, temporary choice and extreme step-growth validation.
- New transaction API contracts were introduced as no-behaviour scaffolds, then nine executable tests failed before implementing prepare/apply, local transitions, resume and preview parity.
- Four additional concrete edge regressions failed then passed: reapply remaining-start changes, no replay of a Middle Turbo, stale identity after backup, quantised fan bounds.
- Two-phase Start preparation regression failed then passed.
- Final complete suites passed: core 134/134; app 85 passed, 4 pre-existing ScreenshotCapture cases skipped, zero failures. The final small override-history attribution fix was then covered by another complete 134-test core run.

## Environment

Gradle requires escalation for local coordination sockets. The first elevated run failed on a temporary binary resolution store; a no-daemon retry cleared it without deleting caches or changing dependencies. Use:

```
JAVA_HOME=/workspace/scratch/ad4eb857ceea/nocturne-jdk17/jdk-17.0.20.1+1
GRADLE_USER_HOME=/workspace/scratch/ad4eb857ceea/gradle-cache
./gradlew ... --offline --console=plain
```

Initial RED ran under system Java successfully for core; subsequent builds use the complete explicit JDK. Baseline SDK XML/deprecated icon/Gradle warnings are not introduced by this task.

## Interim API contract (final details to follow)

`NightIdentity(nightId, revision)` is the optimistic identity. `prepareStart`, `prepareRetime`, `reapply` and `resume` produce `Preparation.Ready(change)`, `Stale`, or `Refused`. A Ready contains both pending and intended states. `apply(change, current, commandVersion, publish, beforeWrite)` must be called under the caller-owned lock and connection. It reads authoritative current state, publishes pending before any write, runs durable original-slot backup in beforeWrite, checks eligibility again, writes/readbacks/starts, then publishes confirmed. Exceptions before a possible write yield FAILED; any possible write yields UNKNOWN. Cancellation publishes the terminal uncertainty in NonCancellable and rethrows. A failure to persist the initial pending state propagates and performs no hardware work.

Task 4 supplies repository callbacks, captures/persists monotonically increasing command generation for resume, owns deadline/lock/connection lifecycle, and removes the unsafe interim adapters once migrated. No claim of whole-app two-phase durability or stale protection is made by Task 3 alone.

## Test fixture changes

The old Turbo-then-Heat planner/boost tests asserted Turbo under maxTemp=76 (38°C), which contradicts the explicit new truthful-cap requirement. Their valid-chain fixtures now use maxTemp=86 (43°C), retaining assertions for Turbo then Heat and the hardware Heat maximum 80 (40°C). Separate new regression tests require rejecting Turbo below 43°C for both chaining modes. No existing behavioural assertion is weakened to preserve the unsafe cap claim.

## Evidence files

Raw outputs are retained beside this report as `task3-*.log`; the final report will embed the relevant command/output evidence and final suite totals.


## Final implementation and exact contracts

### State and history

- `ActiveNight` remains readable from v1 JSON. It now stores `deviceId`, nullable `profile` snapshot, `wakeOrigin` (default derived from v1 `wakeFromAlarm`), `revision`, `applied`, `confidence`, `pending` and `actualEnd`. Old confidence is UNKNOWN and applied is empty. `identity` returns `NightIdentity(recordId, revision)`; `usesLiveProfileFallback` is explicit when profile is absent.
- `AppliedProgram(steps, start, wake, revision, programSteps)` records display/history steps plus exact protocol steps (including clock-time flags), so `plan()` does not reconstruct a different programme. `ActiveNight.activePlan(liveProfile?)` prefers applied, then snapshot, then the flagged v1 live fallback.
- `NightRecord.revisions` defaults empty for compatibility. `NightMutation.Applied.recordRevision(record)` preserves original `steps`, reconstructs the first revision from legacy steps when needed, and idempotently appends/replaces the current numbered revision.

### Preparation and application

- `prepareStart(profile, alarm, slot, deviceId, expected: NightIdentity?, current: ActiveNight?, override?, settings, alarmOrigin?)` validates expected identity (null means no active night), uses the shared start preview, validates 3-slot bounds, and produces a new UUID-based night/request.
- `prepareRetime(night, expected: NightIdentity, alarm, deadline = now + 1 minute, liveProfile?, settings, alarmOrigin?)` uses the saved snapshot and applied revision. Alarm acceptance is anchored at the ORIGINAL start. No eligible phone alarm resolves to the SAVED profile default; an active override remains agreed and is not replaced by phone-alarm changes. An identical wake produces Refused/unchanged.
- `reapply(night, editedProfile, expected, newWake?)` is a pure preparation operation. It keeps wake and origin unless `newWake` explicitly sets an OVERRIDE, consumes elapsed Start time, replaces snapshot on confirmation, and increments revision.
- All preparations return `Preparation.Ready(PreparedChange)`, `Preparation.Stale(expected, actual)`, or `Preparation.Refused(reason)`. Ready holds `plan`, `intended`, `previous`, `request`, optional `expectedCommand`, and `pendingNight`. The pending state preserves last applied state while carrying the requested change; it does not claim the intended programme has run.
- `apply(change, current: () -> ActiveNight?, commandVersion: () -> Long = {0}, publish: suspend (ActiveNight) -> Unit = {}, beforeWrite: suspend () -> Unit = {})` performs the hardware operation. Task 4 MUST supply durable callbacks and hold its command lock/connection. It re-reads authoritative current identity before publishing and after beforeWrite. Own pending start/resume publication is allowed; a different current identity/version returns typed Stale with no write. Deadline and confirmed wake boundary are checked again after callback work. CONFIRMED is published only after controller store readback and start confirmation.
- `NightMutation` is Applied(plan,night), Stale(expected,actual), Refused(reason), or Failed(night,cause). BeforeWrite failure produces FAILED; any exception after store could begin produces UNKNOWN, retaining pending request and last applied data without claiming the old programme survived. Initial pending persistence failure propagates before writes. Cancellation during the transaction publishes terminal uncertainty in NonCancellable then rethrows; if publication itself fails, its exception propagates (durability cannot be invented).
- `Eligibility.check(night, now, request)` returns Allowed/Stale/Refused. It rejects deadline equality and later, revision mismatch, actualEnd, confirmed wake-boundary equality and later, and failed/uncertain programmes requiring reconciliation. A genuine untouched v1 night can use the flagged fallback; a failed pending v1 rewrite cannot exploit that exception.

### Local transitions and resume

- Pure `stopTracking(night, reason, at, commandVersion = 0)` returns `LocalTransition(night,event,resumeOffer)`. It stamps actualEnd, clears pending, and records STOPPED without a BedJet call. `expire(night,at)` returns null before end/already ended, otherwise an ENDED transition. `settingsAfter(settings)` clears tonight's override.
- `ResumeOffer` is serialisable and contains the stopped night/snapshot/wake, remaining applied RecordedSteps, stop time, expiry at stop + 15 minutes, and commandVersion. Callers persist it and increment their durable generation on every newer action; Task 4 owns that storage and lifecycle. The offer is supplied when wake time remains, including when no heating remains after removing a started Turbo.
- `resume(offer, now, commandVersion)` rebuilds from remaining applied steps to the original wake. Completed/started Turbo is dropped in every block. Gaps left by dropped Turbo become WAIT; after-wake steps retain their recorded timing. Empty/outsize plans are refused. Expiry is exclusive (at exactly expiry, refused). Application requires no current active night and the captured command version; newer commands or nights produce Stale. Resume uses the same record id and a new revision.

### Wake policy, limits and settings

- `AlarmPolicy(settings).accept(alarm, origin, originalNightStart)` shares attribution and time-window logic. Exactly 1 h and 16 h are accepted; 59 min and 16 h + 1 min are not. Configured Google/Samsung packages are default attribution. Null source is accepted only with documented `Settings.acceptUnknownAlarmSource` (default true). Android AlarmSource delegates attribution to this policy.
- `NightPreview.forStartNow(profile, now, alarm, override?, settings, alarmOrigin?)` returns `Preview(plan?,wake,refusalReason?)`; precedence is valid override, eligible alarm, profile default. Fresh nights must be 1–16 h. The 06:34/07:00 case refuses the next-day 24.5h night and suggests a tonight-only wake. Short remaining rewrites still work: PlanCompiler's low-level compile can represent less than an hour, while new-night length validation belongs to preview/start.
- PlanCompiler refuses >16h wake duration and >32 steps before writing. `ProfileValidation.validate(profile)` tests 1h/16h extremes using actual mode splitting/chaining. Profile mode bounds must overlap its temperature cap; Turbo below 43°C throws the exact required explanation. Fan steps are quantised INSIDE both bounds. WAIT/STANDBY are non-heating modes and retain their protocol placeholder temperature.
- `Settings.setWakeOverride(wakeAt,at)`, `clearWakeOverride`, `resolveWakeOverride(at)` persist and resolve TonightOverride. Resolve expires at equality. History attribution explicitly says tonight-only wake time.
- `chooseTonight(profileId,at)`, `followSchedule`, `migrateTonightChoice(at)`, `resolveTonightChoice(at)`, `resolveProfile(profiles,at)` implement temporary choice. Expiry is the next noon in the current zone. No live choice means weekday schedule then first profile. `selectedProfileId` is kept only as an authorised v1 bridge; migrate once and persist. Repository queues that migration through its durable writer; UI selection calls now create an expiring choice. No UI redesign or new follow-schedule button was introduced; Task 4/UI wiring remains.

## Android compatibility adaptations and integration correction

- AppViewModel.activePlan delegates to saved applied/snapshot state. NightActions automatic re-time and active limits use snapshot fallback; Update tonight still takes the intentionally edited reusable profile. Confirmed retime/reapply append applied history. Start resolves persisted override. Finish clears override. Existing NightActions public surface is unchanged.
- Repository uses temporary choice/schedule helper and persists v1 choice migration. TonightScreen/ProfilesScreen selection callers use chooseTonight; visual layout is unchanged.
- Five Android fixture classes now pin `Graph.clock` to 2026-10-21 22:30 UTC and restore the previous clock in teardown: AppSmokeTest, AppearanceTest, ImportTest, SaveOrderingTest, NightActionsTest. Relative test alarms use graph.now. All old behavioural assertions remain.
- **Authorised integration correction:** DemoBedJet inherited a no-op prepareForStart while GattLink clears cached progress. Under a fixed clock, a restart with an identical first step was suppressed by StateFlow equality, so controller confirmation timed out. A focused fixed-clock RED test proved it; Demo now clears published extra in prepareForStart, matching GATT's existing seam. The same regression passes. No real GATT/controller change was made.
- A provisional clock-advance fixture workaround was explicitly rejected by the lead and reverted before the final suite. The stuck executor's jstack is saved as task3-android-hang.txt. Only that task's identified test-executor process was terminated; no caches or shared daemons were stopped.

## Verification and self-review

- Final full `:core:test :app:testDebugUnitTest --offline --console=plain`: BUILD SUCCESSFUL in 44s. Core 134 tests, zero failures/errors/skips. App 89 discovered, 85 passed, 4 existing ScreenshotCapture skips (simpleLight, simpleDark, dark, light), zero failures/errors.
- Last tiny history attribution correction: separate assertion RED then full `:core:test --offline --console=plain`, BUILD SUCCESSFUL in 3s, 134 tests passing. App had passed immediately before this string-only correction; no further optional app run was necessary.
- Initial full Android run had exactly seven named failures, all retained in evidence: AppSmokeTest.startingANightOnTheDemoBedJetShowsItRunning, AppSmokeTest.aStrayTapDuringANightAsksBeforeStoppingIt, AppearanceTest.simpleViewListsTheNightAndEndsAtTheWakeTime, ImportTest.anImportLeavesTheBedJetAndTheNightAlone, SaveOrderingTest.updateTonightPutsTheEditedProfileOnTheBedJet, NightActionsTest.turboEndsTheNightOnceTheBedJetHasIt, NightActionsTest.aMovedAlarmMovesTheWakeUpAndSaysSo. These are resolved by deterministic evening fixtures plus the genuine Demo confirmation fix, not by weakened assertions.
- `git diff --check` clean. Self-review inspected owned-state defaults, identity checks after callback suspension, snapshot vs live uses, origin preservation, migration first-revision retention, Turbo removal in Start/Middle/tail, and caller changes. New failures found in self-review were fixed test-first and are included below.
- Relevant existing suites pass. Parent owns full post-merge assemble/lint gate and independent review. No supervised phone/BedJet/hardware checks claimed.

## Required Task 4 hand-off / limits

1. Replace all production uses of legacy `start`, `retime`, `quickAction`, `boost`, `stop` adapters with expected-identity prepared apply / owned manual-command paths. Adapters are clearly documented and retained only at lead direction for this intermediate commit. They cannot independently enforce repository stale protection or persistence. Remove them once callers/tests migrate.
2. Persist pending and terminal state through publish; capture/persist original device-slot (including empty marker) through beforeWrite; preserve cancellation and report FAILED/UNKNOWN truthfully. Lock and connection ownership are external to core by design.
3. Persist monotonic command generation and ResumeOffer, invalidate on newer action/device/ownership changes, and wire pure stop/expiry to repository/scheduling.
4. Wire headline/curve to NightPreview, follow-schedule/override/resume UI, and remaining notification/widget/history presentation. Core helpers are ready; no whole-app R16/R26/R29 or UI completion claim is made here.
5. Use original snapshot default when the phone alarm is cancelled/no eligible alarm exists, preserve active override, and never automatically restart after takeover or confirmed wake boundary. Cancellation delivery/retry is Task 4.
6. Hardware confirmation, mode behaviour and interrupted-write acceptance remain pending.

## Changed files

- `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/AlarmSource.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfilesScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppSmokeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppearanceTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/data/ImportTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/data/SaveOrderingTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/night/NightActionsTest.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/link/DemoBedJet.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/plan/PlanCompiler.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/plan/WakeResolver.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/History.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/Settings.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightRunnerTest.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/plan/PlanCompilerTest.kt`
- `docs/review/register.md`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/ActiveNight.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightChanges.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/plan/NightPreview.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightStateHardeningTest.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightTransactionsTest.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/plan/PlanningHardeningTest.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/plan/WakePolicyHardeningTest.kt`

## TDD command/output evidence

Commands below ran in the task worktree with the JDK/cache environment documented above and elevated execution for Gradle sockets. The earliest initial RED used system Java. Missing-symbol compilation of new API contracts and a missing serialization test import were corrected before collecting the executable scaffold RED outputs; those preliminary compile failures are not counted as behavioural RED.

### 8 baseline failures

Command: `./gradlew :core:test --tests '*PlanningHardeningTest' --tests '*NightStateHardeningTest' --no-daemon --console=plain`

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightStateHardeningTest > next night edits cannot affect automatic retiming() FAILED
    org.opentest4j.AssertionFailedError at NightStateHardeningTest.kt:27

NightStateHardeningTest > v1 night gains unknown confidence and alarm origin() FAILED
    org.opentest4j.AssertionFailedError at NightStateHardeningTest.kt:22

NightStateHardeningTest > edited wake phases cannot move the confirmed boundary() FAILED
    org.opentest4j.AssertionFailedError at NightStateHardeningTest.kt:36

NightStateHardeningTest > start stores its device snapshot and first applied revision() FAILED
    org.opentest4j.AssertionFailedError at NightStateHardeningTest.kt:43

PlanningHardeningTest > repeat turbo cannot silently exceed the cap() FAILED
    org.opentest4j.AssertionFailedError at PlanningHardeningTest.kt:65

PlanningHardeningTest > turbo then heat cannot silently exceed the cap() FAILED
    org.opentest4j.AssertionFailedError at PlanningHardeningTest.kt:53

PlanningHardeningTest > over sixteen hours is refused before producing a programme() FAILED
    org.opentest4j.AssertionFailedError at PlanningHardeningTest.kt:41

PlanningHardeningTest > mode minimum cannot silently exceed maximum() FAILED
    org.opentest4j.AssertionFailedError at PlanningHardeningTest.kt:77

8 tests completed, 8 failed

[Incubating] Problems report is available at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 34s
4 actionable tasks: 4 executed
```

### initial GREEN

Command: `./gradlew :core:test --tests '*PlanningHardeningTest' --tests '*NightStateHardeningTest' --offline --console=plain`

```text
Starting a Gradle Daemon, 1 busy and 1 incompatible and 1 stopped Daemons could not be reused, use --status for details
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test
w: Detected multiple Kotlin daemon sessions at 

[Incubating] Problems report is available at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 17s
4 actionable tasks: 4 executed
```

### 7 executable policy scaffold failures

Command: `./gradlew :core:test --tests '*WakePolicyHardeningTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

WakePolicyHardeningTest > unknown attribution obeys preference() FAILED
    org.opentest4j.AssertionFailedError at WakePolicyHardeningTest.kt:22

WakePolicyHardeningTest > validation accounts for turbo chaining at sixteen hour extreme() FAILED
    org.opentest4j.AssertionFailedError at WakePolicyHardeningTest.kt:56

WakePolicyHardeningTest > alarm policy includes one and sixteen hours but nothing beyond() FAILED
    org.opentest4j.AssertionFailedError at WakePolicyHardeningTest.kt:16

WakePolicyHardeningTest > legacy selection migrates to an expiring choice when resolved() FAILED
    java.lang.NullPointerException at WakePolicyHardeningTest.kt:50

WakePolicyHardeningTest > six thirty four default wake refuses a twenty four hour night() FAILED
    java.lang.NullPointerException at WakePolicyHardeningTest.kt:37

WakePolicyHardeningTest > temporary choice expires to schedule and follow schedule clears it() FAILED
    org.opentest4j.AssertionFailedError at WakePolicyHardeningTest.kt:43

WakePolicyHardeningTest > override wins survives restart and expires at wake() FAILED
    org.opentest4j.AssertionFailedError at WakePolicyHardeningTest.kt:29

7 tests completed, 7 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 4s
4 actionable tasks: 2 executed, 2 up-to-date
```

### 9 executable transaction scaffold failures; absent prepared changes surface as expected cast/null failures in the original helper, subsequently improved to assert Ready

Command: `./gradlew :core:test --tests '*NightTransactionsTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > resume drops turbo already started and refuses expiry or newer action() FAILED
    java.lang.NullPointerException at NightTransactionsTest.kt:104

NightTransactionsTest > preview is the actual start plan() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:117

NightTransactionsTest > deleted live profile does not prevent retime or display() FAILED
    java.lang.ClassCastException at NightTransactionsTest.kt:21

NightTransactionsTest > local stop and expiry need no link and clear override() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:93

NightTransactionsTest > failure before write is failed and partial write is unknown() FAILED
    java.lang.ClassCastException at NightTransactionsTest.kt:21

NightTransactionsTest > reapply keeps agreed wake and origin and increments revision() FAILED
    java.lang.ClassCastException at NightTransactionsTest.kt:21

NightTransactionsTest > eligibility is rechecked after preparation at boundary and deadline() FAILED
    java.lang.ClassCastException at NightTransactionsTest.kt:21

NightTransactionsTest > stale identity and revision refuse without writing() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:47

NightTransactionsTest > pending is published before any write and confirmed follows readback() FAILED
    java.lang.ClassCastException at NightTransactionsTest.kt:21

9 tests completed, 9 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 3s
4 actionable tasks: 4 executed
```

### policy and transaction GREEN

Command: `./gradlew :core:test --tests '*NightTransactionsTest' --tests '*HardeningTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

BUILD SUCCESSFUL in 4s
4 actionable tasks: 4 executed
```

### 4 additional edge regressions

Command: `./gradlew :core:test --tests '*NightTransactionsTest' --tests '*PlanningHardeningTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > reapply changes the remaining start phase without replaying elapsed time() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:126

NightTransactionsTest > resume never restarts an already started middle turbo() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:135

NightTransactionsTest > changing identity during backup prevents a stale write() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:145

PlanningHardeningTest > fan quantisation honours both profile bounds() FAILED
    org.opentest4j.AssertionFailedError at PlanningHardeningTest.kt:29

17 tests completed, 4 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

### edge GREEN

Command: `./gradlew :core:test --tests '*NightTransactionsTest' --tests '*HardeningTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

BUILD SUCCESSFUL in 4s
4 actionable tasks: 4 executed
```

### Start scaffold RED

Command: `./gradlew :core:test --tests '*NightTransactionsTest.start preparation*' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > start preparation is pending and stale replacement is refused() FAILED
    java.lang.ClassCastException at NightTransactionsTest.kt:21

1 test completed, 1 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 4 executed
```

### Start GREEN

Command: `./gradlew :core:test --tests '*NightTransactionsTest' --tests '*HardeningTest' --tests '*NightRunnerTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

BUILD SUCCESSFUL in 3s
4 actionable tasks: 4 executed
```

### 2 legacy regressions

Command: `./gradlew :core:test --tests '*NightTransactionsTest.legacy night*' --tests '*NightTransactionsTest.new revision*' --offline --console=plain`

```text
Starting a Gradle Daemon, 1 busy and 1 incompatible and 2 stopped Daemons could not be reused, use --status for details
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > legacy night uses flagged live fallback for retime() FAILED
    java.lang.ClassCastException at NightTransactionsTest.kt:21

NightTransactionsTest > new revision preserves the first revision of legacy history() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:176

2 tests completed, 2 failed
w: Detected multiple Kotlin daemon sessions at 

[Incubating] Problems report is available at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 13s
4 actionable tasks: 2 executed, 2 up-to-date
```

### legacy GREEN

Command: `./gradlew :core:test --tests '*NightTransactionsTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test
w: Detected multiple Kotlin daemon sessions at 

BUILD SUCCESSFUL in 3s
4 actionable tasks: 4 executed
```

### cancelled-alarm characterization passes; uncertain legacy RED

Command: `./gradlew :core:test --tests '*NightTransactionsTest.cancelled alarm*' --tests '*NightTransactionsTest.uncertain rewrite*' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > uncertain rewrite of a legacy night cannot be retried as legacy fallback() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:201

2 tests completed, 1 failed
w: Detected multiple Kotlin daemon sessions at 

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

### identical-step Demo regression RED

Command: `./gradlew :core:test --tests '*NightTransactionsTest.immediate rewrite*' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > immediate rewrite confirms even when first step is identical() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:210

1 test completed, 1 failed
w: Detected multiple Kotlin daemon sessions at 

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

### Demo GREEN

Command: `./gradlew :core:test --tests '*NightTransactionsTest.immediate rewrite*' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

BUILD SUCCESSFUL in 4s
4 actionable tasks: 4 executed
```

### started-Turbo-only remainder RED

Command: `./gradlew :core:test --tests '*NightTransactionsTest.resume offers*' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > resume offers time remaining even when only started turbo was left() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:217

1 test completed, 1 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

### complete GREEN incl. uncertain legacy/resume tail and real Demo fix

Command: `./gradlew :core:test :app:testDebugUnitTest --offline --console=plain`

```text
Warning: SDK processing. This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered. This can happen if you use versions of Android Studio and the command-line tools that were released at different times.
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:mergeDebugShaders UP-TO-DATE
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets UP-TO-DATE
> Task :app:packageDebugUnitTestForUnitTest UP-TO-DATE
> Task :app:generateDebugUnitTestConfig UP-TO-DATE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :app:compileDebugKotlin
> Task :core:test
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 44s
32 actionable tasks: 7 executed, 25 up-to-date
```

### history incorrectly called override default RED

Command: `./gradlew :core:test --tests '*NightTransactionsTest.preview is*' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTransactionsTest > preview is the actual start plan() FAILED
    org.opentest4j.AssertionFailedError at NightTransactionsTest.kt:120

1 test completed, 1 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

### final full core GREEN

Command: `./gradlew :core:test --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

BUILD SUCCESSFUL in 3s
4 actionable tasks: 4 executed
```

### initial complete run with seven clock-fixture failures, all resolved

Command: `./gradlew :core:test :app:testDebugUnitTest --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:compileTestKotlin UP-TO-DATE
> Task :core:compileTestJava NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig
> Task :app:generateDebugResValues
> Task :app:checkDebugAarMetadata
> Task :app:mapDebugSourceSetPaths
> Task :app:generateDebugResources
> Task :app:packageDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:parseDebugLocalResources
> Task :app:mergeDebugResources
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebug
> Task :app:processDebugManifestForPackage
> Task :app:javaPreCompileDebugUnitTest
> Task :app:mergeDebugShaders
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets
> Task :app:processDebugResources
> Task :app:packageDebugUnitTestForUnitTest
> Task :app:generateDebugUnitTestConfig

> Task :app:compileDebugKotlin
w: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt:74:39 'val Icons.Filled.ViewList: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.ViewList.

> Task :app:compileDebugJavaWithJavac
> Task :app:processDebugJavaRes
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes

> Task :app:testDebugUnitTest

AppSmokeTest > startingANightOnTheDemoBedJetShowsItRunning FAILED
    androidx.compose.ui.test.ComposeTimeoutException at AppSmokeTest.kt:44

AppSmokeTest > aStrayTapDuringANightAsksBeforeStoppingIt FAILED
    androidx.compose.ui.test.ComposeTimeoutException at AppSmokeTest.kt:44

AppearanceTest > simpleViewListsTheNightAndEndsAtTheWakeTime FAILED
    androidx.compose.ui.test.ComposeTimeoutException at AppearanceTest.kt:44

ImportTest > anImportLeavesTheBedJetAndTheNightAlone FAILED
    io.github.willzy12h.nocturne.core.plan.PlanException at ImportTest.kt:29

SaveOrderingTest > updateTonightPutsTheEditedProfileOnTheBedJet FAILED
    io.github.willzy12h.nocturne.core.plan.PlanException at SaveOrderingTest.kt:58

NightActionsTest > turboEndsTheNightOnceTheBedJetHasIt FAILED
    io.github.willzy12h.nocturne.core.plan.PlanException at NightActionsTest.kt:107

NightActionsTest > aMovedAlarmMovesTheWakeUpAndSaysSo FAILED
    io.github.willzy12h.nocturne.core.plan.PlanException at NightActionsTest.kt:66

89 tests completed, 7 failed, 4 skipped

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 1m 6s
32 actionable tasks: 29 executed, 3 up-to-date
```

## Commit and final hand-off

- `5e0a072` — `feat(core): snapshot and transact active nights`.
- Author preserved: Willzy12h <29470889+Willzy12h@users.noreply.github.com>.
- Truthful trailer: `Implemented-By: GPT-6 Astra`; no fabricated Claude co-author/session.
- 27 files changed; worktree clean after commit. No push performed.
- Status: DONE for the agreed Task 3 core scope. Runtime callback wiring/legacy adapter removal and hardware verification remain explicitly tracked for Task 4/lead acceptance.


# Review fix round 1/5 — temporal execution guards

Read the complete independent review `task-3-review.md` and applied receiving-code-review plus TDD. Verified both findings against the execution branches before edits. No reset: original Task 3 commit `5e0a072` preserved.

## Findings and changes

1. **Resume execution could outlive its original wake.** Preparation previously guarded wake only once, while PendingChange.deadline used the 15-minute offer expiry. It now uses the earlier of offer expiry and original wake. Existing eligibility and pre-write guards consequently reject equality and later both before application and after a suspending backup. Before publication it returns Refused without writes or state changes. After PENDING publication but before any write, it publishes FAILED and retains the request; it never claims CONFIRMED.
2. **Upload/readback could consume temporal eligibility before start.** A shared `checkExecutionTime(change)` now checks the strict deadline and the previous revision's confirmed wake boundary before store, after store/readback, and again at the final start send. Any late failure after store remains UNKNOWN, because its slot contents may already have changed; no start command is sent.
3. **Lead-authorised minimal controller hook closes the clock-sync window.** `BedJetController.startProgram(slot, beforeStart: () -> Unit = {})` remains compatible with legacy callers. The synchronous callback executes after suspending syncClock/prepareForStart and immediately before `link.send(Codec.startProgram(slot))`. A thrown guard exits coroutineScope and cancels its active confirmation waiter. NightRunner supplies the same temporal guard. No GATT changes or Task 1 redesign.

## Focused tests and evidence

`NightTemporalEligibilityTest` uses the real controller and DemoBedJet with a narrowly instrumented link: it counts actual programme writes/start packets and suspends inside upload/readback/clock sync while advancing the injected clock. The tests assert actual slot content, publication sequence, retained request and confidence, and absence of start packets.

- Resume pre-apply delay: equality at 07:00 and later at 07:01, with an offer valid until 07:10; Refused, no writes/start/publication, old slot untouched.
- Resume backup delay: equality and later; PENDING -> FAILED, no writes/start, old slot untouched.
- Upload crosses request deadline: equality and later; uploaded slot exists, PENDING -> UNKNOWN, zero start commands.
- Readback crosses confirmed wake-up boundary: equality and later; uploaded slot exists, PENDING -> UNKNOWN, zero start commands.
- Resume readback reaches original wake before offer expiry: UNKNOWN, no start.
- Clock sync reaches deadline: UNKNOWN, no start; virtual execution finishes without waiting for the 10-second confirmation timeout, proving the waiter is cancelled.

RED item 1: two tests failed against `5e0a072`. GREEN item 1: both pass after deadline clamp. RED item 2: six tests run, original two pass and four new upload/readback/clock-sync regressions fail. GREEN final: **36 tests passed, zero failures/errors/skips** (6 temporal, 19 existing night transaction, 11 existing controller). `git diff --check` clean. The temporary test-only ExperimentalCoroutinesApi warnings in the second RED run were removed with an explicit opt-in before GREEN.

No broad rerun added: root owns the post-review full integration gate. Prior complete suite evidence remains above. No hardware checks claimed. Task 4 intended-change durability and UI expiry refresh are unchanged carried integration checks, not implemented in this fix.

## Changed files

- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/link/BedJetController.kt` (lead-authorised final-send guard hook)
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightTemporalEligibilityTest.kt`
- `docs/review/register.md` (only R26/R29/F02 test evidence appended)

## Exact commands/output

All commands used the previously documented explicit JDK/cache, offline mode and elevated Gradle execution.

### task3-fix1-red-resume.log

`./gradlew :core:test --tests '*NightTemporalEligibilityTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTemporalEligibilityTest > resume delayed in backup fails at wake and after wake without any write() FAILED
    org.opentest4j.AssertionFailedError at NightTemporalEligibilityTest.kt:74

NightTemporalEligibilityTest > resume delayed before apply is refused at wake and after wake without any write() FAILED
    org.opentest4j.AssertionFailedError at NightTemporalEligibilityTest.kt:59

2 tests completed, 2 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

### task3-fix1-green-resume.log

`./gradlew :core:test --tests '*NightTemporalEligibilityTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

BUILD SUCCESSFUL in 3s
4 actionable tasks: 4 executed
```

### task3-fix1-red-start-boundary.log

`./gradlew :core:test --tests '*NightTemporalEligibilityTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:compileKotlin UP-TO-DATE
> Task :core:compileJava NO-SOURCE
> Task :core:processResources NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar UP-TO-DATE
> Task :core:processTestResources NO-SOURCE

> Task :core:compileTestKotlin
w: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightTemporalEligibilityTest.kt:142:36 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'
w: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightTemporalEligibilityTest.kt:144:34 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'

> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE

> Task :core:test FAILED

NightTemporalEligibilityTest > readback crossing confirmed wake boundary at equality or later never starts the slot() FAILED
    org.opentest4j.AssertionFailedError at NightTemporalEligibilityTest.kt:95

NightTemporalEligibilityTest > clock sync crossing deadline refuses final start and cancels confirmation waiter() FAILED
    org.opentest4j.AssertionFailedError at NightTemporalEligibilityTest.kt:95

NightTemporalEligibilityTest > resume readback reaching original wake never starts the slot() FAILED
    org.opentest4j.AssertionFailedError at NightTemporalEligibilityTest.kt:95

NightTemporalEligibilityTest > upload crossing request deadline at equality or later never starts the slot() FAILED
    org.opentest4j.AssertionFailedError at NightTemporalEligibilityTest.kt:95

6 tests completed, 4 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':core:test'.
> There were failing tests. See the report at: file:///workspace/scratch/ad4eb857ceea/nocturne-wt-task3/core/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

### task3-fix1-green-start-boundary.log

`./gradlew :core:test --tests '*NightTemporalEligibilityTest' --tests '*NightTransactionsTest' --tests '*ControllerTest' --offline --console=plain`

```text
> Task :core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :core:processResources NO-SOURCE
> Task :core:processTestResources NO-SOURCE
> Task :core:compileKotlin
> Task :core:compileJava NO-SOURCE
> Task :core:classes UP-TO-DATE
> Task :core:jar
> Task :core:compileTestKotlin
> Task :core:compileTestJava NO-SOURCE
> Task :core:testClasses UP-TO-DATE
> Task :core:test

BUILD SUCCESSFUL in 3s
4 actionable tasks: 4 executed
```

Fix-round commit: `479ca09` — `fix(core): enforce night deadlines at final start command`.
Parent commit `5e0a072` preserved. Worktree clean after commit; no push. Both Important review findings addressed, including the lead's clock-sync/final-send extension. Ready for scoped re-review and root integration gate.

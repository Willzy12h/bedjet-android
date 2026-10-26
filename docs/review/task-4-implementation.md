# Task 4 — Runtime implementation report

Implementer: GPT-6 Astra. Base: a5d01c1c83e828421a1420e45516d74bc91a1fde. Branch: astra/nocturne-task4. Final commit and gate results are appended below after verification. No hardware verification and no push.

## Scope and implementation

Implemented the Task4 runtime in the isolated worktree after the required reset and SDK local.properties setup. Read HANDOVER, the complete plan/rulings, register, ledger, Task3 implementation/review/rereview and the implementer prompt. Used the Superpowers implementer/TDD/debugging/verification workflow without subagents. Parent independently reviews and integrates.

NightActions owns whole operations through one mutex, including target validation, connection, durable intended state, original-slot recording, upload/readback/start confirmation and local transitions. There is no second service command queue/lock. The core prepared/apply route is authoritative; unsafe legacy runner start/retime/manual/Boost/stop adapters and Repository.addSlotBackup were removed. Core test helpers model an owner with current state, publish and beforeWrite; old manual/Boost assertions moved to owned app runtime coverage.

PendingChange now persists the complete intended ActiveNight snapshot/applied steps. It retains the previous agreed state while PENDING and never reconstructs an interrupted intended programme from a subsequently edited profile. Repository.runtime is a narrow runtime.json journal using the existing serial durable writer; it is deliberately excluded from portable profile backups. It holds generation, resume offer, pending local ending, pending retime, alarm observation inbox, slot write intention/confidence, reason and wake delivery identity.

All slot writes require a selected device and recordOriginalSlot before mutation, including empty originals. Migrated unknown originals are adopted by originalsFor before restore. Backup failure aborts Start, Boost, pin or restore. Pin uses an explicitly selected non-working slot, refuses a running programme, converts clock-time phases to duration phases, reads back, and explicitly reports that it does not follow alarms. Shared ReplacementPolicy refuses restore while any local night is still tracked; End or Stop tracking must happen first. A fresh device stopped state alone cannot silently invalidate the tracked slot.

Remote mode/Off operations end tracking only after accepted hardware commands; lights/beeps/filter acknowledgment preserve the night. Manual snooze/extend stores its actual one-step applied programme, increments the revision and ends at that manual duration even if shorter than the previous night. It never rearms a past wake. Fresh contradictory status after the grace window records REMOTE_CHANGE and UNKNOWN; ordinary progression is ignored. Keep tracking takes fresh status, records a manual snapshot and RECOVERED event, disables alarm retime; Stop tracking is local and explicitly does not turn the unit off.

Separate wake/end identities, durable wake deduplication, future-only alarm scheduling and mutex-guarded expiry prevent stale delivery from erasing a replacement. Startup/boot/package/time/timezone/exact-permission reconciliation repairs interrupted local endings and marks pending hardware intentions UNKNOWN without replay. The production Clock resolves the current system zone each time; injected clocks retain their configured zone.

## Frozen API contracts for Task5 (UI)

`graph.actions.capture(command, profileId = null, requireIdentity = false): CommandRequest` captures selected target and current night/revision immediately. `execute(request): CommandOutcome` is the preferred typed coordinator route. Real-device UI hardware work must enter it through BedJetService.runOwned; direct calls are for service/job owners, local-only operations or demo. Capture before any save-wait, coroutine queue or dialog delay. `perform(...)` remains a string convenience for existing callers/tests; new UI should use typed outcomes.

```kotlin
val requested = graph.actions.capture(Command.START_NIGHT, profileId, requireIdentity = true)
// Show confirmation if ReplacementPolicy.decide(requested.command, active).needsConfirmation.
// Only the positive callback may set replacementConfirmed; retain requested, do not recapture.
uiScope.launch {
    val outcome = BedJetService.runOwned(context) {
        graph.actions.execute(requested.copy(replacementConfirmed = true))
    }
    // Haptics/status use outcome.kind, never parse outcome.message.
}
```

Off is the identity-free exception to night/revision validation: a stale night identity never blocks it, including an old notification/widget/dialog request carrying `requireIdentity=true` or an Off queued behind a newly starting night. `NightActions.execute` skips only Off's night/revision check; `owned` and `run` still validate its captured target independently. The captured target must remain the selected BedJet 3: a target change rejects the old request without writing, and the request never switches to another target. Other old notification/widget/dialog commands use `requireIdentity=true` and are rejected after the night identity/revision changes. Off is available without replacement confirmation. Unconfirmed Start/Turbo/Boost/Warm/Cool/Resume replacing a night return CONFIRMATION_REQUIRED without mutation. Cancelling a confirmation does nothing. This supersedes the original strict-identity rule for old Off requests.

Existing NightGuard now captures the ActiveNight shown when its dialog opens. Its positive callback calls `AppViewModel.withConfirmedNight(expected, action)`, which refuses a changed identity and scopes capture synchronously for the action. `AppViewModel.run` forwards that captured identity into a strict request; Remote mode callbacks capture target/identity before their queued work. A proposed universal replacementConfirmed=true patch was rejected by automatic approval review and never executed. The final route requires a real positive callback. The exported ShortcutActivity likewise captures at dialog creation, then passes that exact request only on positive confirmation.

Observable contracts:

- `busy: StateFlow<BusyState>`: runningLabel and queuedCount, released in finally even on cancellation/timeout. The 90-second deadline includes queue waiting.
- `outcomes: SharedFlow<CommandOutcome>`: typed completion stream; cancellation propagates rather than masquerading as success.
- `status: StateFlow<NightStatus>`: Confidence? plus reason; pending alarm submission/retime and uncertain slot writes are surfaced.
- `CommandOutcome.kind`: CONFIRMED, ACCEPTED, LOCAL, REFUSED, STALE, FAILED, UNKNOWN, CONFIRMATION_REQUIRED; succeeded is true only for the first three.
- `repository.runtime: StateFlow<RuntimeState>` exposes durable resumeOffer and pending recovery data. Do not mutate it to issue commands.

Device-confirmed means stored programme verified by readback and, when started, fresh program-step confirmation (the existing core controller waits for start/refusal). ACCEPTED is a manual GATT operation accepted by the link, without a claim that subsequent hardware state was independently observed. LOCAL means local tracking/settings/hand-over changes, not a claim that the BedJet stopped or another phone connected. A tracked manual snapshot may be CONFIRMED as the accepted local tracking state; its command outcome remains ACCEPTED. Keep tracking is LOCAL after reading fresh status. PENDING/FAILED/UNKNOWN are retained truthfully; interrupted or possibly accepted partial writes become UNKNOWN.

Owned UI methods (all suspend, return CommandOutcome unless noted):

- `selectTarget(deviceId: String?)`, `forgetTarget()`, `setDemoMode(enabled: Boolean)`, `updateSettings(change: (Settings) -> Settings)`. Active-night target/demo changes are refused; UI can offer End/Stop tracking first. BLE IDs must be `ble:AA:BB:CC:DD:EE:FF`. No implicit nearby choice. Existing remembered address is adopted once at startup and logged.
- `setupFanCheck()`: fresh idle status, only one small supported fan adjustment, bounded finally restoration. Refuses running/active state; does not start a mode.
- `setWakeOverride(wake: ZonedDateTime?)`: null clears; active confirmed programme is prepared/applied under ownership using its saved profile; manual/uncertain nights refuse. Start and Reapply respect Settings override precedence. End clears it.
- `pin(profileId: String, slot: Int)`, `restoreOriginal(slot: Int)` use zero-based slots 0..2, originals and readback. `ReplacementPolicy.permitsSlotRestore(active)` is false for any tracked night.
- `remote(label, endsNight = false, target = currentDevice, expected = currentIdentity, work: suspend (BedJetController) -> Unit)`. UI must pass pre-captured target/identity if awaiting before this call. Mode changes use endsNight=true; auxiliaries false. No detached caller work inside work.
- `execute(capture(STOP_TRACKING / KEEP_TRACKING / RESUME, requireIdentity = true))` for recovery actions; confirm Resume if replacing a current night.
- `diagnostics(): String` returns bounded JSON; `exportDiagnostics(uri: Uri)` writes it to a user-selected output. Includes settings (no token field), current night, last50 events and notices, version/device model/link state.
- `Settings.quietNightNotification` defaults false. When enabled the quiet status notification offers End/Open.

Theme seam: `Settings.isDarkTheme(at: ZonedDateTime, systemDark: Boolean): Boolean` is the shared pure resolver. Task5 extends it for Scheduled. `widgetColors(settings: Settings, at: ZonedDateTime)` now delegates to it while SYSTEM keeps dynamic light/dark providers; widget callers use context.graph.now(). There is no obsolete enum-only overload/exhaustive switch to break Task5's isolated build. Task4 introduces no scheduled-theme fields. Task5 owns its setup/check-in/theme settings; Task6 owns PlatformSettings.

## Frozen API contracts for Task6 (platform)

Charger automation owns its JobService coroutine and cancellation. No foreground service or detached coroutine is created by this route:

```kotlin
val request = graph.actions.capture(Command.START_NIGHT, profileId, requireIdentity = true)
val result = graph.actions.performJobOwned(request) { fresh ->
    // This callback runs after acquiring whole-command ownership and connecting/re-reading status,
    // before the first mutation. Recheck CURRENT charging/time/settings eligibility here.
    !fresh.isRunning && chargingStillValid() && automationStillEligible()
}
```

`performJobOwned` is bounded to 90 seconds, refuses any current tracked night, validates captured target/strict identity, and invokes eligible under the same ownership. The caller must include idle and fresh policy checks, not rely on a pre-connection observation. Cancelling the job propagates; possibly accepted mutations are journalled UNKNOWN with NonCancellable persistence, never retried blindly. No FGS fallback. This API is intended for short eligible automatic Start work; it is not a route for arbitrary commands that bypass replacement policy.

`releaseForOtherPhone(untilMs: Long? = null, declineIfBusy: Boolean = false)` waits for whole-operation ownership before calling/joining LinkSessions.suspendAutoConnect. Peer requests use declineIfBusy=true and receive refusal while a transaction is running/queued. Manual release waits. `takeBack()` resumes auto-connect, obtains fresh status and reconciles contradictions without replaying a programme. Outcomes are LOCAL: they do not assert that the other phone has connected. Do not invoke raw suspendAutoConnect after a separate idle check.

`BedJetService.start(context, command, profileId = null, replacementConfirmed = false): Boolean` captures now and reports whether a service acquired the delivery; `start(context, captured: CommandRequest)` carries an existing confirmed request. Service owns the whole operation, checks Bluetooth CONNECT for real hardware before promotion, uses connectedDevice for real hardware and shortService on API34 for demo/local Stop tracking, and catches platform FGS denial visibly. A false return means no operation owner exists. Notification pending intents are immutable and capture the target and strict night/revision identity. Off ignores the captured night/revision under the exception above; its captured-target validation remains mandatory.

## Durable alarm submission and restart semantics

The alarm observation inbox is a submission signal, not another hardware command queue. `submitAlarmChange()` (compatibility alias `recordPendingRetime()`) prearms best-effort retry and writes a single newest observation containing ID/night/device/time through Repository's existing writer, within an 8-second local budget. It does not acquire the hardware mutex or touch BLE. Receiver `goAsync` work has an 8.5-second budget and always finishes; persistence exceptions show/log failure while the prearmed OS retry remains best effort. This bounded failure path cannot promise durable persistence if storage itself is unavailable.

When coordinator ownership becomes available, it consumes by observed ID, rereads the latest phone alarm, validates current night/device/confirmed eligibility and prepares a current-identity retime. Conditional clear by ID preserves a newer signal. Other runtime transforms preserve the inbox, including upload completion. The actual PendingRetime has ID, expected identity, alarm metadata and deadline at the confirmed wake boundary. Retry uses exact where permitted or setAndAllowWhileIdle, approximately10 minutes capped at boundary, never an alarm-clock alarm. App opening and alarm changes request retry. At/missed boundary it expires visibly with no mutation. Manual/takeover/override/ineligible nights do not replay an obsolete alarm. A partial upload UNKNOWN is not replayed.

Generation and ResumeOffer survive process restart. New commands/target changes invalidate older offers; offers expire after the core fifteen-minute window and are revalidated against current time/generation/device. A completed stop or replacement offers remaining time, dropping already-started Turbo steps via Task3 preparation. Local end has a separate PendingEnd journal so a crash between runtime/history/active/settings writes can finish idempotently, preserving its offer without hardware access. Startup transforms pending hardware state to UNKNOWN while keeping the full intended snapshot for review/recovery. It never reconstructs that intent from live edited profiles. Wake delivery identity is durable and manual snooze/extend will not re-pop it.

## Tests and evidence

All log paths below are under `/workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/`. Commands use JDK17.0.20.1+1, shared gradle-cache, `--offline --console=plain`, and approved local Gradle sockets. Focused behavioral RED runs precede their implementation. Some early scaffolding runs first required compilation fixes; those are not counted as behavioral RED. An initial hand-over fixture could leave its deferred upload blocked after assertion failure; fixed it to release in finally, then reran the actual RED. No unrelated daemon was stopped or cache deleted.

| Regression batch | RED evidence | GREEN evidence |
| --- | --- | --- |
| Empty original, shorter manual, past wake, stale finish, dynamic zone | task4-red-baseline.log:5 failures | task4-green-baseline.log:5 pass |
| Ownership, intent-before-write, backup abort, cancellation, queued Off, target, pin/restore, resume, takeover, diagnostics, hand-over/job | task4-red-ownership.log:16 tests,9 failures | task4-green-ownership.log:16 pass |
| Delivery/coalescing/boundary/dedup/shortcut/permission/theme and adapter delegation | task4-red-delivery.log:7 failures | task4-green-inbox.log combined focused pass; one earlier fixture needed main-looper idle after AlertDialog click |
| Long-upload durable alarm inbox | task4-red-inbox.log:1 failure | task4-green-inbox.log pass; RuntimeServiceTest also exercises actual receiver |
| Shared confirmation and wake override | task4-red-policy-override.log:2 failures | task4-green-policy-override.log pass |
| Interrupted local ending and supervised fan | task4-red-finish.log:1 failure | task4-green-finish.log:2 pass |
| Actual service cancellation / actual bounded receiver / job without FGS | — integration extension of ownership RED | task4-green-service.log:3 pass |
| Denied retry at boundary expires locally | task4-red-denied-expiry.log:1 failure | task4-green-denied-expiry.log pass |
| Accepted manual curve and restore backup migration | task4-red-manual-snapshot.log:1 failure | task4-green-manual-snapshot.log:2 pass |
| Confirmation identity changes while dialog open | task4-red-dialog.log:1 failure | task4-green-dialog.log complete focused UI/repository/runtime suite pass |
| Restore refuses tracked state even if unit stopped | task4-red-restore-policy.log:1 failure | final full gate below |

The first stale-dialog draft asserted before the asynchronous action completed and unexpectedly passed; strengthened it to wait for the explicit stale outcome before asserting unchanged identity, then observed the expected failing RED. No real-time sleeping or injected-clock changes were used to mask the regression.

First full gate task4-full-gate.log found three integration assertion failures: two migrated RepositoryTest fixtures expected a hard-coded UTC savedAt rather than the existing injected Europe/London clock; corrected the fixture expectations only. AppSmokeTest treated appearance of End night as completion, but durable PENDING now appears before confirmed hardware/history; it now awaits typed CONFIRMED, completed history and idle owner before its unchanged device/history assertions. Parent explicitly authorised both mechanical migrations and the focused stale-dialog integration test.

The next four-task gate task4-full-gate-final.log was interrupted by Gradle daemon disappearance during parallel dex/lint/test work, without an assertion failure reported. Retried the exact four tasks with `--max-workers=2 -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8'`, no persistent build configuration change, log task4-full-gate-bounded.log. Final counts appended below. Parent confirmed cgroup memory.max=8 GiB and one OOM kill in memory.events; this was a host resource failure, separate from assertion failures.

## Manifest and compatibility changes

BedJetService declares connectedDevice|shortService. BootReceiver additionally handles TIMEZONE_CHANGED, TIME_SET and SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (existing boot/package actions preserved). ShortcutActivity remains exported for launcher/Routines/Bixby but all commands require its visible confirmation. Existing static shortcuts remain usable. New runtime copy lives in strings_runtime.xml, UK English.

Newly discovered carried integration defect: AndroidBedJetLink inherited BedJetLink.prepareForStart's no-op despite GattLink implementing the reset. Added the minimal forwarding override and optional fake-link constructor seam; a behavioral adapter test proves it reaches the reset. This is not a redesign of Task1 transport.

## Self-review and practical limits

Reviewed ownership/timeout finalisation, original backup ordering, cancelled writes, conditional inbox clear, restart paths, target binding, strict versus fresh Off, confirmation capture, readback/start meanings and zero-based slot indices. `git diff --check` is clean. Removed now-unused retimeWithin/schedulePendingRetry wrappers and obsolete retry constant. Legacy core manual/Boost cases are preserved in runtime tests; core test count falls by two because those scenarios moved, not because behavior coverage was discarded.

Robolectric/fake/Demo tests establish ordering and Android entry-point behavior; they do not establish physical BedJet timing, Samsung/OneUI background restrictions, Doze delivery precision, physical remote reports, or real two-phone transfer. No hardware verified claim. Task5 still owns rich pending/failed/unknown UI, confirmation presentation/localisation cleanup and feature screens; Task6 owns charger/widget/platform functionality. Quiet notifications and runtime outcomes are available now. Best-effort OS retries can be delayed; missed deadlines never justify late hardware replay. A manual accepted command is not claimed as independently confirmed physical state.

## R27 review correction — service-owned UI delivery

The lead identified that the first implementation's graph.scope UI calls had mutex/timeout/BLE leases but no foreground-service ownership. That was a real gap; it was corrected before handoff. `BedJetService.runOwned<T>(context, work: suspend () -> T): T` now owns real-device UI execution. It registers a short-lived in-process token/deferred, starts/promotes the service, and executes work in the service's scope only after promotion. It creates no second command mutex, durable closure queue or replay mechanism. Both delivery wait and execution are bounded to90 seconds. Cancellation removes undelivered tokens; delivered operation cancellation reaches the coordinator and durable UNKNOWN cleanup. Service destruction cancels waiting callers, including cancellation before the service coroutine starts. Demo runs the bounded work directly without an FGS; local-only and job-owned operations remain separate.

The handoff captures selected device before suspension and carries an internal SubmissionTarget coroutine-context element. NightActions checks this submission target, as well as its operation target, only after acquiring its mutex. Thus even a closure that calls pin/restore/setup after delayed delivery cannot adopt a newly selected device. Existing AppViewModel act captures that context before waiting for pending saves; real hardware commands, Remote, restore and Reapply use runOwned, while Stop tracking/forget stay local. App-open retry uses the normal service intent route. Reapply captures strict identity before its queued save. Confirmed dialog identity remains captured independently of target.

Task5 default hardware pattern:

```kotlin
val captured = actions.capture(Command.REAPPLY, requireIdentity = true)
uiScope.launch {
    val result = BedJetService.runOwned(context) { actions.execute(captured) }
}
// Pin/setup/restore can be deferred inside runOwned: submission target is enforced by the owner.
val pinResult = BedJetService.runOwned(context) { actions.pin(profileId, zeroBasedSlot) }
// Local-only tracking:
val stopped = actions.execute(actions.capture(Command.STOP_TRACKING, requireIdentity = true))
```

Call runOwned at submission (before other arbitrary suspensions); existing AppViewModel preserves the earlier tap target over save-wait via its context marker. New UI can capture a request before awaiting; pin/restore/setup should enter runOwned immediately when submitted. The service's closure API is only for this app's finite operations. It never accepts executable code from intents; the service is unexported and the random token refers only to a live in-process operation. If the process dies, reconciliation sees durable state, not a replayable lambda.

`task4-red-ui-service.log` records the missing-ownership assertion failure. `task4-green-ui-service.log` passed the real service ownership/cancellation regression plus UI smoke tests. Added actual service tests for cancellation before delivery and deferred pin refusing a changed target with zero hardware writes; these run in `task4-final-owned-gate.log` alongside the full suite. Resource self-review removed seven new lint warnings by using all numbered string arguments, avoiding plural-like slot wording, abbreviating duration units and removing one unused string; baseline warnings are reported below.

## Final verification and handoff

Committed as `dd0c365` (`feat(runtime): own durable night commands and recovery`) on astra/nocturne-task4, based on a5d01c1. Commit includes the truthful `Implemented-By: GPT-6 Astra` trailer. No push. Worktree clean after commit. Register runtime rows updated with named automated evidence, presentation/hardware portions left pending. Parent owns independent review and integration.

Final command (approved local Gradle sockets):

```sh
JAVA_HOME=/workspace/scratch/ad4eb857ceea/nocturne-jdk17/jdk-17.0.20.1+1 \
GRADLE_USER_HOME=/workspace/scratch/ad4eb857ceea/gradle-cache \
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  --offline --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8'
```

Final output, `task4-verified-gate.log`: **BUILD SUCCESSFUL in 1m42s**, 59 actionable tasks:15 executed,44 up-to-date, exit0. Parsed fresh XML: core138 tests,0 failures,0 errors,0 skipped; app137 tests,0 failures,0 errors,4 skipped =133 passing. Lint0 errors,2 warnings: OldTargetApi and ObsoleteSdkInt (baseline). Debug APK assembled. `git diff --check` clean. Two core tests moved into app runtime coverage (manual limits/Boost); no meaningful assertions discarded.

The final suite also includes deterministic `endAlarmQueuedBehindExtendCannotExpireItsNewRevision`: hold the owner, enqueue Extend then old end, release, verify the new revision survives, then expire exactly at the manual end with no BLE calls. This supplements the earlier stale-finish/manual-boundary RED/GREEN cases. UI busy cleanup now clears in finally when service cancellation propagates. Final added service regressions prove cancellation before delivery removes the token and deferred pin refuses target changes under coordinator ownership.

No known blocking test/build/lint failures remain. Physical BedJet, actual phone background policy/Doze, notification permission UX and two-phone hand-over remain hardware-pending. Full report contains frozen Task5/6 contracts and the service default route; do not use a bare UI graph.scope call as foreground-service ownership.

## Independent review fix round 1 (base dd0c365)

Read the five Important findings verbatim in task-4-fix-1.md and full task-4-review.md; applied the receiving-code-review workflow. No reset, no subagents, no push. All five findings were confirmed against current control flow and fixed in this round. Root will run the integrated full gate; this round uses precise RED/GREEN cases followed by the five requested covering suites.

### Fixes and contracts

1. **Interrupted Boost:** SlotWrite now durably includes `startRequested: Boolean = false` and `displaced: NightIdentity? = null`, before hardware mutation. The writer populates both for Boost. A restart with a matching displaced active identity marks that night UNKNOWN whether the slot journal was PENDING or CONFIRMED; neither readback completion nor start acceptance is mistaken for completion of the local displacement. Both queueRetimeLocked and retryRetimeLocked independently reject an unfinished replacement. The stored programme/previous identity remains reviewable; there is no automatic upload replay. Two restart regressions seed both crash windows with a pending alarm observation and assert zero hardware writes, UNKNOWN and no remaining automatic retime.

2. **Bounded wake/end delivery:** RuntimeState adds `localDeliveries: List<LocalDelivery> = emptyList()`; each received signal has its own ID, action, exact night/revision and receipt timestamp. `submitLocalDelivery(action, expected)` performs bounded durable submission through the existing Repository writer without waiting for the hardware mutex. It coalesces at most the two action kinds, rechecks identity inside the serial write transform, and prearms/rearms a separate `ACTION_LOCAL_RETRY` alarm. `drainLocalDeliveries()` uses tryLock, never waits in the receiver and never acquires BLE. The current whole-operation owner's finally drains under its existing mutex; a one-minute best-effort local retry and startup reconciliation cover process loss/other local owners. Clearing is by consumed delivery ID, preserving newer signals. Wake/end implementation is split into locked helpers so there is no nested mutex.

   This is a received-delivery journal, not startup replay: reconciliation never creates a historical wake signal. An actually received delayed wake is delivered once only while that same confirmed nonmanual revision remains in its live wake/end interval. A delayed wake after the end or after a revision change is discarded locally. A received end still performs only identity-bound local expiry. Owner cleanup uses a bounded 8-second NonCancellable drain; failure retains/rearms the local signal. The receiver itself retains its 8.5-second total budget. Storage failure is still a bounded failure path, not a guarantee of durability when the disk cannot save.

   Actual BroadcastReceiver tests hold ownership, wait for durable submission, advance Robolectric's looper by10 seconds (beyond the broadcast budget), then release. They prove wake/end delivery, one wake notice, no hardware writes or additional connection acquisitions. A further actual-receiver test changes the revision before release and proves the old delivery is discarded. The initial GREEN attempt advanced virtual timeout before the disk submission could complete; corrected the fixture to observe durable submission first, isolating the intended long mutex wait rather than simulating slow storage.

3. **Equal-time override reset:** `setWakeOverride(null)` resolves fallback attribution using the original night start and AlarmPolicy. When the instant is unchanged it returns LOCAL, preserves the applied programme/wake instant, clears tonightOverride and updates wakeOrigin/wakeFromAlarm. A narrow `wakeSourceChange` journal makes the settings/active-file transition restart-completable under the same coordinator; reconcile finishes it only for its captured identity. No BLE is acquired for this case. Changed-time resets retain the prepared transaction route, now carrying alarm creator attribution. The regression resets an override equal to the alarm, then changes that alarm and proves normal retime follows it.

4. **Truthful quiet status:** all coordinator active-night publications now go through a helper that reconciles the quiet notification after persistence. UNKNOWN, FAILED, PENDING, no active night and manual tracking cancel it. Prepared operations, failed manual commands, physical/other-phone takeover, take-back, snooze and Keep tracking all use that helper. Owned settings updates refresh it immediately; Graph observes Repository.settingsPublication together with active-night state so a restored/imported setting outside the command path refreshes it as well; the monotonic publication signal cannot disappear when the Settings value returns to its prior value. NotificationManager-shadow tests cover takeover, failure, manual tracking, take-back, snooze, off/on setting changes and a direct restored-setting path.

5. **Replacement Resume notification:** successful programme replacement with a valid offer now posts the Resume notification. Its action and body open ShortcutActivity's real confirmation dialog via an immutable activity PendingIntent, capturing the current night/revision/target when posted. New helper `ShortcutActivity.confirmationIntent(context, request)` carries that identity into the dialog; external extras still cannot waive confirmation. Only the positive callback sets replacementConfirmed and retains the captured request. Cancellation executes nothing. If the night changes even before the notification dialog opens, acceptance delivers the old strict identity and returns STALE. The notification test covers Start-over-Start, an actual cancel, then changed-revision-before-open acceptance and stale service rejection. Task5 may use its own richer presentation but must preserve this exact confirmation discipline.

Existing public command/service/job APIs are unchanged. New runtime fields are defaulted for old JSON. New receiver APIs are local-only; Task5/6 should not use them as hardware command routes. No manifest change in this fix round. R35's removed core Boost test citation was mechanically replaced with RuntimeOwnershipTest.manualLimitsAndBoostUseOwnedRuntimeAndPreserveOriginal, retaining its planning tests.

### Commands and RED/GREEN evidence

Every run used this worktree and the lead's serialized wrapper, invoked with bash because the supplied script lacks executable permission:

```sh
bash /workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/run-gradle.sh \
  :app:testDebugUnitTest --tests '<specified case or suite>' --offline
```

Approved elevated sockets, JDK17, 2GiB Gradle heap, max-workers2 and shared flock were supplied by that wrapper. No cache deletion or daemon stop. The initial direct invocation returned exit126 before Gradle; bash invocation resolved it without changing file permissions.

- `task4-fix1-red-boost.log`: two Boost restart failures.
- `task4-fix1-boost-green-receiver-red.log`: four tests, two Boost cases passed, two actual receiver cases failed.
- `task4-fix1-green-receiver.log`: initial fixture timing failed both receivers because virtual timeout preceded disk completion; no success claimed.
- `task4-fix1-receiver-green-state-red.log`: six tests, receivers passed; equal override, two quiet tests and replacement notification failed (four expected failures).
- `task4-fix1-green-override.log`: equal override reset/follow-alarm case passed.
- `task4-fix1-green-quiet.log`: three quiet transition tests passed.
- `task4-fix1-green-resume.log`: replacement notification/confirmation case passed.
- `task4-fix1-red-restored-setting.log`: restored-setting observer case failed before the settings observer was added.
- `task4-fix1-focused-green.log`:66 tests,65 passed, one restored-setting test failed. The initial scheduling hypothesis was insufficient: both Thread.yield and coroutine yield still failed. Debugging confirmed active observer jobs and persisted quiet=false. The boolean-only map/distinct observer could suppress the final false after conflation, while synchronous active publication had displayed the notification. The fix observes combined settings and active-night state and refreshes using current persisted values. The same assertNull remains; the test allows coroutine/looper work without real-time sleep or changing Graph.clock.

Final focused output/counts and fix commit are appended after verification. Baseline Gradle deprecations and test-shadow API deprecations remain reported; no warning-free or hardware-verification claim. Root retains the full build/lint/integrated gate.

### Corrected notification observer diagnosis

The combined66-test failure was investigated rather than treating an extra wait as a fix. `task4-fix1-green-restored-setting.log` and `task4-fix1-green-restored-setting-yield.log` both still failed. Temporary test diagnostics (`task4-fix1-debug-observer.log` / XML) showed active scope children and quiet=false; removed those prints afterwards. Boolean-only `map { quietNightNotification }.distinctUntilChanged()` omitted a necessary refresh when transitions were conflated and the active-night path had posted a notification synchronously. Graph now combines persisted Settings and ActiveNight events and refreshes from their current values without the lossy boolean filter. `task4-fix1-green-observer.log`: one test passed, BUILD SUCCESSFUL in19s. The eventual assertNull is unchanged; coroutine yield only permits asynchronous collector work, and the preceding failed yield run proves synchronisation alone did not hide the defect. The final corrected combined run is task4-fix1-focused-verified.log.

### Stable-night settings conflation follow-up (lead review)

The lead correctly identified that observing whole Settings plus ActiveNight still permits StateFlow to conflate false→true→false when the final Settings equals the last delivered value and the active night is unchanged. Added `quietNotificationRefreshSurvivesSameSettingsRoundTripWithStableNight`: let the observer consume quiet=false for an existing confirmed night, synchronously enable quiet and assert a visible notification, restore the exact prior Settings without pumping the observer between writes, then verify the same active night and assert the notification is absent. `task4-fix1-red-stable-settings.log` failed that assertion with the intermediate combine-only implementation.

The final causal fix is a narrow `Repository.settingsPublication: StateFlow<Long>` (internal, process-local, not persisted), incremented by the existing serial writer only after successful Settings publication, including restore. It creates no new writer/store or command lock. Graph combines that monotonic publication revision with active-night state and always reads current settings/night when refreshing. A same-value settings restore now necessarily changes the revision even if Settings StateFlow suppresses the value emission. The notification assertion and stable-night assertion remain unchanged; observer pumping is only to deliver asynchronous work. This supersedes the intermediate boolean-filter/whole-settings solution. The original66-test corrected run passed before this additional case; the final67-test covering run includes the stronger scenario.

### Fix round 1 final verification

Fix commit: `5cdb4a0` (`fix(runtime): close reviewed recovery and delivery gaps`), parent dd0c365, truthful Implemented-By: GPT-6 Astra. No push; worktree clean. All five Important review findings addressed; R35 citation corrected. Existing public UI/job/service contracts preserved, with additive local-delivery and confirmation-intent helpers documented above.

`task4-fix1-green-settings-publication.log`: both settings-notification regressions passed, BUILD SUCCESSFUL in22s. Final combined command:

```sh
bash /workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/run-gradle.sh \
  :app:testDebugUnitTest \
  --tests '*RuntimeOwnershipTest' --tests '*RuntimeServiceTest' \
  --tests '*RuntimeDeliveryTest' --tests '*RuntimeRegressionTest' \
  --tests '*NightActionsTest' --offline
```

`task4-fix1-final-focused.log`: **BUILD SUCCESSFUL in1m40s**, exit0,30 actionable tasks:1 executed,29 up-to-date. Fresh XML totals: **67 tests,67 passed,0 failures,0 errors,0 skipped**. Breakdown: RuntimeOwnershipTest30, RuntimeServiceTest9, RuntimeDeliveryTest14, RuntimeRegressionTest5, NightActionsTest9. `git diff --check` clean before commit. Main and test compilation passed through these runs. Full core/app/build/lint integration gate is intentionally left to root after review, as instructed; no renewed full-gate or hardware claim. Existing Gradle9 deprecation notice and shadow-API compiler deprecations remain disclosed. No known remaining failure in the reviewed scope.

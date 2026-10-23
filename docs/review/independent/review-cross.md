# Cross-task end-to-end review: Nocturne hardening @ 7fffabc

Reviewer: independent read-only pass (Claude). Worktree `/home/user/review-nocturne`, i.e. `origin/claude/nocturne-hardening` @ `7fffabc`.
Scope: I traced each of the 10 scenarios from its Android entry point through the production code down to GATT and storage, then checked contract drift for Tasks 5–7.
**No hardware was used. No Gradle was run.** Every verdict comes from reading the code. File paths are relative to `app/src/main/kotlin/io/github/willzy12h/nocturne/` unless they start with `core/`.

## Summary

| # | Scenario | Verdict | Findings |
|---|---|---|---|
| 1 | Off (app or remote), then the phone alarm changes | **Defect** (physical remote); OK (app Off) | X-1, X-5 |
| 2 | BT disconnect during upload or verify | OK at code level; partial-fragment semantics on the unit are unverifiable without hardware | — |
| 3 | Process death after overwrite, before start confirmation | OK with caveats | X-4, X-8 |
| 4 | Target change or Forget with work queued | **Defect** | X-2 |
| 5 | Physical-remote takeover during a pending re-time | **Defect** | X-1 |
| 6 | Expired request acquires the mutex after waiting | **Partial** | X-6, X-3 |
| 7 | Snooze or Extend racing expiry or the end broadcast | OK | — |
| 8 | Stale widget or notification command | OK | — |
| 9 | Backup write fails before an overwrite | OK (overwrite blocked); state afterwards is untruthful | X-3, X-4 |
| 10 | Reboot or TIME_SET with an uncertain programme | OK (the retry path inherits X-1) | — |

Findings: one High bordering Critical (X-1), one High (X-2), four Medium (X-3 to X-6) and two Low (X-7, X-8). All are new except X-2: the race behind it is a Task 1 minor deferred in sdd-ledger.md ("disconnect() not serialised with in-progress connect"). Its wrong-unit and mixed-original consequence was never assessed.

---

## Scenario traces

### 1. Off, then the phone alarm changes

**Turned off in the app** (Remote screen, widget OFF, notification End, or quiet-notification End):
1. The UI goes `AppViewModel.remote` (ui/AppViewModel.kt:228-232), then `BedJetService.runOwned`, then `NightActions.remote` (night/NightActions.kt:667-672). The service and widget path goes `BedJetService.onStartCommand`, then `execute`, then `run`, then `Command.OFF`. Both reach `manualChange(endsNight=true)` (NightActions.kt:192-207).
2. `finishLocked` (NightActions.kt:239-245) clears `retime`. `completeLocalEnd` publishes `null`, and `NightAlarms.cancel` removes WAKE_UP, NIGHT_END and RETRY (night/NightAlarms.kt:34).
3. A later `ACTION_NEXT_ALARM_CLOCK_CHANGED` reaches `AlarmChangedReceiver`, which returns early because `activeNight.value == null` (night/Receivers.kt:37).
4. If an earlier `RETRY_RETIME` was already queued, `consumeAlarmObservation` drops it on a night-id mismatch and `retryRetimeLocked` refuses it.
5. Result: no restart. This is covered by `RuntimeOwnershipTest.remoteOffEndsNightAndLaterAlarmCannotRestartIt`.

**Turned off with the physical remote:** this is a Defect (X-1). Takeover is detected only in `observeStatus` (NightActions.kt:480-505), which runs only:
- while the link is READY;
- while the command mutex is free (`if (lock.isLocked) return`, line 482).

With the screen off, LinkSessions drops the connection 5 s after the last holder (night/LinkSessions.kt:84-87), so a remote Off is normally not seen. The next alarm change then:
1. queues a re-time against the still-CONFIRMED night (`queueRetimeLocked`, lines 318-335);
2. connects inside the owned transaction, where the fresh status is suppressed by line 482;
3. rewrites the slot and calls `startProgram` without ever comparing the fresh status with the applied programme (`retryRetimeLocked` lines 348-354, then `NightRunner.apply` in core/.../night/NightRunner.kt:156-190).

The BedJet restarts after the user switched it off.

Separately, X-5: an alarm change that does not move the wake still produces a "failed" notification.

### 2. Bluetooth disconnect during upload or verify readback

The path is `NightRunner.apply` (NightRunner.kt:165-189), then `BedJetController.store` (core/.../link/BedJetController.kt:45-54), then `GattLink.send`/`query`, then `exchange` (ble/GattLink.kt:274-289).

- **Disconnect mid-fragment or during readback.** `Session.shut` fails the pending operation with `NotConnected` (GattLink.kt:392-398). A timeout or cancellation retires the session (lines 279-285). `store` throws, and because `writeMayHaveHappened` is already true, the night is published UNKNOWN under NonCancellable (NightRunner.kt:184-188).
- **No silent continuation inside a transaction.** `ensureConnected` does reconnect on the next `send` (GattLink.kt:165-169), but the failed fragment has already aborted `store`. The readback compare (BedJetController.kt:53) is the guard against a half-written slot.
- **No false CONFIRMED.**
  - `startProgram` subscribes after `prepareForStart` (BedJetController.kt:66-74; GattLink.kt:114-117; AndroidBedJetLink.kt:48).
  - A drop after the start write leaves the waiter with `null` status, so it times out to `Timeout` and the night is UNKNOWN.
- **No replay.**
  - `retryRetimeLocked` clears `retime` and cancels the retry when the result is not a success (NightActions.kt:352).
  - BedJetService is START_NOT_STICKY.
- **GATT cleaned up.** `retire` calls `shut`, which calls `port.disconnect()` and `close()` (GattLink.kt:220-229, 400-403). LinkSessions releases its hold in `finally`.
- **Residual risk (unverifiable without hardware):** BedJet status has no programme identity. A start acknowledged at GATT level but ignored by the unit, while an older programme reports `programStep==1`, could still confirm. This is a protocol limit, not a code defect.

### 3. Process death after the slot overwrite, before start confirmation

1. **Before the write.** `publish(pending)` is durable through `Repository.update`, which writes an AtomicFile before publishing (data/Repository.kt:310-315, 420-430). The pending state is saved before `beforeWrite`/`store` (NightRunner.kt:166-179).
2. **Original backed up first.** `recordOriginalSlot` keeps the first original and writes it durably (Repository.kt:181-188).
3. **On restart**, `App.onCreate` calls `startRuntime`, which calls `initialise`, which calls `reconcile` (NightActions.kt:443-471):
   - PENDING night becomes UNKNOWN with reason "interrupted", and `retime` is cleared (lines 455-458);
   - a PENDING `slotWrite` becomes UNKNOWN (459-461);
   - a Boost-displaced night becomes UNKNOWN (447-452).
4. **No replay anywhere:**
   - BedJetService is START_NOT_STICKY;
   - the charger attempt is claimed before the start (platform/ChargerRun.kt:54);
   - KEEP_TRACKING reads fresh status only.

Caveats:
- X-4: the UNKNOWN `slotWrite` is never cleared, so it contaminates later state.
- X-8: an interrupted *new* start leaves an active night with no history record.

### 4. Different BedJet selected (`targetDevice` change or Forget) while work is queued

What works:
- **Captured at queue time:**
  - `capture()` (NightActions.kt:33-34);
  - service intent extras (service/BedJetService.kt:113-123);
  - `SubmissionTarget` in `AppViewModel.act` and `runOwned` (AppViewModel.kt:239-248; BedJetService.kt:80-82, 43-44);
  - `PendingRetime.deviceId` (NightActions.kt:330, 345).
- **Checked under the mutex:** `owned` (NightActions.kt:78-79) and `run` (104).
- **Target changes are owned:** `selectTarget` and `updateSettings` refuse while a night is active and call `graph.link.disconnect()` (NightActions.kt:603-622).
- **Backups use the captured id:** `backup(change.intended.deviceId, …)` (144).

**Defect X-2, at the transport layer.** The coordinator check compares settings strings, not the physical session. `GattLink.connect` reads the address once and loops over retries (GattLink.kt:69-88). A `disconnect()` from `selectTarget` only flips `wanted` and retires the current session. A new command for the new target then calls `connect()`, which sets `wanted = true` before waiting on `connecting` (line 70). The old loop resumes after its backoff with `wanted == true` and attaches the **old** address. `attach`'s success test (line 213) never compares the session address with `target()`. Commands for unit B then run on unit A, and `backup(B, slot)` records A's programmes as B's originals.

### 5. Physical-remote takeover during a pending re-time

- **Takeover seen while connected and idle** (lines 487-503): the night becomes UNKNOWN, `retime` is cleared, the retry is cancelled and REMOTE_CHANGE is recorded. Correct.
- **Takeover while disconnected, or while the mutex is held:** the next retry runs `retryRetimeLocked`, then `connected { apply(...) }` (348-354). It checks identity, deadline, manual tracking and CONFIRMED status, but never fresh device status. Defect X-1: the re-time overrides the user.

### 6. Expired request acquires the mutex after waiting

- **Re-time, reapply and resume carry deadlines:**
  - `PendingRetime.deadline` = `wakeUpStart`;
  - `PendingChange.deadline` (NightRunner.kt:51, 79, 148);
  - rechecked after backup, after store and before the start byte (NightRunner.kt:177-181, 192-200).

  An expired re-time is refused or expired without a write. OK.
- **Other commands (Turbo, Boost, Start, Snooze, pin, restore and the rest)** carry no age or deadline. The only bound is `withTimeout(OPERATION_MS)` in `owned` (NightActions.kt:73), and that timeout includes the queue wait (as documented in task-4-implementation.md:40). There is no admission check after `lock.lock(owner)` (74-79). A request that acquires the lock with seconds left starts a multi-packet upload anyway, and the timeout then cancels it mid-write, which turns into UNKNOWN. That is Partial, recorded as X-6.
- **Pre-write expiry of a re-time** marks the still-running night FAILED. That is X-3.

### 7. Snooze or Extend racing night expiry or the end broadcast

1. The Snooze action comes from `Notifications.showWakeUp`, which uses `BedJetService.pendingIntent(…, requireIdentity=true)` (BedJetService.kt:139-144). `execute` rejects a stale identity (NightActions.kt:40).
2. The end alarm goes through `NightAlarmReceiver`, which calls `submitLocalDelivery`. That records the delivery durably, never waits for the mutex, and uses `tryLock` (407-428).
3. **Snooze holding the lock.** `timedManual` publishes revision+1 (218-221). The owner's `finally` then drains the end delivery: `expireLocked(oldIdentity)` sees an identity mismatch, does nothing, and drops the delivery (429-442). The night continues with its new end alarm.
4. **Expiry first.** A Snooze carrying the old identity is STALE.
5. **Snooze fails mid-command.** The night becomes UNKNOWN with the same identity, and the drained end delivery then expires it at its real end. That is acceptable.

Verdict OK. This is covered by `endAlarmQueuedBehindExtendCannotExpireItsNewRevision`.

### 8. Stale widget or notification command after the night changed

Every route carries the night id and revision, and `execute`/`remote` compare them under the mutex (NightActions.kt:40, 54, 670):
- **Widget:** `widgetRequest` captures `requireIdentity=true` (widget/WidgetPresentation.kt:8-9). `PlatformActionActivity.intent` puts id and revision in the extras and the data URI (platform/PlatformActionActivity.kt:80-88), and `readRequest` restores `strict=true`.
- **Notifications:** identity-bearing pending intents with distinct request codes (BedJetService.kt:139-144).
- **ShortcutActivity:** captured or current identity, always confirmed (ui/ShortcutActivity.kt:30-43).
- **NightGuard:** dialog identity through `withConfirmedNight` (ui/NightGuard.kt:36; AppViewModel.kt:212-224).

A stale request returns STALE with "This action belongs to an older night". The only cost is that a stale widget Off needs a second tap after a re-render. Verdict OK.

### 9. Backup write fails before a slot overwrite

- **Programme path.** `beforeWrite = backup(...)` (NightActions.kt:144, 180-185) runs `originalsFor`, then `storedPrograms`, then `recordOriginalSlot`, which throws when the write fails (Repository.kt:178-188). The throw happens before `writeMayHaveHappened = true` (NightRunner.kt:169-178), so FAILED is published and `store` is never called.
- **Slot path.** `writeSlotLocked` calls `backup` before `writing = true` (NightActions.kt:537-539), so the confidence is FAILED and nothing is written.
- **Verdict.** The overwrite is correctly blocked. However, X-3: a re-time or Boost that fails here relabels the still-running previous night as FAILED. And X-4: a FAILED `slotWrite` sticks forever.

### 10. Reboot with an uncertain programme (BOOT_COMPLETED, LOCKED_BOOT, TIME_SET)

- **Receivers.** `BootReceiver` handles BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED, TIME_SET and the exact-alarm permission change (night/Receivers.kt:28-34; AndroidManifest.xml:89-97). `PlatformClockReceiver` re-syncs jobs and reminders only. LOCKED_BOOT_COMPLETED is not registered and the app is not direct-boot aware, so nothing runs before the first unlock. That is acceptable: the AlarmManager state is lost at reboot anyway.
- **`reconcile`** (443-471) does the following:
  - PENDING becomes UNKNOWN, with no replay;
  - `expire()` ends nights whose `endsAt` has passed;
  - `NightAlarms.schedule` skips past times, so a past wake is never re-armed (night/NightAlarms.kt:20-24, 41);
  - no historical wake delivery is invented.
- **Uncertain nights block everything automatic:**
  - re-time requires CONFIRMED (320, 345);
  - the charger job refuses an active or uncertain night (ChargerRun.kt:41-43);
  - the wake notification requires CONFIRMED (475).
- **Concurrent receivers.** A `RETRY_RETIME` racing the window between `initialise`'s two locks sees a non-CONFIRMED night and refuses.

Verdict OK, but a post-boot retry of a CONFIRMED night's pending re-time inherits X-1.

---

## Findings

### X-1 — High (borders Critical): an automatic re-time restarts a night the user stopped or changed with the physical remote
- **Root cause.** Takeover detection depends on an observed status while the link is idle. The status collector is suppressed while the mutex is held (`if (lock.isLocked) return`, NightActions.kt:482). The re-time transaction never reads or compares the fresh status it obtains on connect.
- **User-facing failure.**
  1. At 23:00 the alarm changes while the BedJet is unreachable, so a PendingRetime retries every ~10 min.
  2. At 01:00 the user switches the BedJet off, or to Cool, with the physical remote because they are too hot. The phone has been disconnected since the 5 s grace.
  3. At 01:10 the retry connects, overwrites the slot and calls `startProgram`.

  The BedJet heats again and the app reports CONFIRMED "Wake-up moved". The same happens if the user sets a later alarm after a remote Off. This contradicts plan R14 ("A later alarm change after a takeover never restarts the programme") and acceptance N08 ("suppress blind automatic rewrite").
- **Evidence.**
  ```kotlin
  // NightActions.kt:480-482
  suspend fun observeStatus(status: DeviceStatus, target: String?, ...) {
      if (lock.isLocked) return
  // NightActions.kt:345-351 — the only pre-write checks
  if (n?.identity != pending.expected || hasUnfinishedReplacement(n) || n.trackingManual || n.confidence != Confidence.CONFIRMED || pending.deviceId != ...)
  ...
  connected { val result = apply(graph.runner().prepareRetime(...), R.string.runtime_retimed) ... }
  ```
  `NightRunner.apply` (156-183) goes straight from backup to `store` to `startProgram`. The R14 test covers only the in-app `remote("Off")` (RuntimeOwnershipTest.kt:187-192).
- **Minimal fix.**
  1. Extract the contradiction test from `observeStatus` and `takeBack` (lines 493-497 and 656-658) into one helper.
  2. In `retryRetimeLocked`, inside `connected {}` and before `apply`, call `freshStatus()`. If it contradicts the applied step, publish UNKNOWN, record REMOTE_CHANGE, clear `retime`, cancel the retry and return REFUSED.

  Apply the same gate to the automatic route at minimum. Consider it for REAPPLY and `setWakeOverride`, which today also overwrite silently.
- **Regression test.** In RuntimeOwnershipTest, with Demo or a fake link:
  1. start a night;
  2. disconnect;
  3. call `demo.send(Codec.setMode(Mode.STANDBY))` directly on the unit, bypassing NightActions;
  4. change the next alarm so a re-time is prepared;
  5. run `RETRY_RETIME`.

  Assert that no write or start bytes are sent, the night is UNKNOWN, a REMOTE_CHANGE event exists and `runtime.retime == null`.
- **Affected tasks:** 4 (owner), 3 (`NightRunner.apply` has no precondition hook), 6 (charger already gates correctly), and the acceptance checklist N03/N08.
- **Status:** new.

### X-2 — High: a connect retry started for the old target can complete after Forget or a target change, so commands, writes and originals hit the wrong BedJet
- **Root cause.** `GattLink.connect` captures `address` once before the retry loop. `disconnect()` only sets `wanted=false` and retires the current session. Any later `connect()` sets `wanted=true` before taking the `connecting` mutex. The old loop then continues `attach(oldAddress)`, and the success test never re-checks `target()`.
- **User-facing failure.**
  1. In Setup, the screen hold is retrying an unreachable or old BedJet A (12 s connect timeout, 500 ms and 1.5 s backoffs).
  2. The user selects BedJet B. `selectTarget` disconnects and saves B.
  3. The immediately following owned operation calls `connect()`: the setup fan check, Start or Pin.
  4. The old loop wakes, sees `wanted==true` and attaches A. The new caller returns on `current?.ready == true` and runs against A.

  The fan check, or worse a slot write, lands on A. `backup(B, slot)` reads A's slots and stores them durably as **B's** first originals (Repository.kt:181-188 keeps the first), which corrupts B's restore source permanently. The coordinator's target check passes because it compares settings, not the session.
- **Evidence.**
  ```kotlin
  // ble/GattLink.kt:69-84
  wanted = true
  connecting.withLock {
      if (current?.ready == true) return
      ...
      val address = DeviceId.address(target()) ?: ...
      for (attempt in 0..timings.retryBackoffMs.size) {
          if (attempt > 0) delay(timings.retryBackoffMs[attempt - 1])
          if (!wanted) break
          if (attach(address, readVerifiedName = attempt == 0)) return
  // GattLink.kt:213
  attached = wanted && current === session && session.ready
  // GattLink.kt:90-93
  override suspend fun disconnect() { wanted = false; operations.withLock { current?.let(::retire) } }
  ```
- **Minimal fix.**
  1. Add a monotonically increasing `epoch`, incremented in `disconnect()`.
  2. Capture it at loop start and break when it changes. Also re-read `target()` per attempt and break on a mismatch.
  3. Require `DeviceId.address(target()) == session.address` in the `attach` success test and in the `current?.ready` fast path.
- **Regression test.** In GattLinkTest with FakeRadio:
  1. target A's first attach fails, which puts the loop into backoff;
  2. during the backoff call `disconnect()`, switch the target to B and start `connect()` concurrently.

  Assert that `radio.connect` is never invoked with A after the disconnect, and that the operations go to B.

  Add an app-level test: `selectTarget(B)` during an in-flight screen-hold connect to A, followed by `pin`. The backup's deviceId B must contain B's slots.
- **Affected tasks:** 1 (transport), 4 (target binding R20), 2 (original integrity), 5 (setup flow will exercise this).
- **Status:** previously reported only as a deferred Task 1 minor ("disconnect() not serialised with in-progress connect", sdd-ledger.md, Task 1 minors). The wrong-target and mixed-original consequence is new, and the severity is raised.

### X-3 — Medium: a failure before any write marks the still-running previous night FAILED, which suppresses the wake notification and blocks recovery
- **Root cause.** `PreparedChange.pendingNight` is `previous.copy(PENDING)` (core/.../night/NightChanges.kt:18-21). On any exception before the write — a backup query or `recordOriginalSlot` failure, the deadline or the wake boundary crossing during backup — `apply` publishes `pending.copy(FAILED)` as the **active** night (NightRunner.kt:184-186). `writeSlotLocked` does the same for Boost (NightActions.kt:551). The BedJet still runs the previous confirmed programme, but:
  - `wakeUpLocked` requires CONFIRMED (line 475), so no wake-up notification;
  - `observeStatus` ignores non-CONFIRMED nights (491), so no takeover detection;
  - Eligibility refuses FAILED (core NightChanges.kt:49-51);
  - KEEP_TRACKING requires UNKNOWN (383);
  - the quiet notification is cancelled.

  The only exit is Stop tracking.
- **User-facing failure.** A 03:00 alarm-driven re-time hits a transient GATT `BadResponse` while reading originals, or reaches `wakeUpStart` during the backup reads. The night shows "failed" and the morning wake notification never appears, although the BedJet runs exactly as confirmed.
- **Evidence.** `val failed = pending.copy(confidence = if (writeMayHaveHappened) Confidence.UNKNOWN else Confidence.FAILED); withContext(NonCancellable) { publish(failed) }` (NightRunner.kt:185-186).
- **Minimal fix.** When `previous != null && !writeMayHaveHappened`, publish `previous` unchanged (CONFIRMED, `pending=null`) and return `Failed` with the cause. Keep FAILED only for a new start with no previous night. In `writeSlotLocked`, restore the displaced night to its prior confidence when `!writing`.
- **Regression test.** Start a night, inject a backup or `recordOriginalSlot` failure on a re-time, then assert:
  - the active night is CONFIRMED with the same identity;
  - the outcome is FAILED;
  - a wake delivery at `wakeUpStart` still shows the wake notification.

  Repeat with the clock advanced past the deadline inside `beforeWrite`.
- **Affected tasks:** 3 (core semantics), 4 (wake and takeover gating), 5 (status presentation).
- **Status:** new. The FAILED rule is documented in task-3-implementation.md:33,61, but its effect on an existing night was not assessed.

### X-4 — Medium: `RuntimeState.slotWrite` is never cleared, so a stale FAILED or UNKNOWN poisons later nights, the charger and outcomes
- **Root cause.** `slotWrite` is set only in `writeSlotLocked` (NightActions.kt:535-551) and turned UNKNOWN in `reconcile` (459-461). No code path sets it to `null`, and Keep tracking or Stop tracking do not resolve it. Four consumers treat it as current truth:
  - **(a) `NightActions.status`** (line 29) reports `slotWrite.confidence` instead of the active night's whenever it is PENDING, FAILED or UNKNOWN. A pin, restore or Boost that failed weeks ago makes every later confirmed night display FAILED or UNKNOWN. Task 5 is about to bind this.
  - **(b) ChargerRun `uncertain`** (platform/ChargerRun.kt:43) permanently suppresses charger auto-start.
  - **(c) `owned`'s exception path** (86) reports any unrelated later failure, for example a Turbo connect timeout, as UNKNOWN.
  - **(d) `reconcile`** (447-452) re-applies `displaced` without checking `slotWrite.confidence`. After a Boost that FAILED before writing, every screen-open flips the still-running night to UNKNOWN with "interrupted", and `hasUnfinishedReplacement` (316-317) blocks re-times for it.
- **Evidence.** `grep slotWrite` shows no `slotWrite = null` anywhere. `NightStatus(... else if (r.slotWrite?.confidence in setOf(PENDING, FAILED, UNKNOWN)) r.slotWrite?.confidence else n?.confidence ...)` (line 29).
- **Minimal fix.**
  1. Clear `slotWrite` when it is FAILED (nothing written), keeping only a history or notice entry.
  2. Scope UNKNOWN to the device and slot, and clear it on a later confirmed write to that slot, on a successful `restoreOriginal`, or on an explicit "Mark as checked" or Keep-tracking action.
  3. In `status`, use `slotWrite` only when `slotWrite.displaced == active?.identity` or when there is no active night.
  4. In `reconcile`, require `slotWrite.confidence != CONFIRMED && != FAILED` before flipping `displaced`.
- **Regression tests.**
  - A failed pin to slot 1, then a confirmed START: `status.confidence == CONFIRMED`, and charger eligibility is not blocked.
  - A FAILED Boost followed by `reconcile()`: the night stays CONFIRMED.
  - A later Turbo connect failure reports FAILED, not UNKNOWN.
- **Affected tasks:** 4, 5 (`status` consumer), 6 (charger gate).
- **Status:** new.

### X-5 — Medium: false "wake-up change expired" and "command did not finish" error notifications from routine alarm events
- **Root cause.**
  1. `AlarmChangedReceiver` fires on every NEXT_ALARM_CLOCK_CHANGED during a night. That includes the wake alarm itself firing, being snoozed or being dismissed, and alarms set by apps that do not count.
  2. `submitAlarmChange` treats any observation at or after `wakeUpStart` as an expired *change* and posts a PROBLEM notification (NightActions.kt:292-300). The night is still active then, because the after-wake phases run past the wake.
  3. The receiver also starts `RETRY_RETIME` (Receivers.kt:41). `retryRetimeLocked` returns REFUSED ("The wake-up change expired." or "Wake-up unchanged."), and `BedJetService` posts `showError("The command did not finish.", …)` for every result that is not a success, including background-originated REFUSED ones (BedJetService.kt:51).
- **User-facing failure.** On every alarm-anchored morning, dismissing or snoozing the phone alarm produces "The wake-up change expired. … The BedJet was not restarted." followed by "The command did not finish". Both are logged as PROBLEM notices although no change was requested. A calendar or timer alarm during the night that does not count gives "The command did not finish: Wake-up unchanged."
- **Evidence.**
  ```kotlin
  // NightActions.kt:292-299
  expired = repo.activeNight.value?.identity == night.identity && !graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))
  ...
  if (expired) { NightAlarms.cancelRetry(context); Notifications.showError(context, text(R.string.runtime_retime_expired), ...)
  // BedJetService.kt:49-51
  val result = withTimeout(...) { graph.actions.execute(request!!) }
  if (!result.succeeded) Notifications.showError(this@BedJetService, getString(R.string.runtime_failed), result.message)
  ```
- **Minimal fix.**
  - After the boundary, drop the observation silently. Show "expired" only when a real `PendingRetime` existed, or when the newly observed counted alarm differs from the night's `wakeAt`.
  - In `BedJetService`, do not notify REFUSED/STALE outcomes of `RETRY_RETIME`, or more generally of requests with no user origin. Keep FAILED and UNKNOWN visible.
- **Regression test.** With a night past `wakeUpStart`, deliver NEXT_ALARM_CLOCK_CHANGED with an unchanged or advanced alarm. Assert that no ERROR notification is posted and no PROBLEM notice is logged. With a counted alarm that does not move the wake before the boundary, assert no error notification from the RETRY_RETIME service run.
- **Affected tasks:** 4, 5 (notice history).
- **Status:** new.

### X-6 — Medium: no admission check when a queued request finally acquires the mutex
- **Root cause.** The 90 s `withTimeout` in `owned` spans queue waiting plus work (NightActions.kt:73), and nothing checks the remaining budget or the request's age once `lock.lock(owner)` returns. In the UI route the outer `runOwned` timeout starts before FGS promotion (BedJetService.kt:91). It fires first and cancels the service operation in its `finally` (99), mid-transaction.
- **User-facing failure.** A Boost or Start queued behind a slow upload acquires the lock with a few seconds left. It publishes PENDING and backs up, then the timeout cancels `store` partway, leaving a self-inflicted UNKNOWN on a unit that was fine. Separately, an explicit Turbo or Start tapped ~80 s earlier still runs, which surprises the user (only night identity, not age, is checked).
- **Evidence.**
  ```kotlin
  return withTimeout(OPERATION_MS) {
      lock.lock(owner); acquired = true
      ... val result = if (target != ...) stale(...) else work()
  ```
  The contract in task-4-implementation.md:40 confirms "The 90-second deadline includes queue waiting".
- **Minimal fix.**
  1. Add `submittedAt` to `CommandRequest` (set at capture), or measure in `owned`.
  2. After acquiring the lock, refuse with STALE/"timed out waiting" when the time waited exceeds a bound, or when the remaining budget is below a minimum mutation budget (e.g. 45 s for programme or slot writes).
  3. Have `runOwned` pass its own deadline into the handoff so the service and coordinator use the same deadline.
- **Regression test.** Hold the owner for `OPERATION_MS - 5 s`, then release. Assert the queued START returns STALE without any PENDING publish or BLE write. A fresh request still runs.
- **Affected tasks:** 4; 5 and 6 as callers of `runOwned` and `performJobOwned`.
- **Status:** new.

### X-7 — Low: hand-over release, including an automatic Lull peer request, erases the UNKNOWN reason and the resume offer
- **Root cause.** `releaseForOtherPhone` calls `advanceGeneration()` (NightActions.kt:645), which sets `resumeOffer = null, reason = null` (100-102). `PhoneHandover.watch` triggers it from any advertiser of the UUID derived from the public BedJet name (platform/PhoneHandover.kt:41-47).
- **User-facing failure.** After a takeover the night is UNKNOWN with reason "Someone else changed the BedJet". A nearby Lull request then wipes the explanation, and wipes the 15-minute "Resume the rest of the night" offer.
- **Minimal fix.** In release, bump `generation` only. Keep `reason`, and drop `resumeOffer` only if invalidating resume is the intended semantic, recording that ruling.
- **Regression test.** UNKNOWN night with a reason and an offer, then `peerRequest()`: the reason is preserved.
- **Affected tasks:** 4 (API side-effect) and 6 (caller).
- **Status:** new.

### X-8 — Low: an interrupted or failed new START has no history record, so its events are dropped
- **Root cause.** `addRecord` runs only on `Applied` (NightActions.kt:153-156). `History.addEvent` silently skips unknown ids (core/.../store/History.kt:48-49). After a crash or failure on a first START, the UNKNOWN or FAILED active night has no record. `recordError`, REMOTE_CHANGE, RECOVERED and STOPPED events are lost, contrary to U09 (history records failures and recovery).
- **Minimal fix.** Create the `NightRecord` with the pending state from the `publish(pending)` callback when `previous == null`.
- **Regression test.** Process death after `store`: reconcile, then Stop tracking. The history contains the record with ERROR or STOPPED events.
- **Affected tasks:** 4, 5 (History UI).
- **Status:** new.

---

## Contract drift Astra must know (Tasks 5–7)

1. **`NightActions.status` is not the night's confidence.** It is overridden by a stale `slotWrite` (X-4) and by `alarmInbox` (PENDING). Until X-4 is fixed, do not present it as "tonight failed", and pair it with `activeNight.confidence`.
2. **Real hardware work from UI must go through `BedJetService.runOwned`** together with `SubmissionTarget` captured at the tap, as `AppViewModel.act` does (AppViewModel.kt:239-248).
   - Direct `graph.scope` calls to `restoreOriginal`, `pin`, `setupFanCheck`, `setWakeOverride` or `retryPendingRetime` bypass FGS ownership.
   - `retryPendingRetime()` and `perform()` are public but capture at call time; UI should not use them.
   - `remote()`'s default `target` and `expected` are evaluated at call time, so pass the captured values.
3. **Dialogs must use `withConfirmedNight(identity)`** or a captured `CommandRequest` with `requireIdentity=true` and `replacementConfirmed=true`. CONFIRMATION_REQUIRED is a normal outcome that UI must handle.
4. **`runOwned(notifyFailures=false)` callers** must show both exception messages and `TimeoutCancellationException` at the origin (PlatformActionActivity.kt:69 and FeatureSections.kt:108 do). Per X-6, the outer timeout also cancels hardware work mid-write.
5. **Target selection in Setup (Task 5)** must not immediately chain an owned BLE operation after `selectTarget` while a screen-hold connect to the previous unit may still be retrying, until X-2 is fixed. Alternatively, call `graph.sessions.holdForScreen(false)` and await before selecting.
6. **Task 6 bypass check.**
   - The charger job uses `performJobOwned` with fresh-status eligibility (correct).
   - The tile is navigation only (correct).
   - Reminders open PlatformActionActivity, which uses `runOwned` (correct).
   - Hand-over release and take-back go through the owner (correct), but X-7.
   - The charger's `uncertain` gate is permanently tripped by X-4.
   - `PlatformRuntime.observe` writes the automation journal outside the mutex. That is acceptable: it is display-only and goes through the serial writer.

   No route adds a second command lock.
7. **`BedJetService` posts an error notification for every REFUSED or STALE result of service-started commands** (X-5). Origin-owned UI should therefore prefer `runOwned` over `BedJetService.start` to avoid duplicate or noisy errors.
8. **Task 7 / acceptance.**
   - N08 and Q01 should explicitly include "remote Off while disconnected, then alarm change or pending retry", which will currently FAIL (X-1).
   - Add "select a new target while the old one is out of range and retrying" (X-2).

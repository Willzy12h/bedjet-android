# Nocturne runtime review (Task 4 + integrated head)

Reviewer: independent Android runtime review. Read-only. No Gradle, no git state changes, no edits in the worktree.
Worktree: `/home/user/review-nocturne` at `7fffabc` (origin/claude/nocturne-hardening). Task 4 commits `7f5203b`, `19ebb6b`, merge `69b9973`.
Method: static reading of production and test code, and of the core contracts that the runtime depends on (`core/.../night/NightRunner.kt`, `NightChanges.kt`, `link/BedJetController.kt`, `plan/Profile.kt`, `plan/Presets.kt`).
**No hardware, phone or Samsung verification was done. Nothing below claims device behaviour was observed.** Where a finding depends on Android or BedJet platform behaviour that I could not run, it is labelled *(platform-dependent)*.

Paths below are relative to `app/src/main/kotlin/io/github/willzy12h/nocturne/` unless they start with `core/` or `app/src/test`.

---

## Summary

| ID | Sev | Title | Status |
|---|---|---|---|
| RT-1 | **High** | Automatic re-time (also reapply/override/resume) rewrites and restarts the BedJet without a fresh status check, so it overrides a physical-remote Off or change made while the phone was disconnected | new |
| RT-2 | Medium | Snooze/Extend record a CONFIRMED manual night after the packets are only sent, with no readback, and manual nights are then excluded from takeover detection | new |
| RT-3 | Medium | Remote temperature, fan and timer changes restore the old CONFIRMED programmed night. The user's own change is then reported as a physical takeover, or a re-time can overwrite it | new |
| RT-4 | Medium | Failures before any write (backup read, expiry after backup) mark a still-running, unchanged night FAILED. This silences the wake notice and blocks re-time, and no recovery path exists | new |
| RT-5 | Medium | A phone alarm ringing or being dismissed during the after-wake phase raises a daily false "wake-up change expired" problem, plus a BLE session and a "command did not finish" error | new |
| RT-6 | Medium | Widget Off (and other widget buttons) are rejected as STALE after any background night transition, because widgets only re-render once MainActivity has started WidgetUpdater | new (cross-task 4/6) |
| RT-7 | Medium | `RuntimeState.slotWrite` is never cleared. One FAILED/UNKNOWN pin/restore/Boost silently disables charger auto-start for good | new (cross-task 4/6) |
| RT-8 | Low | BedJetService can stop itself without calling `startForeground` after `startForegroundService` *(platform-dependent crash)* | new |
| RT-9 | Low | Background FGS start from AlarmChangedReceiver posts an error notification when the app is not battery-exempt *(platform-dependent)* | new |
| RT-10 | Low | The wake-up notification (id 2) and Resume notification (id 7) are not withdrawn on takeover/uncertainty or when the offer is invalidated | new (complements fixed finding #4) |
| RT-11 | Low | Queue wait uses up the 90 s operation budget, so a late-acquiring command can be cancelled part-way through an upload and leave the night UNKNOWN | new |
| RT-12 | Low | `reconcile()` from the time/zone/package receivers waits on the mutex with no bound inside an 8.5 s receiver budget, which gives a false "command did not finish" error | new |

All five Important findings from `docs/review/task-4-review.md` are **verified fixed in code**, each with a regression test (see "Verification of task-4-review findings").

---

## Findings

### RT-1 — High — Automatic re-time overrides a physical-remote action made while disconnected

- **Where:** `night/NightActions.kt:336-354` (`retryRetimeLocked`), `night/NightActions.kt:480-482` (`observeStatus` early return), `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt:156-182` (`apply`), `core/.../night/NightChanges.kt:42-54` (`Eligibility.check`), `night/LinkSessions.kt:76-90` (release after 5 s grace).
- **Root cause:** Takeover detection only happens in `observeStatus`, which needs a live READY link and an unlocked mutex:
  ```kotlin
  // NightActions.kt:480-482
  suspend fun observeStatus(status: DeviceStatus, target: String?, receivedAt: ZonedDateTime = graph.now()) {
      if (lock.isLocked) return
  ```
  Overnight, `LinkSessions` drops the connection 5 s after the last holder. Only the visible screen holds it. A physical-remote change made in that period is therefore never observed. When the alarm-follow retry runs, it takes the mutex and connects, so `observeStatus` drops every status that arrives during the connect. It then calls `apply` directly:
  ```kotlin
  // NightActions.kt:349-351
  connected {
      val result = apply(graph.runner().prepareRetime(n, pending.expected, ...), R.string.runtime_retimed)
  ```
  `NightRunner.apply` checks identity, deadline and local `confidence` only. It never reads device status before `controller.store(...)` and `controller.startProgram(...)` (`NightRunner.kt:179-181`). The stored night is still CONFIRMED because the takeover was never seen.
- **User-facing failure:** At 02:00 the user turns the BedJet off with its physical remote because they are too hot (the phone is not connected). At 05:30 the phone alarm moves, or the 10-minute retry fires. Nocturne connects, uploads and starts the programme, and the BedJet turns itself back on. The same gap applies to user-initiated REAPPLY ("Update tonight"), `setWakeOverride` and RESUME. These at least follow an explicit tap, but they also restart over a physical change without saying so.
- **Minimal fix:** In `retryRetimeLocked` (and the REAPPLY, override and resume paths), inside `connected { }` and before `apply`, call `freshStatus()`. Run the same step/mode/temperature comparison that `takeBack()` uses (`NightActions.kt:655-662`). If it contradicts, publish UNKNOWN, record REMOTE_CHANGE, clear `retime`, cancel the retry and return REFUSED/UNKNOWN without writing. The check can be factored out of `takeBack`.
- **Regression test:** Start a night. Have the demo unit go to STANDBY while no observer is connected (call `demo.send(Codec.setMode(STANDBY))` without calling `observeStatus`). Move the alarm, `submitAlarmChange()`, `retryPendingRetime()`. Assert that no programme or start packets were sent, the night is UNKNOWN, a REMOTE_CHANGE event exists and `retimePending == false`.
- **Affected tasks:** Task 4 (runtime), Task 3 core contract (R29 "recheck eligibility after acquiring the lock and connection"; register `R29` claims auto-verified, but eligibility here is local-state only), acceptance item "physical-remote takeover".
- **Status:** new.

### RT-2 — Medium — Snooze/Extend record CONFIRMED from command submission alone

- **Where:** `night/NightActions.kt:208-227` (`timedManual`), `night/NightActions.kt:192-207` (`manualChange`), `core/.../link/BedJetController.kt` `runManual` (sends `setMode`, `setTemperature`, `setFan`, `setRuntime` with no readback).
- **Root cause:**
  ```kotlin
  // NightActions.kt:218-220
  val n = old.copy(endsAt = until.toString(), revision = old.revision + 1, confidence = Confidence.CONFIRMED,
      pending = null, trackingManual = true, applied = AppliedProgram.from(plan, old.revision + 1))
  publishNight(n)
  ```
  This runs as soon as `runPreset` returns, which means the GATT writes were accepted, not that the unit reports the new mode, temperature and timer. After that, `observeStatus` returns early for every manual night (`NightActions.kt:491`: `if (n.confidence != Confidence.CONFIRMED || n.trackingManual) return@withLock`). The snoozed state is therefore never checked against the device. By contrast, Start, retime and Boost wait for `programStep == 1` (`BedJetController.startProgram`), and Keep tracking uses `freshStatus()`.
- **User-facing failure:** The user taps "Snooze warm 10m" on the wake notification. The writes go through but the unit drops one of them, for example the mode change after a reconnect, or the timer is reset by a following mode packet. The app says "Warming for 10 min", tracks a CONFIRMED manual run, schedules the end and shows the wake notice. The BedJet is actually off or in another mode, and nothing ever corrects this.
- **Minimal fix:** After `runPreset`, read a fresh status (`freshStatus()`, or the next status after `prepareForStart`-style dropping). Require mode, the temperature where applicable, and `isRunning`. Then mark the night CONFIRMED. Otherwise publish UNKNOWN with a reason and return UNKNOWN. Alternatively, keep the night `PENDING` and let `observeStatus` confirm it for manual nights against the single manual step.
- **Regression test:** In the demo link, make `setRuntime` (or `setMode`) a silent no-op. `perform(SNOOZE_WARM)`. Assert that the outcome is not CONFIRMED/ACCEPTED-as-confirmed and that the night confidence is not CONFIRMED.
- **Affected tasks:** Task 4. Task 5 presentation ("Snoozed" shown as fact).
- **Status:** new.

### RT-3 — Medium — Remote setters restore the pre-command CONFIRMED programmed night

- **Where:** `ui/RemoteScreen.kt:69,77,84` (temperature, fan, timer → `model.remote(label)`, `endsNight = false`, no guard), `night/NightActions.kt:667-672` (`remote`), `night/NightActions.kt:204-205`:
  ```kotlin
  if (endsNight && night != null) finishLocked(night, EventKind.STOPPED, message)
  else if (night != null) publishNight(night)   // the snapshot taken before the command, CONFIRMED
  ```
- **Root cause:** For setters that change the running programme's output, the runtime puts back the pre-command snapshot (CONFIRMED, same identity) as though nothing changed. That is right for Lights/Beeps/acknowledge, which `lightsBeepsAndAcknowledgementDoNotEndNightOrLeaveItPending` tests. It is wrong for temperature, timer and possibly fan. *(How BedJet behaves to a set-temperature/runtime packet during a programme is platform-dependent and was not verified.)*
- **User-facing failure:** During a programmed night the user drags the Remote temperature slider. The command succeeds and the night stays CONFIRMED. On the next status `observeStatus` sees `targetTemperature != step.temperature`. It marks the night UNKNOWN, logs `REMOTE_CHANGE` "changed by its remote" and posts the takeover notification, blaming the user's own in-app action on the physical remote. If a pending re-time gets the mutex before a status arrives (the mutex is free right after the command), it rewrites the programme over the user's adjustment.
- **Minimal fix:** Classify Remote setters. Lights, Beeps and acknowledge keep the current behaviour. For temperature, fan, timer and mode with an active programmed night, either route through the NightGuard and end tracking (`endsNight = true`), or publish the night as UNKNOWN with an "adjusted from Remote" reason (or move it to manual tracking through a fresh-status Keep tracking) instead of restoring CONFIRMED.
- **Regression test:** Start a night. `remote("Temperature set") { it.setTemperature(other) }`. Assert that the night is not CONFIRMED immediately afterwards, that `observeStatus` does not add a REMOTE_CHANGE event, and that `retryPendingRetime()` sends no packets.
- **Affected tasks:** Task 4 runtime, Task 5 Remote screen.
- **Status:** new.

### RT-4 — Medium — Failures before any write mark a valid running night FAILED

- **Where:** `core/.../night/NightRunner.kt:165-189` (`apply`: `publish(pending)`, then on any exception before `writeMayHaveHappened` it publishes `pending.copy(confidence = FAILED)`, where `pendingNight` is the *previous* night (`NightChanges.kt:18-20`)). `night/NightActions.kt:547-552` (`writeSlotLocked`). `night/NightActions.kt:352` (re-time clears `retime` on a non-success).
  ```kotlin
  // NightActions.kt:548-551 (Boost path, start = true)
  val confidence = if (writing) Confidence.UNKNOWN else Confidence.FAILED
  ...
  if (start) repo.activeNight.value?.let { publishNight(it.copy(confidence = confidence)) }
  ```
- **Root cause:** A failure in `backup()` (a BLE `storedPrograms()` read), or `checkExecutionTime` throwing after the backup (`NightRunner.kt:176`, "request has expired"/"wake-up has already started"), happens before any hardware mutation. The programme on the BedJet is untouched, but the active night is marked FAILED. After that:
  - `wakeUpLocked` needs CONFIRMED, so **no wake notification** (`NightActions.kt:475`).
  - `showQuiet` cancels the quiet notification.
  - `Eligibility.check` refuses re-time and reapply for FAILED.
  - `keepTrackingLocked` only accepts UNKNOWN (`NightActions.kt:383`). No Keep tracking or Stop tracking UI exists in `ui/` at this head anyway.

  For Boost, `slotWrite{startRequested, displaced = night}` also stays in place. `hasUnfinishedReplacement` then blocks re-time for good, and the next `reconcile()` (every `onScreenVisible`, `ui/AppViewModel.kt:84`) marks the night UNKNOWN as "interrupted" even though nothing was written.
- **User-facing failure:** At 05:50, with a wake phase starting at 06:00, an alarm-follow re-time hits a transient read error during backup, or its backup ends after 06:00. The BedJet carries on with the original programme, but the app now treats the night as FAILED: no wake notification, no quiet notification, and a widget showing a pending/unknown wake. In the Boost variant, a Boost tap that fails on its first BLE read leaves tonight unable to follow the alarm.
- **Minimal fix:** When `writeMayHaveHappened == false` and a `previous` night exists, republish `previous` unchanged (its original confidence, `pending = null`) and return Failed/Refused with a reason, instead of `pendingNight.copy(FAILED)`. Reserve FAILED for a Start with no previous night. In `writeSlotLocked`, only change the displaced night when `writing == true`, and clear `slotWrite` (or set it to FAILED with `startRequested = false`) when nothing was written.
- **Regression test:** (a) Start a night, set `failBackups = true`, queue and retry a re-time. Assert that the night is still CONFIRMED with the same `applied`, and that a later wake delivery shows the wake notice. (b) Same with BOOST and `replacementConfirmed = true`: assert CONFIRMED after the failure and after `reconcile()`. The existing `pinAndBoostAbortWhenOriginalBackupFails` has no active night, so it does not cover this.
- **Affected tasks:** Task 3 core contract, Task 4 runtime.
- **Status:** new.

### RT-5 — Medium — False "wake-up change expired" problem each morning

- **Where:** `night/Receivers.kt:35-43` (`AlarmChangedReceiver`), `night/NightActions.kt:289-301` (`submitAlarmChange`), `night/NightActions.kt:337` (`retryRetimeLocked` with no pending → refused "expired"), `service/BedJetService.kt:51` (shows an error when the outcome is not a success). Presets include `afterWake` phases (`core/.../plan/Presets.kt:12,20,35`), so `endsAt > wakeAt` and the night is still active after wake.
- **Root cause:** `ACTION_NEXT_ALARM_CLOCK_CHANGED` also fires when the phone alarm rings, is snoozed or is dismissed, because the "next" alarm changes *(platform-dependent but standard AlarmManager behaviour)*. With the night still active and `now >= wakeUpStart`, `submitAlarmChange` always does:
  ```kotlin
  if (expired) { NightAlarms.cancelRetry(context)
      Notifications.showError(context, text(R.string.runtime_retime_expired), text(R.string.runtime_retime_expired_detail))
  ```
  It does this without checking whether any re-time was pending or whether the observed alarm differs from the night. The receiver then still calls `BedJetService.start(RETRY_RETIME)`. That connects to the BedJet, gets REFUSED "expired", and posts "The command did not finish."
- **User-facing failure:** Every morning, when the user's own alarm goes off or is dismissed, they get "The wake-up change expired. … The BedJet was not restarted." as a PROBLEM notification, a second error notification, and a BLE connection made during the after-wake phase. This trains the user to ignore real problems.
- **Minimal fix:** In `submitAlarmChange`, once `now >= wakeUpStart`, drop the observation silently (clear the inbox) unless a `retime` was already pending for this identity. Return a non-LOCAL outcome, and have `AlarmChangedReceiver` start the service only when the submission returns LOCAL. In `retryRetimeLocked`, make "nothing pending" a silent no-op for receiver/alarm origins.
- **Regression test:** Start a preset night with after-wake, set the clock to `wakeAt + 1 min`, send `NEXT_ALARM_CLOCK_CHANGED` to the real receiver. Assert there is no PROBLEM notice, no notification id 3, no started service and no connect.
- **Affected tasks:** Task 4.
- **Status:** new.

### RT-6 — Medium — Widget commands go stale after background transitions, including Off

- **Where:** `widget/WidgetPresentation.kt:8-9` (`widgetRequest` captures `requireIdentity = true` at render), `platform/PlatformActionActivity.kt:31,38-40` (Off and other non-replacing commands run the *rendered* request with no dialog), `night/NightActions.kt:40` (strict identity → STALE), `ui/MainActivity.kt:45` (the only `WidgetUpdater.start` call site).
- **Root cause:** Widgets carry the night identity from when they were rendered, which is correct. But re-rendering is driven only by `WidgetUpdater`, which starts only from `MainActivity.onCreate` and lives as long as the process. Nights started by the charger job, re-timed by the alarm path, snoozed from the notification, or expired by the end alarm in a process where the UI never opened all leave the widget's `PendingIntent` with the old identity. `PlatformClockReceiver` only updates on boot and clock events. `ReplacementPolicy` says "Off is always executable", but a widget Off with an old identity is rejected:
  ```kotlin
  if (request.requireIdentity && request.expected != repo.activeNight.value?.identity) return@owned stale()
  ```
- **User-facing failure:** Charger auto-start begins tonight's night in the background (or at 05:40 the alarm moves the wake). The widget still holds "no night" or the old revision. At 03:00 the user taps the widget's Off button and gets "This changed — try again" while the BedJet keeps running.
- **Minimal fix:** (a) Start `WidgetUpdater` from `Graph.startRuntime()` (process start), or call `updateAll` on every `activeNight` publication. (b) For OFF from a widget (policy says always executable, with no replacement confirmation), match only the target device and fall back to a fresh capture, as `dynamic` START does, or show a confirmation dialog with the current identity instead of returning STALE.
- **Regression test:** Render the widget with no night, start a night through `performJobOwned` without starting `MainActivity`, then send the widget Off intent. Assert the BedJet receives a stop and the night ends, or that a confirmation dialog appears, and never STALE.
- **Affected tasks:** Task 4 identity contract, Task 6 widgets/charger.
- **Status:** new. This is the cross-task question in `task-4-review.md` ("do Task 6 widgets capture strict identity … ?"). Capture is correct, but freshness is not maintained.

### RT-7 — Medium — `slotWrite` is never cleared, so charger auto-start is permanently blocked

- **Where:** `night/NightActions.kt:535,545,550` (only writes to it), `night/NightActions.kt:29` (`status` prefers `slotWrite` FAILED/UNKNOWN over the night), `platform/ChargerRun.kt:43`:
  ```kotlin
  uncertain=repo.runtime.value.slotWrite?.confidence in setOf(Confidence.PENDING,Confidence.UNKNOWN,Confidence.FAILED),
  ```
- **Root cause:** No code sets `slotWrite = null`, or moves a FAILED/UNKNOWN entry on once it is resolved. Only another *successful* pin, restore or Boost overwrites it. `reconcile` only changes PENDING to UNKNOWN.
- **User-facing failure:** A pin to slot 1 fails once at the backup read (FAILED, nothing written). From then on charger auto-start never fires (`PlatformPolicy.eligible` needs `!uncertain`), and nothing in the UI explains why. Any consumer of `NightActions.status` would also report FAILED/UNKNOWN for every later, healthy night.
- **Minimal fix:** Clear `slotWrite` when nothing was written (FAILED). For UNKNOWN, clear it after a successful fresh readback of that slot, or when a later night completes. Limit `status`'s preference for `slotWrite` to the night that `displaced` names.
- **Regression test:** `failBackups = true; pin(...)`. Then `failBackups = false` and run the charger eligibility check. Expect it to be eligible. Also check `actions.status` for a newly started night reports CONFIRMED.
- **Affected tasks:** Task 4, Task 6 (charger).
- **Status:** new.

### RT-8 — Low — Service can stop without `startForeground` *(platform-dependent)*

- **Where:** `service/BedJetService.kt:25-37`.
  ```kotlin
  if (request == null && handoff == null) { if (pending == 0) stopSelf(startId); return START_NOT_STICKY }
  try { if (!permitted(...)) throw SecurityException(...) ... startForeground(...) }
  catch (e: RuntimeException) { ...; if (pending == 0) stopSelf(startId); return START_NOT_STICKY }
  ```
- **Root cause:** Every start comes from `startForegroundService`. If the handoff was already removed (`runOwned` timed out or its caller was cancelled before `onStartCommand`, for example when `PlatformActionActivity` is dismissed), or the permission was revoked in between, or `startForeground` throws, the service stops without ever entering the foreground. On Android 8+ the framework then raises "Context.startForegroundService() did not then call Service.startForeground()" and crashes the process (AMS `SERVICE_FOREGROUND_CRASH_MSG` on bring-down). *(Framework behaviour not reproduced here. Robolectric does not enforce it.)*
- **Failure:** A rare process crash while a night is being tracked, which kills any in-flight receiver work.
- **Minimal fix:** At the very start of `onStartCommand`, always call `startForeground(WORKING_ID, working, shortService/none-safe type)` before any early return, then `stopSelf`. If the connectedDevice type is not permitted, fall back to `SHORT_SERVICE` on 34+.
- **Regression test:** A unit test can only check that `startForeground` is invoked on every `onStartCommand` path (ShadowService `isForegroundStopped`/`getLastForegroundNotification`), including a missing handoff.
- **Affected tasks:** Task 4.
- **Status:** new.

### RT-9 — Low — Background FGS start from the alarm-changed receiver *(platform-dependent)*

- **Where:** `night/Receivers.kt:41` and `:23`, `service/BedJetService.kt:128-131` (`start` catches `RuntimeException` and calls `failure` with notifications on).
- **Root cause:** `ACTION_NEXT_ALARM_CLOCK_CHANGED` is not a background FGS-start exemption on Android 12+. Unless the user granted "Unrestricted battery" (a recommendation in `ui/SetupChecks.kt:88-93`, not a requirement), `startForegroundService` throws `ForegroundServiceStartNotAllowedException`, which `start` turns into an error notification. The durable inbox and the 10-minute exact retry alarm, which is exempt, still do the work, so the notification is noise.
- **Minimal fix:** Pass `notifyFailures = false` from receiver origins, and only start the service when it is allowed to start, or rely on the exact retry alarm.
- **Test:** Robolectric: make `startForegroundService` throw `ForegroundServiceStartNotAllowedException` and assert no notification id 3.
- **Status:** new.

### RT-10 — Low — Wake-up and Resume notifications are not withdrawn when they become untrue

- **Where:** `notify/Notifications.kt:60-76` (`WAKE_ID`, ongoing), `night/NightActions.kt:499-503` (takeover publishes UNKNOWN, cancels only quiet through `refreshQuietNotification`), `Notifications.kt:89-100` (Resume id 7 is never cancelled when `advanceGeneration` clears the offer, `NightActions.kt:101`).
- **Failure:** After a physical-remote Off during the wake phase, the ongoing "Waking you at 07:00 — Your BedJet is getting you ready" stays until the end alarm. A Resume notification can outlive its offer: tapping it shows a confirmation, then fails with "resume expired" and an error notification.
- **Minimal fix:** In `publishNight`, cancel `WAKE_ID` when confidence is not CONFIRMED, or rewrite its text as uncertain. Cancel id 7 whenever `resumeOffer` becomes null.
- **Test:** NotificationManager shadow: show the wake notice, call takeover `observeStatus`, assert id 2 is cancelled or updated. Clear the offer and assert id 7 is cancelled.
- **Status:** new. Previously reported finding #4 covered only the quiet notification (id 6), which is fixed.

### RT-11 — Low — Queue wait can leave too little budget for an upload

- **Where:** `night/NightActions.kt:73-79` (`withTimeout(OPERATION_MS) { lock.lock(owner); … work() }`).
- **Root cause:** The deadline includes queue wait, as the plan required. But no minimum remaining budget is checked before the first hardware mutation. A Start that gets the mutex after 85 s of waiting starts connect, backup and upload with about 5 s left. It is then cancelled mid-upload, the night becomes UNKNOWN, and replay is (correctly) refused.
- **Minimal fix:** Inside `owned`, after acquisition, refuse with STALE/"busy, try again" if the remaining time is below a floor (for example 30 s) for commands that mutate hardware.
- **Test:** Hold `exclusive {}` for 85 s of virtual time, queue START. Assert REFUSED/STALE with no packets sent and the night not UNKNOWN.
- **Status:** new.

### RT-12 — Low — Unbounded `reconcile` lock wait inside the receiver budget

- **Where:** `night/Receivers.kt:32`, `night/NightActions.kt:443-444` (`lock.withLock` with no tryLock or timeout), `Receivers.kt:48-51` (an 8.5 s timeout leads to `showError(runtime_failed)`).
- **Failure:** A time, zone or package-replaced broadcast while a command holds the mutex produces a false "The command did not finish." problem notification. RTC alarms are absolute, so missing this reconcile is otherwise harmless.
- **Minimal fix:** Use the same tryLock and durable-retry pattern as local deliveries, or suppress the error notification for reconcile timeouts.
- **Status:** new.

---

## Verification of task-4-review.md Important findings (checked in code at 7fffabc)

1. **Boost replay after process death — FIXED.** Intent and displaced identity are persisted before backup and upload: `night/NightActions.kt:531-535` (`SlotWrite(..., startRequested = start, displaced = activeNight.identity)`). Reconcile marks a matching displaced night UNKNOWN and clears `retime`: `NightActions.kt:447-452`. Automatic re-time is blocked by `hasUnfinishedReplacement`: `NightActions.kt:316-317,320,345`. Tests: `app/src/test/.../night/RuntimeOwnershipTest.kt:54-74`, covering both the PENDING and CONFIRMED windows with an alarm observation present, no sends, and UNKNOWN. *Residual problem: a pre-write Boost failure is wrongly treated as "displaced". See RT-4.*
2. **Wake/end receiver timeout delivery — FIXED.** The receivers call `submitLocalDelivery` (`night/Receivers.kt:16-18`). It pre-arms a 1-minute local retry, persists an identity-bound `LocalDelivery` through the repository writer, and drains with `tryLock` only: `NightActions.kt:407-428`. It is consumed under the mutex by owner `finally` (`NightActions.kt:91-92`, `drainLocalSafely` 429-433), by `reconcile` (466) and by the retry alarm (`Receivers.kt:19`). Identity and dedup checks are in `expireLocked`/`wakeUpLocked` (`NightActions.kt:268-273,473-479`). Tests: `app/src/test/.../service/RuntimeServiceTest.kt:50-113` (actual broadcast held beyond 10 s, one wake notice, no writes or connects, changed-revision rejection).
3. **Equal-time override clear — FIXED.** `NightActions.kt:574-582` detects an equal fallback instant and makes a durable local transition through `WakeSourceChange`. `completeWakeSourceChange` (594-602) clears the override and updates origin, and is completed on reconcile (446). Test: `app/src/test/.../night/RuntimeDeliveryTest.kt:36-50` (reset followed by a real alarm re-time moving the wake).
4. **Stale quiet notification — FIXED for notification id 6.** `publishNight` calls `refreshQuietNotification` (`NightActions.kt:690-698`). `showQuiet` cancels for manual, non-CONFIRMED or disabled (`notify/Notifications.kt:78-79`). `App.kt:66-70` reacts to settings publications and active-night changes. Tests: `RuntimeDeliveryTest.kt:52-121`. *The wake-up notification (id 2) is not covered: see RT-10.*
5. **Resume notification after replacement — FIXED.** `NightActions.kt:146-151` creates the offer and `:164-165` posts `Notifications.showResume`. `Notifications.kt:89-100` captures the current identity into a `ShortcutActivity` confirmation. `ui/ShortcutActivity.kt:30-38` grants `replacementConfirmed` only in the positive callback and keeps the captured identity. Test: `RuntimeDeliveryTest.kt:122-155` (Start-over-Start, real dialog, cancel without running, stale rejection after a revision change).

---

## Verified correct (in code; no device verification)

- **Single command owner.** Every production hardware write goes through `NightActions` (`owned`/`performJobOwned`). A grep for `controller()`, `graph.link.`, `bleLink` and `sessions.` outside `night/NightActions.kt` finds only read-only state/status/name observation (`ui/AppViewModel.kt:56,58,243`, `widget/*`, `platform/NightTileService.kt:21`, `platform/ChargerRun.kt:42`, `platform/PhoneHandover.kt:30-39`), connection-hold management (`AppViewModel.kt:82,91,95`) and `BedJetService.runOwned` wrappers (`AppViewModel.kt:167`, `PlatformActionActivity.kt:66`, `FeatureSections.kt:117,122`). The Remote screen goes through `NightActions.remote` (owned). No direct `setActiveNight`, `updateRuntime` or `recordOriginalSlot` callers outside NightActions/Repository.
- **Original slot recorded before writes:** `backup()` (`NightActions.kt:180-185`) runs as `beforeWrite` in `apply` (144) and before the first write in `writeSlotLocked` (538).
- **Deadline includes queue wait:** `withTimeout(OPERATION_MS)` wraps `lock.lock(owner)` (`NightActions.kt:73-74`). The target is re-validated under the mutex (78-79), and identity is checked under the mutex (40, 54).
- **Prepared transactions:** pending is published before backup or write (`NightRunner.apply` 164-165). Identity, command generation and deadline are rechecked after backup and before start (170-181). UNKNOWN is published under `NonCancellable` on a partial write.
- **Retry and expiry:** the retry alarm is clamped to the deadline and never set in the past (`NightAlarms.kt:28-33`). `set()` refuses past triggers (`NightAlarms.kt:41`). The pending re-time expires at `wakeUpStart` without writing (`NightActions.kt:339-344`; test `RuntimeDeliveryTest.kt:172-180`). A connect failure re-arms the retry. A write failure does not replay.
- **Snooze/Extend racing expiry:** end deliveries are identity-bound, so an end queued behind Snooze cannot expire the new revision (test `RuntimeOwnershipTest.kt:75`). The end alarm is re-armed for the new `endsAt` (`timedManual` → `NightAlarms.schedule`).
- **Reboot, time and zone:** `BootReceiver` handles BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED, TIME_SET (`ACTION_TIME_CHANGED` == "android.intent.action.TIME_SET") and exact-alarm permission change. `reconcile` completes local end and source change, turns PENDING into UNKNOWN, expires past nights and re-arms alarms. An UNKNOWN programme never gets a wake notice and is never replayed. Stored times are absolute offsets, so zone changes do not move alarms.
- **Manifest and security:** `BedJetService`, `NightAlarmReceiver` and `PlatformActionActivity` are `exported=false`. `ShortcutActivity` is exported but always shows a confirmation, and external extras cannot waive it (`ShortcutActivity.kt:30-38`). `BootReceiver` and `AlarmChangedReceiver` are exported but only trigger local reconcile or read the real `AlarmManager.nextAlarmClock`. Every PendingIntent is `FLAG_IMMUTABLE`. Notification command PendingIntents carry the captured identity and use `requireIdentity = true` (`BedJetService.kt:139-144`).
- **FGS on Android 14+ (targetSdk 35, minSdk 31):** `foregroundServiceType="connectedDevice|shortService"`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` is declared, and `BLUETOOTH_CONNECT` is checked before `startForeground` with `TYPE_CONNECTED_DEVICE` (`BedJetService.kt:29-33`). Demo and Stop tracking use `SHORT_SERVICE` on 34+.
- **Exact alarms:** `USE_EXACT_ALARM` (33+) and `SCHEDULE_EXACT_ALARM` (≤32). `canScheduleExactAlarms()` falls back to `setAndAllowWhileIdle` and also catches `SecurityException` (`NightAlarms.kt:42-47`). A permission change triggers reconcile and a re-arm. Note: `USE_EXACT_ALARM` is restricted by Play policy, which is not a concern for a GitHub-distributed APK.

## Cross-task observations (not counted as Task 4 defects)

- No Keep tracking, Stop tracking or Resume control exists in `ui/` at this head. These commands can only be reached through the Resume notification and tests, so UNKNOWN/FAILED nights can only be ended with End night (which turns the BedJet off) or by the end alarm. Register F02 and R-items already mark Task 5 presentation as pending. RT-4 makes this worse.
- The app is not direct-boot aware. After an unattended overnight reboot (for example a scheduled auto-restart), wake, end and re-time handling stays paused until the user unlocks. The BedJet keeps running its programme. *(Platform-dependent; not verified on Samsung.)*
- `AppViewModel.useAlarmsFrom` (`ui/AppViewModel.kt:197-199`) writes `alarmPackages` directly with no owned call and no re-time. Accepting a new alarm source during a night does not re-check the alarm until the next alarm-change broadcast.

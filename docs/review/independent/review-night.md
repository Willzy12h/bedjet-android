# Nocturne night scheduling and state-machine review (Task 3, worktree @ 7fffabc)

Read-only source review. I did not run Gradle or any tests, and I did not verify anything on hardware. Every statement about BedJet behaviour below comes either from the code's own comments or from the DemoBedJet model, which treats a late clock-time step as running until that time the next day (`DemoBedJet.kt:175-180`).

Files read: `core/.../night/{NightRunner,NightChanges,ActiveNight}.kt`, `core/.../plan/{PlanCompiler,WakeResolver,NightPreview,Profile}.kt`, `core/.../link/{BedJetController,DemoBedJet}.kt`, `core/.../protocol/{Model,Codec}.kt` (limits and encoding), `NightTemporalEligibilityTest.kt`. For context on callers I also read `app/.../night/NightActions.kt` (apply/retime/resume/override flows), `app/.../ble/GattLink.kt` (send path), `AppViewModel.kt` and `TonightScreen.kt` (preview), `Settings.kt`, and docs/review task-3-review, task-3-rereview, task-4-review #3 and the register rows listed in the brief.

## Findings

### NGT-1: The final start guard checks the request deadline and the old boundary, not whether the uploaded plan can still run. A late retime can start a programme whose clock-time step has already passed.
- **Severity:** Medium. It is unlikely in practice because it needs a delay of 10 minutes or more inside `apply`, but the impact would be severe: no wake-up, and a step that may run for about 24 hours.
- **Where:** `core/.../night/NightRunner.kt:193-200` (checkExecutionTime), `:177-181`. Caller: `app/.../night/NightActions.kt:323-325,350-351`. Planner: `core/.../plan/PlanCompiler.kt:51-60`.
- **Root cause:** The plan is compiled from the preparation instant `time`. The final guard (`checkExecutionTime`) checks two things: `now < request.deadline`, and for RETIME/REAPPLY, `now < previous.wakeUpStart`. For RETIME, the app sets `deadline = ZonedDateTime.parse(n.wakeUpStart)` of the *old* night, which can be hours away (NightActions.kt:323/350). Neither the deadline nor the old boundary bounds:
  - (a) how stale `plan.start` is,
  - (b) the *new* plan's `wakeUpStart`, which is earlier than the old one whenever the alarm moved earlier, or
  - (c) the end time of the new plan's clock-time last-Middle step.
- **Evidence:**
  ```kotlin
  // NightRunner.kt:193-199
  if (!time.isBefore(ZonedDateTime.parse(change.request.deadline))) throw PlanException("This request has expired.")
  if ((change.request.kind == ChangeKind.RETIME || ...) && change.previous != null &&
      !time.isBefore(ZonedDateTime.parse(change.previous.wakeUpStart))) throw ...
  // PlanCompiler.kt:52-53 (the hazard, as the code itself describes it)
  // A short last step could already be past its clock-time end by the time it begins ... and the BedJet would then wait for that time tomorrow.
  ```
  `MIN_CLOCK_TIME_STEP = 10` is the only margin. It is sufficient for START and REAPPLY, which have 1-minute deadlines (`NightRunner.kt:51,74`). It is not sufficient for RETIME, whose deadline is the old wake-up boundary.
- **Scenario:** The night wakes at 07:00 and the wake-up starts at 06:30. At 05:40 the alarm moves to 06:20, so the new wake-up starts at 05:50 and the new last Middle step ends at clock 05:50 (≥10 min, so it is marked endsAtClockTime). The BLE backup, upload and readback are slow, for example because of repeated reconnects inside `GattLink.send`/`query` (see NGT-2). The start goes out at 05:52. That is still before the old 06:30 boundary and the old-boundary deadline, so the guard passes. The BedJet runs the Middle mode until 05:50 *tomorrow*, and the app publishes CONFIRMED with a 06:20 wake.
- **Minimal fix:** In `checkExecutionTime`, also require that:
  - `now < change.intended.wakeUpStart` (the new boundary) for RETIME/REAPPLY/RESUME;
  - for every `endsAtClockTime` step in `change.plan`, `now < step.endsAt - MIN_CLOCK_TIME_STEP` (or simply `now < plan.start + 1–2 min` staleness for any kind, recompiling if it is exceeded).

  Alternatively, give RETIME a short execution deadline (prepare-time + 1 min) that is separate from the durable PendingRetime deadline. The retry path already re-prepares at execution (NightActions.kt:350), so a short per-attempt deadline costs nothing.
- **Regression test:** `NightTemporalEligibilityTest`: start the night (22:30 to 07:00), then prepare a retime at T to an earlier wake whose last Middle is a clock-time step ending at E, with deadline = old wakeUpStart. Use `observedRunner(afterReadback = { now = E + 1min })`. Assert `starts == 0`, the result is not Applied, and the published state is UNKNOWN. A positive control: with DemoBedJet and the current code, the step would end 24 h later.
- **Affected tasks:** 3 (core guard), 4 (retime deadline choice).
- **Status:** New. Task-3-review #2 and the rereview covered request deadline and *old* boundary only.

### NGT-2: Time can still pass between `beforeStart()` and the start byte: `GattLink.send` may reconnect or wait on a mutex after the check
- **Severity:** Low. The slack is bounded at roughly 50 s.
- **Where:** `core/.../link/BedJetController.kt:75-76`, `app/.../ble/GattLink.kt:95-103,165-169,52`.
- **Root cause:** `beforeStart` is synchronous and runs after `syncClock()` and `prepareForStart()`, as intended. But `link.send` then suspends before writing:
  - `ensureConnected()`, which on a dropped link calls `connect()`: three attempts, each up to `connectMs` 12 s plus `firstStatusMs` 3 s, with 0.5 s and 1.5 s backoff, so about 47 s worst case;
  - `operations.withLock` behind any in-flight query, up to `operationMs` 5 s.

  The check and the send are therefore not atomic.
- **Evidence:**
  ```kotlin
  // BedJetController.kt:75-76
  beforeStart()
  link.send(Codec.startProgram(slot))
  // GattLink.kt:95-101
  override suspend fun send(command: ByteArray) {
      ensureConnected()            // may reconnect with retries
      operations.withLock { ... write(session, command) }
  ```
- **Scenario:**
  - A RETIME passes the guard at 06:29:50, 10 s before the old wake-up boundary. The link drops, and the start is sent at about 06:30:30 after reconnecting. This replaces a wake-up that has already begun, which is exactly what R11/R29 say must not happen.
  - For START (deadline +1 min) the effect is a night up to about 1 minute shorter than the 1 h minimum. That is harmless.
- **Minimal fix:** Make the start send non-reconnecting and run the guard inside the link's operation lock. Two options:
  - add `BedJetLink.sendStart(command, guard: () -> Unit)` that calls `live()` (throw if not ready, never `connect()`), then `guard()`, then `write` under `operations`; or
  - pass a `reconnect = false` flag for the start command.

  A drop then becomes a failure with UNKNOWN state, not a late start.
- **Regression test:** Two tests.
  - Core: a link fake whose `send` for 0x80..0x82 advances the clock past the deadline before "writing". Assert the guard result. (With the current API this cannot be expressed, which is itself the evidence.)
  - App (`AndroidBedJetLinkTest`/GattLink fake port): retire the session between clock sync and start. Assert that no start write occurs after a reconnect.
- **Affected tasks:** 3, 4 (link).
- **Status:** New. Task-3-rereview treated the controller hook as "immediately before sending" without considering suspension inside `send`.

### NGT-3: Across a DST change the BedJet clock is synced in a different UTC offset from the one the clock-time end was computed in. The wake-up is then ±1 h off.
- **Severity:** Low. The window is the length of `apply` (seconds, up to about 1 min for START), straddling 01:00 UTC on the two Europe/London change nights.
- **Where:** `core/.../plan/PlanCompiler.kt:56` (end computed from `start.toLocalTime()`), `core/.../link/BedJetController.kt:90-93` (`syncClock` uses a fresh `now()` local time).
- **Root cause:** `endOnBedJetClock = plan.start.toLocalTime() + real minutes`. This is correct only if the BedJet clock is set to wall time in **plan.start's offset**. `syncClock` sends `now().hour/minute` at start time. If preparation is before the change and the sync is after it, the two are an hour apart.
- **Evidence:**
  ```kotlin
  val endOnBedJetClock = start.toLocalTime().plusMinutes(Duration.between(start, planned.endsAt).toMinutes())   // PlanCompiler:56
  val time = now(); link.send(Codec.setClock(time.hour, time.minute))                                           // BedJetController:91-92
  ```
- **Scenario:** Spring 2026-03-29 in Europe/London. START is prepared at 00:59:40 GMT (plan.start 00:59), and the sync happens at 02:00:05 BST. With wake at 07:00 BST and a 30-minute wake phase, the clock-time end is 00:59 + 331 min = 05:30. The BedJet clock reads 02:00 at 01:00 GMT, so it reaches 05:30 at 04:30 GMT (05:30 BST). The wake-up runs an hour early and is finished before the alarm. In autumn the same thing happens in the other direction: the wake-up is an hour late.
- **Minimal fix:** Sync the clock in the plan's offset: `syncClock(at = now().withZoneSameInstant(plan.start.offset))`, passing the offset through `startProgram`. Or refuse in `checkExecutionTime` when `now().offset != change.plan.start.offset`.
- **Regression test:** Use a `ZoneId.of("Europe/London")` clock. Prepare START at 00:59:40Z on 2026-03-29 and advance to 01:00:05Z in `afterUpload`. With DemoBedJet, assert that the clock-time step ends at the planned `wakeUpStart` instant (or that the start is refused).
- **Affected tasks:** 3.
- **Status:** New. Normal DST and midnight cases are correct; see "Verified correct".

### NGT-4: A start refused at the final guard is reported as UNKNOWN even though the core knows no start byte was sent. For a fresh START this leaves a phantom active night.
- **Severity:** Low. The report errs on the safe side, but it does not say what actually happened.
- **Where:** `core/.../night/NightRunner.kt:178-189`, `NightChanges.kt:139-141` (`pendingNight` for `previous == null` is `intended` with empty `applied`). Caller: `app/.../night/NightActions.kt:170-174`.
- **Root cause:** `writeMayHaveHappened = true` is set before `store`. Every later exception, including the `PlanException` thrown by `beforeStart` (which proves the start was **not** sent), is reported as `Confidence.UNKNOWN` on the pending night. When there was no previous night, that pending night is a brand-new record. It has no history record and no `NightAlarms.schedule` (that only runs on Applied), so it stays as an UNKNOWN "active night" until the user picks Stop tracking. Keep tracking is refused because the BedJet is not running.
- **Evidence:**
  ```kotlin
  writeMayHaveHappened = true
  controller.store(...); checkExecutionTime(change)
  controller.startProgram(change.intended.slot) { checkExecutionTime(change) }
  ...
  } catch (e: Exception) { val failed = pending.copy(confidence = if (writeMayHaveHappened) Confidence.UNKNOWN else Confidence.FAILED)
  ```
- **What the slot holds:** After a refusal, the working slot keeps the new programme, including its endsAtClockTime step, and it is not restored automatically. This is true after every normal night too, so it is not specific to the refusal. Only `pin` converts clock-time steps to durations (NightActions.kt:515). If the slot is started from the unit itself, the clock-time step would run until that clock time, possibly the next day. That is unverified on hardware, so it is an observation, not a finding.
- **Minimal fix:** Distinguish "start definitely not sent" from the rest. Throw a dedicated `StartWithheld` exception from the guard. For START/RESUME with `previous == null`, publish FAILED with a "slot was rewritten" reason instead of UNKNOWN, or clear the active night. Keep UNKNOWN when a previous programme may be running from the overwritten slot (RETIME/REAPPLY/replacement), because the hardware effect of overwriting a running slot is unknown.
- **Regression test:** Fresh START with `afterReadback = { now = deadline }`. Assert outcome FAILED (not UNKNOWN), no active night left (or one flagged as not started), and the slot change recorded.
- **Affected tasks:** 3, 4.
- **Status:** New. The UNKNOWN behaviour was a deliberate choice in 694425b and task-3-review #2. I am raising its truthfulness for the fresh-START case.

### NGT-5: The Start-now plan card and headline do not use the same resolver as the actual start: the tonight-only override is ignored
- **Severity:** Medium. The UI states a wake time and curve that differ from what will run. (UI wiring, but it bears directly on focus 3.)
- **Where:** `app/.../ui/AppViewModel.kt:107-117`, `app/.../ui/TonightScreen.kt:119,138`. Compare with START at `NightActions.kt:113-114`, which calls `NightPreview.forStartNow(..., s.resolveWakeOverride(now), s, origin)`.
- **Root cause:** `previewPlan(startNow = true)` and `wakePreview` call `WakeResolver.resolve`, which:
  - has no override parameter;
  - uses `AlarmPolicy()` with default Settings and origin `null`;
  - bases the headline on `nextBedtime`, not on now;
  - relies on a cached `nextAlarm`.
- **Evidence:**
  ```kotlin
  PlanCompiler.compile(profile, start, WakeResolver.resolve(profile, start, nextAlarm.value?.takeIf { it.counts }?.time).time)
  ```
- **Scenario:** The user sets a tonight-only override of 06:00 while the phone alarm is 07:00. The Tonight screen shows "Wakes you at 07:00" and a 07:00 curve. "Start night now" programmes 06:00.
- **Minimal fix:** Replace both calls with `NightPreview.forStartNow(profile, now, alarm.time, settings.resolveWakeOverride(now), settings, alarm.creator)`, using the same `now`. Show `refusalReason` on failure. The platform callers (`PlatformActionActivity.kt:36`, `ChargerRun.kt:35`) already do this.
- **Regression test:** ViewModel test with an override set. Assert `previewPlan(startNow=true).wake == NightPreview.forStartNow(...).wake ==` the started night's `wakeAt`.
- **Affected tasks:** 3 (shared preview), 5 (UI wiring).
- **Status:** Previously tracked. Register R36 is "in progress … Task 5 UI wiring pending". I confirmed it is still present in code at 7fffabc.

## Verified correct (in code)

- **Final guard order:** `startProgram` runs `syncClock()`, then `prepareForStart()`, then registers the waiter (UNDISPATCHED), then `beforeStart()`, then sends start. No suspension point inside the controller sits between the guard and `link.send` (the NGT-2 caveat is inside `send`). If the guard throws, the confirmation waiter is cancelled by `coroutineScope`. The test asserts it is cancelled in under 10 s. `syncClock` is the only clock sync in the codebase, so there is no later delayed sync.
- **Guard placement:** There are guards after backup, after store/readback, and at the final send (`NightRunner.kt:177,180,181`). Identity and command version are rechecked after `beforeWrite` (`:170-175`). Resume's deadline is clamped to `min(expiresAt, wake)` (`:147`). Every refusal after publishing the pending state publishes FAILED (before the write) or UNKNOWN (after it), never CONFIRMED.
- **Identity:** Every runner entry point compares `NightIdentity(nightId, revision)`. `apply` rejects stale identity both before and after backup. The own-pending-start exception is tied to the exact `requestId`. At the app layer, widget, notification, shortcut and platform requests capture identity with `requireIdentity = true`, and NightActions rejects a mismatch (`:40,:54`). Snooze/Extend bumps the revision, so an expiry alarm queued for the old revision cannot end the new one (`expireLocked` identity check, `:268-270`).
- **Task-4 finding 3 (equal-wake override reset):** Fixed. `NightActions.kt:574-582` handles an equal fallback as a local wake-source change, clearing the override and setting origin to ALARM/PROFILE_DEFAULT while keeping `applied` and `wakeAt`. The regression `RuntimeDeliveryTest.clearingEqualTimeOverrideFollowsSubsequentAlarmChange` asserts that a later alarm change is followed.
- **16-hour guard and the 06:34 case:** The default wake is `nextOccurrence(default, now+1h)`. At 06:34 with a 07:00 default this gives the next day's 07:00 (24 h 26 m), which `NightPreview` and `compile` both refuse with "Set a tonight-only wake time". Wake exactly now, or less than 1 h ahead, is refused (`NightPreview.kt:247-249`; retime `NightRunner.kt:86`). Exactly 1 h and exactly 16 h are accepted. Override times in the past are dropped consistently by `resolveWakeOverride` and `NightPreview`.
- **DST and midnight (normal case):** `compile` uses real minutes. The clock-time end is `start local + real minutes`, which matches a BedJet clock that does not observe DST, provided it was synced at the start in the same offset (spring and autumn nights are both correct). The end wraps past midnight through `LocalTime`. The hours field is encoded as `hours and 0x7f | 0x80` (`Codec.kt:64`), so 0–23 fits. The ≤10-minute last Middle step runs by duration. `nextOccurrence` handles a gap by shifting forward and an overlap by keeping the earlier offset.
- **Same clock for preview and start:** `prepareStart` reads `now()` once and uses it for the preview, the plan, `startedAt` and the request.
- **"Use tonight":** expires at the next noon (`Settings.chooseTonight`).
- **Truthful limits:**
  - fan is clamped to the quantised `minFan`/`maxFan`, and an impossible range throws;
  - temperature is clamped per mode;
  - Turbo throws when `maxTemp < 86` instead of silently exceeding it;
  - Turbo is capped at 10 min, then chains Heat at `limits.maxTemp` or repeats Turbo;
  - Heat steps are split by the cut-off table (`ProgramStep.longestMinutes`), and other steps at 11 h 59 m;
  - the 32-step guard is applied in `compile`, `resume` and `store`;
  - `ProfileValidation` checks both the 1 h and 16 h extremes.

## Not verified or uncertain
- BedJet behaviour when the slot that is currently running is overwritten (the RETIME and replacement paths), and what happens when a stored clock-time programme is started from the unit itself. Both need hardware.
- How likely NGT-1 is in practice depends on real BLE reconnect frequency during one transaction. I have not measured it.

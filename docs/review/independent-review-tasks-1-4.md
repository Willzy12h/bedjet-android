# Independent review: hardening Tasks 1–4

- **Reviewer:** Claude (independent, read-only), with five delegated reviewers covering Bluetooth, storage, night state, Android runtime and cross-cutting scenarios.
- **Date:** 2026-10-22.
- **Baseline:** `origin/claude/nocturne-hardening` @ `7fffabc` ("docs: complete platform review and integration gate").
- **Scope:** Tasks 1–4 as they now stand in code, including later edits from Tasks 3, 4 and 6. Tasks 5–7 are Astra's open work and are only checked where they consume Task 1–4 contracts.
- **Method:** static review of the actual implementation plus the full automated gate. No production code, tests or Gradle files were changed. Nothing was merged, rebased or force-pushed.
- **Hardware:** nothing here has been verified on a Samsung phone or a BedJet 3.

Full per-domain evidence, with code quotes and regression test sketches, is in [`independent/`](independent/):

| Report | Covers |
|---|---|
| [review-ble.md](independent/review-ble.md) | Bluetooth link (BLE-n findings) |
| [review-storage.md](independent/review-storage.md) | Storage and import/export (STO-n) |
| [review-night.md](independent/review-night.md) | Night planning and timing (NGT-n) |
| [review-runtime.md](independent/review-runtime.md) | Android runtime (RT-n) |
| [review-cross.md](independent/review-cross.md) | Cross-cutting scenarios (X-n) |

This document consolidates and de-duplicates those findings as IR-nn. I re-checked every High finding, and the Medium findings marked ✔, against the code myself.

---

## A. Executive assessment

**What has been integrated.** Tasks 1, 2, 3, 4 and 6 are merged into the branch and build. The gate passes at `7fffabc`: 138 core tests, 190 app tests passed with 4 screenshot tests skipped, debug assembly succeeds, and lint reports 0 errors. Several things are real and well built:
- the session-owned GATT machine;
- durable-before-publish storage;
- identity- and revision-checked night mutations;
- the single command owner (`NightActions` and `BedJetService.runOwned`);
- an original-slot backup before every slot write.

**Earlier findings are fixed.** All five Important findings from `task-4-review.md` are fixed in code, each with a regression test:
- Boost replay;
- wake/end receiver delivery;
- clearing an override with an equal wake time;
- the stale quiet notification;
- resume after a replacement.

The Task 1 focus item is also fixed: the production adapter does forward `prepareForStart()` (`AndroidBedJetLink.kt:48`, wired via `App.kt:51/57`).

**Is "Tasks 1–4 complete" accurate?** Not yet. The guarantees most at risk are the central ones from the plan:
- **Never override the user:** IR-01.
- **Commands reach only the chosen BedJet:** IR-02.
- **The original slot is really the original:** IR-03.
- **Pending, failed or unknown status is reported truthfully:** IR-05, IR-06, IR-08 and IR-09.

None of these is caught by the current tests, which exercise the in-app paths.

### Release blockers (must be fixed before Task 7 or acceptance)

1. **IR-01 (High).** An automatic re-time can restart a night the user switched off with the BedJet's physical remote.
2. **IR-02 (High).** After Forget or a change of BedJet, a connect retry still running for the old unit can finish. Commands, slot writes and the original-slot backup then go to the wrong BedJet.
3. **IR-03 (High).** If `slot-backups.json` is unreadable, the app records its own programme as the user's "original" and keeps it for good.

### Should be fixed in the same wave (truthfulness and user-facing noise)

- **Status shown as confirmed or failed when it isn't:**
  - IR-04: a pre-start status can still confirm a start.
  - IR-05: a failure before any write marks a healthy night FAILED.
  - IR-06: `slotWrite` is never cleared.
  - IR-08: Snooze/Extend are confirmed on submission alone.
  - IR-09: Remote changes republish the old confirmed night.
- **Late or stale commands:**
  - IR-07: false error notifications every morning.
  - IR-10: a late re-time can start a programme whose clock-time end has already passed.
  - IR-11: there is no admission check when a queued request gets the lock.
  - IR-12: widget Off is rejected as stale.
- **Storage:**
  - IR-13: import is not crash-safe, so "all-or-nothing" is overstated.
  - IR-14: the unreadable-data state is never shown.

---

## B. Findings register

**Status key:**
- *New*: not recorded in any earlier review document.
- *Previously reported*: the earlier source is cited.
- *Raised*: reported earlier at a lower severity.

Every row has all the required fields in its source report. Below, the High findings are given in full and the rest in condensed form.

### IR-01 — High — An automatic re-time restarts a night stopped or changed with the physical remote
- **Sources:** X-1, RT-1. Lead-verified ✔. **Status:** new; contradicts R14 and acceptance N08.
- **Where:**
  - `app/.../night/NightActions.kt:336-354` (`retryRetimeLocked` calls `apply(prepareRetime…)`);
  - `NightActions.kt:480-482` (`observeStatus` returns early while the lock is held);
  - `core/.../night/NightRunner.kt` (`prepareRetime`/`apply` never read device status).
- **Root cause:**
  - Takeover is detected only reactively, from status notifications. Overnight the link is dropped 5 s after the last holder (`LinkSessions`), so a physical Off while disconnected is never observed.
  - The re-time path checks local identity, deadline and confidence, then rewrites and starts the slot without comparing a fresh device status against the expected programme step.
  - `takeBack()` (`NightActions.kt:650-665`) already has the right check, but re-time doesn't use it.
- **Failure scenario:**
  - At 02:00 the user turns the BedJet off with the remote while the phone is asleep and disconnected.
  - At 05:30 the phone alarm moves, so a re-time is queued and retried.
  - The app connects, uploads and starts the programme. The BedJet turns back on against the user's choice.
  - Reapply, override and resume share the same gap.
- **Evidence:** the retry body goes `connected { val result = apply(graph.runner().prepareRetime(n, …)) }` with no `freshStatus()` comparison. `grep status core/.../NightRunner.kt` finds no status read.
- **Minimal fix:** inside `connected {}` and before any automatic rewrite (re-time, reapply, override, resume), read `freshStatus()` and compare `programStep`, mode and temperature with `n.activePlan(...).stepAt(now)`, the same test `takeBack` uses. On a mismatch, mark the night UNKNOWN with `runtime_takeover`, clear the re-time, record a REMOTE_CHANGE event and do not write. Factor the comparison into one helper shared with `takeBack`.
- **Regression tests:** in `RuntimeDeliveryTest` (Robolectric), using Demo or FakeRadio:
  1. Confirmed night → set unit status to STANDBY directly, with no notification observed → submit an alarm change → run the retry → assert no slot write or start, the night is UNKNOWN, the reason is takeover, and the retry is cancelled.
  2. The same, with a manual heat run on the unit.
  3. Reapply and override variants.
- **Affected tasks:** 3 (runner contract), 4 (runtime), 5 (the UI must show this takeover state), 7 (acceptance N08).

### IR-02 — High — After Forget or a target change, an old connect retry can attach the old BedJet; commands and originals hit the wrong unit
- **Sources:** X-2, BLE-2. Lead-verified ✔. **Status:** raised. A Task 1 deferred minor in `sdd-ledger.md` ("disconnect not serialised with connect") recorded the race, but not the wrong-unit consequence.
- **Where:**
  - `app/.../ble/GattLink.kt:69-88` (the address is read once at :74 before the retry loop; connect returns early at :72 if any session is ready);
  - `GattLink.kt:213` (attach success doesn't compare against `target()`);
  - `ensureConnected()`/`live()`;
  - `App.kt:72-74` (statuses are attributed to the current target, not the session address);
  - `LinkSessions.reconnect`/`holdForScreen`/timed release, which reconnect without the NightActions lock.
- **Root cause:** the transport treats "a ready session exists" as "connected to the target". `NightActions`' target check compares settings strings, not the physical session address.
- **Failure scenario:**
  - The user switches from BedJet A to B while A is in its connect backoff.
  - A new command for B calls `connect()`, which sets `wanted = true`. The old loop then attaches A.
  - `backup(B, slot)` reads A's slots and records them as B's originals. The first original is kept for good, so B's restore source is permanently wrong.
  - The programme is written to and started on A, the bed the user just said not to use.
- **Minimal fix:**
  - Recompute `DeviceId.address(target())` on every retry and abort if it has changed.
  - In `connect`, `ensureConnected` and `live`, treat a session whose `address` differs from the current target as not connected: retire it and reconnect.
  - Tag each published status with its session address, and have `observeStatus` and `App` drop statuses from any other address.
  - Make `selectTarget` await the link's disconnect, including any in-flight connect job, before saving the new target.
- **Regression tests:**
  1. `GattLinkTest` with `FakeRadio`: A fails its first attempt; the target changes to B during backoff; `connect()` is called. Assert no `radio.connect("A")` after the change and that the session address is B.
  2. `LinkSessionsTest`: a screen holder reconnects during a target switch.
  3. `RuntimeOwnershipTest`: after the switch, `backup(B, …)` never reads through an A session.
- **Affected tasks:** 1 (transport), 2 (originals binding), 4 (target check), 5 (the setup flow must not chain an owned operation straight after `selectTarget` until this is fixed).

### IR-03 — High — An unreadable `slot-backups.json` makes the app record its own programme as the user's original
- **Source:** STO-1. Lead-verified ✔. **Status:** new; defeats R19.
- **Where:**
  - `app/.../data/Repository.kt:93` (`load(SLOT_BACKUPS…).orEmpty()`);
  - `Repository.kt:181-206` (`recordOriginalSlot`/`originalsFor` don't check the unreadable state);
  - `NightActions.kt:180-185` (`backup` reads the slot and records it);
  - `Repository.kt:257-259` (restore merge keeps the current record).
- **Root cause:**
  - The corrupt file is correctly quarantined, but the in-memory list becomes empty and slot writes are not gated on it.
  - `backup()` then treats each slot as never backed up. It reads the slot, which usually holds last night's Nocturne programme, and records that as the original.
  - Only charger auto-start checks `unreadable`.
- **Failure scenario:**
  - Storage corruption, or a crash mid-rename on a device without atomic rename guarantees, damages the backups file.
  - The next Start silently records the app's programme as the user's own.
  - "Restore original" can never bring back the user's programme. Restoring a snapshot doesn't help either, because the current record wins the merge.
- **Minimal fix:**
  - While `SLOT_BACKUPS` is in `unreadable`, make `recordOriginalSlot` and `originalsFor` throw a typed error, so `NightRunner` and Boost refuse before any write, as they already do for a failed backup.
  - Surface that state (see IR-14).
  - On restore, prefer the snapshot's record over a record created after the corruption.
- **Regression tests:** in `RepositoryTest`, write invalid JSON to `slot-backups.json`, construct the repository, and assert `recordOriginalSlot` throws and nothing is written. In `RuntimeOwnershipTest`, Start with a corrupt backups file is REFUSED with no `store`.
- **Affected tasks:** 2, 4, 5 (unreadable banner).

### Medium

| ID | Sources | Location | Problem and user-facing failure | Minimal fix | Regression test | Tasks | Status |
|---|---|---|---|---|---|---|---|
| IR-04 | BLE-1 | `ble/GattLink.kt:114-117, 306-310` | `prepareForStart` forgets the program progress in only some paths. A full-status notification with `extra` (likely at MTU 247), a remainder read triggered by a head arriving after prepare, or a reconnect before the start write can still deliver the old `programStep`. A start during step 1 is then CONFIRMED from the unit's previous state, or an earlier refusal fails a good start. | A per-session `startPending` flag, set under the operations lock in `prepareForStart` and when the start is sent, and cleared after the start write completes. While it is set, `receiveStatus` strips `extra`. | `GattLinkTest` cases: a full-status notification between prepare and start; a head notification followed by a remainder read; a reconnect between prepare and send. Assert the controller doesn't confirm. | 1, 3 | New. R06 is only partly closed. |
| IR-05 ✔ | X-3, RT-4 | `core/.../NightRunner.kt:184-186`; `NightActions.kt:547-552` | A failure **before any write** (the backup read or `recordOriginalSlot` throws, or the deadline expires during backup) marks the still-running confirmed night FAILED. Wake notifications stop, takeover detection stops, re-time is refused, and Keep tracking only accepts UNKNOWN, so the user can't recover. | If nothing was written and a previous night exists, republish it unchanged and return a FAILED outcome without changing its confidence. | A backup throw during a re-time of a confirmed night: the night is still CONFIRMED, the wake alarm is still scheduled, and the outcome is FAILED. | 3, 4 | New |
| IR-06 ✔ | X-4, RT-7 | `night/RuntimeState.kt:20-21`; no `slotWrite = null` anywhere in `app/src/main` | A stale FAILED or UNKNOWN `slotWrite` from an old pin, restore or Boost is never cleared. It overrides `NightActions.status` for every later night, permanently blocks charger auto-start (`ChargerRun`'s `uncertain` check), and makes `reconcile` mark later nights "interrupted". | Clear `slotWrite` when a later operation succeeds, when its night ends, or when the user acknowledges it. Never let it outlive its own night or operation. | Fail a Boost before any write, then start the next night: status is CONFIRMED, charger eligibility is true, and a later `reconcile` doesn't touch it. | 4, 6 | New |
| IR-07 | X-5, RT-5 | `NightActions.kt:278-300`; `service/BedJetService.kt:51` | Snoozing or dismissing the phone alarm after the wake phase has begun fires NEXT_ALARM_CLOCK_CHANGED. The app posts "The wake-up change expired" and then "The command did not finish", every morning, with nothing actually wrong. | Ignore alarm observations once `now` is at or after `wakeUpStart`. Treat "unchanged" or "expired" as quiet outcomes; don't post them as service errors. | An alarm change after `wakeUpStart` posts no notification and doesn't start the service. An overnight timer change posts nothing. | 4 | New |
| IR-08 ✔ | RT-2 | `NightActions.kt:208-225` | Snooze and Extend publish `confidence = CONFIRMED` as soon as `runManual` has sent its packets, with no status readback. Manual nights are also excluded from takeover detection (`:491`). | Mark the night PENDING until a fresh status matches mode, temperature and timer, and confirm only then. Keep takeover detection on for manual nights by comparing against their manual step. | A link that acknowledges but reports a different mode: the night is not CONFIRMED. | 4 | New |
| IR-09 ✔ | RT-3 | `NightActions.kt:196-205` | A Remote temperature, fan or timer change during a night republishes the pre-command CONFIRMED night (`else if (night != null) publishNight(night)`). The user's own in-app change then shows up as a physical-remote takeover on the next status, or a pending re-time can overwrite it. | After a manual change on a programmed night, either switch to manual tracking with the new step or record the expected deviation, so it isn't seen as a takeover. Clear any pending re-time. | Remote temperature change → next status: no takeover event, the re-time is cancelled. | 4, 5 | New |
| IR-10 | NGT-1 | `core/.../NightRunner.kt:193-200`; `NightActions.kt:323, 350` | The final check before start compares `now` only with the request deadline. For a re-time that deadline is the *old* night's wake-up start, possibly hours away. A re-time delayed by reconnects past the *new* plan's clock-time step end still starts. The BedJet then holds the Middle step until that clock time *tomorrow*. | At the final check, also require `now` to be before the new plan's `wakeUpStart` and before every `endsAtClockTime` step end minus its 10-minute margin. Alternatively, give each re-time attempt a short deadline and rely on re-prepare. | Core: prepare a re-time, advance the clock past the new step end but not the old deadline, then `apply` → refused, no start byte sent. | 3, 4 | New |
| IR-11 | X-6, RT-11, NGT-2 | `NightActions.kt:65-91`; `GattLink.send` | The 90 s budget includes time spent queued. There is no admission check once the lock is acquired, and `GattLink.send` can reconnect for up to about 47 s after the final check. A request that gets the lock late starts an upload, is cancelled mid-write and leaves the BedJet UNKNOWN because of the app's own timeout. | On acquiring the lock, refuse the request if its remaining budget is below what a write-and-start needs (about 30 s). Send the start without reconnecting, and evaluate the final check under the link's operations lock. | Hold the lock for 80 s with a fake owner; the queued Start is refused with no store. | 1, 3, 4 | Partly reported: `task-4-review.md` covered the deadline including the queue, but not admission. |
| IR-12 | RT-6 | `widget/WidgetUpdater.kt` (started only by MainActivity) | Widgets re-render only after MainActivity has started `WidgetUpdater` in the current process. After a night is started, re-timed or snoozed in the background, the widget holds an old identity and its **Off** returns STALE, although the plan says Off is always executable. | Treat Off as identity-free (target-bound only), and/or refresh widgets from the repository's active-night publication in `App`, not from MainActivity. | Background re-time → widget Off → the BedJet is turned off (outcome ACCEPTED). | 4, 6 | New |
| IR-13 | STO-3 | `Repository.kt:260-266`; `SnapshotStore.kt:37-41` | Import writes four separate atomic files. Process death or an `Error` between them leaves a mix of new and old files, with no journal and no recovery at next start. Rollback itself can fail, for example when storage is full. "Validated, all-or-nothing import" (R22, HANDOVER) is overstated. | Write a durable `restore-pending.json` (snapshot id plus target set) before the first overwrite, delete it last, and complete or roll back on the next start. Sync the directory after renames (STO-7). | A fault-injecting writer kills the import after file 2; a new Repository on the same dir recovers to entirely old or entirely new. | 2 | Raised from a deferred minor in the ledger |
| IR-14 | STO-2 | `Repository.unreadable`/`dismissUnreadable()` have no callers in the UI | The unreadable-data state is never surfaced. Charger auto-start stays blocked with no reason shown (combined with IR-03, slot writes would carry on silently). | A Task 5 banner with View, Dismiss and Restore actions. Until then, include it in diagnostics and the charger refusal reason. | A UI test: an unreadable file shows the banner, and Dismiss clears it. | 2, 5 | Previously reported (`sdd-ledger.md:40`); still open. R23 is marked auto-verified only at the repository level. |
| IR-15 | STO-4 | `Repository.kt:283-294` | Import and export file I/O through the system file picker runs on the single serial storage writer. A slow cloud provider stalls night-state writes and the receivers' 8 s save budgets. | Read and write the external file outside `serially`; enter the writer only to apply validated data. | A slow `ContentResolver` stub: a concurrent `updateRuntime` finishes within 1 s. | 2, 4 | Raised from a deferred minor |
| IR-16 | STO-5 | Repository import merge; `adopts()` | Imported slot backups are merged into the live originals without range checks. Imported v1 or "unknown"-device originals are adopted by whichever BedJet is selected, so that unit's real slots are never backed up, and Restore writes the imported programme. | Validate imported programmes with the same step limits as the codec. Never adopt unknown-device originals that came from an import; keep them read-only, labelled "from backup". | Import a v1 backup with an unknown-device original, select BedJet B, then Start: B's real slot is recorded. | 2 | Partly reported (`sdd-ledger.md:34`); adoption is new |
| IR-17 | NGT-5 | `ui/AppViewModel.kt:107-117` | The Tonight plan card and headline use `WakeResolver.resolve`, which ignores the tonight-only override. Start uses `NightPreview.forStartNow` with the override. With an override of 06:00 and an alarm of 07:00, the screen shows 07:00 while the night is programmed for 06:00. | Drive the card from `NightPreview.forStartNow` (one preview policy). | A ViewModel test: with an override set, the preview wake time equals the override. | 3, 5 | Previously reported (R36); still in code |

### Low

| ID | Sources | Problem (one line) | Fix in short | Status |
|---|---|---|---|---|
| IR-18 | BLE-3 | `AndroidBedJetLinkTest` passes `DemoBedJet` as `preparedLink`, so the production `engine` path is never tested. | Test through `FakeRadio` with a full status. Remove the seam. | New |
| IR-19 | BLE-4 | A timed release ends on one wall-clock check after a monotonic delay. If the clock jumps backwards, `released` stays true and the screen never reconnects. | Loop until the deadline has passed. | New |
| IR-20 | NGT-3 | A start that straddles a Europe/London clock change uses the plan's offset for the clock-time end but a fresh `now()` for the clock sync, so the wake-up is an hour out. | Sync in the plan's offset, or refuse when the offsets differ. | New |
| IR-21 | NGT-4 | A fresh START refused at the final check is reported UNKNOWN, giving a stuck night with no expiry, although the core knows no start was sent. | FAILED with a "slot rewritten" reason for a fresh start. | New |
| IR-22 | X-7 | `releaseForOtherPhone` → `advanceGeneration` wipes the UNKNOWN reason and the 15-minute resume offer. It is triggered by a Lull peer, or by any device advertising the UUID derived from the BedJet's name. | Keep the reason and offer across a hand-over release. | New |
| IR-23 | X-8 | An interrupted or failed first START has no history record, so its error, takeover and stop events are dropped. | Create the record before the first write. | New |
| IR-24 | RT-8 | `BedJetService` can stop without calling `startForeground` (missing hand-off, revoked permission), which may crash the process. | Always call `startForeground` before `stopSelf`. | New, platform-dependent |
| IR-25 | RT-9 | A background service start from `AlarmChangedReceiver` posts an error notification when the app isn't battery-exempt. | Persist the observation only, and let the retry alarm do the work. | New, platform-dependent |
| IR-26 | RT-10 | The wake-up (id 2) and Resume (id 7) notifications aren't withdrawn when they stop being true. | Reconcile them like the quiet notification. | New |
| IR-27 | RT-12 | `reconcile` from the time and zone receivers waits on the mutex with no time limit, which gives a false error. | Persist a reconcile request and run it under the owner. | New |
| IR-28 | STO-6 | An import during a night removes tonight's history record. | Keep the current record during an import. | New |
| IR-29 | STO-7 | No directory sync after snapshot writes and renames; leftover `.tmp` files are never deleted. | Sync the directory and clean up `.tmp` files at start-up. | Previously reported |
| IR-30 | STO-8 | Restore overwrites tonight's override and choice; a v1 selected profile isn't migrated. | Exclude tonight-scoped fields; run the migration. | New |
| IR-31 | STO-9 | v1 never recorded empty slots, so the first write after upgrade can record the app's own old programme as the original. | Label migrated originals as "uncertain". | New, uncertain |
| IR-32 | STO-10 | Device-to-device transfer copies device-local runtime files, not just the secrets. | Exclude `runtime.json`, `active-night.json`, `automation.json`, `unreadable.json` and `*.corrupt-*`. | New |

### Previously reported, verified fixed in code

| Item | Fix (file:line) | Test |
|---|---|---|
| Task 1: adapter forwards `prepareForStart` | `ble/AndroidBedJetLink.kt:48`; production graph `App.kt:51, 57` (`preparedLink` is null) | `AndroidBedJetLinkTest` only tests the seam (IR-18) |
| Task 4 #1: Boost replay after process death | `NightActions.kt:531-535, 447-452, 316-320, 345` | `RuntimeOwnershipTest:54-74` |
| Task 4 #2: wake/end delivery past the receiver budget | `Receivers.kt:16-19`; `NightActions.kt:407-442` | `RuntimeServiceTest:50-113` |
| Task 4 #3: clearing an override with an equal wake time | `NightActions.kt:574-582, 594-602` | `RuntimeDeliveryTest:36-50` |
| Task 4 #4: stale quiet notification | `NightActions.kt:690-698`; `Notifications.kt:78-79`; `App.kt:66-70` | `RuntimeDeliveryTest:52-121` |
| Task 4 #5: resume notification after replacement | `NightActions.kt:146-151, 164-165`; `ShortcutActivity` | `RuntimeDeliveryTest:122-155` |
| Task 3: the 06:34 → 24.5 h night is refused; 1–16 h bounds | `NightRunner`/`NightPreview` (see `review-night.md`) | core tests |
| Task 3 fix `694425b`: final check after `syncClock` and `prepareForStart` | `BedJetController.kt:66` ordering | `ControllerTest.startingPreparesTheLinkAfterTheClock…` (but see IR-10 and IR-11) |

---

## C. Integration assessment

### What is committed versus documented

- **Committed and merged:** Tasks 1–4 and 6 in code (`65f9d5c`, `69b9973`, `a286f62`). Task 5 exists only on Astra's local branch `astra/nocturne-task5` (not on origin) and in `docs/review/task-5-implementation.md`. Task 7 has not started.
- **Documented but not true in code:**
  - "Validated, all-or-nothing import" (IR-13).
  - R23 "auto-verified", which holds for the repository but not end to end (IR-14).
  - R14 "takeover never overridden", which holds for in-app Off only (IR-01).
  - R06 "only a fresh status confirms", which is partial (IR-04).
- **The register and handover are otherwise accurate** about the absence of hardware evidence.

### Contract changes Astra must know (Tasks 5–7)

1. **`NightActions.status` is not the night's confidence.** It is overridden by `RuntimeState.slotWrite`, which is never cleared (IR-06). The UI must read `activeNight.confidence` for the night and treat `slotWrite` as a separate, operation-scoped banner. Do not build on the current override behaviour.
2. **All UI hardware work goes through `BedJetService.runOwned`**, with the target and night identity captured **at the tap**, not at execution. The runtime reviewer confirmed that no production code outside `NightActions` writes to the BedJet directly. Keep it that way, and add no second owner.
3. **Setup and target selection.** Until IR-02 is fixed, Task 5's setup flow must not chain an owned operation (test connection, read slots, first Start) straight after `selectTarget`/Forget. If it does, it can run against the previous unit.
4. **Recovery controls.** Keep tracking only accepts UNKNOWN. After IR-05, a pre-write failure leaves the night CONFIRMED with a FAILED outcome. Design the banner around the *outcome* rather than inferring it from confidence.
5. **Unreadable data.** Task 5 owns the banner (IR-14). After IR-03, Start and Boost will refuse while backups are unreadable, so the banner must offer Restore or Dismiss.
6. **Tonight preview.** Use `NightPreview.forStartNow` for every wake time shown (IR-17).
7. **Widgets (Task 6).** Off should be target-bound only (IR-12), and widget refresh must not depend on MainActivity being alive.
8. **Charger auto-start (Task 6).** It is currently blocked for good after any failed slot operation (IR-06), with no reason shown (IR-14).
9. **Lull hand-over (Task 6).** A release wipes the UNKNOWN reason and resume offer (IR-22).
10. **Acceptance checklist (Task 7).** Add these supervised cases:
    - physical-remote Off while the phone is **disconnected**, then move the alarm;
    - switch BedJet while the old unit is retrying;
    - MTU 247 full-status timing around Start (IR-04);
    - a start across a clock change.

---

## D. Verification report

| Check | Result |
|---|---|
| Baseline | `git fetch`; review worktree at `origin/claude/nocturne-hardening` = `7fffabc`; working tree clean before and after the run |
| Command | `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` (fresh worktree, this cloud host) |
| Outcome | **BUILD SUCCESSFUL** in 7 min 4 s; 59 actionable tasks, all executed |
| Core tests | 138 run, 0 failures, 0 errors |
| App tests | 194 run, 190 passed, 4 skipped (screenshot capture, by design), 0 failures, 0 errors |
| Debug APK | `app-debug.apk`, 65,604,270 bytes, which matches the size in the handover |
| Lint | 0 errors, **23 warnings**: 11 UseKtx, 7 GradleDependency, 2 AndroidGradlePluginVersion, 1 ObsoleteSdkInt, 1 OldTargetApi. The handover records 14; the difference is mostly the online dependency and AGP version checks, which depend on network access. Not warning-free. |
| Static review | Five reviewers on separate domains, plus the lead re-checking every High finding and the Medium findings marked ✔ |
| Hardware | **None.** No Samsung, no BedJet 3. Hardware-dependent items: IR-04 timing at MTU 247; BedJet behaviour when the running slot is overwritten; how often reconnects mid-transaction happen (IR-10, IR-11); Samsung background limits (IR-24, IR-25). |

The automated suite passes, but it does not cover IR-01 to IR-03: every takeover test uses in-app Off, every target test switches while idle, and no test starts from a corrupt backups file.

---

## E. Recommended fix order (work packages)

Each package should be test-first: write the regression tests named above, then make them pass. Each should get a scoped re-review and the full gate. None of these duplicates Astra's Task 5 UI work, apart from the banner touch-points in P5, which belong to Task 5.

| # | Package | Findings | Why this order |
|---|---|---|---|
| P1 | **Never override the user** (runtime and core) | IR-01, then IR-09 and IR-08 | The highest-harm defect. A shared "fresh status matches the plan" helper serves all three. |
| P2 | **Right BedJet only** (transport) | IR-02, IR-04, IR-11 (transport half), IR-19, IR-18 | All in `GattLink`/`LinkSessions`; one transport fix round. Must land before Task 5's setup flow ships. |
| P3 | **Originals integrity** (storage) | IR-03, IR-16, IR-31, then IR-13, IR-15, IR-29 | IR-03 is a blocker. Import crash-safety and the writer split share the restore code path. |
| P4 | **Truthful outcomes and timing** (runtime and core) | IR-05, IR-06, IR-10, IR-11 (admission half), IR-21, IR-23, IR-20 | Changes outcome and confidence semantics that Task 5's recovery UI consumes. Land before Task 5's review if possible, or tell Astra about the semantic change. |
| P5 | **Noise and presentation** | IR-07, IR-12, IR-17, IR-26, IR-14 (Task 5 banner), IR-22 | User-facing polish. IR-14 and IR-17 can be done inside Task 5. |
| P6 | **Low storage and platform items** | IR-24, IR-25, IR-27, IR-28, IR-30, IR-32 | Before Task 7's release candidate. |

**Suggested sequencing with Astra:**
- P1–P3 are independent of Task 5's UI and can run in parallel worktrees now.
- P4 should land before Task 5's independent review, so the recovery UI is reviewed against the final semantics.
- P5 and P6 fold into Task 5 and the Task 7 fix wave.

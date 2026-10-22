# Nocturne: independent-review fixes and Task 5 recovery

- **Branch:** `claude/nocturne-ir-fixes`, from `origin/claude/nocturne-hardening` @ `7fffabc`. Push only this branch.
- **Do not update** `claude/nocturne-hardening` or `claude/nocturne-independent-review`.
- **Spec:** the independent review `docs/review/independent-review-tasks-1-4.md` (IR-01 to IR-32), its per-domain evidence in `docs/review/independent/review-*.md` (BLE-n, STO-n, NGT-n, RT-n, X-n), and the hardening plan `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`. Its Global constraints, the Task 5 brief and the "Additional Task 5 review requirements" rulings still bind.
- **Task 5 recovery:** Astra's Task 5 commits (`7117c85`, `0691f86`) were never published and are not recoverable from GitHub. Task 5 is rebuilt here from the Task 5 brief and Astra's report `docs/review/task-5-implementation.md`, which describes the verified behaviour, exact strings and test names.

## Global constraints (copied into every brief)

- **Build and tests:**
  - Gradle gate: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. `local.properties` already exists in the worktree.
  - Core is pure Kotlin with JUnit 5. App tests are Robolectric (JUnit 4, `@Config(sdk = [34])`). Under Robolectric, advance the main looper with `shadowOf(Looper.getMainLooper()).idleFor(...)`.
- **BedJet 3 only.** 3 slots, working slot index 2 by default, at most 32 steps, the protocol limits unchanged. No hardware or Samsung verification claims anywhere: in code comments, the register or reports.
- **Keep the current UI design:** Midnight Ink, Daylight, Simple view, the five tabs, cards and curve. Extend locally using progressive disclosure. UK English, plain words. Show pending, failed and unknown truthfully.
- **Time:** use `Graph.now()` / `Graph.clock` in app code, and an injected `() -> ZonedDateTime` in core. Never call `ZonedDateTime.now()`.
- **Device identity:** `DeviceId` strings (`demo` or `ble:<MAC>`). `Settings.deviceId()` is the effective target.
- **One command owner:**
  - All hardware work goes through `NightActions` (its mutex) or `BedJetService.runOwned`. No new UI, receiver or widget code calls `BedJetController` or the link directly.
  - Capture the target and night identity at the tap.
  - Read `docs/review/task-4-implementation.md` for the frozen runtime contracts before changing NightActions.
- **Test discipline:** TDD for every behaviour change.
  1. Write the regression test.
  2. Run it and record the RED output, failing for the right reason.
  3. Implement.
  4. Record the GREEN output.
  - Before fixing an IR finding, verify it reproduces on the current code. If it doesn't, record "not reproduced" with evidence rather than changing code.
  - Never weaken an existing test. If one must change, explain why in the report.
- **Commits:** coherent, author `Willzy12h <29470889+Willzy12h@users.noreply.github.com>`. Message bodies end exactly with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XqCZrCNi6uzceBxEgZxBsr
  ```
  Do not push.
- **Register:** for each IR-nn you close, add or update a row in `docs/review/register.md` under a new section "Independent review (IR)". Fill in status, commit and tests, in the same commit as the work.
- **Before reporting DONE,** run the full Gradle gate once and report the exact counts (core, app passed/skipped, lint errors/warnings).

---

### Task 1: IR-01 — never override the user (automatic rewrites check the device first)

**Findings:** IR-01 (X-1, RT-1). Evidence: `docs/review/independent/review-cross.md` (X-1) and `review-runtime.md` (RT-1).

**Files:** `app/.../night/NightActions.kt`, runtime strings if needed, app tests under `app/src/test/.../night/` or `service/`. Core `NightRunner` only if a status-aware hook is unavoidable; prefer keeping the check in NightActions.

**Behaviour:**
- Before any **automatic or programme-preserving rewrite** of the running night writes to the BedJet, read a fresh device status and compare it with what the active night expects at `now`. This covers:
  - re-time retry (`retryRetimeLocked`) and any immediate re-time path;
  - reapply after a saved-profile edit;
  - tonight-only wake override set/clear when it rewrites;
  - resume;
  - any other path that rewrites the slot while a night is CONFIRMED.
- The comparison checks programme step (`extra.programStep == step index + 1`), mode, and, for temperature modes, target temperature. Use one shared helper and make `takeBack()` use it too, with no behaviour change to `takeBack`.
- **On a mismatch** (the BedJet is off, in another mode, or running another step or a manual run):
  - do not write;
  - publish the night as `Confidence.UNKNOWN` with reason `runtime_takeover`;
  - clear the pending re-time and cancel its retry alarm;
  - add a `REMOTE_CHANGE` event;
  - return a non-success outcome that the UI and notifications present truthfully, using the existing takeover copy.
- **Exception:** a user-initiated Start that replaces the night, or explicit confirmations, stay as they are. This rule is about not silently undoing a physical-remote change.

**Tests (Robolectric, through the real `NightActions` with the Demo link or FakeRadio):**
1. Confirmed night, then the unit is set to STANDBY directly, without the app observing a status. Submit an alarm change and run the retry. Assert: no slot write or start command, the night is UNKNOWN, the reason is takeover, the retry is cancelled, and a REMOTE_CHANGE event exists.
2. The same with a manual heat run on the unit.
3. Reapply after a profile edit while taken over: refused, no write.
4. Positive control: a matching status still re-times successfully.
5. `takeBack` behaviour is unchanged (existing tests stay green).

### Task 2: IR-02 — commands reach only the chosen BedJet

**Findings:** IR-02 (X-2, BLE-2). Evidence: `review-cross.md` (X-2) and `review-ble.md` (BLE-2).

**Files:** `app/.../ble/GattLink.kt`, `GattPort.kt` if needed, `night/LinkSessions.kt`, `App.kt` (status attribution), `night/NightActions.kt` (`selectTarget`/Forget wait), app tests under `ble/` and `night/`.

**Behaviour:**
- **Every attach attempt targets the current choice.** `GattLink.connect` recomputes `DeviceId.address(target())` on each retry attempt. If the target has changed (or is null), it stops retrying the old address.
- **A session for another address doesn't count as connected.** `connect()`, `ensureConnected()` and `live()` treat a session whose `address` differs from the current target's address as not connected: retire and close it, then connect to the current target. A ready session for the old unit must never satisfy a command for the new one.
- **Statuses are attributed to their source.** Each published status (or the link) exposes the session address that produced it. `App.kt`'s status observer and `NightActions.observeStatus` ignore statuses from an address other than the current target.
- **Changing target waits for the old link.** `selectTarget` (including Forget / null target) disconnects the link and waits until any in-flight connect job has finished or been cancelled, then saves the new target. This stays inside the existing owned block.
- **Background reconnects.** `LinkSessions.reconnect`, `holdForScreen` and the timed-release expiry must not reconnect to an address other than the current target. This follows from the GattLink rule; add a test.
- **Keep the Task 1 guarantees:** stale callbacks rejected, timeout and cancel retirement, bounded same-address retry for a *stable* target, verified-name metadata bound to the session address.

**Tests:**
1. `GattLinkTest` with FakeRadio: target A fails its first attempt; during the backoff the target changes to B; `connect()` is called. Assert no `radio.connect(A)` after the change, the final session address is B, and A's port is closed.
2. A ready session on A, then the target becomes B: the next `send` connects to B, not A.
3. A status from an A session after switching to B is not observed as B's status.
4. `LinkSessionsTest`: a screen holder reconnect during a target switch only connects to B.
5. `RuntimeOwnershipTest` (or similar): after a switch, `backup(B, slot)` never reads through an A session.

### Task 3: IR-03 — an unreadable backups file never yields fake originals

**Findings:** IR-03 (STO-1). Evidence: `review-storage.md` (STO-1).

**Files:** `app/.../data/Repository.kt`, `night/NightActions.kt` (refusal mapping and copy), runtime strings, app tests under `data/` and `night/`.

**Behaviour:**
- **While unreadable:** while `slot-backups.json` is listed in `Repository.unreadable`, `recordOriginalSlot` and `originalsFor` throw a typed error (for example `SlotBackupsUnreadable`). Every slot write (Start, re-time, Boost, Pin, Restore original, resume) then refuses **before any write**, with a truthful UK English message: "Your saved BedJet slot backups couldn't be read. Restore a snapshot or dismiss the warning before changing BedJet slots."
- **A refusal must not change confidence.** A running night stays as it was. This is consistent with IR-05, which Task 5 generalises, so implement the narrow case here.
- **Dismiss** (`dismissUnreadable()` for that file) accepts the loss explicitly. After it, writes proceed and originals are recorded afresh.
- **Restore from a snapshot:** the snapshot's slot-backup records win over records created after the corruption, for the same device and slot.

**Tests:**
1. `RepositoryTest`: invalid JSON in `slot-backups.json`, then construct the Repository. `recordOriginalSlot` throws and nothing is written. After `dismissUnreadable`, it succeeds.
2. NightActions: Start with a corrupt backups file is REFUSED, with no `store`/send to the link, and the active night is unchanged.
3. Restore-merge precedence test.

### Task 4: Transport remainder (IR-04, IR-11 transport half, IR-18, IR-19)

**Evidence:** `review-ble.md` (BLE-1, BLE-3, BLE-4) and `review-night.md` (NGT-2).

**Files:** `ble/GattLink.kt`, `ble/AndroidBedJetLink.kt`, `night/LinkSessions.kt`, tests under `ble/` and `night/`.

**Behaviour:**
- **IR-04: only a fresh status confirms a start.** Use a per-session `startPending` flag:
  - set it under the operations lock in `prepareForStart`, and when a start command is written;
  - clear it after the start write completes;
  - while it is set, `receiveStatus` strips (or ignores) `extra.programStep` and notification data from full-status notifications and remainder reads.
  - A status that arrives *after* the start write is accepted.
  - A reconnect between prepare and send must not let the new session's first report confirm.
- **IR-11, transport half:** a start command is sent without reconnecting. If no live session exists at send time, fail with `NotConnected` rather than reconnecting.
- **IR-18:** test the production `engine` path of `AndroidBedJetLink.prepareForStart` through FakeRadio, with no DemoBedJet seam. Remove the `preparedLink` test seam if nothing else needs it.
- **IR-19:** the timed release re-checks the wall clock in a loop until the deadline has truly passed, so a backwards clock jump can't strand `released = true`.

**Tests:**
- full-status notification between prepare and start;
- head notification followed by remainder read;
- reconnect between prepare and send;
- adapter forwarding through FakeRadio;
- backwards clock jump during a timed release.

### Task 5: Truthful outcomes and timing (IR-05, IR-06, IR-08, IR-09, IR-10, IR-11 admission, IR-20, IR-21, IR-23)

**Evidence:** `review-cross.md` (X-3, X-4, X-6, X-8), `review-runtime.md` (RT-2, RT-3, RT-4, RT-7, RT-11) and `review-night.md` (NGT-1, NGT-3, NGT-4).

**Files:** `night/NightActions.kt`, `night/RuntimeState.kt`, `core/.../night/NightRunner.kt`, `NightChanges.kt`, `ActiveNight.kt` (only if needed), `core/.../link/BedJetController.kt` (IR-20), `platform/ChargerRun.kt` (only for the IR-06 eligibility read), strings, core and app tests.

**Behaviour:**
- **IR-05:** when a rewrite fails *before any hardware write* (backup read, `recordOriginalSlot` failure, deadline expiry before upload, the IR-03 refusal) and a previous night exists:
  - republish that night unchanged (same confidence, wake alarm still scheduled);
  - return a FAILED outcome with the reason.
- **IR-06: `RuntimeState.slotWrite` is operation-scoped.**
  - Clear it when a later slot operation succeeds, when the user acknowledges it, or when its night ends.
  - `NightActions.status` must not let a stale `slotWrite` override a later confirmed night.
  - `ChargerRun`'s uncertainty check uses only a current, unresolved `slotWrite`.
  - `reconcile` must not repeatedly flip a night to "interrupted" because of an old pre-write failure.
- **IR-08:** Snooze and Extend (`timedManual`) publish the night as pending (not CONFIRMED) until a fresh status matches the manual mode, temperature and timer, then confirm. Takeover detection stays active for manual tracking, comparing against the manual step.
- **IR-09:** a Remote manual change (temperature, fan, timer) during a programmed night must not republish the old CONFIRMED programmed night. Switch to manual tracking with the new step (as `timedManual` does, but pending until fresh status confirms it). Cancel any pending re-time.
- **IR-10:** the final check before start also requires `now` to be before the new plan's `wakeUpStart`, and before every `endsAtClockTime` step's end minus 10 minutes.
- **IR-11, admission:**
  - On acquiring the command lock, refuse a request whose remaining budget is below 30 seconds, before any write. The refusal says the command waited too long; nothing changed.
  - The UI's outer `runOwned` timeout must not be shorter than the inner owner budget plus margin.
- **IR-20:** sync the BedJet clock in the plan's UTC offset, or refuse the start when the plan's offset differs from the offset at sync time. Choose the refusal if it is simpler, and document the choice.
- **IR-21:** a fresh START refused at the final check, when no previous night exists, is FAILED with "Nothing started; the BedJet slot holds the new night but it was not started", not UNKNOWN.
- **IR-23:** create the history record for a new START before the first hardware write, so its events are kept even when it fails or is interrupted.

**Tests:** one regression per item, using the scenarios in the evidence reports.

### Task 6: Storage durability (IR-13, IR-15, IR-16, IR-28, IR-29, IR-30, IR-31, IR-32)

**Evidence:** `review-storage.md` (STO-3 to STO-10).

**Files:** `data/Repository.kt`, `core/.../store/*` (SnapshotStore, Backup, BackupValidator, Migrations), `AndroidManifest.xml` and `res/xml` backup rules, data and store tests.

**Behaviour:**
- **IR-13: crash-safe import.**
  1. Before the first overwrite, durably write `restore-pending.json` with the snapshot id and the set of files.
  2. Write the files.
  3. Delete the marker last.
  4. On Repository start, if the marker exists, roll back to the pre-import snapshot (or complete the import if all new files are verifiably present; choose rollback for simplicity) and record a notice.
  - A failed rollback must not leave memory and disk disagreeing.
- **IR-29:** after atomic renames, sync the directory where the platform allows. Delete leftover `*.tmp` files at start-up.
- **IR-15:** read and write the external backup file (SAF `ContentResolver` I/O) outside the serial writer. Only applying validated data enters `serially`.
- **IR-16:** validate imported slot-backup programmes with the codec's step limits. Originals from an import with an unknown or legacy device are never adopted by a real BedJet. Keep them as read-only "from backup" records that Restore can offer only when explicitly chosen.
- **IR-28:** an import during an active night keeps tonight's history record.
- **IR-30:** restore doesn't overwrite tonight-scoped settings (tonight choice, tonight override). A v1 selected profile is migrated.
- **IR-31:** originals migrated from v1 are marked "uncertain", so restore copy can say so. Use a narrow flag with a default, plus a validator update.
- **IR-32:** device-transfer rules exclude `runtime.json`, `active-night.json`, `automation.json`, `unreadable.json` and `*.corrupt-*` (as well as the secrets).

**Tests:** process-death import, using a fault-injecting writer that kills after file 2 followed by a new Repository on the same directory; plus one test per remaining item.

### Task 7: Runtime noise and platform edges (IR-07, IR-12, IR-22, IR-24, IR-25, IR-26, IR-27)

**Evidence:** `review-cross.md` (X-5, X-7) and `review-runtime.md` (RT-5, RT-6, RT-8, RT-9, RT-10, RT-12).

**Files:** `night/NightActions.kt`, `night/Receivers.kt`, `service/BedJetService.kt`, `notify/Notifications.kt`, `widget/*`, `App.kt`, `platform/PhoneHandover.kt`, strings, tests.

**Behaviour:**
- **IR-07:** ignore alarm observations once `now >= wakeUpStart` of the active night. "Unchanged" and "expired" re-time outcomes are quiet: no error notification, and no service-level "did not finish" error.
- **IR-12:** widget Off is target-bound only (no night-identity staleness). Widgets refresh from the repository's active-night/runtime publication in `App`, not only when MainActivity has started `WidgetUpdater`.
- **IR-22:** a hand-over release keeps the UNKNOWN reason and the resume offer.
- **IR-24:** `BedJetService` always calls `startForeground` before it can `stopSelf`, or uses the documented safe path, so it can't crash with ForegroundServiceDidNotStartInTimeException.
- **IR-25:** `AlarmChangedReceiver` persists the observation and relies on the retry alarm when a background service start is not allowed. No error notification for that case.
- **IR-26:** the wake-up (id 2) and Resume (id 7) notifications are withdrawn when they stop being true, like the quiet notification.
- **IR-27:** `reconcile` from the time and zone receivers is bounded. Persist the request and run it under the owner, with no false error.

**Tests:** one per item.

### Task 8: Task 5 recovery A — night and runtime UI, feature placements

**Requirements:**
- The Task 5 brief in `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`, the items listed below.
- Astra's verified behaviour in `docs/review/task-5-implementation.md`, the sections named below.
- The UI preflight `docs/review/ui-preflight.md`.

**Files:** `app/.../ui/**` (not ShortcutActivity), `res/values*/**`, UI tests. A minimal `NightActions` addition only where the brief or report names one.

**Behaviour:**
- **U07/U08: outcomes and progress.**
  - The ViewModel uses `viewModelScope`.
  - The running command label and queued count come from the coordinator.
  - One outcome collector presents coordinator outcomes, including externally initiated ones, with no duplicate snackbars.
  - Adopt `BedJetService.runOwned(context, notifyFailures = false) { … }` for origin-owned UI actions. Catch `TimeoutCancellationException` and show it before rethrowing a generic `CancellationException`. Other exceptions are shown once.
- **F16:**
  - Confirmed/pending/unknown indicator on Tonight, driven by `activeNight.confidence` (see the review's contract note 1), with manual tracking shown.
  - Stop tracking with the explanation "This doesn't turn the BedJet off".
  - Keep tracking.
  - Diagnostics export in Settings.
  - Quiet status notification toggle.
- **F02:** "Resume the rest of the night" while the offer lasts, through the shared replacement confirmation.
- **F01 / U02:**
  - "Wake time for tonight only" picker, clear and expiry note, available both idle and running.
  - Temporary choice shows "Tonight only · Follow schedule" with a reset.
  - A screen held open refreshes at the noon expiry.
- **IR-17 / R36:** the Tonight headline and curve both use `NightPreview.forStartNow`, including the tonight-only override, and show a start refusal truthfully.
- **U10:** show "Saved for next night", and pending versus confirmed wake changes.
- **U09:** History shows the new event kinds and revisions.
- **F15:** in the profile overflow, "Keep on BedJet slot…" with slot choice, preview, the duration explanation and restore original. Slot originals are filtered to the selected target, plus legacy unknown.
- **IR-14:** a calm banner, "Some saved data couldn't be read — restore from a snapshot or dismiss", with Restore and Dismiss (`dismissUnreadable()`). Include IR-03's slot-backup case.
- **Task 6 placements** (exact, from the report):
  - `PlatformSettingsSection(model.graph)` in Settings, after Phone setup;
  - `item { HistoryExportButton(model.graph) }` after the History header;
  - `HealthSleepOverlay(model.graph, night)` below each History card's curve;
  - `PhoneHandoverMenuItems(model.graph) { more = false }` in a More overflow available both idle and active; Re-check alarm only when a night exists.

**Tests:** Robolectric per item, following the test names and scenarios in the Task 5 report where they apply.

### Task 9: Task 5 recovery B — setup, BedJet selection, navigation and drafts

**Requirements:** the same sources as Task 8, plus the "Additional Task 5 review requirements" rulings in the hardening plan.

**Behaviour:**
- **F08: first-run setup.**
  - Permission and system-screen re-checks on return.
  - Samsung battery guidance (copy only; no claims).
  - Explicit bounded discovery via `discover()` and selection.
  - A clearly separate "Use demo instead".
  - A supervised coordinator fan check.
  - Durable completion, and reachable again from Settings.
  - With no target, Tonight and Remote show "Choose your BedJet", leading to setup.
- **Atomic real-device selection:** `NightActions.selectTarget(target, useRealDevice = true)`, an explicit option that defaults to false. It saves the real target and `demoMode = false` in one durable settings publication, inside the owned block. It keeps the active-night refusal and the disconnect (and IR-02's wait), and emits one outcome.
- **Forget** says exactly "Forgot this BedJet. Choose a BedJet to connect to.", once, then routes to selection.
- **Navigation:** MainActivity honours and consumes the boolean extra `openTonight` on the first and on new intents, over restored navigation, and never discards a dirty draft (it is parked, with "Resume editing draft").
- **U04:** a SavedStateHandle holds the selected tab and the serialised editor draft and source, surviving recreation and process death.
- **Fixture rulings** from the hardening plan apply where needed.

**Tests:**
- `SetupSelectionTest`: success, active-night refusal, failed persistence, default-caller preservation, invalid real target;
- Forget outcome;
- navigation consume and recreate;
- draft recreation and the parcelled SavedStateHandle round trip;
- setup re-check on return.

### Task 10: Task 5 recovery C — theme, accessibility, copy, check-in, haptics, captures

**Requirements:** the same sources as Task 8.

**Behaviour:**
- **F05: scheduled theme.**
  - `ThemeMode.SCHEDULED` with defaults of 22:00 dark-from and 07:00 light-from.
  - Handles overnight and daytime ranges, zone and DST changes.
  - Equal times mean always dark; document it.
  - MainActivity and `SingleActionConfigActivity` share an injected-clock theme timer that recomposes at the boundary while held open.
  - Widgets use the shared resolver.
  - `BackupValidator` rejects out-of-range theme minutes, and malformed `tonightChoice.expiresAt` / `tonightOverride.wakeAt` / `setAt`.
- **U05/U06: accessibility and layout.**
  - The curve has a text alternative.
  - Sliders have labels, units and state descriptions.
  - Switch rows are toggleable with 48 dp targets.
  - At 320 dp width and 200 % font, Quick buttons wrap and nothing primary is clipped.
  - At large font, navigation uses named icons.
  - Contrast tokens: ≥ 4.5:1 for text, ≥ 3:1 for essential outlines and after-wake bars.
- **U01:** the snackbar uses a short duration and measured bottom padding, so lower actions stay reachable.
- **U11/F14:** UI copy, plurals, units and accessibility text in `res/values/strings.xml`, UK English, with correct `%d`/`%s` placeholders and lint clean.
- **F09: morning check-in.**
  - Offers too hot / just right / too cold, linked to the completed record.
  - A separate dialog proposes a validated Middle-only change.
  - Accepting applies it against the *current* reusable profile, preserving unrelated edits. It refuses a deleted profile, a changed Middle or changed limits, or feedback already applied.
  - It never applies to a running night.
- **F13: haptics.** `CLOCK_TICK` when an action is accepted, `CONFIRM` when the BedJet confirms, via `View.performHapticFeedback`.
- **ScreenshotCapture:**
  - `Graph.clock` is pinned before composition.
  - Setup state and permissions are explicit.
  - Add 320 dp / 200 % captures and the new feature controls.
  - The outer opt-in rule skips cleanly unless `SCREENSHOT_TAG` is set.

**Tests:** scheduled boundaries, including held open; the config theme; a11y semantics; `LargeLayoutTest`; `ContrastTest`; `CheckInTest`, including after an edit or deletion; validator tests.

### Task 11: Records and hand-off

**Files:** `docs/review/register.md`, `docs/review/sdd-ledger.md`, `docs/HANDOVER.md`, `docs/review/samsung-bedjet-acceptance.md`, and a new `docs/review/ir-fixes-report.md`.

**Behaviour:**
- Close each IR row as fixed (commit, test), not reproduced (evidence) or deferred (owner and reason).
- Correct the overstated wording: R22 "all-or-nothing", R23, R14, R06.
- Mark the Task 5 items rebuilt, with their commits.
- Add these acceptance cases, as NOT RUN:
  - physical-remote Off while disconnected, then move the alarm;
  - switch BedJet while the old unit is retrying;
  - MTU-247 full-status timing around Start;
  - a start across a clock change.
- Update HANDOVER so Astra knows this branch supersedes its local Task 5 branch, and lists what remains (Task 7).

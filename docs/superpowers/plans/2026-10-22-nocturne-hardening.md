# Nocturne hardening and quality-of-life plan

**Source:** implementation brief of 2026-10-22, plus `docs/review/claude-second-review.md`. The attached review action plan was not available; see the note at the top of `docs/review/register.md`.

**Base:** `claude/lucid-mayer-i1h2gy` @ `22766fd`.
**Branch:** `claude/nocturne-hardening`.
**Register:** `docs/review/register.md`.
**Handover:** `docs/HANDOVER.md`.

## Baseline (recorded 2026-10-22)

- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passes: core 69/69, app 24/24, lint 0 errors / 26 warnings.
- Real-app renders are in `docs/screenshots/baseline/`:
  - Dark, Light, Simple-dark and Simple-light, covering Tonight (top and lower), Profiles, Remote, History, Settings and Tonight running.
  - Rendered by Robolectric NATIVE graphics, API 34, 411×891 dp at xxhdpi, against the demo BedJet, using the real wall clock (06:34 UTC), halved for storage.
  - These are not device or Bluetooth evidence.
- Lull source used for the protocol: `rkanjani/lull` @ `7214216` (2026-10-21).

## Constraints that bind every workstream

- **Keep the current UI:** Midnight Ink, Daylight, Simple view, navigation, cards, curve and controls. Extend locally; no redesign and none of the review's mock-ups.
- **BedJet 3 only.** The night runs on the BedJet. Keep: 3 slots, default working slot index 2, 32 steps, 1–8 ramp steps, protocol limits.
- **Pure-Kotlin `core`** for anything testable on the JVM; Android in `app`.
- **Wording:** UK English. "Snooze/extend" acts on the BedJet, not the phone alarm. Pending, failed and unknown are shown truthfully.
- **Test discipline:** test-first for every fix (watch it fail). Injected clocks and fakes. Never weaken a test to make it pass.
- **Repository safety:** no force-push, no merge to `main`, no published release, no signing-credential changes.

## Shared contracts (lead, before parallel work)

1. **Clock:** `Graph.clock: java.time.Clock`. Every "now" in the app goes through it, so tests and screenshots can pin time.
2. **Device identity:** a `DeviceId` string, `"demo"` or `"ble:<MAC>"`.
   - `Settings.targetDevice` holds the chosen real BedJet; `Settings.deviceId()` resolves the effective target.
   - The link never silently picks another BedJet once one is chosen.
3. **Schema ownership:**
   - `Settings` moves to its own file `core/store/Settings.kt`, owned by W3 during phase 1.
   - `Backup.kt` (bundle, slot backup, codec) is owned by W2.
   - `ActiveNight` and the night state (`core/night/`) are owned by W3.
4. **NightActions** is owned by W3 in phase 1, then by the lead during integration.

## Phases

| Phase | Work | Who |
|---|---|---|
| 0 | Branch, baseline, docs, contracts (clock, device id, Settings split) | Lead |
| 1 (parallel) | W1 BLE: R01–R08, R21 link side. W2 Persistence: R19 storage, R20 storage, R22–R25, V02. W3 Runtime: R09–R18, R26–R38 core and app runtime, F01/F02/F15/F16 backends, U02/U09 backends | 3 agents in isolated worktrees |
| 2 | Integrate phase 1, full suite | Lead |
| 3 (parallel) | W4 UI: U01–U11, F05, F08, F09, F13, F14, UI for F01/F02/F15/F16, editor drafts. W5 Features: F03, F04, F06, F07, F10, F11, F12, U03, U08 widget side | 2 agents |
| 4 | Integrate; R39/R40 release signing and shrinking; after-screenshots; acceptance checklist | Lead |
| 5 | W6 independent review, then the fix pass | Reviewer, then lead |
| 6 | Docs, push, draft PR, test APK | Lead |

## Decisions log

- **D1:** the register uses its own IDs (R, U, F, V) because the attached plan is missing.
- **D2:** baseline renders use the real clock. `java.time` doesn't follow Robolectric's clock, so fixed-clock renders come after contract 1.
- **D3:** subagents run on Opus at medium effort, as the owner set earlier in the project.

## Global constraints (copied into every brief)

- **Base and branch:** base on `claude/nocturne-hardening` @ `0a7e677` or later. Build with `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. `local.properties` holds `sdk.dir=/home/user/android-sdk` and is gitignored.
- **Keep the current UI:** Midnight Ink, Daylight, Simple view, navigation, cards, curve and controls. No redesign; extend locally.
- **BedJet 3 only.** The night runs on the BedJet. Keep 3 slots, default working slot index 2, 32 steps max, 1–8 ramp steps and the protocol limits.
- **Pure-Kotlin `core`** (JUnit 5) for logic. Android `app` tests are Robolectric (JUnit 4, `@Config(sdk = [34])`). Under Robolectric, wait on UI text and advance the main looper (`shadowOf(Looper.getMainLooper()).idleFor(...)`).
- **Time:** use `Graph.now()` / `Graph.clock` in app code, and an injected `() -> ZonedDateTime` in core. Never call `ZonedDateTime.now()` directly.
- **Device identity:** `DeviceId` strings, `demo` or `ble:<MAC>`. `Settings.deviceId()` resolves the effective target.
- **Wording:** UK English, user-facing copy in plain words. "Snooze/extend" acts on the BedJet, not the phone alarm. Show pending, failed and unknown truthfully.
- **Test discipline:** TDD for every behaviour change: write the test, watch it fail for the right reason, implement, watch it pass. Never weaken an existing test to make it pass; if one must change, explain why in the report.
- **Commits:** coherent, with messages ending exactly:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XqCZrCNi6uzceBxEgZxBsr
  ```
  Git author: `Willzy12h <29470889+Willzy12h@users.noreply.github.com>`. Do not push.
- **Register:** IDs refer to `docs/review/register.md`. Update its Status/Commit/Tests cells for the IDs you own in the same commit as the work.
- **File ownership:** edit only the files your task owns. If you need to change a file outside that list, stop and report it (NEEDS_CONTEXT) unless the brief allows it.

### Task 1: Bluetooth transport and connection ownership (W1)

**Register IDs:** R01–R08; the link side of R21; the link support for F08 (discovery) and F12 (explicit release).

**Owned files:**
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ble/**` (you may split it into several files)
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/LinkSessions.kt`
- new tests under `app/src/test/kotlin/io/github/willzy12h/nocturne/ble/` and `.../night/LinkSessionsTest.kt`
- `App.kt`: ONLY the `bleLink` construction (to pass the target provider)
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/link/BedJetLink.kt`: additive changes only, with a default implementation where you add members, because `DemoBedJet` and test fakes implement it.

**Testability (required):** put the GATT state machine behind a small port interface (for example `GattPort`: connect, discover, requestMtu, write, read, enableNotifications, close, plus a callback sink). `AndroidBedJetLink` then becomes an adapter over `BluetoothGatt`, and a fake port can drive the scenarios below on the JVM or under Robolectric.

**Behaviour required:**
- **Session ownership.**
  - Each connection attempt creates a session object owning the GATT handle, characteristics, pending waiters, split-status buffers (`lastHead`/`lastExtra`) and background jobs.
  - Callbacks from a non-current session are ignored, and that GATT is closed if it is not already (R01).
  - Callbacks for the wrong characteristic/descriptor, and non-success statuses (including `onServicesDiscovered` status), fail the operation they belong to (R01).
- **Timeouts and late callbacks (R02).** After an operation times out or is cancelled while in flight, the session is retired (disconnected and closed) before any further operation, because a late callback on the same GATT can't be told apart from the next operation's callback. The next operation reconnects.
- **Cancelled attach (R04).** It always closes its GATT and clears session state (try/finally), and rethrows `CancellationException`.
- **Abandoned connects (R05).**
  - `LinkSessions`: when the last holder releases, any in-flight connect is cancelled, and a connect that completes after the last release disconnects immediately.
  - Holder accounting stays balanced under cancellation; the screen hold (`holdForScreen`) and `reconnect()` keep their current public behaviour.
- **Fresh status for confirmation (R06).** On a new session and before a program start, split-status state is cleared. `extra` stays null until a fresh remainder read or full notification arrives, so an old `extra` cannot confirm a new start. Old queued remainder reads are dropped with their session.
- **Exceptions (R07).**
  - `SecurityException` (permission revoked) and other platform exceptions from GATT/scan calls become `BedJetError.NotConnected`; state becomes `UNAUTHORIZED` where that applies.
  - `CancellationException` is always rethrown.
  - Pending state is cleaned even when initiating an operation throws.
  - The background remainder read catches every non-cancellation exception.
- **Target and recovery (R08, R21).**
  - The link takes a target provider `() -> String?` (the `DeviceId` from `Settings.deviceId()`) and connects only to that address.
  - With a target set, recovery retries the same address within a bound (handling status 133 and late timeouts with up to 2 retries and backoff). It may scan with a ScanFilter on that address. It never silently connects to a different BedJet.
  - With no target, `connect()` throws `BedJetError.NotConnected` with a message saying to choose a BedJet in setup.
  - Migration: if `Settings.targetDevice` is null but the old remembered address (`ble` SharedPreferences, key `address`) exists, expose it via `fun rememberedAddress(): String?` so the runtime can adopt it. Don't write Settings yourself.
- **Discovery for setup (F08 support).** `suspend fun discover(timeoutMs: Long): List<FoundBedJet>` with `FoundBedJet(address, name, rssi)`. It uses a service-UUID ScanFilter, then a name-prefix "BEDJET" pass, and never connects.
- **Explicit release for hand-over (F12 support).**
  - `LinkSessions.suspendAutoConnect(untilMs: Long?)`: disconnect now, and refuse `acquire()` with a clear `BedJetError.NotConnected("Released for another phone")` until resumed or until the time passes.
  - `resumeAutoConnect()`.
  - `val released: StateFlow<Boolean>`.

**Tests required (fake port; injected clock or virtual time):**
- A late write callback from a previous session doesn't complete the current operation.
- A timeout, then reusing the same GATT: the session is retired and the next op reconnects.
- A cancelled attach closes the GATT.
- A connect finishing after the last release leaves no connection.
- A stale `extra` doesn't satisfy a new start; a fresh one does.
- A `SecurityException` from write becomes NotConnected, and no pending waiter leaks.
- Status 133 with a target retries the same address, then fails without connecting to another device.
- No target: connect throws with the setup message.
- `discover` returns found units without connecting.
- `suspendAutoConnect` blocks acquire and `resumeAutoConnect` allows it.
- Existing `NightActionsTest` and `AppSmokeTest` still pass.

**Done when:** all of the above are implemented and tested, the full suite is green, lint has 0 errors, and the register is updated.

### Task 2: Persistence, backups, migration and device isolation (W2)

**Register IDs:** R19 (storage side), R20 (storage side), R22, R23, R24, R25, V02.

**Owned files:**
- `app/src/main/kotlin/io/github/willzy12h/nocturne/data/**`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/Backup.kt`, `History.kt`, `SnapshotStore.kt`, `NotificationLog.kt`
- new `core/.../store/BackupValidator.kt` and `Migrations.kt`
- tests under `core/src/test/.../store/` and `app/src/test/.../data/`
- `app/src/main/res/xml/` (new backup/data-extraction rules)
- In `AndroidManifest.xml`, ONLY the `<application>` attributes `android:dataExtractionRules` / `android:fullBackupContent`.
- You may make mechanical call-site updates in other files ONLY to follow signature changes you make to Repository (e.g. launching a now-suspend call). List each such file in your report.

**Behaviour required:**
- **Durable before visible (R23).**
  - Every Repository mutation writes durably (AtomicFile, fsync) before the new value is published to its StateFlow.
  - If the write fails, the old value stays published and the error is thrown to the caller.
  - Writes run off the main thread on a single serial writer (e.g. a limited-parallelism IO dispatcher). Mutations become `suspend` where needed.
  - `SnapshotStore.list()` reads only the header (stream-parse, no `readText` of whole files).
  - Unreadable files stay set aside (`.corrupt-<ts>`). The unreadable state survives restarts via a marker file until the user restores or dismisses it (`dismissUnreadable()`).
- **Device-bound original-slot backups (R19, R20).**
  - `SlotBackup` gains `deviceId: String` and `empty: Boolean` (an explicit empty-slot marker when the slot held nothing), with `program` becoming nullable. Old backups migrate with `deviceId = "unknown"`.
  - API: `suspend fun recordOriginalSlot(deviceId: String, slot: Int, program: StoredProgram?): SlotBackup`. It saves durably and returns the stored record. It does NOT replace an existing record for the same device and slot (the first original is kept across retries). It throws if it can't save, so the caller aborts the BedJet write.
  - `fun originalSlot(deviceId: String, slot: Int): SlotBackup?`.
  - Demo and real records never mix.
- **History tagging (R20).** `NightRecord` gains `deviceId: String = "unknown"`. The demo unit's history is filterable (expose a helper; don't hide data).
- **Import (R22).**
  - `BackupValidator.validate(bundle): List<String>` (errors) checks:
    - schema ≤ current;
    - unique profile ids;
    - phase minutes 1..720;
    - fan 5..100;
    - temperatures within the mode ranges;
    - rampSteps 1..8;
    - slot 0..2;
    - ISO times parse;
    - settings ranges;
    - total file size ≤ 5 MB (check before decoding).
  - `restore()` refuses invalid bundles with readable errors before any change.
  - On success it first saves a "Before import" manual snapshot, then replaces profiles, settings and history as one coherent step. It keeps the current `demoMode`, `targetDevice` and update token, never touches the BedJet, and never changes or clears an active night or pending work. Slot backups merge as now (current wins).
- **Secrets (R24).**
  - The update token is excluded from exports, diagnostics and snapshots (already true for bundles: keep it so and add a test).
  - Add `res/xml/data_extraction_rules.xml` (Android 12+) and `backup_rules.xml` that exclude the `secrets` shared prefs from cloud backup and device transfer, and reference them in the manifest `<application>`.
- **Migrations (R25).**
  - `BackupBundle` schema 2 with a `Migrations` step from 1 → 2 (slot backup device/empty fields, NightRecord deviceId).
  - Decoding v1 bundles and v1 on-disk files must work. Keep retention: 60-day history, the last 20 automatic snapshots, manual snapshots until deleted.
- **Keep the existing public Repository surface working.** If you change a signature (e.g. to `suspend`), update every call site mechanically and list them.

**Tests required:**
- Publish-after-durable (a failing writer leaves the old value and throws).
- Writes don't happen on the main thread (assert the thread name, or use an injected dispatcher).
- A corrupt file is set aside and the unreadable marker survives a restart.
- `recordOriginalSlot` keeps the first original on retry, stores the empty marker, and throws on write failure.
- Demo and real backups are separate.
- Validator rejects each bad field class.
- Oversized import is rejected before decoding.
- A valid import makes a pre-import snapshot, keeps target/demo/token/active night, and changes no BedJet state.
- v1 → v2 bundle migration round trip, and v1 files on disk load.
- The token never appears in exported JSON.
- Retention is preserved.
- Existing tests still pass.

**Done when:** all implemented and tested, full suite green, lint 0 errors, register updated.

### Task 3: Night state and planning core (W3a)

**Register IDs:**
- R09–R12, R13 (data side), R16 (identity part), R17, R26 (state model), R29 (eligibility model), R33, R34, R35, R36
- backends for F01 (tonight-only wake override) and F02 (resume)
- U02 backend (temporary selection plus follow schedule)

**Starts after:** Tasks 1 and 2 are integrated.

**Owned files:**
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/**`
- `core/.../plan/**`
- `core/.../store/Settings.kt`
- tests under `core/src/test/.../night/` and `.../plan/`
- `app/.../night/AlarmSource.kt`
- the parts of `app/.../night/NightActions.kt` needed to call the new core API. Keep its public surface; Task 4 owns NightActions next.

**Behaviour required:**
- **ActiveNight v2 (R09).** Serialisable and backward compatible with v1 JSON. It adds:
  - `deviceId`
  - `profile: Profile?`, a snapshot taken at start
  - `wakeOrigin: WakeOrigin` = `ALARM | PROFILE_DEFAULT | OVERRIDE`, derived from `wakeFromAlarm` for v1
  - `revision: Int`
  - `applied: AppliedProgram`: the steps actually written, as `RecordedStep`s, plus the start and wake of that revision
  - `confidence: Confidence` = `CONFIRMED | PENDING | FAILED | UNKNOWN`
  - `pending: PendingChange?`, with request id, kind, requested wake, requested-at and deadline
  - `actualEnd: String?`

  Old saved nights load with sensible defaults; a null snapshot falls back to the live profile, with a flag.
- **Snapshot use (R10, R11).**
  - Re-time, display (`activePlan`) and every mid-night decision use `ActiveNight.profile` and `applied`, never the live profile.
  - Editing or deleting the reusable profile never changes the active night.
  - Automatic re-time never rewrites at or after the confirmed wake boundary (`wakeUpStart` of the applied revision).
- **Update tonight (R12).** `NightRunner.reapply(night, editedProfile)` rewrites the rest of tonight from the edited profile but keeps the agreed wake instant and `wakeOrigin`, unless an explicit new wake is passed. It bumps `revision`, updates `applied` and replaces the snapshot.
- **Applied history (R13 data).** Each applied revision is recorded on the `NightRecord`: add `revisions: List<AppliedProgram>`, keeping `steps` as the first revision for compatibility.
- **Identity (R16).** Every mutating runner API takes the expected `(nightId, revision)` and returns a typed `Stale` result if the night or revision has changed.
- **Local transitions (R17).** Pure functions for `stopTracking(night, reason, at)` and `expire(night, at)` that need no BedJet.
- **Two-phase state (R26).** `NightRunner` returns the intended next state before the BedJet write (`PENDING`), and the confirmed state after readback/confirmation. On failure the state becomes `FAILED`, or `UNKNOWN` when a partial write may have happened. A partial rewrite never claims the old programme survived.
- **Eligibility (R29).** `Eligibility.check(night, now, request)` is used after lock and connection acquisition and before writing. It rejects a request past the confirmed wake boundary or past its deadline, and a stale revision.
- **One alarm policy (R33).**
  - `AlarmPolicy.accept(alarm, origin, nightStart)` is used by both start and re-time.
  - Accepted attribution is the configured Samsung/Google packages.
  - An unknown creator is accepted only when `Settings.acceptUnknownAlarmSource` (new, default true, documented) is on.
  - The window is 1 h to 16 h after the ORIGINAL night start, inclusive at 1 h and exclusive above 16 h. Document the boundaries in KDoc and test them.
- **Night length (R34).** The supported night length is 1–16 h. A plan longer than 16 h or over 32 steps is refused before any write, with a clear reason. This covers the observed case of starting at 06:34 with a 07:00 default, which planned a 24.5 h night. For a start more than 16 h before the next default wake, the reason suggests setting a tonight-only wake time. Profile validation covers mode-specific step growth (heat splitting, turbo chaining) at the extremes.
- **Truthful limits (R35).**
  - Limits apply to every mode. If a mode can't honour the cap (Turbo is fixed at ~43 °C), a profile containing Turbo with `maxTemp` below the Turbo temperature is rejected by validation, with the explanation "Turbo always runs at 43 °C, above this profile's limit".
  - Test the TURBO_THEN_HEAT and REPEAT_TURBO paths against caps.
- **One preview (R36).** `NightPreview.forStartNow(profile, now, alarm, override)` returns the plan, wake choice and refusal reason that Start will use. Tonight's headline, curve and Start must call it; Task 4 wires the UI.
- **F01 tonight-only wake override.**
  - `TonightOverride(wakeAt, setAt)` lives in Settings or night state. It persists across restarts and expires at that wake time or when the night ends.
  - Precedence: override > accepted phone alarm > profile default. The phone alarm is never touched.
  - Provide set, clear and resolve APIs.
- **F02 resume.**
  - `ResumeOffer` is created when a night is stopped or replaced while time remains. It holds the snapshot, wake, the remaining applied steps from the stop moment, and an expiry 15 minutes after the stop.
  - `NightRunner.resume(offer, now)` rebuilds from now to the original wake. It never replays completed Turbo (Turbo phases already started are dropped) and is refused if a newer night or command exists, or once the offer has expired.
- **U02 temporary selection.**
  - `Settings.selectedProfileId` becomes `tonightChoice: TonightChoice?` (profile id + expiry at the end of tonight's bedtime window, e.g. noon next day), plus `followSchedule` semantics: when there is no temporary choice, the weekday schedule decides.
  - Keep the JSON migration from `selectedProfileId`.

**Tests required:** each item above has a test that failed before the change. That includes:
- v1 ActiveNight JSON migration;
- "next night" edit not changing tonight's re-time;
- deleting the profile mid-night;
- re-time refused after the wake boundary;
- reapply keeping the wake and origin;
- stale revision rejected;
- the alarm-policy boundaries at exactly 1 h, 16 h and 16 h + 1 min, and an unknown creator with the setting on and off;
- the 06:34 start refused;
- Turbo-versus-cap validation;
- preview equal to the actual start plan;
- override precedence, expiry and restart;
- resume dropping completed Turbo, expiring, and refusing after a newer action;
- temporary choice expiring back to the schedule.

### Task 4: App runtime, alarms, service, takeover and NightActions (W3b)

**Register IDs:**
- R13 (notifications/widget data), R14, R15, R16 (serialisation), R18, R19 (wiring: back up the original before the write, abort on failure; covers Start, Boost, pin, restore), R20 (command bound to its target), R26–R28, R30–R32, R37, R38
- runtime for F01, F02, F15 (second-slot pinning), F16 (Stop tracking, diagnostics export, quiet status notification)
- U09 backend

**Starts after:** Task 3 is integrated.

**Owned files:**
- `app/.../night/**`
- `app/.../service/**`
- `app/.../notify/**`
- `app/.../ui/ShortcutActivity.kt`
- `app/.../App.kt`
- `AndroidManifest.xml` (components and permissions only)
- `core/.../night/NightRunner.kt` for wiring tweaks only
- tests under `app/src/test/.../night/` and `.../service/`

**Behaviour required:**
- **Takeover from the Remote (R14).**
  - Remote Off and mode changes go through a NightActions command that finishes the night after the BedJet accepts the change.
  - LED, beeps and the filter acknowledgement do not end a night.
  - A later alarm change after a takeover never restarts the programme.
- **Takeover by someone else (R15).**
  - When status arrives while a night is active and shows a mode or programme that contradicts the applied programme (programme step 0 with a running mode, or a mode or temperature not matching the current step after a grace period), record a `REMOTE_CHANGE` event and move the night to `UNKNOWN`.
  - The user is offered "Keep tracking" or "Stop tracking".
  - Normal step progression is not a takeover.
  - Never claim instant detection while disconnected.
- **Serialisation and stale actions (R16, R18).**
  - Every command, expiry, replacement, slot restore and target change runs under the one NightActions mutex and carries the night id and revision.
  - Notification and widget actions for an old night or revision are rejected with a message.
  - Fix the `exclusiveUntil` timeout race: lock with an owner token, release on timeout if held, and release any acquired hold.
- **Local transitions (R17 wiring).** Expiry (NIGHT_END) and Stop tracking run under the mutex without acquiring Bluetooth.
- **Original-slot backup (R19 wiring).** Before every write to a slot (Start, Boost, re-time, pin, restore), call `Repository.recordOriginalSlot(deviceId, slot, program)` (from Task 2). If it throws, abort the write and show the reason.
- **Target binding (R20).** A command captures `Settings.deviceId()` when it is queued and runs against that target even if the setting changes. Switching demo mode or the target while a night is active asks first (UI in Task 5) and is refused at runtime with a clear message.
- **Persist first (R26).** Save the intended state (PENDING) before writing to the BedJet. On success, CONFIRMED. On failure, FAILED or UNKNOWN as Task 3 defines.
- **Bounded operations (R27).** The whole operation runs under the foreground service's ownership with an overall time limit. The receiver never runs long rewrites. The `goAsync` fallback only records the pending re-time and schedules the retry (below).
- **Pending re-time (R28).**
  - One coalesced, durable pending re-time (newest request wins, with an id and a deadline at the confirmed wake boundary).
  - Retry with a best-effort `setAndAllowWhileIdle`, or exact where permitted, about 10 minutes apart, until the deadline. Never use alarm-clock alarms for retries.
  - Retry when the app opens and when the alarm changes.
  - Before writing, re-check eligibility (Task 3); expire missed requests with a notification.
- **Alarm scheduling (R30, R31).**
  - Wake-up and night-end are scheduled separately, and a past time is never re-armed. This fixes snooze/extend re-popping the notification and duplicate log entries.
  - A manual run shorter than the remaining night ends tracking when it ends.
  - Resolve the race between night end and Extend under the mutex.
  - Startup, boot and permission changes reconcile the schedule without replaying past wake events.
- **Foreground-service prerequisites (R32).** Check Bluetooth CONNECT permission and FGS eligibility before `startForeground` (connectedDevice). If denied, show a visible notification and log entry. Demo mode needs no BLE permission (use FGS type `shortService` or no FGS for demo).
- **Exported entry points (R37).**
  - ShortcutActivity: launcher shortcuts can still start the app's own flow. Commands that change the BedJet (start, Turbo, Boost, Off) arriving from an external intent must not execute silently. Either require a confirmation screen, or accept them only from the app's own static shortcuts verified by an unforgeable extra (a per-install random token written into dynamic shortcuts via `ShortcutManager`) and show a confirmation otherwise.
  - Keep Samsung Routines/Bixby working through the confirmation route.
- **Replacement policy (R38).** One policy object decides, for UI, widget, shortcut, notification and slot restore, whether a command replaces a running night and whether it needs confirmation. Off is never blocked; it may ask in the UI only.
- **F01 runtime.** Re-time and start honour the override. The override is cleared when the night ends.
- **F02 runtime.** Create the resume offer on stop or replace, with a notification action and in-app action "Resume the rest of the night" that runs through the coordinator.
- **F15 second-slot pinning.**
  - `pin(profileId, slot)` checks that the slot isn't the working slot or a slot running a programme, records the original, writes, reads back and reports.
  - Pinned programmes are duration-based (they don't follow any night's alarm). State this in the result text.
  - Provide `restoreOriginal(slot)` to put the original back.
- **F16 observability.**
  - Expose `NightStatus` (confirmed/pending/failed/unknown plus a reason) for the UI.
  - Stop tracking ("does not turn the BedJet off"), local only.
  - Diagnostics export: settings without secrets, the active night, the last 50 events and notices, app version, device model, and link state. The token is never included.
  - Optional quiet ongoing notification "Waking at hh:mm" with End/Open actions (opt-in setting).
- **U09 history events.** Record BedJet notices (clean filter, refusals), Remote failures (with a command label), applied revisions, takeover and recovery.

**Tests required:** each behaviour has a regression test that failed first. Use the fake link from Task 1 or DemoBedJet, the injected clock, and Robolectric's AlarmManager and NotificationManager shadows. At minimum:
- Off followed by an alarm change doesn't restart;
- snooze doesn't re-pop or duplicate the log;
- a shorter manual run ends tracking;
- end-versus-extend race;
- receiver fallback only records and schedules;
- pending re-time retried and then expired at the boundary;
- stale notification action rejected;
- demo switch during a night refused;
- original slot backed up before Start, Boost, pin and restore, and the write aborted when the backup fails;
- shortcut from an external intent requires confirmation;
- `exclusiveUntil` timeout leaves the lock free;
- takeover detection versus normal progression;
- diagnostics contain no token;
- resume offer flow;
- pinning refuses the working slot.

**Carried from Tasks 1–2 (added at integration):**
- **From Task 1:** the link connects only to `Settings.deviceId()` and refuses when there is no target. At app start, if `Settings.targetDevice == null` and `bleLink.rememberedAddress() != null`, adopt it: set `targetDevice = DeviceId.ble(address)` durably, once, logged as a notice. Never pick a nearby unit silently.
- **From Task 2:** `NightRunner.start` still writes the slot BEFORE NightActions saves the backup. Replace this with `Repository.recordOriginalSlot(deviceId, slot, program)` (or `originalsFor(deviceId)`) before `controller.store`, for Start, Boost, re-time, pin and restore. Abort the write if it throws. Then deprecate and remove `addSlotBackup`.
- **From Task 2:** with no target chosen, no original is saved. Make sure a target is set before any slot write.
- **From Task 2:** migrated "unknown" originals are adopted by the first real BedJet (`originalsFor`). Use `originalSlot()` for restore.


## Lead integration rulings (22 October, Astra continuation)

- Task 3 retains legacy runner mutators only for interim source compatibility. Migrate all production callers to its prepared/typed transaction APIs and current persisted identity. Remove unsafe adapters when no production callers remain; do not preserve unsafe paths merely for old tests. Persist the command generation needed by resume offers, and report the exact public API for Tasks 5/6.
- Ownership extends to minimal `Repository.kt`, core `store/Settings.kt` and `store/History.kt` additions needed for durable resume offers/command generation, revisions, quiet notification settings and new event kinds. This is runtime integration of Task 2's durable writer, not a redo of storage. All writes use the existing durable serial writer; do not add a second generic persistence framework.
- Task 6 charger automation will run as a native charging-constrained JobScheduler job. Provide a bounded, cancellable **job-owned** command route that does not start an FGS. The job caller owns lifecycle/cancellation; no detached coroutine or automatic FGS fallback. The normal receiver path still only records/schedules, and normal service ownership still protects service-triggered operations. Cancellation after a possibly accepted hardware mutation must remain UNKNOWN durably; never replay a partial upload blindly.
- Provide a serialised safe hand-over API / whole-operation busy gate for Task 6. `LinkSessions.suspendAutoConnect` must not run after a stale idle check and disconnect halfway through the coordinator's multi-packet transaction. Manual release waits for the mutex; a peer request may decline while busy. Take-back resumes and reconciles status. A per-packet lock is insufficient.
- Provide the minimal coordinator methods Task 5 needs for selecting/forgetting a target, changing demo mode and supervised setup fan check. Target changes/forgetting must not bypass command ownership. The supervised check reads status, changes only the fan by a small supported amount, restores it in a bounded `finally`, and refuses active/unknown/manual takeover situations; it must never start a night or mode silently.
- Commands capture the selected target when queued; when ownership is acquired, either run against that same target or refuse if settings changed. Do not silently switch a physical link to a newly selected unit.
- Keep a documented running-command label / queued count and outcome stream for Task 5; truthful device-confirmed versus accepted/local results must remain distinguishable.
- Feature persistence contract for Tasks 5/6: Task 5 owns scheduled-theme/setup/check-in additions and Task 6 owns a new serialisable `PlatformSettings` (nested `Settings.platform` with defaults) in a separate core/store file. Both may minimally add their own Settings fields; lead resolves that one shared-file merge. Task 6 may persist its small durable automation journal through narrow Repository APIs; it must not invent a second command lock or bypass NightActions.

- New runtime notification and command-outcome copy belongs in `res/values/strings_runtime.xml` (Task4 ownership extension), using UK English. Task5 moves remaining screen copy. Keep typed outcomes/confirmation separate from rendered text so haptics and status do not infer success by parsing an English sentence.

### Task 5: UI, accessibility and in-app features, in the existing design (W4)

**Register IDs:** U01, U02 (UI), U04–U07, U08 (in-app), U09 (display), U10, U11/F14, F05, F08, F09, F13, and the UI for F01, F02, F15 and F16.

**Starts after:** Task 4 is integrated.

**Owned files:**
- `app/.../ui/**`, except ShortcutActivity
- `app/src/main/res/values*/**`
- tests under `app/src/test/.../ui/` and the smoke/appearance tests
- Settings sections for Task 6 features are wired here: Task 6 provides composables in `ui/features/`, and this task places them.

**Constraint:** KEEP the current design: Midnight Ink and Daylight colours, typography, cards, curve, navigation and Simple view. New things extend existing screens. Use progressive disclosure: secondary options go in expandable sections and dialogs, not new tabs.

**Behaviour required:**
- **U01 snackbar.** Quick actions stay reachable while it shows: bottom padding or inset on scrollable content equal to the snackbar height, and a shorter duration.
- **U02.** "Use tonight" sets a temporary choice; show "Tonight only · Follow schedule" with a reset.
- **U04 editor drafts.** The profile editor draft and navigation (tab, open editor) survive configuration change and process death (SavedStateHandle with the draft serialised).
- **U05 TalkBack.**
  - The curve gets a text alternative summarising the blocks and times.
  - Sliders get labels, units and a state description.
  - Switches get row-level toggleable semantics.
- **U06.** Verify contrast, 48 dp touch targets, 320 dp width and 200 % font scale. Fix local failures without changing the look. Add Robolectric semantics tests.
- **U07.**
  - Move `stateIn` to `viewModelScope`.
  - Show queued and running commands accurately: a busy indicator with the command label, and queued count.
  - No duplicate snackbars.
- **U08.** Show in-app outcomes of widget, shortcut and update actions when the app is open (read the service's outcome flow).
- **U09 display.** History shows the new event kinds and revisions.
- **U10.** Show "Saved for next night" edits and pending versus confirmed wake changes on Tonight, briefly and truthfully.
- **U11/F14 strings.** Move all UI copy, plurals, units and accessibility text to `res/values/strings.xml` (UK English). Don't fabricate translations.
- **F05 scheduled theme.**
  - Add `ThemeMode.SCHEDULED` with dark-from and light-from local times. It handles overnight ranges, time-zone and DST changes, and recomposes at the boundary.
  - Equal start and end means "always dark" (document it).
  - Widgets follow the same resolved theme.
- **F08 first-run setup.**
  - A one-time flow: permissions, Samsung battery guidance, explicit BedJet selection via `discover()` (Task 1), a clearly separate "Use demo instead", and a short supervised check (connect, read status, nudge the fan, restore).
  - Real settings are re-checked on return from system screens.
  - Reachable again from Settings.
- **F09 morning check-in.** After a night ends, offer too hot / just right / too cold, linked to that night record. Suggest a small, validated change (e.g. fan −10 % in Middle, or temperature ±1 °C) that the user must accept; profiles are never edited silently.
- **F13 haptics.** A light tick when a tap is accepted, and a confirm pattern when the BedJet confirms. Respect the system haptic setting (`HapticFeedbackConstants` via `View.performHapticFeedback`).
- **F01 UI.** On Tonight, "Wake time for tonight only" with a time picker, clear and an expiry note.
- **F02 UI.** A "Resume the rest of the night" action after stop or replace, while the offer lasts.
- **F15 UI.** In the profile overflow, "Keep on BedJet slot…" with slot choice, preview, the duration-based explanation, and restore original.
- **F16 UI.**
  - Confirmed/pending/unknown indicator on Tonight.
  - Stop tracking with the explanation "This doesn't turn the BedJet off".
  - Diagnostics export in Settings.
  - Quiet status notification toggle.

**Tests required:** Robolectric tests per item, semantics checks (distinct from real TalkBack testing), saved-state recreation, scheduled theme boundaries, setup flow re-check, check-in suggestion acceptance, and the narrow-width plus large-font layouts having no clipped primary actions.

**Carried from Tasks 1–2 (added at integration):**
- **From Task 1:** BedJet selection UI. When no target is chosen, Tonight and Remote show "Choose your BedJet", leading to setup (F08) with `bleLink.discover()`.
- **From Task 1 review:** "Forget this BedJet" must clear `Settings.targetDevice` (and the legacy address via `bleLink.forget()`), then send the person to BedJet selection. Truthful message: "Forgot this BedJet. Choose a BedJet to connect to."
- **From Task 2:** surface `Repository.unreadable` with a calm banner ("Some saved data couldn't be read — restore from a snapshot or dismiss") with Restore and Dismiss (`dismissUnreadable()`).


## Lead ownership rulings (22 October, Astra continuation)

- Minimal core/store/Settings.kt additions and pure scheduled-theme helpers/tests are owned by Task5; Task6 owns a separate PlatformSettings file plus one defaulted Settings.platform insertion. Lead resolves this shared-file merge. Do not modify Task6 feature fields/composables/strings.
- Morning check-in can use a typed history event linked to the completed record; minimal History/Repository support is permitted where needed. Reuse durable persistence, and keep user-approved suggestions separate from applying them to a running night.
- Task4 supplies coordinator APIs for target/demo/forget/setup fan check, pin/restore, release/take-back and running/queued command status. Do not call hardware controllers directly from new UI, and do not change frozen NightActions without asking lead.
- ScreenshotCapture is in scope: pin `Graph.clock` to a suitable evening instant, make setup state/permissions explicit, capture the existing Dark/Light/Simple flows plus320dp and200%font variants. Label render evidence accurately as Robolectric; no physical-device claims. Root will run SCREENSHOT_TAG=after after integration.

### Task 6: Platform features: widgets, tile, reminders, auto-start, Health Connect, CSV, hand-over (W5)

**Register IDs:** U03, U08 (widget/shortcut side), F03, F04, F06, F07, F10, F11, F12.

**Starts after:** Task 4 is integrated. It can run in parallel with Task 5 in its own worktree.

**Owned files:**
- `app/.../widget/**`
- new `app/.../platform/**` (tile, reminders, auto-start, Health Connect, CSV, hand-over)
- new composables in `app/.../ui/features/**`
- new strings in `app/src/main/res/values/strings_features.xml`
- `app/build.gradle.kts` and `gradle/libs.versions.toml` (add the Health Connect client only)
- tests under `app/src/test/.../widget/` and `.../platform/`
- `AndroidManifest.xml`: components and permissions for these features only.

Report every manifest change.

**Behaviour required:**
- **U03 widgets.** A dynamic "Start tonight" resolves the profile when tapped. Buttons fixed to a profile that has been deleted show "Profile deleted — reconfigure".
- **F07 richer widget.** Shows the BedJet name, the confirmed next wake (with "pending" when not confirmed), and the current or last-known mode with its age ("2 min ago"). Never hold a permanent connection.
- **F06 Quick Settings tile.**
  - State: night running / idle / unknown.
  - Tap opens Tonight; long-press opens the app.
  - The tile's quick action (Start tonight or End night) goes through the coordinator and replacement policy, and asks for confirmation through an activity when a replacement would happen.
- **F03 bedtime reminders.** Opt-in at the profile's bedtime minus N minutes, with duplicate suppression per night, notification-permission handling, and a tap that opens the Start preview.
- **F04 charger auto-start.**
  - Opt-in and OFF by default.
  - Fires on power connected within a bedtime window (bedtime −60 min to +120 min), only for the selected BedJet, only if the plan is valid and at least 3 h long, and once per night (idempotent key).
  - Suppressed when a night is active, during a manual run, or in unknown or takeover states, and when prerequisites are missing.
  - It notifies "Starting <profile> in 60 s" with Cancel, then starts through the coordinator.
  - Easy to disable from the notification.
  - Mark "supervised acceptance pending" in Settings copy.
- **F10 Health Connect overlay.**
  - Optional. Read only `SleepSessionRecord` and the minimum permissions. Handle the provider being absent (no Health Connect installed), revocation, time zones and missing data.
  - Shows sleep stages as an overlay on History night cards, labelled "From Health Connect". It never affects BedJet control.
  - Include the permissions-rationale activity required by Health Connect.
- **F11 History CSV.**
  - Export nights, revisions and events through the system file picker.
  - Header with units: temperatures in °C (with °F in parallel columns if the setting is on), timestamps in ISO 8601 with offset, plus a zone column. RFC 4180 escaping.
  - No device addresses unless the user ticks "Include device identifiers".
- **F12 phone hand-over.**
  - "Release BedJet for another phone" in the overflow menu calls `LinkSessions.suspendAutoConnect` from Task 1, which suspends auto-reconnect and re-time.
  - On return ("Take back"), resume and reconcile from status.
  - Lull interop: Lull (`rkanjani/lull` @ 7214216) asks for a hand-over by advertising a 128-bit service UUID derived as SHA-256("lull.handoff.<device name>")[0..16] with version bits (`[6] = (b & 0x0f) | 0x50`, `[8] = (b & 0x3f) | 0x80`), and the phone holding the BedJet yields when it sees it. Implement:
    - (a) while connected, scanning for that UUID and releasing when seen (needs no new permission beyond BLUETOOTH_SCAN);
    - (b) when wanting the BedJet, advertising the same UUID for about 8 s (needs BLUETOOTH_ADVERTISE; request it only when the feature is enabled).

    Unit-test the UUID derivation against a fixed name. Don't claim shared state with Lull.

**Tests required:** tap-time resolution, deleted-profile widget, tile state and actions, reminder duplicate suppression, auto-start eligibility matrix (every suppression case), Health Connect absence and revocation (fake client), CSV escaping, time zones and the privacy toggle, hand-over UUID derivation, and suspend/resume.


## Lead platform rulings (22 October, Astra continuation)

Read `docs/review/platform-preflight.md` in the integrated base. These amend implementation details that the original plan could not satisfy on current Android:

- F04 is **best-effort while charging within the bedtime window**, not an exact power-connected edge. No manifest ACTION_POWER_CONNECTED receiver and no charger-triggered FGS. Use native charging-constrained JobScheduler and a bounded JobService-owned operation via Task 4's coordinator. `onStopJob` cancels work; no detached coroutine. Recheck all eligibility before countdown and write. A fresh 60 s cancellable grace is required after a stopped countdown; persist once-per-night attempt/cancel suppression. After a possibly accepted partial hardware write, retain UNKNOWN and require reconciliation, never automatic replay. Use a finite overall operation limit (120 s after grace unless transport evidence justifies a documented adjustment). Notification Cancel/Disable is durable. Explain timing honestly in Settings; no new WorkManager dependency is needed.
- Health Connect stable 1.1.0's inspected Google Maven AAR requires compileSdk36/AGP>=8.9.1. Task 6 may change compileSdk to36 and AGP to8.10.1 (explicit API36 support), retaining target35/min31/Gradle8.14.3/Kotlin2.0.21/Compose versions. Report merged-manifest library services as well as app additions. Host SDK36 is installed.
- Lull pinned SHA is721421664f45752ba23ff131f9f53cee589a0d90. Use first16 SHA256 bytes and exact GATT-derived device name through first NUL, without trimming/case changes. Advertisement payload is service UUID, not service data. Unknown name disables interoperability. Implement 8 s request and10 s peer-yield courtesy; explicit manual release remains until Take back. Whole coordinator transaction must gate yield (not merely pending packet). Android may not see background iOS overflow advertisements; show/document the limitation, no hardware interop claim.
- API34+ tiles launch an explicit PendingIntent. Unknown state must still allow opening Tonight; do not use a disabled unavailable tile for navigable unknown state. Quick action is offered through the activity and shared replacement policy.
- Settings ownership: introduce serialisable `PlatformSettings` in a new core/store file and a defaulted `Settings.platform` field for feature preferences. This keeps the simultaneous Task5 theme/setup edits disjoint except that single Settings insertion. Minimal Repository automation-journal methods and app Graph/platform lifecycle wiring are permitted; report all shared-file edits. NightActions itself is frozen after Task4: request needed coordinator changes from lead. New feature UI stays in ui/features and strings_features.xml.

### Task 7: Release signing and shrinking (V)

**Register IDs:** R39, R40, V04.

**Starts after:** Tasks 5 and 6 are integrated.

**Owned files:**
- `app/build.gradle.kts` (signing and minify)
- `app/proguard-rules.pro`
- `.github/workflows/build.yml`
- `README.md` (release section)

**Behaviour required:**
- **R39 signing.** Release builds sign only with the configured release key (env/secrets). With no key, `assembleRelease` produces an unsigned APK and says so. It never falls back to the debug key. The CI release job fails clearly if the secrets are missing.
- **R40 shrinking.**
  - `isMinifyEnabled = true` and `isShrinkResources = true`.
  - Keep rules only where needed: kotlinx.serialization (`@Serializable` classes in core and app), Glance receivers and widgets, Health Connect if added, and the manifest-referenced components (AGP keeps those automatically).
  - No blanket keep-everything rules.
- **Verify the minified build:**
  - `./gradlew :app:assembleRelease` with a throwaway test keystore (never committed; generated in a temp dir).
  - Then check that `apkanalyzer`/`aapt2` lists all manifest components.
  - Then a JVM test (in `app/src/test`) that runs a decode/encode round trip of a BackupBundle and Settings JSON against the minified classes. If that's impractical, document precisely why, and instead verify the serializer classes are present in the minified dex with `apkanalyzer dex packages`.
  - Record the APK size before and after.

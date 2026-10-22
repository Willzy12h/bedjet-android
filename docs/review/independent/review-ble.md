# Task 1 BLE transport review: claude/nocturne-hardening @ 7fffabc

Reviewer: independent, read only. Gradle was not run, and nothing here has been checked on hardware. Every claim below comes from reading the source and tracing it by hand.
Scope: `ble/{GattLink,GattPort,AndroidBedJetLink}.kt`, `night/LinkSessions.kt`, `core/link/{BedJetLink,BedJetController,DemoBedJet}.kt`, the link wiring in `App.kt`, and the tests listed in the brief. I also read the callers in `NightActions.kt` and `PhoneHandover.kt` where they affect transport behaviour. The scope diff since 83e6efa covers the `preparedLink` seam, `DeviceNames`, the GATT 2001 verified-name read, `beforeStart`, Demo `prepareForStart`, and `suspendAutoConnect` now returning `Job`.

## Summary

| ID | Severity | Title | Status |
|---|---|---|---|
| BLE-1 | Medium | A status from before the start write can still confirm a start: full-status notifications, a remainder read finishing ahead of the write, or a reconnect inside `send` | new (R06 is only partly closed) |
| BLE-2 | Medium | `GattLink` never checks that the live session's address is still the target. A ready session, or a connect loop that is retrying, can stay on the previous BedJet after a target change, and its statuses get attributed to the new target | new (R21 covers only the link side) |
| BLE-3 | Low | The adapter forwarding test goes through a test-only seam and never reaches the production `engine` path | new (test gap; production code is correct) |
| BLE-4 | Low | A timed release ends on one wall-clock check after a monotonic `delay`. If the clock jumps, `released` stays true and the screen does not reconnect | new |

Focus 1, the headline question: **production does forward.** Details are in the "Verified correct" section.

---

## BLE-1: Medium. A status from before the start write can still confirm a start

**Files:**
- `app/.../ble/GattLink.kt:95-103` (send), `:114-117` (prepareForStart), `:296-330` (receiveStatus)
- `core/.../link/BedJetController.kt:62-83`

**Root cause.** The R06 protection works only on split reports: a head-only notification followed by a remainder read. Three paths still let an `extra` from before the start reach `_status` after the controller has subscribed with `drop(1)` and before the unit has received the start command.

1. **Full-status notifications.** The code asks for MTU 247 so that "the whole status [can] arrive in one notification" (`GattLink.kt:415-416`). A full packet bypasses every forget:
   ```kotlin
   val parsed = Codec.parseStatus(chunk) ?: return
   if (parsed.extra != null) {
       session.lastExtra = parsed.extra
       _status.value = parsed          // pre-start extra published to the subscriber
       return
   }
   ```
   The `send()` forget (`if (isStart(command)) session.forgetExtra()`) clears only session fields. It does not clear `_status`, and it has no effect on the next full notification. Any periodic notification that arrives between `prepareForStart()` and the unit acting on the start carries the old program state.
2. **A remainder read that finishes before the start write takes the queue.** A head-only notification can arrive after `prepareForStart()`. Because `prepareForStart` nulls `lastRemainderRead`, that notification immediately starts a remainder read under `operations.tryLock()` (`:318-329`). `send()` then waits on `operations` until the read finishes. The read therefore always completes before the start write, and it merges the pre-start `extra` into `_status`. Separately, `prepareForStart` runs without the `operations` lock, so a read that already captured `lastHead` (`:299`) can still merge after the forget.
3. **A reconnect inside `send`.** If no session is ready when `prepareForStart()` runs, `current?.forgetExtra()` does nothing. `send()` then reconnects through `ensureConnected()`. The new session's first notifications, full ones or a head plus a remainder read, report the program state from before the start, and they reach the subscriber before `send` takes the lock and writes.

**User-facing failure.** Take a Re-apply, Re-time, Boost or Start that replaces a program while the unit is reporting `programStep == 1`, for example during the first phase of the night. The start is reported as confirmed from the unit's previous state. If the unit never acts on the new start, and no refusal arrives within the 1.5 s grace, the night is recorded as CONFIRMED when it is not. The reverse case also exists: a refusal notification still pending from earlier (`PROGRAM_NEEDS_CLOCK` or `PROGRAM_TOO_LONG`) makes a good start fail with `ClockNotSet`, and the controller sends a needless acknowledge. How likely this is depends on whether the BedJet sends full statuses at MTU 247 and how often it notifies. **Needs hardware** to size the window. The code path itself is deterministic.

**Evidence that the tests miss it.** `GattLinkTest.anOldProgramStepDoesNotConfirmANewStartButAFreshOneDoes` (`:219`), `preparingForAStartForgetsTheProgramStepUntilAFreshReport` (`:239`) and `aReportBetweenTheClockAndTheStartDoesNotConfirmIt` (`:253`) inject only `Status.head()` while the start is pending. No test injects `Status.full(programStep = 1)` in that window.

**Minimal fix, in GattLink only.**
- Add `@Volatile var startPending: Boolean` to `Session`.
- `prepareForStart()`: under `operations.withLock`, set `current?.startPending = true`, call `forgetExtra()`, and null `_status.extra`.
- `send()` for a start command: set `startPending = true` on the live session before writing. This covers the reconnect path. After `write()` returns successfully, clear `startPending` and null `lastRemainderRead`, so the next head triggers a fresh read.
- `receiveStatus()`: while `session.startPending` is set, publish `parsed.copy(extra = null)`, leave `lastExtra` unchanged, and drop remainder merges.
- `forgetExtra()`/`retire` already handle cleanup on failure.

**Regression tests to add (GattLinkTest).**
- (a) Copy `aReportBetweenTheClockAndTheStartDoesNotConfirmIt`, but call `port.notify(Status.full(programStep = 1))` while `holdWrites` holds the start write. After 1.8 s, assert the start is not complete. Then `ackWrite()`, notify `Status.full(programStep = 1)`, and assert it completes.
- (b) Same idea for a head notification after `prepareForStart` whose remainder read is answered with `programStep = 1` before the start write is issued.
- (c) Retire the session (`link.shutdown()`) between `prepareForStart` and `send`. On the new session, a first status that is full with `programStep = 1` must not confirm.

**Affected tasks:** Task 1 (R06). Task 3/4 start, re-time, re-apply, Boost and pin+start confidence (R26, F15).

---

## BLE-2: Medium. The connection is not bound to the current target

**Files:**
- `app/.../ble/GattLink.kt:69-88` (connect: `if (current?.ready == true) return`; `address` captured once at `:74` and reused for every retry), `:165-171` (ensureConnected / live: no address check)
- `app/.../night/NightActions.kt:603-621` (updateSettings / selectTarget: `graph.link.disconnect()` comes before `repo.updateSettings`)
- `app/.../App.kt:72-74` (statuses attributed to the settings target)

**Root cause.** `GattLink` reads `target()` once per `connect()` call. After that, any session that is `ready`, or any retry inside the loop, counts as "connected", whatever the target is now. Target changes rely only on `NightActions` calling `link.disconnect()` before it saves the new settings. `LinkSessions` entry points are not under the NightActions mutex: `reconnect()` from the connection chip, `holdForScreen(true)` on activity re-show, and `endRelease()`→`reconnect()` when a timed release expires. Any of these can call `GattLink.connect()` inside that window.

- **Window A.** `disconnect()` sets `wanted = false` and retires. While `repo.updateSettings` is still doing its durable, off-main write, a racing `connect()` sets `wanted = true`, reads the old `target()` and attaches to the old BedJet. After settings save, every later command passes NightActions' target check (`request.deviceId == settings.deviceId()`). `acquire()` → `GattLink.connect()` then returns immediately, because `current?.ready == true` (`:72`), and commands go to the old BedJet.
- **Window B.** A connect to the old target is mid-retry (backoff of 500 ms or 1500 ms) when the target changes. A concurrent `connect()` flips `wanted` back to true, so the loop's `if (!wanted) break` (`:82`) does not stop it. The loop keeps retrying the captured old `address`.
- **Consequence for state.** `App.kt` maps `link.status` to the *settings* target (`link.status.map { target to it }`). `NightActions.observeStatus` (`:481`) only checks that target against settings. Statuses from the old unit are therefore treated as the new unit's: takeover/UNKNOWN decisions, unit notices, and the platform widget observation.

**User-facing failure.** The user switches to a replacement BedJet in Setup and taps "Reconnect" at the wrong moment, or a 10 s hand-over release expires at that moment. The app then sends Start, Boost or a manual change to the previous unit, which could be in another room or someone else's bed, while showing the new unit as the target. The window is narrow, but the result is a write to the wrong device.

**Minimal fix, in GattLink.**
- In `connect()`, change the early return to `current?.takeIf { it.ready && it.address == DeviceId.address(target()) }`. Otherwise retire the session and attach to the current target.
- In `ensureConnected()` and `live()`, apply the same address check. A mismatch retires the session and throws `NotConnected`, or reconnects for `ensureConnected`.
- In the retry loop, recompute `DeviceId.address(target())` on each attempt, and stop if it changed.
- Optionally expose the session's address with each status, for example a `StateFlow<Pair<String, DeviceStatus>?>` or an `address` field, so `App.kt` attributes reports to the unit that sent them rather than to settings.

**Regression tests to add.**
- `GattLinkTest`: `connect()` to A; change `target` to B; `send()` must not write on A's port. It must retire A (`ports[0].closed`) and connect to B (`fake.connects == [A, B]`).
- `GattLinkTest`: A answers `133` and a retry is pending; switch the target to B; the retry must not dial A.
- `LinkSessionsTest` or `RuntimeOwnershipTest`: `selectTarget(B)` with a `sessions.reconnect()` launched between disconnect and save. Assert that no command reaches A.

**Affected tasks:** Task 1 (R08/R21), Task 4 (R16/R20 target serialisation), Task 6 (hand-over timed release, widget observation).

---

## BLE-3: Low. The adapter forwarding test never exercises the production path

**File:** `app/.../ble/AndroidBedJetLink.kt:32,48`; `app/src/test/.../ble/AndroidBedJetLinkTest.kt:22-28`

```kotlin
override suspend fun prepareForStart() = (preparedLink ?: engine).prepareForStart()
...
val adapter = AndroidBedJetLink(context, demo) { null }
adapter.prepareForStart()
assertNull(demo.status.value!!.extra)
```

**Root cause.** The test injects `DemoBedJet` as `preparedLink`, so it only proves that the seam works. Production always passes `preparedLink = null` (`App.kt:51`), and the `engine` branch is never tested. For example, if line 48 were changed to `preparedLink?.prepareForStart()`, the test would still pass while the R06 no-op regression returned. The seam also *replaces* the engine call instead of observing it, so it is a production-visible parameter that changes behaviour.

**User-facing failure.** None today. This is a guard against a future regression of the Task 4 carried defect (`docs/review/task-4-implementation.md:117`, `sdd-ledger.md:117`).

**Minimal fix.**
- Replace `preparedLink` with an `internal` constructor parameter `radio: GattRadio? = null`, used as `GattLink(radio ?: AndroidRadio(...), ...)`.
- Test through `FakeRadio`: connect with `firstStatus = Status.full(programStep = 1)`, call `adapter.prepareForStart()`, and assert `adapter.status.value?.extra == null`.

**Regression test:** the one described in the fix.

**Affected tasks:** Task 1, and the Task 4 carried fix.

---

## BLE-4: Low. A timed release can stay "released" after a wall-clock jump

**File:** `app/.../night/LinkSessions.kt:119-151`

```kotlin
releaseEnds = untilMs?.let { scope.launch { delay(it - now()); isReleased() } }
...
if (now() < until) return true   // isReleased(): no reschedule
```

**Root cause.** `delay` measures monotonic time, while `now()` is the wall clock (`graph.clock.millis()`). If the wall clock moves backwards during the release (network time correction, manual change), the single check sees `now() < until`, returns true, and schedules nothing more. `_released` stays true. With the screen open, nothing calls `isReleased()` again: `holdForScreen` is already held, and the connection chip shows released. The app does not reconnect until the user taps Take back or another command runs `acquire()`.

**User-facing failure.** After a 10 s Lull/peer hand-over (`PhoneHandover.peerRequest`), the app can stay disconnected for an unbounded time while the user believes it will take the BedJet back automatically.

**Minimal fix.** Loop instead: `while (isReleased()) delay(max(1_000, until - now()))`. Alternatively, track the deadline with a monotonic clock.

**Regression test (LinkSessionsTest).** Use an injectable `now`. Call `suspendAutoConnect(now + 1_000)`, then move `now` back by 5 s before the delay elapses. Assert that `released` becomes false once `now` passes `until`.

**Affected tasks:** Task 1 (F12 support), Task 6 (hand-over).

---

## Verified correct (with evidence)

1. **Production forwards `prepareForStart` (focus 1).** The object graph is:
   - `App.onCreate` → `Graph(this)` (no `linkOverride`)
   - → `Graph.link` returns `bleLink` when not in demo (`App.kt:57`)
   - → `bleLink = AndroidBedJetLink(context) { … deviceId() }`, where `preparedLink` defaults to null (`App.kt:51`)
   - → `AndroidBedJetLink.prepareForStart() = (null ?: engine).prepareForStart()` (`AndroidBedJetLink.kt:48`)
   - → `GattLink.prepareForStart()` (`GattLink.kt:114-117`)

   `Graph.controller() = BedJetController(link)` (`App.kt:78`), and every start path goes through `graph.controller()` or `graph.runner()`: `NightActions.kt:544` and `NightRunner.kt:181`. No other `BedJetLink` wrapper or delegate exists in `app/src/main` (grep found only GattLink, AndroidBedJetLink and DemoBedJet). Demo also overrides it (`DemoBedJet.kt:55`).
2. **Controller ordering.** The order is `syncClock` → `prepareForStart` → subscribe with `UNDISPATCHED` + `drop(1)` → `beforeStart()` → `send(start)` (`BedJetController.kt:63-76`). If `beforeStart` throws, `coroutineScope` cancels the waiter and no start is sent.
3. **Stale callbacks.** Every sink callback runs through `callback { if (current !== this) return shut() … }` (`GattLink.kt:446-453`). This closes the stale GATT instead of acting on it. `answer()` uses a CAS on `pending`, and a kind or attribute mismatch retires the session before the waiter fails (`:456-469`).
4. **Timeouts and cancellation retire and close the GATT.** See `exchange()` (`:274-289`) and the attach `finally { if (!attached) retire(session) }` (`:215-217`). `shut()` cancels the scope, fails `services` and `pending`, and calls `disconnect()` + `close()` (`:392-403`). A port that arrives after shutdown is closed in `attachPort` (`:386-389`).
5. **Bounded retry, same address only.** There are `0..retryBackoffMs.size` attempts (3) on the captured address (`:80-84`). No scan fallback picks another unit, and with no target the error carries the setup message (`:74-77`). The address is stale after a target change; see BLE-2.
6. **Disconnect during upload (focus 3).** A drop or timeout during a fragment or name write fails the waiting `pending` with `NotConnected` or `Timeout`, and the session is retired, closing the GATT (`:224, :280, :396-397`). `NightActions.writeSlotLocked` marks `writing = true` before `store`, so any exception becomes `Confidence.UNKNOWN` and is persisted under `NonCancellable` (`NightActions.kt:531-555`). A drop *between* fragments reconnects transparently on the next `send`. That is acceptable because each fragment carries its slot, index and total (`Codec.writeProgram`, `0x40, fragment(slot,index), size`), and `store` always reads back and compares (`BedJetController.kt:51-53`). Whether the unit keeps a partly written slot across a disconnect **needs hardware**; the readback check covers it either way.
7. **The remainder read cannot leak the queue.** `tryLock()` is paired with `invokeOnCompletion { operations.unlock() }`, and that runs even when the session scope was already cancelled (`:318-329`). Non-cancellation exceptions are swallowed.
8. **API 33 versus legacy.**
   - Writes use `writeCharacteristic(c, data, WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS` on API 33+, and the deprecated value/writeType path below that (`AndroidBedJetLink.kt:146-157`).
   - CCCD writes follow the same split (`:163-176`).
   - The deprecated `onCharacteristicRead`/`onCharacteristicChanged` overrides run only below API 33, so nothing is delivered twice (`:205-218`).
   - `onConnectionStateChange` binds the gatt if the callback beats `connectGatt` (`:189-193`).
9. **Permissions.** `minSdk = 31`, so checking `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` is valid on every supported level (`AndroidBedJetLink.kt:68-69`). A `SecurityException` from any stack call becomes `NotConnected` + `UNAUTHORIZED`, and the session is retired (`GattLink.kt:336-350`).
10. **MTU and discovery.** `requestMtu(247)` has a 1.5 s fallback to discovery, and a refused `discoverServices` is retried when the MTU answer arrives (`GattLink.kt:413-424, 472-476`). The connection uses `autoConnect = false` and `TRANSPORT_LE` (`AndroidBedJetLink.kt:94`). If MTU is never negotiated, the 43-byte fragment writes depend on Android's automatic long write for `WRITE_TYPE_DEFAULT`. Whether the unit accepts that **needs hardware**.
11. **Threading.** Callbacks run on a binder thread and touch only atomics, `@Volatile` fields and `StateFlow`. Nothing in the transport needs the main thread.
12. **LinkSessions holder accounting.**
    - `acquire` undoes its hold on any throw (`LinkSessions.kt:64-74`).
    - The last release cancels connects that are still under way (`:76-90`).
    - A connect that finishes after the last release, or while released, is disconnected under `NonCancellable` (`:180-187`).
    - When released, `acquire` throws `NotConnected("Released for another phone")`, and the screen hold skips connecting (`:67, :102`).
    - `resumeAutoConnect` cancels the timer and reconnects only while the screen is held (`:133-143`).
    - Nesting is last-writer-wins on `releasedUntil`, not counted. That is acceptable for a single explicit hand-over, and a single resume ends it.
    - `suspendAutoConnect(...).join()` makes release ordering visible to `NightActions` (`NightActions.kt:646`).
13. **Verified-name metadata (Task 6 change in scope).** Metadata is cleared for each new session before anything is read (`GattLink.kt:182`). It is published only for `current === session && wanted && ready`, and it is bound to the session's own address, not to a mutable target (`:196, :203-204`). A timed-out 2001 read retires the session, and the retry skips the metadata read (`:83, :201`).

## Not reported (checked, not defects)
- `exchange` throws `NotConnected` without retiring when the stack refuses to start an operation (`start()` returns false). The session stays usable and the error is surfaced. A slightly misleading message is all it costs.
- In `connect()`, `wanted` is set outside the `connecting` mutex. This only matters together with BLE-2, and the BLE-2 address check fixes it.

# Nocturne storage/durability review (Task 2 persistence as of 7fffabc)

Reviewer: independent, read-only. No Gradle run, no hardware. Scope: `app/.../data/Repository.kt`, `core/.../store/*`, `night/RuntimeState.kt` persistence, their callers in `NightActions`/`NightRunner`/`ChargerRun`, and the tests. Paths are shortened to `Repository.kt` = `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt`, `NightActions.kt` = `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt`, and store files = `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/`.

## Summary

| ID | Sev | Title | Status |
|---|---|---|---|
| STO-1 | High | An unreadable `slot-backups.json` makes the app record its own program as the "original"; restoring a snapshot can't fix it | new |
| STO-2 | Medium (High together with STO-1) | `unreadable` is never shown and `dismissUnreadable()` can't be reached, so charger auto-start is blocked silently with no way to clear it | previously reported (sdd-ledger:40, HANDOVER:434), still open; R23 says "auto-verified" but that is only true at the repository level |
| STO-3 | Medium | Import isn't atomic across a crash: 4 separate AtomicFile writes, no journal and no recovery; the rollback can itself fail | previously reported as minor (sdd-ledger:30); severity raised because R22/HANDOVER:21 claim "all-or-nothing" |
| STO-4 | Medium | SAF import/export stream I/O runs on the single durable writer and blocks night journals and the 8 s receiver budgets | previously reported as minor (sdd-ledger:31); severity raised because of Task 4/6 |
| STO-5 | Medium | Imported slot backups are merged into live originals without validation, imported "unknown" ones are adopted by whichever BedJet is selected, and Restore original then writes them to the hardware | partly reported (sdd-ledger:34: validator skips programs); the adoption path is new |
| STO-6 | Low | An import during a night deletes tonight's history record; later events go nowhere | new |
| STO-7 | Low | No directory fsync for snapshots/AtomicFile; stray `.tmp` files are never cleaned | previously reported (sdd-ledger:36) |
| STO-8 | Low | Restore replaces tonight-scoped settings (`tonightOverride`/`tonightChoice`) and leaves v1 `selectedProfileId` unmigrated | new |
| STO-9 | Low (uncertain) | v1 never recorded empty originals, so after the upgrade the first write on a slot v1 used records the app's old program as "original" | new |
| STO-10 | Low | Device-local journals (`runtime.json`, `active-night.json`, `automation.json`, `unreadable.json`, `*.corrupt-*`) are not excluded from device transfer | new |

---

## STO-1 — High — Unreadable slot backups → the app's own program recorded as the original

- **Where:** `Repository.kt:93` (load → empty list on unreadable), `Repository.kt:181-188` (`recordOriginalSlot`), `Repository.kt:257-259` (restore merge: current wins), `NightActions.kt:180-185` (`backup`).
- **Root cause:** if `slot-backups.json` can't be decoded, it is set aside and `_slotBackups` becomes `emptyList()`. Nothing gates slot writes on that: `backup()` finds no existing original, reads the slot (which by now usually holds Nocturne's program from an earlier night) and records it durably as the first original. From then on "the first original is kept". If the user later restores a snapshot that has the true original, the merge keeps the **current** record: `list + bundle.slotBackups.filter { restored -> list.none { it.covers(restored) } }`. The true original can't be recovered through the app; it survives only in `slot-backups.json.corrupt-<ts>`.
- **Evidence:**
  ```kotlin
  private val _slotBackups = MutableStateFlow(load(SLOT_BACKUPS, Migrations::decodeSlotBackups).orEmpty())
  ...
  val original = existing?.program ?: if (existing == null) graph.controller().storedPrograms()[slot] else null
  repo.recordOriginalSlot(device, slot, original)
  ```
  The only consumer of `unreadable` that gates anything is `ChargerRun.kt:44`. Start, Boost, Pin and Restore are not gated.
- **Scenario:** a downgrade, a bad sector, or a hand-edited file makes `slot-backups.json` unreadable. The user sees nothing (STO-2), starts a night, and the slot's "original" becomes last week's Nocturne program. "Restore original" writes that program, and the user's own BedJet program is lost for good. This defeats R19.
- **Minimal fix:** in `recordOriginalSlot` and `originalsFor`, throw when `SLOT_BACKUPS in _unreadable.value` (for example `IOException("Saved slot originals couldn't be read; restore a snapshot first")`). `writeSlotLocked`/`apply` already turn a backup failure into FAILED with no write. Optionally, in `restore`, let snapshot originals win over records saved after the unreadable event.
- **Regression test:** `RepositoryTest.anUnreadableSlotBackupFileBlocksRecordingOriginals`: write `{garbage` to `slot-backups.json`, build the repo, then `assertThrows { recordOriginalSlot("ble:AA:..", 2, program) }` and assert that no new `slot-backups.json` exists. A runtime test: Start with unreadable slot backups → FAILED, zero `store` calls.
- **Affected tasks:** 2 (Repository), 4 (NightActions backup), 6 (charger is the only gate).

## STO-2 — Medium — The unreadable state is never shown and can't be dismissed

- **Where:** `Repository.kt:81`, `Repository.kt:214`. No consumer in `app/src/main/.../ui` (grep: the only use outside Repository is `platform/ChargerRun.kt:44`).
- **Root cause:** the banner and Dismiss UI were deferred to Task 5 and are still unimplemented at 7fffabc.
- **Scenario:** a file goes corrupt and its defaults show silently (for example presets instead of the user's profiles). Charger auto-start is refused forever (`prerequisites = ... && repo.unreadable.value.isEmpty()`), with no reason shown and no way to clear it except a restore. STO-1 has no warning at all.
- **Evidence:** `grep -rni unreadable app/src/main | grep -v Repository.kt` → only ChargerRun. HANDOVER:434 lists the banner as a Task 5 to-do. Register R23 is marked "auto-verified" for "unreadable state exposed".
- **Fix:** expose `repo.unreadable` in `AppViewModel`; add a banner with Restore (Settings snapshots) and Dismiss (`dismissUnreadable`). Note the unreadable state in the charger refusal reason.
- **Regression test:** a Compose test that seeds a corrupt `profiles.json` and checks that the banner is visible, that Dismiss clears it and that it stays cleared after a restart.
- **Status:** previously reported (sdd-ledger:40), still open. The register should say "repository-level only; UI pending".

## STO-3 — Medium — Import/restore is not crash-atomic

- **Where:** `Repository.kt:246-274`, `Repository.kt:321-332`.
- **What is written, in order:**
  1. The pre-import snapshot: `SnapshotStore.save` writes `snap-<ms>-m.json.tmp`, runs `fd.sync()`, then `ATOMIC_MOVE` to `.json`, then prunes automatic snapshots (`SnapshotStore.kt:30-44`). It is durable before the first overwrite with respect to process death (verified). Against power loss there is no directory fsync (STO-7).
  2. Then four independent `writeDurably` AtomicFile replaces: `profiles.json` → `settings.json` → `history.json` → `slot-backups.json`.
  3. Then the in-memory publish.
- **Root cause:** each file is atomic, but the set is not. Rollback runs only for `Exception` thrown in-process. On process death, power loss, or an `Error` (OOM/`StackOverflowError` is not caught by `catch (e: Exception)`) between files 1 and 4, the disk is left mixed: new profiles with old settings/history. There is no intent journal and nothing in `init` detects or repairs it. On ENOSPC the rollback (`runCatching { writeFile(done.old) }`) needs a temp file of similar size and can fail too. The error is thrown with suppressed causes, memory shows the old values, but the disk holds a mix that appears on the next restart and is not flagged as unreadable.
- **Scenario:** the user imports a backup on a low-memory phone and the process is killed after `profiles.json`. On relaunch the imported profiles show alongside the old settings: `tonightChoice` points to a profile id that no longer exists, so `resolveProfile` falls back to the weekday or first profile, and `workingSlot` and `boost` are old. The UI earlier showed no success message, so the user doesn't know a partial import happened. The pre-import snapshot exists, but nothing points the user to it.
- **Fix (minimal):** before the first overwrite, durably write `restore-pending.json` = `{snapshotId, files: {name: base64(new)}}` with `writeDurably`. Delete it after the last write. In `init`, before the `load(...)` calls, if it exists, roll forward (re-write all four files) and delete it. Alternatively, keep all four in one file. Also catch `Throwable` for the rollback.
- **Regression test:** in `RepositoryTest`, use a `writeFile` that writes `profiles.json` and then throws an `Error` (simulating death, so no rollback). Build a new `Repository` on the same dir and assert `bundle()` is entirely old or entirely new. A second case: `settings.json` throws IOException and the rollback write of `profiles.json` throws too → after a restart the state is coherent or the restore is reported as interrupted.
- **Status:** previously reported as minor deferred (sdd-ledger:30). Raised because HANDOVER:21/R22 describe import as "all-or-nothing", which holds only for in-process exceptions.

## STO-4 — Medium — SAF import/export holds the single durable writer

- **Where:** `Repository.kt:283-294`.
  ```kotlin
  suspend fun exportTo(uri: Uri) = withContext(writer) { ... contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ... }
  suspend fun importFrom(uri: Uri): BackupBundle = withContext(writer) { contentResolver.openInputStream(uri)?.use { importFrom(it) } ... }
  ```
- **Root cause:** `writer = Dispatchers.IO.limitedParallelism(1)` is the only thread for every durable write. A cloud document provider (Drive, OneDrive) can block `openInputStream`/`write` for many seconds.
- **Scenario:** the user imports or exports to Drive at night. Meanwhile an alarm-change receiver (`NightActions.kt:281/289/411`, budget `withTimeout(8_000)`) or `publishNight(PENDING)` before a BLE write waits behind the provider. Receiver submissions time out and fall back to a best-effort retry, and commands stall toward the 90 s `OPERATION_MS`. Also, `withTimeout` cancelling a write that already ran reports failure although the write is durable and published (`withContext` re-checks cancellation on return).
- **Fix:** do SAF stream I/O on plain `Dispatchers.IO`. Take only `bundle()` under `serially { }` for export, and decode on IO for import.
- **Regression test:** a gated `InputStream` that blocks in `importFrom(stream)` on the real path, plus a concurrent `updateRuntime` that must finish within a short timeout.
- **Status:** previously reported as minor (sdd-ledger:31). Raised because Task 4/6 now rely on 8 s writer budgets.

## STO-5 — Medium — Imported slot backups are unvalidated, adopted across units and written to hardware

- **Where:** `BackupValidator.kt:130-133` (checks only slot range and time), `Repository.kt:257-259` (merge), `Repository.kt:377` and `Repository.kt:384-392` (adoption), `NightActions.kt:180-185` (an existing record short-circuits reading the slot), `NightActions.kt:521-529` (Restore writes `original.program`).
- **Root cause:**
  - (a) The `StoredProgram` steps in imported backups are not range-checked (mode/temperature range, fanStep 0..19, durations, 1..`STEPS_PER_PROGRAM`, `empty == (program == null)`, `deviceId` format). `Codec.writeProgram` clamps temperature to only 0..255.
  - (b) A v1 backup, or any bundle with `deviceId = "unknown"`, adds originals that the next real BedJet adopts for each slot it has none for. That can be a different unit, or the imported file may be from another household. After adoption, `backup()` uses the adopted record and never reads the slot's real content.
- **Scenario:** on a new phone the user imports an old v1 backup made with a different BedJet (or a friend's). The first night on slot 3 adopts the imported "original", the real slot-3 program is never backed up, and "Restore original" writes the imported program over it.
- **Fix:** in `restore`, don't merge imported backups whose `deviceId == UNKNOWN_DEVICE` (migration-on-disk adoption stays as it is), or merge only those whose `deviceId == current.targetDevice`. Extend the validator to check program steps, `empty`/`program` consistency and `deviceId` format.
- **Regression tests:**
  - `BackupValidatorTest.slotBackupProgramOutOfRangeIsRefused` (fanStep 40, Heat at 200 half-degrees).
  - `RepositoryTest.anImportedUnknownOriginalIsNotAdopted`: restore a v1 bundle with slot 1, call `originalsFor("ble:..")`, assert no slot-1 record; then `recordOriginalSlot` records the live program.
- **Status:** (a) previously reported (sdd-ledger:34). (b) is new. The adoption design (`Repository.kt:195-201`) assumes an "unknown" original came from the BedJet the app was used with, which holds for on-disk migration but not for imported files.

## STO-6 — Low — An import during a night drops tonight's history record

- **Where:** `Repository.kt:256` (`History.prune(bundle.history, now())` replaces history wholesale). `History.kt` `addEvent` is a no-op for a missing id. `NightActions.kt:245-247` (`completeLocalEnd` → `addEvent`).
- **Scenario:** a restore during a running night keeps `activeNight` (by design) but removes its `NightRecord` unless the bundle contains it. Snooze, extend and END events go nowhere and the night ends with no history entry. A later apply recreates a bare record (`NightActions.kt:153-156`) without the earlier events.
- **Fix:** in `restore`, carry over current records whose id equals `_activeNight.value?.recordId` or `runtime.ending?.identity?.nightId`.
- **Test:** `restoreKeepsTheActiveNightsRecord`.

## STO-7 — Low — No directory fsync; stray temp files

- **Where:** `SnapshotStore.kt:37-41`; `Repository.kt:420-430` (AtomicFile also renames without a directory fsync).
- **Detail:** the content is fsynced but the rename is not made durable. After a power cut a snapshot can be lost or survive only as `.json.tmp`, which `list()` ignores and nothing deletes. Whether the renames reach disk on the Samsung f2fs `/data` is uncertain. Process-death durability is fine.
- **Fix:** after the move, open the directory and `FileChannel.force(true)`, or accept it as is. Delete `*.tmp` older than a minute in `list()`/`prune()`.
- **Status:** previously reported (sdd-ledger:36).

## STO-8 — Low — Restore overwrites tonight-scoped settings

- **Where:** `Repository.kt:254`.
  ```kotlin
  val settings = bundle.settings.copy(demoMode = current.demoMode, targetDevice = current.targetDevice, platform = ...)
  ```
- **Detail:** `tonightOverride`/`tonightChoice` come from the backup. A running night with an override then shows no override in Settings (the night itself is protected: `prepareRetime` refuses OVERRIDE). A restored `selectedProfileId` (v1 bundle) isn't passed through `migrateTonightChoice(now())`, and `tonightsProfile()` migrates it against `settingsLoadedAt`, so it can resolve as expired until the next settings write turns it into a fresh "tonight" choice.
- **Fix:** keep `current.tonightOverride`/`tonightChoice` and apply `.migrateTonightChoice(now())` to the restored settings.
- **Test:** `restoreKeepsTonightsOverrideAndMigratesV1Selection`.

## STO-9 — Low (uncertain about real-world frequency) — v1 slots whose original was empty

- **Where:** v1 `NightRunner.backupIfNeeded` at `e4b0cae` returns null for an empty slot (`?: return null`), so v1 never recorded an empty original. Current `NightActions.kt:180-185`.
- **Detail:** after the upgrade, a slot v1 used holds v1's last program, and no migrated record exists for it. The first v2 write records that program as the original (`recordOriginalSlot`). "Restore original" later writes an old Nocturne night instead of leaving the slot empty. No user data is lost, but the restore is wrong. The same applies to a migrated legacy night (`deviceId = "unknown"`): `Eligibility` lets it be rewritten via `legacyFallback`, and its backup is recorded under "unknown" and adopted later.
- **Fix:** record a one-time migration note that v1 slots without records are of uncertain origin, or compare the slot's program name with profile names and `"Boost"` and flag the backup. Low priority.

## STO-10 — Low — Device-local journals travel in device transfer

- **Where:** `app/src/main/res/xml/data_extraction_rules.xml`: only `secrets.xml` is excluded. `AndroidManifest.xml:25` sets `allowBackup="false"`, which on API 31+ still allows device-to-device transfer, and minSdk is 31.
- **Detail:** `runtime.json` (slotWrite PENDING/UNKNOWN, generation, inbox), `active-night.json`, `automation.json` (claims), `unreadable.json` and `*.corrupt-*` are copied to a new phone. A transferred UNKNOWN `slotWrite` or `unreadable` marker blocks charger auto-start there. Task 4/6 describe these files as device-local.
- **Fix:** add `<exclude domain="file" path="runtime.json"/>` and the same for the other files under `<device-transfer>` and `<cloud-backup>`. Extend `BackupRulesTest`.

---

## Verified correct

- **Durable before publish, and serial:** `Repository.kt:308-315` reads and writes under the Mutex on the single-thread writer, writes, then publishes. A failed write keeps the old value and throws. The same pattern holds in `recordOriginalSlot` (181-188), `claimAutomation` (114-122), `adoptMigrated` (384-392) and restore (266-272, publish after all writes).
- **Atomic replace:** `writeDurably` uses AtomicFile (fsync, then rename; `Repository.kt:420-430`). Loads go through `AtomicFile.readFully`, which recovers a leftover backup or new file.
- **One writer process:** there is no `android:process`, and there is one Repository per process (`App.kt:50`). Receivers, widgets and service share it, so there are no cross-process writers.
- **Export consistency:** `exportTo` runs `bundle()` on the single writer thread, and restore's publish block has no suspension point, so an export can't see a half-published restore.
- **Every hardware slot overwrite backs up first and aborts on failure:**
  - `NightRunner.kt:166-178` calls `beforeWrite()` before `controller.store`; a throw is caught with `writeMayHaveHappened=false` and the night is marked FAILED.
  - `NightActions.kt:531-541` (`writeSlotLocked` for Boost, Pin, Restore and delete) runs `backup()` before `store`/`deleteProgram`; a throw leaves `writing=false`, marks it FAILED and writes nothing.
  - Charger and handover starts go through `performJobOwned`, then START_NIGHT, then `apply`.
  - `controller.store` has only these two production callers (grep).
- **Device binding:** adoption needs a real BLE address (`Repository.kt:377`). Demo and a null target never adopt (`Repository.kt:202-205`). Lookups are per `deviceId` (`Repository.kt:191-193`). A target change outside a night reads the new unit's slot.
- **Import validation happens before any change, with a pre-import snapshot first:**
  - `Repository.kt:247-251` validates before the lock and the snapshot is saved before any overwrite.
  - `SnapshotStore.kt:37-41` fsyncs the content before the atomic move.
  - Restore keeps target, demo, token and active night, and sanitises the charger opt-in (`Repository.kt:254-255`).
- **Corrupt-file handling:**
  - Unreadable files are queued for set-aside on the FIFO writer before any later write (`Repository.kt:134`), so a default can't overwrite them.
  - The marker persists (`Repository.kt:370-373`) and automatic snapshots pause (`Repository.kt:301`).
  - The deferred "dismissUnreadable may race the marker write" (sdd-ledger:56) is not reachable in production: the set-aside task is enqueued first on a FIFO single-thread dispatcher.
- **Migrations:**
  - Record-wise and idempotent (`Migrations.kt:30-56`).
  - All fields added by Tasks 3/4/6 have defaults: `ActiveNight.kt:45-53`, `RuntimeState.kt:8-19`, `Settings.kt` `tonightChoice`/`tonightOverride`/`platform`, `PlatformSettings` defaults.
  - Newer schemas are refused (`Backup.kt:69`).
  - Unknown keys are ignored; an unknown enum is set aside (load) or reported as DAMAGED (import).
  - `PlatformSettings.validationErrors` is delegated to the validator (`BackupValidator.kt:175`).
- **Secrets:**
  - The token lives only in the `secrets` SharedPreferences.
  - `bundle()` leaves it out, and so do snapshots built from it.
  - Diagnostics (`NightActions.kt:680-688`) include `Settings`, which has no token.
  - XML rules exclude `secrets.xml` from cloud backup and device transfer.

## Test gaps worth adding

- Crash in the middle of an import (STO-3).
- Unreadable slot backups combined with a slot write (STO-1).
- Imported unknown-device originals (STO-5).
- Restore during an active night keeps its record (STO-6).
- A writer blocked by SAF while a receiver submits (STO-4).

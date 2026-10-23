# Nocturne completion review

23 October 2026. Branch `claude/nocturne-completion` starts from independent-review commit `ae6e4d52a38dc2f295dd5c26f0f4614a24fed587`, which contains hardening commit `7fffabcb58741c48b765285d78c9284ce9967c27`. The remote had no newer fix branch when fetched. The Task 5 checkpoint SHAs described in the handover were unavailable from this clone and remote; the missing UI integration was reconstructed against the reviewed base.

This table disposes of every finding in `independent-review-tasks-1-4.md`, using the narrower qualifications in `final-pass.md`. “Implemented” means source and automated regression work; it does not mean a Samsung phone or BedJet 3 has passed physical acceptance.

| Finding | Disposition and evidence |
|---|---|
| IR-01 | Implemented: automatic re-time requires fresh matching mode, temperature, fan and programme step; a second status check after upload blocks an observed physical Off before START. Runtime ownership regressions cover disconnected and in-flight Off. A physical change after the last read remains a hardware race for supervised acceptance. |
| IR-02 | Implemented: target-bound GATT retries, attach, status and operations; target-change/late-callback regressions. |
| IR-03 | Implemented: persistent originals-integrity barrier survives restart and notice dismissal; Start, Boost and pin refuse writes when originals are unreadable. Corrupt/restart regressions. Since P4 the barrier has one explicit lift path for a slot with no exact backup: `Repository.dismissUnreadable(acceptLostOriginals = true)` (`AppViewModel.acceptLostOriginals()`, shown while `originalsBlocked` is true). It records the acceptance durably (`slot-backups-loss-accepted.json`), then removes the barrier; originals recorded from then on are marked `uncertain`, and a restored backup's exact record of the same BedJet slot replaces such a one. The UI must ask for explicit confirmation first; placing that control is a later UI task. |
| IR-04 | Implemented: per-session pre-start barrier suppresses old progress across notification, remainder and reconnect paths. GATT regressions. |
| IR-05 | Implemented: pre-write failure keeps a previous confirmed night and reports failed operation; core transaction regression. |
| IR-06 | Already fixed on the reviewed base: stale slot-write state is cleared and bounded to its operation/night; runtime regression retained. |
| IR-07 | Implemented: post-wake alarm observations are quiet and issue no hardware work; delivery regression. |
| IR-08 | Already fixed on the reviewed base: manual confirmation needs fresh matching status and remains takeover-observable; runtime regression retained. |
| IR-09 | Implemented: in-app manual programme changes update tracking and cancel obsolete re-time; runtime regression. |
| IR-10 | Implemented: final clock-time and offset eligibility checks, including the transport boundary; core regression. |
| IR-11 | Implemented: minimum remaining queue budget and guarded START without a reconnect inside the final operation; ownership/transport regressions. |
| IR-12 | Implemented with strict stale identities retained for other actions: app-level widget updates and target-bound Off resolution; background widget regression. |
| IR-13 | Implemented: durable multi-file import and check-in journals recover after process death; repository fault regressions. Directory durability is best effort on Android and needs physical acceptance. |
| IR-14 | Implemented: unreadable-data banner with View, Dismiss and Restore; UI/repository regressions. |
| IR-15 | Implemented: external picker I/O runs outside the serial repository writer; slow-provider concurrency regressions for import (`slowExternalImportDoesNotHoldTheNightStateWriter`) and, since P4, export (`slowExternalExportDoesNotHoldTheNightStateWriter`). |
| IR-16 | Implemented: imported slot programmes are validated, provenance is retained, and unknown imported originals are not adopted for a newly selected device; migration/import regressions. Since P4, saved programmes are checked against what the codec carries (temperature 0–255, not the mode range or heat cut-off table, so the app's own snapshots restore), and a saved original must name `demo`, `unknown` or a `ble:<address>` device (`BackupValidatorTest.aRecordedOriginalOutsideTheModeRangeStillPasses`, `aSlotBackupMustNameABedJet`). |
| IR-17 | Implemented: one shared start preview drives Tonight headline and curve, including override and noon expiry; UI/core regressions. |
| IR-18 | Implemented: outer Android BLE adapter is exercised with FakeRadio rather than a prepared demo link. |
| IR-19 | Implemented: timed release rechecks wall-clock deadline after monotonic waits; clock-jump regression. |
| IR-20 | Implemented: a changed zone offset makes clock-time start unsafe and refuses it; core regression. |
| IR-21 | Implemented: a known pre-START refusal is FAILED; a possible in-flight START remains UNKNOWN. Transport-boundary and ambiguous acknowledgement regressions. |
| IR-22 | Implemented: hand-over preserves UNKNOWN explanation and eligible Resume offer; runtime regression. |
| IR-23 | Implemented: the first START intention/history record is durable before hardware writing; runtime regression. |
| IR-24 | **Rejected as a confirmed crash** per `final-pass.md`: the supplied evidence does not establish an Android foreground-service timeout after timely stop. Framework/Samsung timing remains **NOT RUN**. No blanket foreground promotion was added. |
| IR-25 | Implemented: expected background FGS denial keeps durable retry without an alarm-error notification; delivery regression. Samsung frequency remains unmeasured. |
| IR-26 | Implemented: wake/Resume notifications reconcile against persisted current state, including deliberate manual wake and elapsed Resume expiry; delivery regressions. |
| IR-27 | Implemented: busy time/zone reconciliation persists and retries rather than reporting a false command failure; receiver regression. |
| IR-28 | Implemented: importing history preserves the current night's record and, since P4, that of a night being ended (`runtime.ending`); repository regressions `importPreservesTheLiveNightsHistoryRecord`, `importKeepsTheRecordOfTheNightBeingEnded`. |
| IR-29 | Implemented best-effort parent-directory sync and regular snapshot temporary-file cleanup; store regressions. Until P4 this covered snapshots only: `Repository.writeDurably` now also syncs the directory after its rename (`RepositoryTest.aDurableWriteSyncsTheDirectoryAfterTheRename`). Power-loss durability on the actual phone remains unverified. |
| IR-30 | Implemented: restore preserves the current tonight's choice and wake override, including a null override. A version 1 `selectedProfileId` in the backup is migrated and becomes tonight's choice only when none is set (corrected in P4: until then it was dropped, despite this row); repository regressions `restorePreservesTheCurrentTonightChoiceAndWakeOverride`, `restoreMigratesAVersion1SelectionIntoAnEmptyTonightChoiceButKeepsAnEmptyWakeOverride`. |
| IR-31 | **Conditional unresolved legacy limit:** a v1 empty slot was never recorded. An old `[]` cannot prove whether it was originally empty. Since P4, every v1-migrated original carries `SlotBackup.uncertain = true` (kept through adoption and backups; `MigrationsTest.version1OriginalsAreMarkedUncertain`); showing it in Settings and the restore outcome is a later UI task. Imported unknown originals remain read-only; a supervised original-slot inventory/restore is required before accepting an upgraded real installation. |
| IR-32 | Implemented: exact-file automatic-transfer allowlists carry portable profiles/history and the slot originals with their integrity barrier together. Runtime, target, journals and update-token preferences are excluded. XML regression and lint. |

## Scope and remaining acceptance

Task 5 now places setup, platform settings, history export, sleep overlay, hand-over, recovery, wake override, check-in and explicit spare-slot pinning in the existing Midnight Ink/Daylight/Simple UI. Pinning checks the captured preview and target/profile/slot under command ownership. A 320 dp / 200% text layout uses a compact navigation bar with accessibility names. New copy is in UK English resources; migration of all older literal UI strings remains incomplete.

Task 7 makes unsigned release builds explicit when no key is configured and fails partial signing configurations. Release builds enable R8 and resource shrinking, with no blanket keep rules. The tag workflow requires a signing key, explicit increasing version code and expected certificate fingerprint. This branch does not create a tag or publish a release.

Fixed-clock Robolectric captures use 22 October 2026 23:30 Europe/London before Activity composition. The before set uses the hardening commit with only its screenshot fixture clock pinned; the after set adds the same views plus 320 dp / 200% and feature placements. These are rendered app evidence, not device acceptance.

Every physical result in `samsung-bedjet-acceptance.md` remains **NOT RUN**. The first supervised checks are test-signed install/upgrade and signer match; explicit BedJet 3 target choice; transport/Off; all three original slots and restore; then preview/override and interrupted-write recovery. Keep a separate physical Off path and original-slot exports available during that work.

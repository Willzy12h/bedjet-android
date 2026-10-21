# Second Claude review (commit 32687d9), not yet fixed

Verdict: **ready with fixes.**
- No critical issues and no deadlocks.
- All tests and lint passed.
- Fix 1–3 before relying on it overnight; 4–8 soon after.

## Important

1. **Snooze/Extend re-pops the wake-up notification and wipes its "until hh:mm" text.**
   - **Where:** `night/NightActions.kt` (wakeAction) together with `night/NightAlarms.kt` (schedule).
   - **What goes wrong:** `wakeAction` calls `schedule(extended)`, which re-arms WAKE_UP at a `wakeUpStart` that is already past. That alarm fires at once, so the generic notification shows again (heads-up) and a duplicate entry is logged.
   - **Fix:** re-arm only NIGHT_END, or have `schedule` skip alarms whose time has passed.
2. **Changing mode on the Remote screen doesn't end the night, though the confirmation says it will.**
   - **Where:** `ui/RemoteScreen.kt` and `AppViewModel.remote`.
   - **What goes wrong:** the mode change never calls `finish()`, so the wake notification and night-end alarm still fire. A later alarm change re-times and restarts the program.
   - **Fix:** add a NightActions command that sets the mode and then finishes the night.
3. **Leaving the app mid-connect can leave the BedJet connected indefinitely.**
   - **Where:** `night/LinkSessions.kt` with `ble/AndroidBedJetLink.connect`.
   - **What goes wrong:** the delayed `disconnect()` doesn't cancel a `connect()` in progress (12 s attach, then up to 20 s scan). The connect finishes after the release, so the link stays up with zero holders.
   - **Fix:** cancel the connect job on release, or release immediately when `connect()` returns with `holders == 0`.
4. **A cancelled `attach()` leaves an open BluetoothGatt.** `AndroidBedJetLink.kt`, attach.
   - **What goes wrong:** there is no try/finally. On cancellation (e.g. the 9 s receiver fallback) the gatt is never closed, and the next attach orphans it.
   - **Fix:**
     - catch CancellationException → disconnect, close, drop, rethrow;
     - close any existing gatt before `connectGatt`;
     - close non-current gatts on disconnect.
5. **Start confirmation can be satisfied by a stale cached `extra`.**
   - **Where:** `AndroidBedJetLink.receiveStatus` with `BedJetController.startProgram`.
   - **What goes wrong:** `lastExtra` (program step) is never cleared.
   - **Fix:** clear `lastExtra`/`lastHead` in `dropConnection` and before sending the start.
6. **The running night depends on the live profile.**
   - **Where:** `AppViewModel.activePlan`, `NightRunner.retime`, `ProfileEditor` "Next night".
   - **What goes wrong:** edits saved with "Next night" still show on Tonight and are applied by the next alarm re-time. Deleting the running profile silently breaks re-time.
   - **Fix:** store a profile copy (or the compiled steps) in `ActiveNight` at start, updated only on REAPPLY, and use it everywhere.
7. **A failed overnight re-time is retried only when the app opens or the alarm changes again.**
   - **What goes wrong:** the user can be woken late.
   - **Fix:** while a re-time is pending, schedule an exact-alarm retry every 5–10 min until `wakeUpStart`. A broadcast from an exact alarm may start the foreground service.
8. **Before a night starts, Tonight's "Wakes you at" can disagree with "Start night now".**
   - **Where:** `TonightScreen` / `AppViewModel.wakePreview`.
   - **What goes wrong:** the headline is based on `nextBedtime`, but the button starts now. At 02:00 it says 07:00 (the default) while the night will wake at the 06:15 alarm.
   - **Fix:** derive the headline from the same start-now preview.

## Minor
1. **`NightActions.exclusiveUntil`:** a `withTimeoutOrNull` race can leak the lock or a connection hold. Use `lock(owner)`, then on timeout check `holdsLock` and unlock, and release a successful acquire.
2. **REAPPLY ("Update tonight")** passes `night.wakeAt` as the alarm, so the label flips to "From your alarm" and default-wake edits are handled inconsistently. Keep `wakeFromAlarm` and re-resolve the wake time from the real alarm.
3. **`onServicesDiscovered`** checks neither the gatt identity nor the status, so a late callback from an old connection can complete a new attach.
4. **The remainder read catches only `BedJetError`.** A SecurityException (permission revoked) crashes the process. Catch `Exception`.
5. **`NightAlarmReceiver` NIGHT_END calls `finish()` without the NightActions lock**, so it races a snooze in progress.
6. **No tests for the new paths:**
   - re-time retry and failure
   - REAPPLY through NightActions
   - receiver fallback
   - snooze notification

   `aMovedAlarmMovesTheWakeUpAndSaysSo` uses real time and can flake around 02:00. Inject a clock.
7. **`AppViewModel` `stateIn(graph.scope, Eagerly)`** leaks collectors each time the activity is recreated. Use `viewModelScope`.
8. **`BedJetService.lastResult` is never shown**, so widget and shortcut commands give no feedback.
9. **Repository does file I/O on the main thread**, fsyncing on every event, and `SnapshotStore.list()` still reads whole files. Move writes to Dispatchers.IO with a single writer.
10. **Turning on Demo mode during a real night** sends later commands to the demo unit. Disable the switch, or confirm, while a night runs.
11. **The night curve Canvas has no TalkBack description.**
12. **Lint `DataExtractionRules`:** the GitHub token in the `secrets` prefs can move to a new phone during device transfer. Add `dataExtractionRules` excluding it.

## Quality-of-life ideas from this review
- "End in app only" when the BedJet can't be reached.
- A short confirmation notification or toast when a widget or shortcut command succeeds.
- Show the running night on the widgets (wake time, End).
- An all-night low-priority notification with "Waking at hh:mm" and an End button.
- Show the last re-time, and whether one is pending, on Tonight.
- Confirm before a widget's Start replaces a running night.
- Show which profile edits are waiting for the next night.

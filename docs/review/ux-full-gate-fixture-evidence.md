# Full-gate fixture review and mutation evidence

Source checkpoint: `d8b31611291f49be0b3d81ec8b1f2f1d2af34719`. The following repair changes two test fixtures only; production remains byte-identical to that checkpoint. This is team self-review, not independent sign-off or device acceptance.

## Complete gate before repair

`ux-combined-full-gate-2` ran the exact requested offline gate plus `:app:assembleRelease`, with two workers, a 2 GiB Gradle heap and in-process Kotlin. It finished with exit 1 after **1,147.51 seconds**. Core passed **240/240**. App had **1,342 total: 1,299 passed, five failed, 38 expected opt-in capture skips**, zero errors. All **632 tracked source/document input hashes** were unchanged during the run. The failure stopped execution before debug assembly, lint and release assembly; their presence in the command is not evidence that they ran. Exact counts and failure messages are preserved in [ux-gate-at-d8.json](ux-gate-at-d8.json).

The failures were `IdleProcessTimersTest.notificationExpiryDoesNotWakeTheIdleProcessEveryMinute` (expected one clock read, observed three), and `U06PrimaryLayoutTest.remoteModeTimerAndBoostControlsAreReachableAboveFeedback` in MIDNIGHT, DAYLIGHT, SIMPLE_MIDNIGHT and SIMPLE_DAYLIGHT (waiting for obsolete feedback).

## Clock measurement diagnosis and correction

The original counter measured every use of `Graph.clock`, including independent Repository backup timestamps. Setup changes Settings, and the real Repository starts a 30-second change debounce. Its IO writer calls `Repository.bundle` and, if a snapshot changes, `SnapshotStore.save`. Those reads can occur during the test's 180,001 ms idle interval. Draining current MainLooper work does not await a future debounce/IO writer.

The traced `ux-full-gate-fixture-diagnostic` run passed all four timer cases. It recorded a `Repository.bundle` clock read at **+30,000 ms on DefaultDispatcher-worker-1** in the running-Demo case. The idle notification case recorded exactly one initial `Notifications.reconcileWakeResume` read. The original full-gate failure did not capture its two extra callers, so this later trace proves the unrelated measurement path without retrospectively identifying those exact calls. No production idle loop was established by that failure.

The fixture now constructs the real Repository through the existing factory seam with a separate stable timestamp. Graph notification/Demo time remains measured. Before sampling idle time, the real observer must perform exactly one initial reconciliation. The test still uses the production default expiry flow, advances **180,001 ms**, and requires **zero additional reads**. It introduces no tick substitution, read allowance, caller filter, sleeps, ignored case or production timer change. Real notice expiry, hidden/visible Demo and running-programme/end controls are retained.

To verify that isolation does not hide the prohibited timer, a temporary production mutation added a **60,000 ms emit loop for empty notification deadlines**. `ux-idle-periodic-mutation-red` ran the one unchanged idle test and failed as intended: **expected 1, observed 4**, zero errors/skips, **95.44s**. All 632 input hashes stayed unchanged during that run. The mutation was removed immediately afterwards; `Notifications.kt` was verified back to exact Git blob **`c2ee807acbaebdffe18409862b0a22b1db454026`**. No mutation is included in the committed patch.

## Captured feedback and device-reported timer expectations

The recent idle preset capture repair intentionally makes a Warm action report the reviewed mode and duration. The fixture's actual Heat/30-minute action therefore reports **“Heat for 30 minutes.”**, while the old U06 expectation waited for **“Warming.”** The test had already observed actual Heat state and an idle owner before timing out. The correction captures the fixture preset before tapping and builds expected copy directly from resources, without invoking the production message helper. Real snackbar appearance and explicit dismissal remain required.

With only that copy correction, `ux-full-gate-fixture-diagnostic` completed **eight cases: four passes, four failures**, zero errors/skips, in **69.37s**. All four UI cases passed feedback appearance/dismissal, then failed the obsolete requirement for a clickable **8 h** control. The Heat report is **240 minutes**, and the existing `RemoteRuntimeLimitsTest.heatDoesNotOfferAnEightHourTimerWhenTheUnitReportsFourHours` had already exercised the intentional production limit through RED then GREEN.

The U06 fixture now explicitly requires the real report to be 240 minutes, checks the complete text and touch geometry of **15/30 minutes and 1/2/4 hours**, and asserts there are **zero clickable 8 h controls**. Both feedback passes retain every mode, Boost, navigation, overlay and supported-control geometry assertion. No unsupported control was restored to production.

## Focused GREEN after exact production restoration

`ux-full-gate-fixtures-green` passed **8/8**, zero failures/errors/skips, in **102.23s**:

- `IdleProcessTimersTest.aRunningDemoProgrammeStillEndsWhileTheScreenIsHidden`.
- `IdleProcessTimersTest.aResumeNoticeStillExpiresWithoutARepositoryChangeThenStopsItsTimer`.
- `IdleProcessTimersTest.theDemoClockSleepsWhileHiddenAndIdleThenRefreshesWhenVisible`.
- `IdleProcessTimersTest.notificationExpiryDoesNotWakeTheIdleProcessEveryMinute`.
- `U06PrimaryLayoutTest.remoteModeTimerAndBoostControlsAreReachableAboveFeedback` in all four looks.

All **632 tracked source/document hashes** stayed unchanged. The exact production Notifications blob remained `c2ee807acbaebdffe18409862b0a22b1db454026`. The two reviewed test blobs are Idle **`58ca1b863566c86e678cfeb8b3b7167778a0e15f`** and U06 **`ff8a3a0e030cfc1e24270ad9f1b99091e1881864`**. A second complete-file/cumulative-diff review found no additional defect in these changes. The full gate must run again; this eight-case result does not replace it.

## Documentation correction in the same pass

The current widget introductions still called the 38-case role/cap RED batch “latest” despite the later 38/38 GREEN. They now preserve both results and distinguish the current focused result from complete-gate evidence. The widget ruling also now distinguishes unconfirmed emergency Off compatibility from reviewed Off, which must retain its captured-night identity through owner admission. Historical test observations remain unchanged.

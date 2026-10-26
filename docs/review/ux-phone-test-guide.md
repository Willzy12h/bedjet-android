# Nocturne UX — next phone checks

This checklist is for the test APK identified in the final handover. Automated Kotlin/Robolectric checks do not establish physical BedJet, Samsung, TalkBack or overnight behaviour. Use the phone's own wake alarm independently.

## 1. Alarm explanation and the short equipment test

In Samsung Clock, set an alarm five minutes ahead. In Nocturne, open Settings → Alarms and use **Re-check alarm**. Check the actual detected time and creator package. The normal night policy deliberately requires at least one hour and at most 18 hours: a five-minute alarm should be identified as too soon, with the profile's fallback time explained. Android supplies only the next system alarm, so another earlier alarm can be the one Nocturne sees.

Set an alarm more than one hour ahead and within 18 hours. Re-check again; it should read the new system value and identify Samsung Clock. If the creator is unknown, it should say so. An unapproved named creator should be visible in Settings with the option to allow it.

For a short BedJet run, use Settings → **Test sleep and wake** → **Preview five-minute test**. Check the captured BedJet and slot, then explicitly start it. The preview describes one minute of Cool at 22 °C, two further minutes of Cool at 22 °C, then two minutes of Heat at 30 °C; fan 30% throughout. The wake phase starts after about three minutes and the run ends after about five. Observe the physical changes and compare pending/confirmed state. This test does not use or modify phone alarms. It remains labelled as an equipment test and is excluded from real-night History, sleep exports and Health Connect comparisons.

## 2. Bluetooth off, recovery and guarded actions

With no tracked night, turn Bluetooth off. Check Remote and Tonight for **Bluetooth is off** and **Enable Bluetooth**. A command should fail promptly. Enable should follow Nearby devices permission if needed, then Android's enable request. Returning Bluetooth should clear the banner and reconnect; the failed command should not run by itself.

During a supervised tracked run, check that **Stop night** asks for confirmation and cancellation leaves the programme running. Confirming Stop should send Off and wait for the device report. For **Resume night**, use a separate ordinary tracked night: the five-minute equipment test deliberately has no resume offer. Check Resume from the recovery UI and its notification; it should return control to the remaining programme. A re-time failure, if deliberately reproduced, should identify the requested time and BedJet and offer **Check BedJet**, which reconnects and reads rather than replaying the change.

## 3. Widgets on the actual launcher

Add and resize both widgets. Check compact, medium and large layouts, then use ‹ and › to reach each page. Start tonight, the configured Boost and Turbo 10m should remain reachable. Longer Boosts must name Turbo followed by Heat; the accessibility description gives the exact durations. A 15-minute Boost means Turbo 10 minutes plus Heat 5 minutes. Ordinary controls should act in place; only **Open app** opens the full app. A tracked-night Off/Stop request should show the small confirmation activity.

Watch one successful and one unavailable-device action: pending must settle to receipt-confirmed, failed or unknown. A cached matching display is not enough to confirm a new command. Repeat at maximum font size and inspect all primary labels and touch areas. Samsung launcher cell dimensions and force-stop delivery still need phone observation.

## 4. Temporarily, then resume

Use an ordinary tracked night with enough time before wake for the temporary interval and its ten-minute clock anchor; the fixed five-minute equipment test is deliberately too short for this feature. During that supervised night, open Warm, Cool, Turbo or Boost in the app, a shortcut and each relevant widget page. Check **Temporarily, then resume** against the alternative **Stop night**. The duration control changes only the temporary action: the stopping alternative retains the originally reviewed preset. Inspect the original wake time, temporary target/fan/time, planned return and any refusal before approving. Standalone Turbo cannot exceed ten minutes; a longer Boost uses Turbo for at most ten minutes and Heat for the remainder.

The return is compiled into the same BedJet programme; there is no later phone command scheduled to restart it. Test one return with the phone out of range, then separately turn the BedJet physically off during the temporary period and confirm it stays off at the planned return. Those firmware behaviours require physical observation. Ordinary Off and Stop night must still ask for confirmation during a tracked night; cancelling must do nothing. **Stop tracking** is a separate local action and leaves the stored BedJet programme running, including its return. Do not treat local tracking cancellation as physical Off.

## 5. Tonight, editor and themes

Collapse Quick, reorder the lower sections by dragging and by Move up/down, leave the app and return. Check that the order and collapse choice persist and the wake header stays visible while scrolling. While a command is working, watch for content movement. Tap a curve phase for its interval/target and long-press to edit that phase; inspect existing unsaved drafts after tab changes.

Choose Daylight, cancel by tapping outside, then repeat and use Switch. Test System while the phone is light and dark. At maximum font scale, inspect the native wake-time picker separately, all five tabs and both normal/Simple looks. With system animations removed, active markers should be static. TalkBack traversal is a separate check.

## 6. Longer acceptance after the short checks

Run one ordinary night, then inspect History/check-in and repeat a controlled alarm change. Afterwards test screen-off/Doze, reboot/unlock and force-stop/widget recovery as separate scenarios. Compare the actual BedJet state with app, notification and widget claims and export diagnostics for any mismatch.

Health Connect currently reads existing sleep sessions; this build does not add a completed-night writer. Backup import currently has no preview of changes or blanket queued-restore ban; the captured-original guard remains narrower. Keep these as missing features rather than expecting a new test result to establish them.

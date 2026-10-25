# Nocturne — owner's phone test guide

**Status: NOT RUN on a device.** This is a checklist for the owner to complete. No item below has been run on a phone or BedJet 3 by the implementation team. Host tests and a successful APK build do not establish phone behaviour, physical haptics, Samsung compatibility or TalkBack accessibility.

## Test build and record

Use only the supplied `nocturne-test-<shortsha>.apk`. It is a minified release-variant **test build**, signed with a newly generated throwaway certificate whose subject is `CN=Nocturne Test`. It is not a production release and cannot update an installation signed with a different certificate. The accompanying handover supplies the exact source commit, APK SHA-256 and test-certificate SHA-256.

| Record | Owner's entry |
|---|---|
| Test date and tester | |
| Phone model, Android version and build | |
| APK filename and source commit | |
| APK SHA-256 | |
| Test-certificate SHA-256 | |
| BedJet 3 name/address and firmware, if available | |
| Theme, Simple view, font size and display size | |
| Screenshots, logs and exported files | |

## Install and first run

1. **Export anything you need before uninstalling.** Preserve Nocturne backups, original-slot records and relevant History/CSV outside the app. Uninstalling removes the app's local data. Do not treat a present-day slot read as proof of a missing historical original.
2. **Uninstall any earlier Nocturne build first.** This APK uses a different signing certificate. Do not change the production signing identity or try to install it as an update over a differently signed build.
3. Copy the supplied APK to the phone. In Android's **Install unknown apps** setting, allow the browser or file manager used to open it to **Allow from this source**, then install. Menu wording can vary by phone. Remove that install permission afterwards if it is no longer needed.
4. Open Nocturne and complete the visible **Phone setup** / setup checks: allow **Nearby devices**; allow **Notifications**; enable **Alarms & reminders** when Android opens that settings page. Check the app's battery/background setting as part of overnight testing. Record any permission you cannot grant rather than marking the feature as passed.
5. Turn on Bluetooth and keep the intended **BedJet 3** nearby. Use Nocturne's **Choose your BedJet 3** flow to find and explicitly select the correct name/address. Confirm that demo mode is off before physical checks. This app selection is the pairing/connection step; discovering a unit alone does not choose it.
6. While present and awake, run the offered fan check and establish which physical unit is controlled. Keep the BedJet remote available. Preserve all three slot originals before programme writes; do not overwrite a slot merely to manufacture a passing test.

Use an independent wake alarm during testing. Record the app's actual state: pending, failed, unknown or confirmed. A pending/unknown result must not be recorded as a confirmed BedJet action.

## Checklist

The PASS, FAIL and NOTES cells are deliberately blank. Tick one result only after performing the check; explain anything not run or blocked in NOTES. Include screenshots or exported evidence where useful.

| Check | Actions and observations to record | PASS | FAIL | NOTES |
|---|---|---|---|---|
| Installation and identity | Uninstall the previous build, install the supplied test APK, and record its filename/hash and the phone's app version. Check that the expected Nocturne app opens. | | | |
| First-run permissions | Complete Nearby devices, Notifications and Alarms & reminders. Return to Nocturne and check that the corresponding missing-setup prompts clear. Record battery/background settings separately. | | | |
| BedJet 3 selection | Select the intended unit explicitly, ensure demo mode is off, and observe the supervised fan check on that same unit. | | | |
| Remote lights — off to on | In Remote, note the selected unit and its actual light state, then switch **Lights** on. Observe that physical unit's lights turn on, the switch remains on after fresh status and the app reports confirmation. Record pending, failed or unknown instead if confirmation is absent. | | | **NOT RUN** |
| Remote lights — on to off | Switch **Lights** off. Observe the selected physical unit's lights turn off, the switch remains off after fresh status and the app reports confirmation. Record any mismatch between physical lights, switch state and outcome. | | | **NOT RUN** |
| Remote beeps — off to on | Switch **Beeps** on. On the selected physical unit, use an ordinary supervised control that normally beeps and listen for its sound; check that the switch remains on after fresh status and the app reports confirmation. The label means sound enabled. | | | **NOT RUN** |
| Remote beeps — on to off | Switch **Beeps** off, then repeat the same ordinary supervised control and check that the selected unit is silent. Check that the switch remains off after fresh status and the app reports confirmation; record any physical/switch/outcome mismatch. Restore the owner's preferred settings after both-direction checks. | | | **NOT RUN** |
| Start tonight | Select a valid profile and wake time with sufficient remaining time, then tap **Start tonight**. Observe pending/confirmed or the actual refusal/error, the BedJet's response and the selected working slot. Do not call a merely requested start confirmed. | | | |
| Quick controls and Off confirmation | During a tracked night, open a Quick action/Off confirmation, cancel it and check that the night continues. Repeat and deliberately confirm Off; observe the actual BedJet outcome and resulting app state. Check the other Quick controls while supervised. | | | |
| In-app haptic tick and confirmation | With system haptics enabled, try an in-app command. Check for a light tick when accepted by the command owner and a confirmation only after a BedJet-confirmed outcome. Record a refusal/unconfirmed case if available; it must not produce a success confirmation. Dialogue taps currently use the activity host view. | | | |
| Wake picker — U-B5 | Open the tonight-only wake picker with an existing override, check its initial time, and accept an unchanged time. Repeat at the largest font setting. Inspect both hour and minute dial numerals for overlap, crowding and readability; attach screenshots. **U-B5 remains open pending this device check.** | | | |
| Simple view at largest font — U-B4 | Enable Simple view and repeat in Midnight Ink and Daylight at the largest font setting. Inspect the tonight-only wake row, clocks, Start/Off actions, scrolling and dialogues. At 320 dp width and font scale 2.0 the override label is compact; check that no word is split or clipped. Record the phone's actual width/font settings rather than assuming they equal the host fixture. | | | |
| Ordinary views and navigation | Return to normal font/display settings. Check all five tabs, both themes, cards, curve and navigation. Confirm the ordinary wake label remains recognisable and there is no unintended layout change. | | | |
| Widgets | Add **Nocturne controls** and **Nocturne button** to the home screen; configure the single button, resize where offered and try the intended actions while supervised. Check target, pending/confirmed wording and refresh after app state changes. Record any delayed theme refresh separately. | | | |
| Quick Settings tile | Add Nocturne's tile through Android's tile editor. Observe its label/state and tap it to open Tonight, where the existing command controls and confirmations remain. Record any lock-screen or permission behaviour encountered. | | | |
| Charger auto-start: cancellation | With the selected real BedJet idle, valid settings/profile, required permissions and charger auto-start enabled, enter the configured bedtime window while charging. Record whether and when the grace notice appears; cancel during its 60-second grace and check that no start follows. Scheduling is best effort, so record delays or a missed window. | | | |
| Charger auto-start: accepted attempt | On a separate eligible attempt/window, remain present and let the grace period finish. Record the actual BedJet action and app/notification state. Use a valid night of at least three hours; do not assume plugging in guarantees an immediate attempt. Disable automatic starts afterwards if they are not wanted. | | | |
| History and CSV | After a tracked night has ended, inspect its History entry and available event details. Export History CSV, open it and compare the recorded times/outcome with the entry and your notes. Record failed/unknown outcomes faithfully. | | | |
| Settings originals — uncertain | With a preserved, known test backup containing an uncertain matching original, check the extra uncertainty line in Settings and in **Preview / put back**. Check an uncertain unknown/other-device record in the read-only **From backup** group too. Open then cancel the restore dialogue; a write is not needed to inspect the label. If a suitable fixture is unavailable, leave the result blank and explain in NOTES. | | | |
| Settings originals — certain and provenance | For certain originals, check that the uncertainty warning is absent in the matching list, From backup group and restore dialogue. Unknown/other-device records must remain read-only. The label does not reconstruct v1 missing empty-slot records or certify that a saved programme is the true original. | | | |

## Reporting an issue

Record the exact action sequence, time, selected unit, theme/font/display settings, expected result and actual text/state. Attach a screenshot or short recording where useful. Export diagnostics and relevant History/CSV through the app when available; those files contain device/settings information, so share them only through the agreed support route.

If control or state is unclear, stop that test and check the physical unit with its remote before proceeding. Preserve the failing evidence. Do not replace FAIL or NOT RUN with a host-test result.

## Source and scope

The checklist follows the current setup, permission and feature wording in `SetupFlow.kt`, `SetupChecks.kt`, `strings_features.xml` and the supervised [Samsung + BedJet acceptance plan](samsung-bedjet-acceptance.md). This shorter owner guide does not mark any case in that full acceptance plan as passed.

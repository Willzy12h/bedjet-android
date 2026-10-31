# Acceptance polish handover

Date: 30 October 2026. Branch: `claude/nocturne-acceptance-polish`, built on `43ea8bd` (the permanent TEST signature fix).

This is the implementer's handover and self-review. It is **not** the final independent sign-off, and every physical-device result below is **NOT RUN**.

## What changed on this branch

| Area | Change | Commit |
| --- | --- | --- |
| Security | SEC-01: unsafe export destinations are refused before they are opened. | `2f742f3` |
| Home, Profiles, Settings | UX-01 to UX-04 and UX-06 to UX-08 acceptance polish. | `8bb5907` |
| Navigation | Remote replaces History in the tab bar; History and the new Sleep page move into Settings. | `27c5ab9` |
| Boost | Turbo-then-Heat is named plainly and its Heat minutes are editable (Settings row and Remote). | `27c5ab9`, `61165a2` |
| Sliders | One coloured slider style everywhere, with role colours that adapt to each palette. | `61165a2`, `27588e7` |
| Sleep data | Health Connect nights (30 days of permission history) joined with BedJet nights: figures, averages and noticed patterns. | `dfdbfa3` |
| Sleep page | Range switch, movable sections, night chart, compare and timeline. Nights kept for 7, 30, 90 or 365 days, with a confirmation when shortening and a delete action. | `3c506e9` |
| Tonight | Last night card with stages and the week's bars; it opens the Sleep page. | `074e787` |
| Sleep history | Stays on this phone only: excluded from cloud backup and device transfer, and the Sleep settings say so. | `9e2ea9c` |
| Demo mode | No longer offered. Anyone already in Demo can still leave it. | `07bd6da` |
| Widgets | Controls widget and one-cell preset tiles (below). | `6aca921` |

## Widgets

**Controls** (resizable, from 2 × 1):

- The top shows the room temperature and a status line. The status line shows, in order of priority: Choose a BedJet; the last tap's outcome (Sending…, or the action and its result); the BedJet's own mode and target with the tracked profile; or No BedJet report. Tapping this area opens Nocturne. Nothing else on the widget opens the app.
- Below it sit the person's chosen presets as 56dp pills, with **Off always present**. At 2 × 1 the widget is a strip: the header plus Off.
- More cells show more presets: up to 4 columns, and up to 4 rows (2 at large text). With several rows, Off starts the last row. A single row shares the full width, and wide pills add the preset's temperature ("Heat 30°").
- While a night runs and there is room (4 × 2 and above), a plan card shows "Running · profile", the phase and the wake time. An unconfirmed programme says "Phase unconfirmed" and "Wake unknown" in the card, and also in the status line.
- A configuration screen lets the person tick and order the presets: Turbo, Heat, Cool, Dry, Boost, Start tonight and each profile. Off is not listed because it is always shown. It opens when the widget is placed and can be reopened from the launcher's reconfigure action.

**Preset tile** (1 × 1): the whole tile is one button. It shows the mode's stripe, icon and name, plus one detail line: temperature, run time, Boost's total time, or tonight's profile. Tiles placed before this change keep their saved command.

**Decisions that differ from the approved design:**

- Labels are 16sp and targets 56dp, as the accessibility spec (P4) requires. The design's 13px labels were not used. Where a name cannot fit, a short form is used (Start tonight becomes "Start") and the spoken description keeps the full text.
- Off is always labelled "Off". The design's "End" label during a night was not adopted. Off still opens the small confirmation when a night is tracked.
- Controls has no Stop tracking control. Off covers ending a night. Older single-action placements that saved Stop tracking keep it.

Every command still goes through the existing single owner, with the same confirmation guards. Pending, Confirmed and Unknown wording comes only from the owner's evidence. From `0.3.0-acceptance.2` the widget also reads the BedJet's status in the background (item 12 below); that read sends no command.

## Verification

Full local gate on `ede24a3` (the APK's source; later commits change only docs): `./gradlew --init-script docs/redesign/verification.init.gradle :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` exited 0.

| Task | Result |
| --- | --- |
| `:core:test` | 334 tests, 0 failures, 0 errors |
| `:app:testDebugUnitTest` | 1,878 tests, 0 failures, 0 errors, 43 skipped |
| `:app:assembleDebug` | Built |
| `:app:lintDebug` | 0 issues |

These are host (JVM and Robolectric) results. Hosted CI has not been run on this branch.

Widget renders (host Robolectric images, not device evidence) were produced by `SCREENSHOT_TAG=widgets ./gradlew :app:testDebugUnitTest --tests '*WidgetCaptureTest'`. They cover Controls at 2 × 1 to 4 × 4 in Dark and Light, every tile preset, and a running night.

### Security review of this branch

- The new `QuickControlsConfigActivity` is exported only for `APPWIDGET_CONFIGURE`. It refuses any widget ID that is not one of Nocturne's own Controls placements, and it returns `RESULT_CANCELED` until Save. Its saved list is re-filtered on every render (`controlKeys`), so unknown keys and Off are dropped.
- Widget command parameters still pass through `validCapturedPreset`, the confirmation activity and the single command owner. Nothing new reaches the BedJet without the existing checks.
- Sleep history and Health Connect data stay on the phone. They are excluded from cloud backup and device transfer, and they are not exported.
- SEC-01 export-destination validation is unchanged since `2f742f3`.
- `WidgetRefreshService` (acceptance.2) is exported only to the system: it requires `BIND_JOB_SERVICE`. It can only run the read-only status read, which takes the single command owner's admission and lock and writes nothing.
- The "Edit presets in Nocturne" intent is explicit (to `MainActivity`) and only opens Tonight's quick preset editor.
- No credentials, keystores or signing secrets are in the repository. The test APK's key is held outside the repository (see below).

## Test APK

| Item | Value |
| --- | --- |
| Built from | `ede24a3` with `assembleRelease` |
| Package | `io.github.willzy12h.nocturne` |
| Version | `0.3.0-acceptance.1`, version code `20261080` (above the previous `20261079`) |
| SDK | minSdk 31, targetSdk 36 |
| Signing | APK Signature Scheme v2, verified with `apksigner verify` |
| Certificate | CN=Nocturne acceptance TEST build, O=Personal test signing, C=GB; valid until 2036 |
| Certificate SHA-256 | `fd5fa67050e21e2f7e4c6926623ba75ac8236c0f53b72d057fa15cba34f40d81` |
| APK SHA-256 | `267326664610ca52d75feb87c28bfc8b31b0b2585e9e07373581908e3ddee1f0` (5,640,753 bytes) |

This APK is signed with a **new test key** made for this acceptance build. It is **not** the permanent TEST certificate (`bd233ddc…5f15`) and not a production key, so it **cannot install over** an existing Nocturne. Before uninstalling the current app, export a backup file (Settings → Backups and snapshots → Export file) and keep the BedJet originals. Sleep history is local only and is lost when the app is uninstalled. Nothing was published as a release.

### Test APK `0.3.0-acceptance.2` (31 October 2026)

| Item | Value |
| --- | --- |
| Built from | `a21fe4a` with `assembleRelease -PversionCode=20261081 -PversionName=0.3.0-acceptance.2`, then `zipalign -p 4` and `apksigner` |
| Version | `0.3.0-acceptance.2`, version code `20261081` (above `20261080`) |
| Signing | APK Signature Scheme v3 (apksigner leaves out v2 for minSdk 31), verified with `apksigner verify` |
| Certificate SHA-256 | `fd5fa67050e21e2f7e4c6926623ba75ac8236c0f53b72d057fa15cba34f40d81`, the same test key as `0.3.0-acceptance.1` |
| APK SHA-256 | `3a0af0d825b8cdcbcd8783e502de01128a8ac7bc4961ab2def16e1ff14777357` (5,755,140 bytes) |

Same key as acceptance.1, so it installs over it as an update and keeps the app's data. It still cannot install over a build signed with any other key. Gate on `a21fe4a`: `:core:test` 334 tests, 0 failures; `:app:testDebugUnitTest` 1,884 tests, 0 failures, 43 skipped; `:app:assembleDebug` built; `:app:lintDebug` 0 issues. These are host results. Hosted CI has not been run.

## Known issues

- `U06RecoveryLayoutTest` has failed intermittently on earlier full runs and passed when re-run alone. It was not changed on this branch. It needs a root cause, not a retry.
- Widget text width is estimated with the system bold font. A launcher with a much wider font could still ellipsise a long profile name. The spoken description stays complete.

## Physical-device checklist (all NOT RUN)

Use this with [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md). Keep physical Off within reach for every check.

| Check | Pass condition | Result |
| --- | --- | --- |
| Install | Export a backup, uninstall, install this APK, restore the backup. Profiles and settings return; opening the app sends nothing. | NOT RUN |
| Controls widget placement | Placing Controls opens the preset chooser; Save shows the chosen presets in order, with Off present. Reconfigure changes them. | NOT RUN |
| Controls sizes | Resize from 2 × 1 to 4 × 4. Nothing is clipped; at 4 × 2 and above a running night shows its plan card. Repeat at the largest font size. | NOT RUN |
| Controls actions | With no night tracked, Heat, Cool, Dry, Turbo and Boost each start the saved preset in place without opening the app. The status line shows Sending… then the BedJet's result. Off stops the BedJet. | NOT RUN |
| Controls during a night | With a night tracked, presets, Start and Off open the small confirmation. Cancelling sends nothing. | NOT RUN |
| Header | Tapping the temperature and status opens Nocturne. With no BedJet chosen, every control opens the app and the widget says Choose a BedJet. | NOT RUN |
| Preset tiles | Place one tile for each preset. Each shows its name and detail; a tap behaves like the matching Controls pill. | NOT RUN |
| Older placements | If any single-action widget from an earlier build remains after restore, it still shows its action and works. | NOT RUN |
| TalkBack | Each pill and tile reads its full description (for example "Heat 32°C for 30 minutes"); the header reads the temperature, status and "Opens Nocturne". | NOT RUN |
| Sleep page | With Health Connect permission, last night's stages appear beside the BedJet steps; the range switch and section moves persist. Without permission, BedJet nights still show. | NOT RUN |
| Sleep history retention | Shortening retention asks first and removes older nights. Delete clears the history. A cloud backup or device transfer does not carry it. | NOT RUN |
| Remote and Boost | Remote is in the tab bar. Boost runs Turbo then Heat for the saved minutes; the edited minutes are used. | NOT RUN |
| Demo hidden | Settings no longer offers Demo mode. | NOT RUN |
| Widget background read | With the app closed and a Controls widget placed, the widget's read age stays within about 15 to 30 minutes and its mode matches the BedJet. Opening the app during a read sends nothing and shows no error. | NOT RUN |
| Controls layouts (acceptance.2) | Place Controls at 2 × 2, 2 × 3, 3 × 3, 4 × 3, 4 × 4 and 4 × 5. Each matches the agreed layout, labels are not cut short, and while a night runs its profile button is ringed and the 4-wide card shows the plan bar. | NOT RUN |
| Widget setup links | "Edit presets in Nocturne" on both setup screens opens Tonight's quick preset editor. | NOT RUN |
| First-run order | A fresh install asks for Nearby devices before pairing, and the picker scans by itself. | NOT RUN |

## Feedback from the first install (done in `0.3.0-acceptance.2`)

Recorded on 30 October 2026 after the owner installed `0.3.0-acceptance.1`. All 13 items are built (see "What was built for the feedback" below). Nothing here is device evidence yet.

1. **First run order.** First run asks to pair with the BedJet first, and asks for Bluetooth second. Ask for Bluetooth (nearby devices) permission first, because pairing can't work without it.
2. **Faster pairing.** Use fewer pop-ups and screens. After permission is granted, scan automatically instead of showing a prompt and then a separate scan step.
3. **Alarm source wording.** Replace the "use alarms from unknown apps" wording with something like "Next known alarm". Add a short note that some clock apps don't say which app they are.
4. **Alarm freshness.** Settings showed the alarm source's next alarm (09:00 tomorrow) before Tonight did; Tonight caught up later, where it had earlier said no alarm was found. Find out why it is delayed, and refresh the next alarm automatically when it changes.
5. **Remote mode display.** The current-mode display at the top of Remote (for example "Off") looks like a button. Make it read clearly as the current mode, and point people to the presets below. It may be smaller. Make design renders for this first. Keep the Turbo, Heat, Long heat, Dry and other preset buttons as they are.
6. **Custom duration.** The custom duration says "between 1 minute and 11 hours 59 minutes". Accept 12 hours as entered and store it as the device maximum (11:59) behind the scenes.
7. **Drag to reorder on Profiles.** Pressing the top profile starts scrolling straight away. Let a held item hover in place, and animate the other items moving up or down as it passes.
8. **One drag style everywhere.** Use the same drag-and-drop behaviour and animation in the profile editor's steps and in every other reorderable list.
9. **Notification timing default.** Notification lead times (for example the temperature notifications) should default to at least 10 minutes. People can still change them.
10. **Health Connect in first-time setup.** Offer Health Connect as an optional, dismissable step in the first-run guide, and say it can be turned on later in Settings.

11. **Widget setup links to the app.** The preset chooser (Controls and the single tile) gets an "Edit presets in Nocturne" button that opens the app's preset settings, and a short line saying presets are customised in the app.
12. **"No BedJet report" while the BedJet is on.** Cause: the widget shows only the last report held in memory by the app. When Android stops the app's process, the report is gone, and the widget never connects by itself, so it says No BedJet report until a tap connects. Decision (owner, 30 October 2026): **refresh in the background**. The widget periodically reads the BedJet's status. Reads are read-only and go through the single command owner. They step aside when the app is connected or a command is in flight, and the widget shows when the status was last read. The widget should also show more about what the BedJet is doing.
13. **Larger Controls layouts.** The 4 × 4 size matters most. Redesign the larger sizes (2 × 2, 2 × 3, 3 × 3, 4 × 3, 4 × 4, 4 × 5) so the extra space shows more information and the presets fill it well, including Start tonight and the profiles. Draft several design versions per size for the owner to choose from before building.
    Design agreed with the owner on 31 October 2026, after two rounds of drafts (the "Final" page of the Nocturne widget drafts canvas). There is one Controls widget, and its layout follows its size:
    - **2 × 2 and 3 × 2:** preset buttons only, with no night line (A1). Off sits on the right of the last row.
    - **2 × 3:** the night card (tonight's profile, with its wake or start time) above four presets by default. The setup screen has a "Widget layout" choice, used only at this size, that switches to six buttons without the night card.
    - **3 × 3:** status and night in the header (A3): temperature with the read age under it, then the mode and the night on two lines. Below that, the preset buttons.
    - **4 × 3, 4 × 4 and 4 × 5:** a night card with the plan graph and a "now" marker while a night is running (A2), and a single night line when none is running. 4 × 4 is the main size.
    - **Every size:** the profiles (for example Winter warm and Summer cool) can be chosen as buttons that start that night. The running profile has a purple ring and a green dot. Labels shorten or wrap rather than run off the tile. Text is at least 16sp and targets at least 56dp. An empty spot at the end of the last row is fine.
    - **Setup:** tick and drag presets (Off is always shown), add the 2 × 3 layout choice, and add an "Edit presets in Nocturne" button with the line that temperatures, times and fan are set in the app. The single tile setup gets the same button.

### What was built for the feedback

| Item | What was done |
| --- | --- |
| 1 | Setup asks for Nearby devices (Bluetooth) first, then pairing. |
| 2 | Pairing asks for access first, then opens a picker that starts scanning by itself. |
| 3 | The setting reads "Next known alarm, from any app", with a note that some clock apps never name themselves. |
| 4 | Tonight reads the next alarm again when Android reports a change or the alarm settings change, so it matches Settings straight away. |
| 5 | Remote's current mode is plain text, not a card that looks like a button. It was built from the agreed notes without a separate render round. |
| 6 | 12 hours is accepted and stored as the BedJet's 11 h 59 min maximum. 12 h 01 min is refused. |
| 7, 8 | One drag style everywhere: a held row stays put until it is moved, the others slide out of its way, and the list only scrolls once the row is carried into an edge. Profiles, the profile editor's steps, the Sleep sections and the widget preset list all use it. Move up and Move down stay as accessibility actions. |
| 9 | No change needed: the notification lead times already default to 10 minutes. |
| 10 | Health Connect is an optional, dismissable setup step that says it can be turned on later in Settings. |
| 11 | Both widget setup screens have "Edit presets in Nocturne", which opens Tonight's quick preset editor, and the line "Temperatures, times and fan are set in Nocturne." |
| 12 | While a widget is placed, a BedJet is chosen and Nearby devices is allowed, Android runs a read about every 15 minutes (a JobScheduler job). The read goes through the single command owner and writes nothing. It steps aside when the app is connected, when a command is in flight and in Demo. The widget shows how long ago the status was read. |
| 13 | The agreed Controls layouts by size, the profile buttons with the running profile ringed and marked with a green dot, the 2 × 3 layout choice, and the plan bar with a now marker at 4 × 3 and larger. |

Also fixed while running the gate: the alarm-settings refresh from item 4 wrote to a field before it was set, which would have crashed the app when it opened. The full gate caught it.

No changes are needed to Appearance. Health Connect import worked on the device and the Sleep page reads well with real data.

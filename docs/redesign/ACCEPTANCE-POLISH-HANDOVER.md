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

Every command still goes through the existing single owner, with the same confirmation guards. Pending, Confirmed and Unknown wording comes only from the owner's evidence, and the widget never connects by itself.

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

## Feedback from the first install (to do)

Recorded on 30 October 2026 after the owner installed `0.3.0-acceptance.1`. None of this is done yet.

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
12. **"No BedJet report" while the BedJet is on.** Cause: the widget shows only the last report held in memory by the app. When Android stops the app's process, the report is gone, and the widget never connects by itself, so it says No BedJet report until a tap connects. Fix still to be decided (see below). The widget should also show more about what the BedJet is doing.
13. **Larger Controls layouts.** The 4 × 4 size matters most. Redesign the larger sizes (2 × 2, 2 × 3, 3 × 3, 4 × 3, 4 × 4, 4 × 5) so the extra space shows more information and the presets fill it well, including Start tonight and the profiles. Draft several design versions per size for the owner to choose from before building.

No changes are needed to Appearance. Health Connect import worked on the device and the Sleep page reads well with real data.

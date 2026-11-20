# Next beta engineering handover

## Beta.10 acceptance checks

The Android branch starts at public beta.9, commit `a1a9eebe78517c5cf0ede32a6c1a900d3ddf27c8`. Beta.10 uses Android build `20261122`. Keep Nocturne installed when updating so the phone retains saved data. The compatibility fixtures include the edited nap and observed history from beta.9.

- Compare 24°C and 24.5°C on Tonight and Remote, then repeat in Fahrenheit. Check the fixed position of the room reading and mode icon, including large text. Try Turbo, Boost and Dry while connected, then interrupt Bluetooth during an active programme and compare both status displays.
- Check the Quiet alarm row against the time in Tonight’s plan. Tap for details with an eligible alarm, a profile wake and a tonight-only wake. Check the moon and Soft shade in Daylight, Midnight Ink and Simple view.
- Start a one-off 30-minute nap, then check that the next Quick nap still shows the saved duration. Confirm the airflow changes after wake and the physical BedJet switches off. Check an edited profile with and without an after-wake step.
- Compare BedJet-only history and a night with tracker data across 7, 30 and 90 days. For a run that lost Bluetooth, check whether the recap identifies the last observation or an observed standby.

Physical BedJet 3 and live Health Connect acceptance remain outstanding.

## Android changes after beta.8

The integration branch `codex/nb9-android` starts at the [published beta.8](https://github.com/Willzy12h/bedjet-android/releases/tag/v1.1.0-beta.8), commit `0ffdcc2c9aa8cb4cfb7f4aa40996ff1f58a8d6b0`.

- **Quick nap** counts from the BedJet start. The default is 10 minutes of Turbo, 30 minutes of Heat at 35°C and 30% fan, then Cool at 22°C and 40% fan up to the 90-minute wake point. Cooling continues for 10 minutes at 80% fan. The nap editor allows shorter or longer durations, stage edits and a profile-opening-only run. Clock time and the phone’s next eligible alarm are explicit alternatives. If the BedJet cannot meet the selected clock time with those stages, Nocturne explains why and still offers a duration-based start. The phone’s Clock app owns any alarm sound.
- **Presets and previews** share the stepped fan chart across Tonight and Profiles. Untouched Heat now starts at 40°C for 30 minutes and Long heat at 30°C for 150 minutes. Check an upgrade with customised legacy Extend settings and explicit Quick settings.
- **Temperature readings** use incoming BedJet reports. On Tonight and Remote, check Turbo to Cool, a stable temperature, reconnect, reduced motion and a switch between units. A stationary value can be current. A moving icon needs recent reports of a running mode.
- **Sleep** keeps tracker sleep, BedJet use and missing observations distinct. Test a date containing both an overnight sleep and a nap, a three-month query with restricted access and a disconnection before the end of a run.

**Physical acceptance pending.** With a BedJet 3, shorten the test preset and check the transition at wake and the later switch to standby, including with the phone disconnected. Verify a relative nap ignores a nearer phone alarm and that explicitly selecting the alarm displays the intended time. Switch from Heat to Cool, then interrupt Bluetooth to check when the icon stops. Compare a known night in Health Connect with the date and source shown in Sleep.

The Midnight Ink fixed-clock capture produced 19 images on Robolectric API 34. Independent review covered naps that wait for connection, fixed wake times the device cannot meet, resuming after interruption and late standby reports. The R8 JVM compatibility check passed 142 saved-data cases across 61 models. The release APK built locally with shrinking enabled and without a signing key.

**Windows verification at `28376c6`.** All 381 core tests passed. Of 2,045 Android debug tests, 1,978 passed, 44 skipped and 23 failed. Debug and release assembly passed. Debug lint reported zero issues.

All 23 failures were in home-screen widget tests (12 command feedback, 10 confirmation, one import admission). AndroidX DataStore 1.0.0 calls `File.renameTo` to replace an existing widget preferences file. Windows rejected that replacement in a single-process probe and in a separate checkout of the published beta.8. Some assertions then saw pending or stale widget state. Run the full gate on the repository’s Linux CI before signing the next beta.

## Published beta.8

Integration branch `codex/nocturne-next-beta` started at beta.7, `18705776806da1efc0e62b327926f3bc679f3acd`. PR #32 was merged and the signed prerelease was published on 20 November 2026.

## Agreed scope

| Items | Work |
| --- | --- |
| 01–03 | Confirm Off with current device evidence, verify backups before replacement, erase deleted history from app-managed snapshots. |
| 04–06 | Confirm and remember manual controls per unit and mode, preserve saved data on update, connect on a deliberate appliance button or preset tap and handle uncertainty. |
| 07–08 | Tonight uses fan height, duration width and mode colour. Long heat occupies a single bottom-left tile. |
| 09–12 | Sleep stage compatibility, honest wake labels, reliable scheduled-start boundaries and persistent hand-over. |
| 13–15 | Lock-screen consent, named shortcut consent and official Nocturne signing for public updates through trusted release CI. |
| 16–18 | Keep clock picker policy. Restrict changes to diagnostics privacy to the exported file and its disclosure. Verify build inputs. |
| 19–23 | First run, sleep coverage labels, separate same-day sessions, skip one scheduled start with Undo, and finish a manual run at a chosen time. |

## Behaviour to peer-review

| Item | Behaviour and acceptance check |
| --- | --- |
| 01 | Off waits for a new standby report from the selected BedJet. Challenge it with a cached standby report, a newer running report and an interrupted connection. |
| 02 | Full backup and snapshot creation pass their encoded bytes through the restore validator before writing. At 128 profiles, existing profiles remain editable. Older valid libraries up to 1,024 can round-trip subject to the same byte, token and field limits. |
| 03 | Deleting a night or clearing history removes it from app-managed recovery snapshots. Interrupted erasure is journalled. Previously exported copies and Health Connect belong to their respective owners. |
| 04 | The Remote remembers confirmed temperature and fan changes for each BedJet and mode. On returning to a mode, the controller checks the combined settings against current limits. |
| 05 | The public update path uses Nocturne’s existing Android application ID, signing certificate and an increasing version code. Saved-data fixtures cover old formats and release serialisation. |
| 06 | A deliberate disconnected Start or preset tap can wait for the selected unit. Cancel is available before sending, repeated taps do not multiply writes and Off takes precedence. A newly discovered running programme requires a replacement decision. |
| 07 | Width represents the duration of each Tonight step, height its fan speed and colour its mode. Text explains the fan scale and identifies the wake source. |
| 08 | Long heat is a single, editable bottom-left quick preset. |
| 09–10 | Sleep maps Health Connect stage values explicitly. Tonight distinguishes an accepted phone alarm, a manual override and a profile wake time. |
| 11–12 | A late scheduled start rechecks its occurrence and wake deadline. Releasing a unit persists across process restart until an explicit reconnect. |
| 13–14 | Lock-screen appliance actions require local opt-in. Disabling the setting revokes the posted action. An externally requested Start displays its target and rejects a profile edited after consent. |
| 15–16 | Public beta signing follows the protected Nocturne release path. The clock-app picker keeps its existing behaviour. |
| 17–18 | Diagnostics export uses an allowlist, local unit aliases and relative times. Exact alarm times are optional. Gradle verifies the wrapper and resolved artefacts against pinned checksums. |
| 19–21 | First run offers connection or demo. Sleep identifies missing BedJet observations and lets the reader distinguish separate same-day sleep sessions. |
| 22 | Tonight can skip one scheduled occurrence and undo the skip. The scheduler and UI share the eligibility check. |
| 23 | Manual Finish at captures an absolute deadline and checks it against the device timer limit immediately before sending. Confirmation requires a matching manual countdown. |

The archive validator also applies a 5 MiB limit, 200,000 JSON tokens, 2,048 characters per string and 32 nesting levels. A larger legacy installation can keep its local library, share individual valid profiles and export Sleep CSV. Those files cannot reconstruct every setting, original slot and history record. Android’s document provider controls file creation and can fail during its write.

## Review evidence

At application revision `2b55392a9b79151b14305b2d4cf0629e2fe22682`, the API 36 emulator upgraded a re-signed copy of the public beta.7 APK to `1.1.0-beta.8-review` (20261120) with Android’s in-place installer. First install time was preserved. All 39 settings keys (72 values) and five profiles (167 values) matched after launch. This included the selected BedJet, edited fan steps and presets, display units, appearance and alarm configuration. The app’s package is `io.github.willzy12h.nocturne`.

A second emulator check seeded three confirmed-setting records for two synthetic BedJets and two modes in the review app. Reinstalling the same signed APK in place retained all three, all 84 settings values and all five profiles. The controller tests cover recording settings after confirmation. A physical BedJet is required to verify the complete interaction.

The release JVM fixture exercises 60 stored models across 139 cases. The full-archive validator found an old fixture slot name exceeded the BedJet limit. The replacement retains Unicode coverage at the real 15-byte limit.

The release APK used R8 and resource shrinking. The 5,951,805-byte rehearsal APK has SHA-256 `de113b9163b5e3dfc6d015ec551299803b5e675dd2652b8d6d1e06216ecd1e4b`. Its disposable signing certificate has SHA-256 `7ad64c553302916ea1be9f32c8ca672c074473511ee3525e476147739314cc33`. The published beta.7 certificate has SHA-256 `fd5fa67050e21e2f7e4c6926623ba75ac8236c0f53b72d057fa15cba34f40d81`. A phone with public Nocturne needs the release-signed update to keep its installation.

The fixed-clock Android capture contains 212 checked images, including 28 with historical references and 184 without. The BedJet chooser now opens a dialog, which adds one window in each of eight tested appearance/size combinations. The release checker validates dimensions, capture metadata and inventory. Physical testing is still required. [Rendered evidence](https://github.com/Willzy12h/bedjet-android/actions/runs/44648664287/artifacts/14588974457)

Independent reviewers found a delayed standby report could confirm Off and that a tracked night could conceal a physical remote's change. They also identified a Cancel button covered by the busy overlay, aged-out history accessible through a long-running process, a restore whose merged result exceeded archive limits and stale consent after profile or lock-screen changes. Each finding has a fix on this branch. The complete Gradle, release and lint results are recorded on [draft PR 32](https://github.com/Willzy12h/bedjet-android/pull/32).

## Physical acceptance — NOT RUN

Use a BedJet 3 and a phone with the current public Nocturne installed. Record phone/Android and BedJet firmware, the prior and candidate versions, and the result of each step.

1. Install a release-signed, higher-version beta over the public build. Check the selected unit, customised profiles, quick presets, units and appearance before touching any controls.
2. Change Cool temperature and fan, switch modes, return to Cool and restart the app. Compare the settings shown by Nocturne with the appliance. Repeat on another saved unit if available.
3. Disconnect, tap a preset twice and cancel while connecting. Confirm no appliance change. Repeat once, let it connect, and confirm exactly one action. Try changing the run with the physical remote before reconnecting.
4. Issue Off while running and briefly interrupt Bluetooth. Check that the UI reports uncertainty until the selected BedJet actually reports standby.
5. Release the connection, force-stop and reopen Nocturne. Confirm intentional hand-over survives and that explicit reconnect works.
6. Set Finish at on a manual run, verify the appliance timer and disconnect the phone. Check that it finishes within the selected minute. Check a scheduled skip, Undo and its normal alarm-triggered start independently.

## Signing and build changes

The installer diagnosis reproduced Android's `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when installing the review APK over public beta.7. The same review APK installed and launched on a clean API 36 emulator. Use the signed APK from the public GitHub release for an in-place update. The PR produces the unsigned candidate for the protected signer and a separately test-signed APK for verification. [Public release notes](releases/1.1.0-beta.8.md)

PR jobs use disposable test signing and transfer an unsigned artefact. The existing protected production job signs verified public prereleases as well as stable releases with the established Nocturne certificate. The optional long-lived test-signing step was removed from PR CI. If old test keys are still stored as repository-level Actions secrets, the repository owner should remove them there or scope them to a protected environment; workflow edits alone cannot revoke repository-level access. No credentials were inspected. The wrapper checksum is pinned to the official 8.14.3 binary digest.

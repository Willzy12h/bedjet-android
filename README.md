# Nocturne

An Android app for the **BedJet 3** that plans each night around your alarm. Set a profile up once; every night it
fits itself to when you go to bed and when your Samsung or Google Clock alarm goes off.

Bluetooth only: no account, no cloud. Not affiliated with BedJet LLC.

## What it does

- **Night profiles** in four parts:
  - **Start**: any phases, e.g. Turbo then Heat to warm the bed.
  - **Middle**: stretches or shrinks to fill the night, ramping fan and temperature.
  - **Wake-up**: ends exactly at your alarm.
  - **After wake**: optional, e.g. keep warm for 30 minutes.

  Any mode works in any part. Each profile has min/max fan and temperature limits that everything stays inside.
- **Follows your alarm.** It reads the next alarm from the clock app, and if you change the alarm during the night,
  the plan on the BedJet is rewritten automatically. With no alarm, the profile's own wake time is used and the
  middle of the night shortens to fit.
- **Runs on the BedJet itself.** Tonight's plan is written into one BedJet program slot (slot 3 by default), so your
  phone can sleep. The first time a slot is overwritten, what was in it is saved and can be put back.
- **Wake-up notification** with up to three buttons you choose: Snooze warm, Snooze cool, Extend warm, Extend cool,
  Off. It stays until the night ends.
- **Remote**: mode, temperature, fan, timer, lights, beeps.
- **Boost**: Turbo for its 10 minutes, then Heat for the rest (15 minutes by default).
- **Widgets**:
  - "Nocturne controls" grows from one button to a row, a grid, then a scrolling list of every action and profile.
  - "Nocturne button" is one button you choose.

  Samsung widget stacks let you swipe between them.
- **Shortcuts**: long-press the icon for Start tonight, Turbo, Boost and Off. Samsung Modes & Routines and Bixby can
  run them too.
- **Seasonal presets**: Summer Cool, Winter Warm, Spring/Autumn, Hot Sleeper, Warm Start Cool Night.
- **History** of every night (plan and changes), kept for 60 days.
- **Backups**:
  - automatic snapshots on every change, plus daily (the last 20 are kept);
  - labelled manual snapshots, kept until you delete them;
  - export and import a backup file.
- **Update check** against this repository's GitHub releases.
- **Demo BedJet** to try everything without the hardware.

## Install

1. Download `nocturne-<version>.apk` from [Releases](../../releases) on your phone.
2. Open it and allow "Install unknown apps" for your browser or file manager when asked.
3. Open Nocturne. The **Tonight** screen lists anything still to set up. On a Samsung phone, set battery use to
   **Unrestricted**, or alarm changes overnight may not reach the BedJet.

## Updates

Settings → Updates → **Check now** compares your version with the latest release here. This repository is private,
so the check needs a GitHub token: a fine-grained token with read-only "Contents" access to this repository.

An update only installs over the old version when both are signed with the same key. Set up the release key once:

```bash
keytool -genkeypair -v -keystore /secure/location/outside-this-repo/nocturne-release.jks -alias nocturne -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 /secure/location/outside-this-repo/nocturne-release.jks   # paste the output into the secret below
```

Then add these repository secrets (Settings → Secrets and variables → Actions):

- `NOCTURNE_KEYSTORE_BASE64`
- `NOCTURNE_KEYSTORE_PASSWORD`
- `NOCTURNE_KEY_ALIAS` (`nocturne`)
- `NOCTURNE_KEY_PASSWORD`

Before creating a version tag, also set these Actions repository variables:

- `NOCTURNE_RELEASE_CERT_SHA256`: the SHA-256 certificate digest of the established release key, as printed by `apksigner verify --print-certs`.
- `NOCTURNE_RELEASE_VERSION_CODE`: the integer code for this release.
- `NOCTURNE_PREVIOUS_VERSION_CODE`: the last published release code; the new code must be greater.

Keep the `.jks` file safe: losing it means uninstalling to update (export a backup first).

Local `assembleRelease` builds a minified, unsigned APK unless all four `NOCTURNE_*` signing values are set.
An incomplete or invalid signing configuration fails the build; it never substitutes the debug key. Pull requests
build an unsigned inspection APK. A version tag such as `v0.2.0` publishes only after the configured key signs the
APK and CI verifies its signature, expected signer and increasing version code. Keep the production key outside Git and backed up securely.

## Development

```bash
./gradlew :core:test                 # protocol, planner, night runner, backups (JVM)
./gradlew :app:testDebugUnitTest     # screens on Robolectric with the demo BedJet
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

| Path | What it is |
| --- | --- |
| `core/` | Pure Kotlin: BedJet 3 protocol, plan compiler, wake resolution, night runner, demo BedJet, backups, update parsing |
| `app/` | Android: Bluetooth transport, foreground service, alarms and notifications, Compose screens, Glance widgets |
| `docs/superpowers/` | Design spec and implementation plan |

The BedJet 3 protocol comes from [Lull](https://github.com/rkanjani/lull) (MIT), which worked it out from the
official app. See [NOTICE](NOTICE).

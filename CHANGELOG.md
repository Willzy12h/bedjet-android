# Changelog

Each release's full notes are in [docs/releases](docs/releases) and on the
[releases page](https://github.com/Willzy12h/bedjet-android/releases).

## 1.1.0-beta.8

- Off waits for the selected BedJet to report standby. Start night and quick presets can wait for a connection and be cancelled before sending.
- Remote remembers confirmed temperature and fan changes per BedJet and mode. Manual runs can finish at a chosen time.
- Tonight shows fan speed by step height, adds Long heat to the quick presets and lets you skip one scheduled start or undo the skip.
- Backups are checked before writing. Deleting history also removes it from Nocturne’s recovery snapshots. Diagnostics export previews the file you will save.
- Sleep distinguishes separate sessions on the same day and shows where BedJet observations are missing. [Full beta.8 release notes](docs/releases/1.1.0-beta.8.md)

## 1.1.0-beta.7
- "Can't see your alarm?" help on Tonight when no alarm is found, with tonight's wake time, a fixed profile wake time
  or a choice of alarm apps.
- A setting for alarms from unnamed apps: Ignore them, Ask each night (the default) or Always use them. Earlier betas
  ignored them.
- Battery tips and settings links for Honor, Huawei, Xiaomi, OPPO, OnePlus, realme and vivo phones, and an explanation
  before Android's battery prompt.
- Phone details in the diagnostics export, and repeated start-up, clock and alarm-change broadcasts handled together.

## 1.1.0-beta.6
- Reset to defaults: a built-in profile in its editor, the built-in profiles on Profiles, and the controls in Settings.
  Each asks first; restoring profiles and resetting controls save a "Before reset" snapshot first.
- A test build reminds you to export a backup, and a fresh install offers to restore from a backup file.
- Saved profiles, settings and backups from earlier betas are checked to load in every release.

## 1.1.0-beta.5
- Any alarm at least an hour after the night starts is used as your wake-up, whatever the time of day, so naps can end
  on an alarm again. This replaces the 3-hours-after-bedtime rule from 1.1.0-beta.3, so a stray alarm from a ticked
  clock app an hour or more after the start is used too.

## 1.1.0-beta.4
Includes everything in 1.1.0-beta.3. See [docs/releases/1.1.0-beta.4.md](docs/releases/1.1.0-beta.4.md).

## 1.1.0-beta.3
- Choose which apps' alarms count, by name, from a list of your clock and alarm apps.
- Tonight names an ignored alarm's app and lets you choose it.
- An alarm less than 3 hours after bedtime is no longer used as your wake-up, unless the night started automatically
  a set time before that alarm.
- About shows the installed version and can open the releases page to check for a newer build.

## 1.1.0-beta.2
- First public build: night profiles that fit between bedtime and your alarm, the plan running on the BedJet itself,
  Remote, Boost, widgets, shortcuts, auto-start, sleep history, optional Health Connect and backups.
- Calendar and reminder alarms no longer move your wake-up.
- A run time longer than a mode allows becomes the longest allowed, with a note.

# Nocturne — BedJet 3 night planner for Android

Status: agreed in chat on 2026-10-21. Version 1 scope below; later versions listed at the end.

## Purpose

A sideloaded Android app (no Play Store) that controls one BedJet 3 over Bluetooth and plans each night
around the owner's alarm. Success: set a profile up once, and every night it fits itself to bedtime and the
next alarm without re-editing.

Protocol knowledge comes from [rkanjani/lull](https://github.com/rkanjani/lull) (MIT), which decompiled the
official BedJet app. Only the BedJet 3 is supported; the V2 is out of scope.

## Night profile

A profile is four blocks, each built from phases. A phase is `mode, temperature (half °C), fan %, minutes`.
Any mode is allowed in any block: Cool, Heat, Gentle heat (extended heat), Turbo, Dry, Pause (wait).

| Block | Timing |
| --- | --- |
| Start | Fixed phases from the moment the night starts. |
| Middle | Fills the time between the end of Start and the beginning of Wake-up. One mode, with a ramp: fan and temperature move linearly from their start to end values in `rampSteps` equal steps (1–8). |
| Wake-up | Fixed phases ending exactly at the wake time. |
| After wake | Fixed phases after the wake time (e.g. "extend warm 30 min"). May be empty. |

Each profile also has:

- **Limits**: min/max fan % and min/max temperature. Every phase, ramp step, snooze and extend is clamped
  to them, then to the mode's own range on the BedJet.
- **Wake source**: `useAlarm` (take the next Samsung/Google Clock alarm) plus a `defaultWakeTime` used when
  there is no alarm in the window (default 16 h after the night starts).
- **Bedtime** (for previews and auto-start) and **days** (which weekdays it is the default profile for).
- **Season** tag: Summer, Winter, Spring/Autumn or none, used for grouping and presets.

Built-in presets: Summer Cool, Winter Warm, Spring/Autumn Balanced, Hot Sleeper, Warm Start Cool Night.

## Compiling a night into BedJet steps

`PlanCompiler.compile(profile, start, wake)` returns the steps written to the BedJet and the timeline shown in
the app.

1. Wake-up phases are placed to end at `wake`; After-wake phases follow it.
2. Middle gets `wake − start − Start − Wake-up`. If that is negative, Middle is dropped, then Start is trimmed
   from its end, then Wake-up is trimmed from its front. The plan never starts before `start`.
3. Unit limits are applied by splitting, never by silently shortening:
   - a step longer than 11 h 59 min is split into equal parts;
   - a Heat step longer than the BedJet's heat cut-off (Lull's table) is split into back-to-back steps
     that each fit;
   - a Turbo phase longer than 10 min becomes Turbo 10 min followed by the remainder as Heat at the
     highest temperature the profile's limits allow (max 40 °C). A setting switches this to repeated
     Turbo steps instead (the second Turbo runs at about 40 °C on the unit anyway).
4. The last Middle step ends at the wake-up start as a **clock time** (BedJet 3 feature), so a delay between
   compiling and running doesn't shift the wake-up.
5. More than 32 steps is an error the editor reports before saving.
6. Phases shorter than 1 minute are dropped.

## Alarms

- Read with `AlarmManager.getNextAlarmClock()`. Only alarms created by the clock apps
  (`com.google.android.deskclock`, `com.sec.android.app.clockpackage`, plus any the user allows) count, so
  calendar reminders don't move the wake-up. Android only exposes the **next** alarm, not a list.
- The alarm is used when it falls between the night's start + 1 h and start + 16 h; otherwise the profile's
  default wake time is used.
- `ACTION_NEXT_ALARM_CLOCK_CHANGED` during a running night re-plans automatically: the rest of the night is
  recompiled from now (remaining Start, then Middle, Wake-up, After-wake), rewritten to the working slot and
  restarted. Once Wake-up has begun, alarm changes are ignored.
- Profiles show "would wake you at HH:MM" from the current next alarm.

## BedJet slots

The app keeps unlimited profiles. Tonight's compiled plan is written to one **working slot** (default slot 3,
changeable). Before the app first overwrites a slot it reads what is there and keeps a copy in the backup
store. Profiles can also be pinned to another slot. After writing, the program is read back and compared.

## Wake-up notification

At the start of Wake-up an ongoing notification appears and stays until the night ends or Off is pressed.
Up to three action buttons, chosen in settings from: Snooze warm, Snooze cool, Extend warm, Extend cool, Off.
Snooze defaults to 10 min, Extend to 30 min; each has its own mode, temperature and fan. An action runs that
setting immediately as a manual run with a timer, clamped to the profile limits.

## Remote, widgets, shortcuts

- Remote: mode, temperature, fan, timer, LED and beep settings, with Boost (Turbo 10 + Heat).
- Widgets: a responsive "Quick controls" widget that shows 1, 2, 4 or more buttons depending on its size
  and scrolls when large (works with Samsung widget stacks for swiping), and a 1×1 single-action widget
  configured to start a profile, Turbo, Boost, Cool or Off.
- App shortcuts for "Start tonight", "Turbo", "Off" (usable from Samsung Routines and Bixby).
- Widget and notification actions run through a short foreground service: connect, send, disconnect.

## Backup, snapshots and history

- Backup bundle (JSON, versioned): profiles, settings, slot backups, history.
- **Automatic snapshots** on every profile or settings change (debounced) and daily; the last 20 are kept.
- **Manual snapshots** with a label are kept until deleted.
- Restore from any snapshot; export/import a bundle as a file through the system file picker.
- History: one record per night — profile, wake source and time, the compiled plan, and events (started,
  re-timed, snooze, extend, ended, BedJet notifications). Kept 60 days.

## Updates

Settings → Check for updates queries `GET https://api.github.com/repos/{owner}/{repo}/releases/latest`
(repo configurable, default `Willzy12h/bedjet-android`, optional token for a private repo), compares the tag
(`v1.2.3`) with the installed version, and opens the release's APK link in the browser. This is the app's only
network use, and it only runs when asked (or weekly, if switched on).

## Reliability on Samsung

Setup walks through: Bluetooth permissions, notifications, exact alarms, and "Unrestricted" battery use (with
a direct link to the app's battery settings). The Tonight screen warns when any of these is missing.

## Demo BedJet

A simulated BedJet 3 that speaks the same bytes (ported from Lull's `DemoLink`) can be switched on in
settings, so every feature can be tried without the hardware.

## Error handling

- BedJet unreachable: retry for 60 s; on failure show a notification saying what didn't happen
  (e.g. "Wake-up still at 06:45 — couldn't reach BedJet").
- BedJet notifications (clock not set, program too long, clean filter) are shown and acknowledged.
- Changes made from the physical remote are detected from status and noted in history, not overwritten.

## Later versions (not in v1)

- v2: Health Connect sleep overlay on History; morning check-in (too hot / just right / too cold) and tuning
  suggestions; quick-settings tile; smart wake from the room sensor; Lull-compatible phone hand-over.
- v3: auto-apply tuning; external Bluetooth thermometers; Home Assistant and Tasker intents.

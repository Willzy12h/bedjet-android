# Review prompt for ChatGPT

**Model:** GPT-6 Astra, highest reasoning effort ("Thinking" / extended). Use GPT-6.1 Sol only for quick follow-ups.
**Access:** connect ChatGPT's GitHub connector to `Willzy12h/bedjet-android` (branch `claude/lucid-mayer-i1h2gy`), or upload a zip of that branch.

---

You are reviewing **Nocturne**, an Android app I sideload. It controls a **BedJet 3** (a bed heating and cooling unit) over Bluetooth LE and plans each night around my phone's next alarm. Repository: `Willzy12h/bedjet-android`, branch `claude/lucid-mayer-i1h2gy`. Claude (Anthropic) wrote it. I want a finished, polished, bug-free app. Review it critically: I'd rather hear about a real problem than be reassured.

## What the app is
- **Night profiles:**
  - Start, Middle, Wake-up and After-wake blocks.
  - The Middle stretches to fit the time until the alarm.
  - Min/max fan and temperature limits; seasonal presets.
- **Alarm-aware:**
  - It reads the next Samsung or Google Clock alarm, and re-times the plan on the BedJet when the alarm changes during the night.
  - With no alarm, it uses the profile's own wake time.
- **The night runs on the BedJet:** the plan is written into one of its three program slots (slot 3 by default), backing up what was there.
- **Wake-up notification** with snooze/extend buttons.
- **Remote, Boost** (Turbo then Heat), widgets showing the BedJet's name, launcher shortcuts.
- **Records:** history kept 60 days, automatic and manual snapshots, backup export/import, and a notification log.
- **Look:** dark "Midnight Ink" and light "Daylight" themes (System/Dark/Light setting), plus an optional "Simple view" timeline.
- **Safety prompts:** it asks before anything replaces a running night, and when you edit tonight's running profile it asks "Update tonight or next night?".

## Where to look
- **Design:** `docs/superpowers/specs/2026-10-21-nocturne-design.md`, and the plan in `docs/superpowers/plans/`.
- **`core/`** (pure Kotlin, JVM-tested):
  - BedJet 3 byte protocol: `protocol/` (ported from github.com/rkanjani/lull, MIT)
  - Plan compiler and wake resolution: `plan/`
  - Night runner: `night/`
  - Controller and simulated BedJet: `link/`
  - Backups, snapshots, history and update parsing: `store/`
- **`app/`** (Android):
  - Bluetooth: `ble/AndroidBedJetLink.kt`
  - Command orchestration: `night/NightActions.kt` (one mutex), `night/LinkSessions.kt`
  - Alarms and receivers: `night/`
  - Foreground service: `service/`
  - Storage: `data/Repository.kt`
  - Compose screens: `ui/`
  - Glance widgets: `widget/`
- **Build and test:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. At handoff:
  - 69 core tests and 24 app tests pass (app tests are Robolectric, against the simulated BedJet);
  - lint has 0 errors.

## Important context
- **Nothing has run on a real phone or BedJet yet.** The Bluetooth path (`AndroidBedJetLink`) is reasoned from Android's documented behaviour and Lull's iOS code, and has never been exercised on hardware. Scrutinise it most.
- Two Claude code reviews have been done and their findings fixed. Deliberate trade-offs are the lines containing `Ruling:` in `.superpowers/sdd/2026-10-21-nocturne-v1/progress.md`. Challenge them if you disagree.
- **Known open items** (confirm, fix approach, or dispute):
  1. The message bar ("snackbar") covers the bottom row of quick buttons for a few seconds after an action.
  2. Release builds aren't shrunk (`isMinifyEnabled = false`). The debug APK is about 60 MB, mostly `material-icons-extended`. R8 needs keep rules checked for kotlinx.serialization.
  3. The Tonight curve and mid-night re-plans are rebuilt from the *current* profile, not the steps actually written. After a profile edit or several re-times they can differ from what the BedJet runs (the stored steps are in `NightRecord.steps`).
  4. The profile editor checks the 32-step limit only for tonight's preview. A ~24 h night (early start with the default wake time) can exceed it at run time.
  5. BedJet notices (e.g. clean filter) and Remote-control failures aren't recorded in History.
  6. The status 133 retry only helps when 133 arrives within the 12 s connect patience.
  7. In the spec but not built:
     - pinning a profile to a second BedJet slot;
     - detecting changes made with the BedJet's physical remote;
     - auto-start at bedtime on the charger.
  8. The theme setting is System/Dark/Light. I also asked for an automatic switch **by time of day**, which isn't built.

## What I want from you
1. **Bugs and risks**, most severe first. For each: file:line, what goes wrong for me as the user, a concrete scenario, and the fix. Focus on:
   - overnight reliability: wrong or missing wake-up, the BedJet left running, a silent failure;
   - Bluetooth;
   - concurrency and deadlocks;
   - Samsung background limits;
   - data loss;
   - security of exported components.
2. **Code quality:** anything unclear, duplicated, over-engineered or fragile, especially tests that check the wrong thing or are flaky.
3. **UI and design:**
   - accessibility (contrast, touch targets, TalkBack labels);
   - layout problems;
   - wording;
   - consistency between Dark, Light and Simple view.
4. **Quality-of-life and features.** Rate each of these ideas (value / effort / risk), and add your own:
   - "Undo" on the message bar after a night is replaced or stopped;
   - a tonight-only wake time that doesn't touch the phone alarm;
   - a bedtime reminder notification, and auto-start when the phone goes on the charger after bedtime;
   - theme switching by time of day;
   - a quick-settings tile;
   - a home-screen widget showing the next wake time and the current mode;
   - a first-run setup wizard (permissions, Samsung battery, pick the BedJet);
   - a morning check-in ("too hot / just right / too cold") that suggests profile tweaks;
   - a Health Connect sleep overlay on History;
   - export History to CSV;
   - phone hand-over compatible with Lull (iOS);
   - haptics on key actions;
   - moving hard-coded UI strings to resources for translation.
5. **A final table:** each item, its severity or value, and who should implement it. I'll confirm whether you or Claude does each one.

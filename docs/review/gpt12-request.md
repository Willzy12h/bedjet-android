# GPT-12 — Nocturne final pass: user-acceptance fixes, R5/R4, cleanup, self-review

Paste into the **same ChatGPT chat that ran GPT-11** if it's still available; otherwise use a new chat. This prompt stands alone.

---

You are doing the final functional pass on **Nocturne**, an Android app (`Willzy12h/bedjet-android`, package `io.github.willzy12h.nocturne`) that controls a **BedJet 3 only** over BLE.
- Stack: Kotlin, Jetpack Compose, Glance widgets, coroutines, Gradle 8.14.3.
- `core` is pure Kotlin with JUnit 5. `app` uses Robolectric, JUnit 4 and `@Config(sdk=[34])`.
- UK English throughout.

The user has installed and used the GPT-11 test APK (source `614cd32`, merged to `main` at `e61b8b4`). The app works. This round covers the user's acceptance feedback, the two known missing behaviours (R5, R4), cleanup, then a full self-review loop. After this round, the user will run a Codex security scan.

## 0. Rules (non-negotiable)
- **Branch.** Create `gpt/nocturne-final` from `origin/main` at `e61b8b4`. Record the base SHA.
- **Do not merge anything into `main`.** Last round merged PR #3 into `main` without authorisation. Don't repeat that.
  - Open a PR from `gpt/nocturne-final` to `main` at the end and **leave it open**. The user merges.
  - Never push to `main` or `claude/*`. Never force-push, rebase pushed commits, `git reset --hard`, `git clean`, or delete a branch or worktree.
- **Signing.** Don't touch the production signing identity or publish a release or tag. Sign the test APK with the **same test key as the GPT-11 APK** (certificate SHA-256 `42efc6e2284a00ae55345479177e27c45242196c02acb0f51640b886f7c2bde9`) so it installs as an update over the user's current build. Use a higher `versionCode` than 20261026.
- **Way of working.**
  - Test-first for every change: RED, then GREEN. Name the tests.
  - All user-visible strings go in `res/values`.
- **Truthful states.** Pending, failed, unknown and confirmed are shown honestly. Confirmed comes only from a BedJet status receipt.
- **No verification claims.** Never claim hardware, Samsung, TalkBack or on-device verification.
- **Keep these:**
  - single command owner: `NightActions` via `BedJetService.runOwned`;
  - timeouts: UI 115 s > service 100 s > owner 90 s;
  - APP-only haptics;
  - tracked-night Off/Stop confirmations;
  - the Temporary-then-resume behaviour;
  - the Turbo 10-minute cap and Boost labels;
  - U06 contrast: curve bars ≥ 3:1, text ≥ 4.5:1;
  - the Bluetooth-off banner;
  - the 30 s connection grace period;
  - the 1–18 h alarm window and its explanations.
- **Gate.** Run this, plus `:app:assembleRelease`. Never skip, disable or `@Ignore` a test; the 38 opt-in capture skips are expected.
  ```
  ./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
  ```

## 1. Bugs (fix first)
**B1. Collapsed Tonight sections can't be reopened.** When a section on the home (Tonight) screen is collapsed, nothing lets the user expand it again.
- A collapsed section must keep a visible header row with a chevron. Tapping it expands the section. It's at least 48 dp, with an accessible "Expand …" / "Collapse …" label and a state description.
- This applies to every collapsible section, Quick included.
- Test: collapse, then expand, for every section, surviving a restart.

**B2. The demo unit traps the home target picker.** After selecting the demo unit, the home screen offers no way back to a real BedJet; the user has to go to Settings and pair manually.
- Remove "Demo" as a selectable unit in the target list.
- Make demo a separate mode: **"Try demo"**, in Settings and on the first-run/no-BedJet screen. It works immediately with no unit selection.
  - A persistent banner says "Demo — no BedJet connected" with a one-tap **Exit demo**.
  - Demo actions never touch BLE, History, Health Connect, backups or real originals.
- The home target picker must let the user **scan and pair a BedJet directly** from home, and switch between remembered BedJets. That makes the home picker meaningful.
- Migrate users currently on the demo target to "no BedJet selected", with a prompt to pair.
- Tests: exiting demo returns to the real-unit flow; the picker scans and pairs from home; demo data is isolated.

**B3. The wake time defaults to the night's end instead of the next alarm.** The top-right wake time must show the **next phone alarm** whenever Android exposes an accepted one, even when the user's profile normally uses a fixed or night-end time.
- When no alarm is available, show the fallback with its small reason text (as now).
- Show the end of the current night schedule only when the user explicitly chose a tonight override.
- Keep the 1–18 h window explanation for alarms outside the window.
- Tests: alarm present beats profile end time; no alarm gives the fallback with its reason; an explicit override wins.

**B4. Scheduled theme gives no light-theme warning.** Choosing **Scheduled** while the schedule is currently in its daytime (light) period doesn't warn. Required behaviour:
- **Choosing a setting:** any choice that would make the theme light right now (Daylight, System while the system is light, Scheduled during its day period) shows the existing confirmation. Tapping outside, Back or Cancel keeps the setting unchanged.
- **Automatic flip while the app is visible:** a Scheduled or System change from dark to light doesn't switch instantly. Show a dark-styled prompt: "Light theme is due — Switch now / Stay dark until tonight". Stay dark holds until the next scheduled dark period.
- **Automatic flip while the app isn't visible:** apply light on the next open, after the same prompt.
- Dark changes never prompt.
- Tests for each case.

## 2. UI changes
**C1. Simple view is the default and the design focus.**
- New installs default to Simple. Existing users keep their saved choice.
- Make Simple the most polished presentation: spacing, typography, colours and tones.
- Other styles must still work, but Simple is the reference.

**C2. Profiles tab uses the Simple view style**, matching the night editor from GPT-11 U3.

**C3. Fixed status header.** One static header at the very top of the main screens shows:
- connection state: Connected, Connecting, Disconnected, Bluetooth off or Demo;
- the selected BedJet;
- the current temperature.

It doesn't scroll away, and it replaces the scattered connection indicators.

**C4. Calmer temperature display.**
- Displayed temperatures update at most every **30 s**, and only when the change is at least 0.5 °C (hysteresis), so the numbers don't flicker.
- Control feedback (mode, setpoint, fan, timer), status receipts and confirmations stay immediate. Don't change the confirmation path.
- Don't add BLE polling. BLE stays notification-driven.
- Tests: the display holds small changes; it updates after 30 s and ≥ 0.5 °C; controls stay immediate.

**C5. Remote slider scales.**
- Fan slider: labelled ticks at 0 %, 50 % and 100 %, aligned to the bar's notches.
- Temperature slider: labelled ticks at the selected mode's minimum, middle and maximum (from `Model.kt`, or the unit-reported limits when present), aligned to the notches.
- The labels mustn't clip at 320 dp / 200 %. They need accessible value descriptions.

**C6. Custom run duration.** Next to the fixed run buttons (15 min, 1 h and so on), add **Custom…**.
- It opens an hours/minutes picker, bounded by the mode's limits: Turbo ≤ 10 min, and other modes up to the BedJet's maximum run time from the protocol (cite it).
- The duration goes through the owner and confirms from the receipt.
- During a tracked night it follows the existing Temporary-then-resume or End-tonight choice.
- Tests: bounds, the Turbo cap, owner routing.

**C7. Move Lights and Beeps from Remote to Settings**, in a device section.
- Keep owner routing and pending-then-confirmed behaviour.
- Keep the switch directions as they are; GPT-10 verified the mappings.

**C8. Auto-start replaces charger auto-start.** Turn the charger-only job into an **Auto-start** feature:
- **When:** either a fixed time, or relative to the wake time, for example "8 h before next alarm". If there's no alarm, it uses the profile fallback, and says so.
- **Which profile:** a chosen profile.
- **Options:**
  - "Only if the phone is charging" (off by default; when on, keep the existing charging grace and cancel behaviour);
  - skip if a night is already running or the BedJet isn't reachable.
- **Scheduling:** one alarm per night (exact if the app already holds that permission, otherwise inexact with a stated window). No polling. Re-arm after reboot and after time or timezone changes.
- **Outcome:** a notification with the truthful result: started (confirmed), failed or unknown.
- **Migration:** existing charger auto-start settings carry over as "Auto-start at bedtime, only if charging".
- Tests: time calculation, charging gate, skip rules, migration, reboot re-arm, no duplicate starts.

**C9. Lull phone hand-over.** This setting asks an iPhone running the Lull app to release the BedJet so this phone can connect. It's only useful in a two-phone household with an iPhone, and its two-phone acceptance never happened.
- Remove it from the UI.
- If its code is isolated, remove the code and its tests. If removing it is risky, leave the code disabled and unreachable, and say which you did.
- Do not remove the Lull protocol credit (see C11).

**C10. Updates.** Remove the in-app update check and its signature or key verification.
- Replace it with a Settings row: "Check GitHub for a new APK", which opens the repository's Releases page in the browser.
- Remove the related code, workers, settings and strings.
- If nothing else needs the `INTERNET` permission afterwards, remove it from the manifest and say so. Check Health Connect, which doesn't need it.

**C11. Tidy the About section.** Keep it short:
- one line describing the app;
- "Not affiliated with BedJet LLC";
- the version;
- a "Project and help on GitHub" link;
- **the required MIT attribution:** "BedJet protocol work by the Lull project (github.com/rkanjani/lull, MIT)". This must stay; the licence requires it.

## 3. Missing behaviours from the GPT-11 cross-check
**R5 (priority). Backup import preview and protection.**
- Before importing, preview what will be added, replaced, preserved or left unchanged across profiles, settings, History and saved BedJet originals.
- Warn about uncertain originals, incompatible data and device associations.
- Refuse an import while any hardware write, restore or queued command is pending or running.
- Re-validate immediately before applying.
- Keep atomic restore, rollback and the existing backups.
- Validate the input strictly: size limits, schema version, and rejection of malformed or oversized fields.
- Tests: preview contents; refusal while busy; the re-validation race; malformed input.

**R4. Health Connect sleep write (optional, off by default).**
- Export only completed nights where the BedJet's run was confirmed, as sleep sessions, with explicit write permission.
- Never export equipment tests, demo nights, or incomplete or uncertain nights.
- No duplicates: use a stable client record ID and update rather than insert again.
- Handle permission revocation, failed writes and retries.
- Add a "Sync now" button and an "exported" marker in History.
- Label it as "BedJet programme ran", not verified sleep.
- Tests for each rule.

## 4. Self-review loop and security pre-check
1. Run the gate. Then review the whole diff from `e61b8b4`, and every touched file, for:
   - correctness;
   - races and mutex misuse;
   - lifecycle and receiver leaks;
   - truthfulness (no confirmed state without a receipt);
   - origin and haptic rules;
   - strings outside resources;
   - accessibility (≥ 48 dp targets, 200 % text, content descriptions);
   - contrast;
   - new lint findings;
   - battery (no new polling, wakelocks or infinite animations).
2. **Security pre-check**, ahead of the user's Codex scan:
   - every exported component and its permission;
   - `PendingIntent` mutability flags;
   - validation of intent extras on widget, shortcut and notification receivers;
   - bounds on backup and import parsing;
   - no secrets or keys in the repo;
   - no personal data in logs or diagnostics beyond what the export states;
   - remaining permissions are each justified;
   - FileProvider paths are minimal.

   Fix anything concrete. List the rest.
3. Remediate every defect, major or minor, test-first. Re-run the gate. Repeat until a full pass finds nothing and the gate is green: 0 lint errors and no new warnings against `e61b8b4`.
4. **Defects only in the loop.** Put new ideas under "Suggestions — not implemented".
5. This is a self-review, not sign-off. Don't call it release-ready.

## 5. Deliverables
- **Test APK** signed with the test key (certificate `42efc6e2…`), with its file name, SHA-256, certificate SHA-256, `versionName`/`versionCode` and source SHA.
- **PR** from `gpt/nocturne-final` to `main`, **open and not merged**.
- **Handover**, in this order:
  1. base and final SHAs, plus the commit list;
  2. B1–B4, C1–C11, R5 and R4: each with status, tests and key file:line;
  3. the C9 decision (removed or disabled) and the C10 decision (whether `INTERNET` was removed);
  4. a self-review log: defects found → fix commit → test;
  5. security pre-check results and open items;
  6. gate results: core and app counts, skips, lint, `assembleRelease`;
  7. APK details;
  8. known limitations, with nothing described as device-verified;
  9. suggestions, not implemented;
  10. a short prioritised phone-test list for this build.

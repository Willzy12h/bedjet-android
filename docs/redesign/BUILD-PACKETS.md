# Nocturne redesign: the full build brief for GPT/Codex

> **Remediation ownership/status:** The owner authorised whole-app remediation on `gpt/nocturne-redesign-remediation`. Earlier Claude-only file reservations are superseded; Claude performs the independent review afterwards. Approved design decisions in this document remain binding. Historical test counts and warning budgets are not the current gate; see [GPT-HANDOVER.md](GPT-HANDOVER.md), [FINAL-REVIEW.md](FINAL-REVIEW.md) and [REMEDIATION-REGISTER.md](REMEDIATION-REGISTER.md).

You are building the whole Nocturne redesign, start to finish, in one run. Work through the phases below **in order**:
finish each one, check it, commit it, then move to the next. Keep going until nothing is outstanding. Then build the APK
and write the handover.

- Design to match (source of truth for look, layout and wording): https://claude.ai/artifact/EXWxySVCprLys7T3YHiiZj
- What already exists and which files each part touches: [CODE-MAP.md](CODE-MAP.md)
- Repo: `Willzy12h/bedjet-android`. Start from the latest `claude/nocturne-redesign-foundation`.

## How to work

1. Create **one** branch, `gpt/nocturne-redesign`, from the latest `claude/nocturne-redesign-foundation`. Put every
   phase on it as its own commits, with messages starting with the phase number (`P3: side-stripe tiles …`).
2. For each phase:
   - Read the phase, the matching part of the design and the files CODE-MAP.md lists.
   - Write the failing tests first and run them to see them fail (RED). Implement until they pass (GREEN).
   - Run the phase check: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug`. All must pass, with no new lint errors.
   - Tick the phase's checklist in your handover file (below), commit, push the branch, and move on.
3. **Roadblocks.** Don't stop and wait for the person. Try to solve it yourself first: read the code, the tests and the
   design. If a decision really is the person's, make the most sensible assumption that keeps the app safe and truthful,
   build it that way, and record it under "Assumptions" in the handover so they can change it later. Only leave something
   unfinished if it can't be done safely; record exactly what and why.
4. When every phase is done, do the **Final phase** (full verification, APK, handover, prompt back to Claude).

## Rules (non-negotiable)

- Push only `gpt/nocturne-redesign`. Never push to `claude/*` or `main`. No force-push, rebase, merges, tags or releases.
  Don't merge any PR. The only PR you may open is the draft PR in the Final phase, and only if you need it for the APK.
- Never read or print secret values. Never commit a keystore, passwords, base64 blobs or APK files. Don't touch
  signing configuration, the production signing identity or the release workflow.
- Test first, RED then GREEN, for every behaviour change.
- All user-visible text goes in `app/src/main/res/values/strings.xml`. UK English ("colour", "favourite", "programme" only
  where the app already says it).
- BedJet 3 only. Keep pending / failed / unknown / confirmed states truthful. Never show a state the BedJet didn't confirm.
- Never claim hardware, Samsung, TalkBack or on-device verification. Report "unit tests pass", "lint passes" and
  "APK builds", and nothing more.
- Don't rewrite the foundation Claude built (`QuietFan` / `FanShape` / `shiftFan`, `PlanCompiler` Middle splitting,
  `HeatScale`, the `Palette` enum, `Settings.palette()`, `ui/Palettes.kt`). Use it. If it truly has to change, make the
  smallest change, add a test, and explain it in the handover.
- Don't change the backup `SCHEMA` or the strict backup parser; Claude decides that in review.
- Keep every existing feature working: confirmations, notifications, alarms, widgets, backups, demo mode, setup checks.
  Restyle them; don't drop them. Don't delete or weaken existing tests; update a test only when the design changes the
  behaviour it checks, and say which ones you changed in the handover.
- Accessibility: every tappable thing has a content description or visible label, touch targets are at least 48dp,
  and colour is never the only signal (the side stripe always has a text label next to it).

---

## P2: Theme wiring and Appearance

1. In `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Theme.kt`, replace the fixed Midnight Ink / Daylight pair
   with colours built from the chosen palette:
   - `palette = settings.palette(at, systemDark).tokens` (see `ui/Palettes.kt` and core `store/Settings.kt`).
   - Material `ColorScheme`: background = ground, surface = surface, onBackground/onSurface = ink,
     onSurfaceVariant = ink2, outline = hairline, primary = primary, onPrimary = onPrimary, error = bad.
     `darkColorScheme` for dark palettes, `lightColorScheme` for Overcast.
   - `NocturneColors` from the same tokens: cool = cool, dry = dry, idle = off, accent = accent,
     heat = ember[1], gentleHeat = ember[0], turbo = ember[3], connected = keep the current green.
   - Where a step's temperature is known (night-curve bars, step list), colour it with
     `tokens.stepColor(mode, temperature)` so heat reads by warmth (Ember scale).
   - Keep `ThemeMode` (System / Dark / Light / Scheduled) and the scheduled logic exactly as they are.
     `NocturneTheme(mode: ThemeMode)` with no settings uses the defaults (Midnight Ink / Overcast).
   - Remove `DaylightColors`, `SimpleDaylightColors` and the old fixed schemes only once nothing references them.
2. Settings › Appearance (`ui/SettingsScreen.kt`): add "Night palette" and "Day palette" rows. Each is a row of seven
   round swatch dots (the palette's ground with an accent ring); the current one is marked with a ring and a tick.
   Content descriptions: Midnight Ink, Forest, Graphite OLED, Plum, Autumn · Rust, Winter · Storm, Overcast. Tapping
   saves `Settings.nightPalette` / `dayPalette` through the existing settings save path.
3. Tests: tokens resolve correctly for Dark, Light, System and Scheduled with custom night and day palettes; both
   palettes survive a backup export → import round trip; tapping a swatch saves it (ViewModel/state level is fine).

## P3: Side-stripe tiles, Home / Tonight and Profiles

1. Add one shared `StripeTile` composable (new file `ui/StripeTile.kt`): clipped rounded corners (about 12dp),
   `Modifier.sideStripe(colour, tokens)`, the icon beside the name on the first line, and the subtitle on its own
   full-width line underneath. The subtitle wraps to a second line rather than cutting off with "…".
   No gradients anywhere.
2. Use it for: quick-action / preset tiles (`DeviceControls.kt`, `QuickDefaultsSection.kt`, `TemporaryActions.kt`),
   Tonight's step list (`TonightSections.kt`), profile cards and preset rows (`ProfilesScreen.kt`). The stripe colour
   comes from `tokens.stepColor(mode, temperature)`.
3. Home / Tonight (`TonightScreen.kt`, `TonightSections.kt`, `TonightWakeHeadline.kt`, `StatusHeader.kt`): match the
   design's Home and Through the night screens in both Midnight Ink and Overcast. The Start button uses
   `tokens.primary` with `tokens.onPrimary` text (Sky blue with white text in Overcast).
4. Night curve (`NightCurve.kt`): squarer bar ends (corner radius about 2dp); bars coloured by `stepColor`.
5. The profile "⋯" menu uses `tokens.menu` with `ink` text and a hairline border (it was dark in Overcast before).
6. Tests: a tile's stripe colour comes from `stepColor`; the subtitle text is complete (not ellipsised) in the semantics
   tree; the Start button colours come from the tokens.

## P4: Profile editor, step editor and fan shapes (R1)

The model is done (`Middle.quietFan`, `FanShape`, `shiftFan`, compiler splitting). Build the UI.

1. **Fan shape chooser.** In the editor's Middle section (`ProfileEditor.kt`, around the FanSlider near line 214) add a
   "Fan" segmented chooser with three options, separate from the temperature shape:
   - Steady: one slider; sets `fanStart == fanEnd`, `quietFan = null`.
   - Ease down: start and end sliders; `quietFan = null`.
   - Quiet middle: settle speed and minutes, asleep speed, rise speed and minutes. Saves `quietFan` and also sets
     `fanStart = settle`, `fanEnd = rise` so older app versions still read something sensible.
     Suggested defaults when first picked: settle 50% / 30 min, asleep 25%, rise 45% / 40 min.
   - All speeds in 5% steps within the profile's fan limits; minutes 0 to 720.
   - Switching from Quiet middle to another shape keeps the settle speed as the start.
2. **Step editor** (`EditorPhaseSelection.kt`, `CustomRunDuration.kt`): any step can run from 1 minute to 12 hours.
   Turbo is fixed at 10 minutes and 43°; show those values as text, with no sliders. Keep the existing Turbo chaining.
3. **Long heat.** Show `EXTENDED_HEAT` as "Long heat" everywhere in the UI, through strings. Leave `Mode` enum names and
   `Mode.displayName` alone unless the UI reads `displayName` directly; if it does, switch those call sites to a string
   resource.
4. **Describe the Quiet middle everywhere** by reading `fanShape`: the night-curve timeline text (`NightCurve.kt`
   around lines 190–237, today "easing off" / "building"), the profile list summary (`ProfilesScreen.kt` around 221)
   and the History morning check-in proposal (`HistoryScreen.kt` around 148: "All fan speeds +10%" for a Quiet middle).
   The words are settle → asleep → rise, matching the design.
5. **Presets** (core `plan/Presets.kt`): give `hotSleeper` a Quiet middle (Cool, settle 50 / 30 min, asleep 25,
   rise 45 / 40 min). Leave the other presets alone. Update `PresetsTest` for this change.
6. Tests: editor state ↔ `Middle` for all three shapes, including the switches between them; Turbo can't be given
   another length or temperature; 1-minute and 720-minute steps are accepted and 0 and 721 are refused; the curve
   description and the check-in text for a Quiet middle; presets still compile within 32 steps.

## P5: Cooldown

1. New profiles (created in the app, not imported) get an after-wake Cooldown by default: one phase,
   `Phase(Mode.COOL, 44, 30, 10)` (Cool, fan 30%, 10 minutes), after which the night ends and the BedJet turns off as
   it does today.
2. Existing profiles are offered it **once**: a dismissible card at the top of Profiles, "Add a 10-minute Cooldown after
   waking?", with a checkbox per profile. It's ticked by default only for profiles with no after-wake; a profile that
   already has an after-wake shows what it would replace and is unticked. Buttons: Add (applies to the ticked ones) and
   No thanks. Store the answer in a new `Settings` field with a default (for example `cooldownOfferAnswered = false`)
   so older settings load. Nothing on a profile changes until the person taps Add.
3. Tests: new-profile default; the card shows until answered and never again after Add or No thanks; Add changes only
   the ticked profiles; the new setting survives a backup round trip and older settings load without it.

## P6: Guided setup restyle, and Help and about

1. Restyle `SetupFlow.kt`, `SetupChecklistCard.kt` and `SetupChecks.kt` to the new tokens and tiles. Don't change the
   steps, their order or their logic.
2. Help and about (`SettingsScreen.kt`): add a "Source code on GitHub" row that opens
   https://github.com/Willzy12h/bedjet-android in the browser, through an `ACTION_VIEW` intent. Failing to open it must
   not crash the app; show a short message instead.
3. Restyle the rest of Settings, History (including the "didn't send" chip, which uses `tokens.warn`), sheets
   (`tokens.sheet`) and dialogs to the new tokens, matching the design's Settings and History screens.
4. Tests: the link row exists and carries the URL; a missing browser is handled.

## P7: Widgets v2

1. Update `widget/Widgets.kt` (Glance 1.1.1: `QuickControlsWidget`, `SingleActionWidget`) to the chosen palette's
   tokens and side-stripe rows, matching the design's widget section. Glance can't use `Modifier.sideStripe`, so use a
   4dp coloured `Box` beside the row content over a tinted background.
2. Widgets follow the same night/day palette as the app.
3. Keep every widget action and its confirmation flow (`WidgetActions.kt`, `WidgetConfirmationActivity.kt`,
   `SingleActionConfigActivity.kt`) exactly as it is.
4. Tests: widget colours come from the palette for both a dark palette and Overcast.

## P8: Polish and audit

1. Go screen by screen against the design (Home, Profiles, Profile editor, Through the night, Settings, History, setup,
   widgets), in Midnight Ink and Overcast, and fix every difference you can see in code.
2. Search the UI for hard-coded colours (`Color(0x`), hard-coded user-visible strings, leftover gradient brushes and the
   names "Daylight", "Sand" or "Gentle heat" in the UI. Fix each one or justify it in the handover.
3. Check every new control for a content description, a 48dp target and a text label next to colour.
4. Run the screenshot tests if the repo has them for these screens, and update their goldens only where the design
   changed the screen.

## Final phase: verify, build the APK, hand over

1. **Full check.** From a clean state, run
   `./gradlew clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Everything must pass. If
   anything fails, fix it and run the whole command again.
2. **APK.** The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Record its size and SHA-256. Don't commit it.
   If you can't give the person the file directly, open a **draft** PR from `gpt/nocturne-redesign` into
   `claude/nocturne-redesign-foundation` (never `main`); its CI run uploads the APK as an artifact. Never merge it.
   Note for the person: if Android refuses to install it over the current test build (a "package conflicts" or
   signature error), export a backup in Settings first, then uninstall and install. Don't change signing to avoid this.
3. **Handover file.** Write `docs/redesign/GPT-HANDOVER.md` and commit it, containing:
   - a checklist of P2–P8 and the Final phase, each ticked, with the commit hashes;
   - files changed per phase, and the tests added or changed (by name);
   - the final gradle summary lines (test counts, lint result) and the APK path, size and SHA-256 (plus the draft PR
     link if you opened one);
   - **Assumptions**: each decision you made for the person, what you chose, and how to change it;
   - **Not done / limits**: anything left unfinished and why, plus anything you couldn't check (on-device, TalkBack and
     hardware are always in this list);
   - anything in the foundation you had to change, and why.
4. **Prompt back to Claude.** End your final reply with a fenced block titled "Prompt for Claude" that the person can
   paste to Claude unchanged. It must say: the repo and branch (`gpt/nocturne-redesign`), the last commit hash, the
   path of the handover file, the draft PR link if any, a one-paragraph summary of what was built, your assumptions,
   anything unfinished, and the request "Review the full diff from claude/nocturne-redesign-foundation to
   gpt/nocturne-redesign against the design and docs/redesign, fix what's needed, and do the final passes."

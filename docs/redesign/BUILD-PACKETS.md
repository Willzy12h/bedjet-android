# Nocturne redesign — build packets for GPT/Codex

Design (source of truth for look and wording): https://claude.ai/artifact/EXWxySVCprLys7T3YHiiZj
Code map: [CODE-MAP.md](CODE-MAP.md). Base branch: `claude/nocturne-redesign-foundation`.

Work the packets **in order**, one branch and one commit series per packet. Claude reviews each before the next.

## Rules for every packet (paste these with each prompt)

- Branch from the latest `claude/nocturne-redesign-foundation` as `gpt/redesign-pN-<name>`. Never push to `claude/*` or `main`.
- No force-push, rebase, merges, tags or releases. Don't open or merge PRs unless the person asks.
- Never read or print secret values. Never commit a keystore, passwords or base64 blobs. Don't touch signing.
- Test first: write the failing test, run it and see it fail (RED), then make it pass (GREEN). Say which tests you added.
- All user-visible text goes in `app/src/main/res/values/strings.xml`. UK English ("colour", "favourite").
- BedJet 3 only. Keep pending / failed / unknown / confirmed states truthful; never show a state the BedJet didn't confirm.
- Never claim hardware, Samsung, TalkBack or on-device verification. Say "unit tests pass" and nothing more.
- Don't change anything in the "Already built" table of CODE-MAP.md unless the packet says so; raise it instead.
- Before finishing: `./gradlew :core:test :app:testDebugUnitTest` and `./gradlew :app:lintDebug` must pass. Paste the summary.
- Finish with a short note: files changed, tests added, anything you weren't sure about.

---

## P2 — Theme wiring and Appearance

Branch: `gpt/redesign-p2-theme`.

1. In `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Theme.kt`, replace the fixed Midnight Ink / Daylight pair
   with colours built from the chosen palette:
   - `palette = settings.palette(at, systemDark).tokens` (see `ui/Palettes.kt` and core `store/Settings.kt`).
   - Material `ColorScheme`: background = ground, surface = surface, onBackground/onSurface = ink,
     onSurfaceVariant = ink2, outline = hairline, primary = primary, onPrimary = onPrimary, error = bad.
     `darkColorScheme` for dark palettes, `lightColorScheme` for Overcast.
   - `NocturneColors` from the same tokens: cool = cool, dry = dry, idle = off, accent = accent,
     heat = ember[1], gentleHeat = ember[0], turbo = ember[3], connected = keep the current green.
   - Where a step's temperature is known (night curve bars, step list), colour it with
     `tokens.stepColor(mode, temperature)` so heat reads by warmth (Ember scale).
   - Keep `ThemeMode` (System / Dark / Light / Scheduled) and the scheduled logic exactly as they are.
     `NocturneTheme(mode: ThemeMode)` with no settings uses the defaults (Midnight Ink / Overcast).
   - Remove `DaylightColors`, `SimpleDaylightColors` and the old fixed schemes only once nothing references them.
2. Settings › Appearance (`ui/SettingsScreen.kt`): add "Night palette" and "Day palette" rows. Each is a row of seven
   round swatch dots (the palette's ground with an accent ring); the current one is marked with a ring and a tick.
   Each dot's content description is the palette name: Midnight Ink, Forest, Graphite OLED, Plum, Autumn · Rust,
   Winter · Storm, Overcast. Tapping saves `Settings.nightPalette` / `dayPalette` through the existing settings save
   path. Defaults: Midnight Ink (night), Overcast (day).
3. Tests (JUnit4 in app, JUnit5 in core, matching the files around them):
   - the theme resolves the right tokens for Dark, Light, System and Scheduled modes with custom night/day palettes;
   - `nightPalette` / `dayPalette` survive a backup export → import round trip;
   - the Appearance pickers save the tapped palette (ViewModel/state level is fine).

## P3 — Side-stripe tiles, Home and Profiles

> Add one shared `StripeTile` composable: rounded corners clipped, `Modifier.sideStripe(colour, tokens)`, icon beside the
> name on the first line, subtitle on its own full-width line underneath (never truncated to "…" when there's room on a
> second line). Use it for quick-action/preset tiles, Tonight's step list (`.stp` in the design), profile cards and
> preset rows. No gradients anywhere. Bars in the night curve get squarer ends (corner radius about 2dp). The Start
> button uses `tokens.primary` with `tokens.onPrimary` text. Match the design's Home, Profiles and Through the night
> screens in both Midnight Ink and Overcast. Tests: tile colour comes from `stepColor`; subtitle text is complete.

## P4 — Editor, step editor and fan shape UI (R1)

> The model is done (`Middle.quietFan`, `FanShape`, `shiftFan`). Build the UI:
> 1. In the profile editor's Middle section add a "Fan" chooser with three options — Steady, Ease down, Quiet middle —
>    separate from the temperature shape. Steady: one slider (sets `fanStart == fanEnd`). Ease down: start and end
>    sliders. Quiet middle: settle speed + minutes, asleep speed, rise speed + minutes; save `quietFan` and also set
>    `fanStart = settle`, `fanEnd = rise`. Switching away from Quiet middle sets `quietFan = null`.
> 2. Steps can be 1 min to 12 h. Turbo is fixed at 10 min and 43° (show it, don't offer a slider).
> 3. Show EXTENDED_HEAT as "Long heat" everywhere in the UI (strings only; leave `Mode` names alone).
> 4. Night curve text (`NightCurve.kt` ~190–237), the profile list summary (`ProfilesScreen.kt` ~221) and the History
>    check-in proposal (`HistoryScreen.kt` ~148) must read `fanShape` and describe a Quiet middle as
>    settle → asleep → rise.
> Tests: editor state ↔ `Middle` mapping for all three shapes; curve description for Quiet middle; check-in text.

## P5 — Cooldown

> New profiles get an after-wake Cooldown by default: Cool, fan 30%, 10 minutes, then off. Existing profiles without an
> after-wake are offered it **once**: a dismissible card on Profiles ("Add a 10-minute Cooldown after waking?") with Add
> and No thanks. Remember the answer in `Settings` (new field with a default so old settings load). Nothing changes on a
> profile until the person taps Add. Tests: new-profile default; offer shown once; declining persists; backup round trip.

## P6 — Guided setup restyle and Help and about

> Restyle `SetupFlow.kt` and its checklist card to the new tokens and tiles (no change to the steps or their logic).
> In Help and about add a "Source code on GitHub" row opening https://github.com/Willzy12h/bedjet-android in the browser.
> Tests: the link row exists and carries the URL.

## P7 — Widgets v2

> Update `widget/Widgets.kt` (Glance 1.1.1) to the chosen palette's tokens and side-stripe rows, matching the design's
> widget section. Glance can't use `Modifier.sideStripe`; use a 4dp coloured `Box` beside the row content over a
> tinted background. Keep every widget action and its confirmation flow exactly as it is.
> Tests: widget colours come from the palette for dark and light.

---

## Then

Claude reviews each packet branch, fixes what's needed, and does the final passes. Final sign-off and on-device testing
are the person's, not any agent's.

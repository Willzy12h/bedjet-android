# Nocturne redesign — code map

> **Remediation ownership/status:** The owner authorised whole-app remediation on `gpt/nocturne-redesign-remediation`. Earlier Claude-only file reservations are superseded; Claude performs the independent review afterwards. Approved design decisions in this document remain binding. Historical test counts and warning budgets are not the current gate; see [GPT-HANDOVER.md](GPT-HANDOVER.md), [FINAL-REVIEW.md](FINAL-REVIEW.md) and [REMEDIATION-REGISTER.md](REMEDIATION-REGISTER.md).

Approved design: https://claude.ai/artifact/EXWxySVCprLys7T3YHiiZj ("Round 4: Overcast + side stripe").
Full build brief for GPT: [BUILD-PACKETS.md](BUILD-PACKETS.md).

This maps each part of the design to the code that has it today, what changes, and how. Paths are relative to
`app/src/main/kotlin/io/github/willzy12h/nocturne/` (app) or `core/src/main/kotlin/io/github/willzy12h/nocturne/core/` (core).

## Already on `claude/nocturne-redesign-foundation` (Claude)

| Area | Files | State |
|---|---|---|
| Fan shape model (R1) | core `plan/Profile.kt`, `plan/PlanCompiler.kt` | `QuietFan` on `Middle`, derived `FanShape` (STEADY / EASE / QUIET_MIDDLE), `Middle.fanSpeeds`, `Middle.shiftFan()`. The compiler cuts the Middle at the union of temperature-step and fan-segment boundaries; the rise keeps its minutes first on a short night; a replan after the Middle began does not settle again. Tests: `FanShapeTest`. |
| Fan shape validation | core `store/BackupValidator.kt` | Quiet speeds checked as fans; settle/rise minutes 0–720. Test: `BackupValidatorTest.quietMiddle`. |
| Fan check-in | app `data/Repository.kt` | Too hot / too cold moves every fan speed, Quiet ones included; refused if any lands outside the limits. Test: `RepositoryTest.aQuietMiddleFanSuggestionMovesEverySpeed`. |
| Palette choice | core `store/Settings.kt` | `Palette` enum (7), `Settings.nightPalette` (Midnight Ink) and `dayPalette` (Overcast), `Settings.palette(at, systemDark)`. Test: `PaletteChoiceTest`. |
| Ember heat scale | core `plan/HeatScale.kt` | Level 1–4 from temperature (30°, 34°, 38° bounds); Turbo is always 4. Test: `HeatScaleTest`. |
| Palette tokens + side stripe | app `ui/Palettes.kt` | `PaletteTokens` for all 7 palettes copied from the design CSS, `stepColor(mode, temp)`, `tint()`, `Modifier.sideStripe()`. **Not wired in yet** (P2). Test: `PaletteTokensTest` (incl. contrast floor). |

Back-compat: every new field has a default, so older saved settings, profiles and backups load unchanged.
Open question for Claude's review: `BackupCodec` has a strict parse (`ignoreUnknownKeys = false`), so an *older* app
importing a backup made by the new one may refuse `quietFan` / palette keys. Decide in review whether to bump `SCHEMA`
so the older app says "from a newer version" rather than "damaged". Do not change it in a packet.

## What GPT builds

| Screen / feature | Today | Change | How |
|---|---|---|---|
| Theme | `ui/Theme.kt` (Midnight Ink + Daylight fixed palettes, `NocturneColors`, `NocturneTheme`) | Seven palettes; night/day choice | P2: build `NocturneColors` and the Material `ColorScheme` from `settings.palette(...).tokens`; keep `NocturneColors.mode()` but route heat through `stepColor`. Retire `DaylightColors`/`SimpleDaylightColors` only after every caller moves. |
| Settings › Appearance | `ui/SettingsScreen.kt`, `ui/ThemeConsent.kt` | Night palette + day palette pickers (swatch dots), defaults Midnight Ink / Overcast | P2 |
| Tiles everywhere | `ui/DeviceControls.kt`, `ui/QuickDefaultsSection.kt`, `ui/ProfilesScreen.kt`, `ui/TonightSections.kt` | Side-stripe tiles: 4dp stripe in mode/Ember colour, flat soft tint, no gradients; icon beside name, subtitle full width underneath | P3: one shared `StripeTile` composable using `Modifier.sideStripe` |
| Home / Tonight | `ui/TonightScreen.kt`, `TonightSections.kt`, `TonightWakeHeadline.kt`, `NightCurve.kt`, `StatusHeader.kt` | Squarer bar ends; Ember by temperature; Start button = `tokens.primary` / `onPrimary` | P3 |
| Night curve text | `ui/NightCurve.kt` lines ~190–237 ("easing off" / "building") | Describe Quiet middle as settle / asleep / rise | P4 |
| Profile editor + step editor | `ui/ProfileEditor.kt` (FanSlider ~214), `EditorPhaseSelection.kt`, `CustomRunDuration.kt` | Steps 1 min–12 h; Turbo fixed 10 min at 43°; "Long heat" label for EXTENDED_HEAT; fan shape chooser (Steady / Ease down / Quiet middle) independent of temperature shape | P4. Rename via `res/values` strings; `Mode.displayName` in core stays for logs unless review says otherwise. |
| Profiles list | `ui/ProfilesScreen.kt` (~221 shows fan) | Profile cards as side-stripe; fan summary reads `fanShape` | P3 / P4 |
| History check-ins | `ui/HistoryScreen.kt` ~148 | Proposal text for Quiet middle ("all fan speeds +10%") | P4 |
| Presets | core `plan/Presets.kt` (5 presets) | Optionally give one cooling preset a Quiet middle; leave others | P4 |
| Cooldown | core `plan/Presets.kt`, `data/Repository.kt` | After-wake default for **new** profiles: Cool, fan 30%, 10 min, then off. Offer once to existing profiles (a dismissible card; remember the answer in `Settings`) | P5 |
| Guided setup | `ui/SetupFlow.kt`, `SetupChecklistCard.kt`, `SetupChecks.kt` | Restyle to new tokens/tiles; no flow change | P6 |
| Widgets | `widget/Widgets.kt` (Glance 1.1.1) | Widgets v2: palette tokens, side-stripe rows | P7 |
| Help and about | `ui/SettingsScreen.kt` | Public GitHub link: https://github.com/Willzy12h/bedjet-android | P6 |

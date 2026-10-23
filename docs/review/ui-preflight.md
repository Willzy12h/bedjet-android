# Task 5 UI implementation preflight

Read-only source/baseline inspection, 22 October 2026. No implementation, test execution, repository edits, or review of in-progress Task 4. References are against the currently integrated UI and may move after integration. This does not replace Task 5's independent review. All phone, Samsung, Bluetooth, physical touch-target and TalkBack checks remain pending.

## Evidence and design to preserve

Read global constraints, Task 5/6 and lead rulings in `docs/superpowers/plans/2026-10-22-nocturne-hardening.md`. Visually inspected actual baseline PNGs: `dark-01-tonight`, `dark-01b-tonight-lower`, `dark-05-settings`, `dark-02-profiles`, `simple-light-01-tonight`, `light-05-settings`. No editor PNG exists in baseline. These renders are 411×891 dp Robolectric API34, halved for storage, with an unpinned early-morning clock; they are not hardware evidence or reliable future timing fixtures.

All source references below use `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/` unless otherwise stated.

- `Theme.kt:45–117`: preserve fixed Midnight Ink and Daylight palettes, system typography, mode colours. Ink ground #0E1220, surface #171C2E, raised #1F2640, text #E8ECF7, muted #A3ACC6; Day ground #F4F6FA, white cards, text #141A2A, muted #4F5A73. Preserve blue cool, orange heat, red Turbo, violet Daylight wake accent.
- `MainActivity.kt:72–108`: five existing tabs only. `TonightScreen.kt:150–193`: dark large wake headline, light paired stat tiles, Simple timeline in the same plan card. Preserve that intentional distinction.
- `TonightScreen.kt:75–77,195–244`: 16 dp page/card spacing; main action full width with 16 dp corners; Quick actions use matching rounded outlined cards and mode dots. Existing components can grow or wrap locally for accessibility without changing their identity.
- `SettingsScreen.kt:223–226`: divider/title/8 dp-spaced Group; `ProfileEditor.kt:251–269`: shared SwitchRow and TimeRow; `NightCurve.kt:164–207`: reusable Simple timeline. Prefer these to parallel visual systems.

## Natural insertion map

| Required behaviour | Existing insertion point and implementation note |
|---|---|
| Tonight-only wake override, expiry and clear | `TonightScreen.kt:117–143`, near the wake/source text and existing profile chips. Reuse `TimeRow`/native picker (`ProfileEditor.kt:262–269`) in a disclosed section/dialog. Both headline and plan must consume the same current NightPreview; preserve Simple view. Do not label an override as the profile default/alarm. |
| Temporary profile choice/reset | `TonightScreen.kt:124–133`, directly under chips: “Tonight only · Follow schedule”, with reset. `ProfilesScreen.kt:48,58` provides badge and Use tonight. Existing `chosen` remembered at `TonightScreen.kt:117` ignores time-only expiry; use current-time resolution rather than retaining the expired choice. Existing 30 s tick at lines69–70 can drive reevaluation, with a focused noon-expiry test. |
| Confirmed/pending/failed/unknown and queued/running | `TonightScreen.kt:80–82,94–115`: retain connection chip but separately expose programme confidence, requested versus confirmed wake, command label and queued count. Bluetooth connected is not programme confirmation. Use frozen Task4 typed flows, not English string parsing. Existing running title at97–99 comes from live profiles; use active snapshot. |
| Resume / stop tracking | Near running End night/Re-check alarm (`TonightScreen.kt:113–115`); resume offer in the idle branch after stop/replace while coordinator offer is eligible. Secondary overflow/disclosure for “Stop tracking”, with exact explanatory copy “This doesn't turn the BedJet off”. Keep End night prominent. |
| Setup / missing target / corrupt data | Existing `SetupChecks.kt:44–68` card and Settings Phone setup (`SettingsScreen.kt:136`). Reuse lifecycle return refresh (`SetupChecks.kt:47–49`). One-time flow adds explicit discover/select, separate demo choice and supervised nudge/restore. `ConnectionLine` (`TonightScreen.kt:247–269`, shared with Remote) is a natural “Choose your BedJet” entry when target absent. Place unreadable-data Restore/Dismiss banner near screen top and link restoration to existing Settings snapshots. Route mutations through Task4 coordinator. |
| Morning check-in | A compact offer on Tonight after a completed night; corresponding completed History card (`HistoryScreen.kt:37–59`) retains record linkage and accepted result. Existing cards/dialogs suit three choices plus separately accepted validated suggestion. No silent reusable-profile edits or running-night application. |
| Scheduled theme | `SettingsScreen.kt:63–73` Appearance: Scheduled chip plus disclosed dark-from/light-from TimeRows and equal-times explanation. Extend shared pure resolver rather than independent switches; `MainActivity.kt:48–57` bars and `Theme.kt:122–132` content must resolve identically and recompose at boundaries/zone change. |
| Keep profile on slot / restore | `ProfilesScreen.kt:46–62`: add profile overflow for “Keep on BedJet slot…” with three-slot preview/explanation and restore. Do not append more buttons to its already tight action Row. Existing Settings slot backup rows (`SettingsScreen.kt:80–92`) can expose originals, including truthful empty-marker handling, through coordinator APIs. |
| Diagnostics / quiet status | Diagnostics export in a disclosed Settings section beside backups/log (`SettingsScreen.kt:138,180`); existing CreateDocument pattern at57. Quiet-status toggle alongside notification controls (`97–120`), using shared SwitchRow. Preserve privacy constraints; no secrets in export. |
| Saved for next night / draft persistence | `ProfileEditor.kt:60–88` already has next-night versus apply-tonight dialog. Persist serialised draft and editor/tab navigation in SavedStateHandle; currently editor state and draft are plain remember (`MainActivity.kt:83`, editor60). Preserve explicit apply choice and show brief truthful next-night feedback. |
| History revisions/events | Extend existing expanded History card (`HistoryScreen.kt:45–56`); current curve `planOf:64–69` uses original steps only. Display applied revision/event meaning, without implying unknown changes were confirmed. |

### Task 6 composable placement contract

Task6 owns implementations in `ui/features/` and `strings_features.xml`; Task5 places them and must not duplicate platform logic or alter those owned fields/strings.

- Bedtime reminders and best-effort charging auto-start: expandable Settings sections adjacent to Alarms/Phone setup (`SettingsScreen.kt:122–136`). Retain opt-in, default-off auto-start, “supervised acceptance pending”, and honest best-effort charging wording. No new tab or all-options expansion above Tonight's main action.
- Health Connect settings: optional Settings disclosure; History overlay inside existing night card near its curve (`HistoryScreen.kt:44`). Keep “From Health Connect” label and absent/revoked/missing data states. Does not affect controls.
- CSV: History header (`HistoryScreen.kt:35`) export action/dialog with explicit Include device identifiers toggle; system picker supplied by Task6.
- Phone hand-over: existing screen overflow introduced locally for release and Take back, with optional interop controls in Settings BedJet (`75–95`). Retain truthful released status on Tonight; Task6 owns feature composable/coordinator wiring and platform limitation copy.
- Widget/tile/shortcut outcomes: consume the same typed app outcome flow once through the app shell (`MainActivity.kt:84–85`); no duplicate message producers. Widget/tile implementation remains Task6, and widget theme must use shared resolver.

## Concrete accessibility / small-screen risks

These are static findings and baseline observations, not claims of a tested 320 dp/200% layout.

1. **Snackbar coverage:** shell `MainActivity.kt:91–101` applies Scaffold padding but no snackbar-height inset; Tonight scroll column ends immediately after Quick (`TonightScreen.kt:146`). Baseline lower PNG shows Quick flush against bottom navigation. Reserve measured snackbar-height scroll space while visible (plus existing navigation inset), and explicitly use short duration. Existing capture waits6000ms to hide the bar, so does not test this regression. Verify Off/Boost remain scrollable and clickable *with* the message displayed.
2. **Quick buttons:** four equal columns (`TonightScreen.kt:211–232`), fixed72 dp height and maxLines1. At320 dp, page32 dp and gaps24 dp leave66 dp per button, about58 dp after content padding; scaled Boost and duration can clip. Use local responsive wrapping/two-column fallback and minimum rather than fixed height. Preserve four controls and colours.
3. **Primary action:** `.height(56.dp)` (`TonightScreen.kt:201`) risks clipping scaled Start/long resumed labels. Use heightIn minimum56 dp and allow wrapped text. Light two-stat row (`163–175`) has narrow equal tiles; at320 each has about106 dp content width after padding. Adapt locally to stacking when required.
4. **Simple time column:** `NightCurve.kt:198` fixes time at52 dp. 200% labels may wrap mid-time; allocate content-driven/scaled width or a compact stacked arrangement. Curve time labels also use thirds (`72–76`), so test long/scaled wake labels.
5. **Rows that need wrapping:** Appearance (`SettingsScreen.kt:65`) cannot safely fit a fourth Scheduled chip at320/200%; use existing FlowRow idiom. Slot chips82, snapshot input/action140–143, export buttons144–146, snapshot Restore/Delete149–155 and Profiles four-action Row57–62 are other narrow-layout hotspots. Editor Save header (`ProfileEditor.kt:94–98`) and TimeRow264–268 need actual scaled render checks.
6. **Navigation:** five labels at `MainActivity.kt:96` have only64 dp each at320. Test 200% directly; retain tabs/icons and accessible labels, adapt local label presentation if necessary. Do not shrink the user's font setting.
7. **Switch semantics:** shared SwitchRow (`ProfileEditor.kt:251–258`) has adjacent text and Switch with separate callback, no row toggleable/role/merged label. Add single row-level toggleable semantics with switch child callback null to avoid double actions; preserve state, full target and accessible detail.
8. **Sliders:** editor `145–155,166–174,208–209,241–247`, Settings `119,242–247`, Remote `66–80` rely on adjacent visible text. Explicitly connect unique label, units and state description to each slider/range endpoint. Celsius protocol values must not be read as degrees without conversion. Native progress semantics alone do not supply field meaning.
9. **Curve alternative:** `NightCurve.kt:53–71` Canvas has no textual programme summary; time labels/legend alone omit blocks/strength/duration. Reuse `timelineEntries` (`144–156`) for a localised text alternative including block/time information, maintaining chronological order and distinguishing applied versus preview plan. Simple rows already merge descendants (`197`).
10. **Targets and heading structure:** Material controls normally provide minimum touch machinery; visual 32 dp chips are not proof of small effective targets. Assert semantic bounds/targets >=48 dp and non-overlap under font scaling. Existing Tonight and Quick headings have heading semantics; Settings/History/Profile titles can use matching semantics. Repeated “Fix” and “Remove” actions should expose contextual labels.

## Contrast calculations from existing tokens

Calculated standard sRGB relative luminance ratio `(Lmax+.05)/(Lmin+.05)`, not screenshot pixel sampling. Normal-text 4.5:1 and essential non-text 3:1 are assessment thresholds; faint decorative dividers alone need not meet3:1.

| Pair / use | Ratio | Interpretation |
|---|---:|---|
| Ink muted #A3ACC6 / Ink surface #171C2E | 7.47 | Healthy normal text contrast. |
| Ink muted / highest card #262E4C | 5.88 | Healthy normal text contrast. |
| Ink primary #6EA8FE / highest card #262E4C | 5.51 | Existing profile-card action text is healthy. |
| Day muted #4F5A73 / white | 6.90 | Healthy normal text contrast. |
| Day wake accent #5B4BC4 / white | 6.46 | Healthy normal text contrast. |
| Ink outline #6B7493 / highest card #262E4C | 2.88 | Audit any essential control boundary actually drawn on this surface; token's stated3:1 aim does not hold on every container. No blanket palette change warranted. |
| Day outline #7A849B / ground #F4F6FA | 3.47 | Healthy boundary contrast on ground. |
| Ink outlineVariant / Ink surface | 1.56 | Decorative card borders; don't use alone for required control state. |
| Day outlineVariant / white | 1.31 | Same qualification. |
| 55%-alpha Day cool curve bar / white | 2.52 | Existing after-wake transparency (`NightCurve.kt:62`) can fall below3:1 for meaningful chart marks. |
| 55%-alpha Day heat curve bar / white | 2.20 | Same; inspect local stronger alpha/outline plus textual meaning while preserving palette. |
| 55%-alpha Ink cool / Ink surface | 3.06 | Barely above3:1 on this particular surface; profile cards have different backgrounds. |

## Harness and verification changes for Task5

`app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt:34–74` currently hardcodes411×891/xxhdpi; covers four looks and existing tabs/top/lower/running but no editor, dialogs, snackbars, narrow or large-font cases. It starts with real time and unspecified prerequisite state; setup warning dominates observed top PNGs and early-morning timing gives misleading overnight previews.

- Inject a fixed suitable evening `Graph.clock` **before** UI/model composition/preview initialisation; merely assigning after activity creation can retain remembered initial time. Match coordinator's shared clock contract; reset fixture between tests.
- Make demo/target/setup-complete, alarm source, permission/exact-alarm/battery shadows and histories explicit. Keep separate setup-required fixture rather than silently erasing setup coverage.
- Keep all four appearance flows and top/lower/running comparisons. Add editor dirty draft/save dialog, setup selection/recheck, override and temporary-choice reset, resume, confidence states, completed-night check-in, scheduled appearance section and expanded History revision. Feature6 sections can be fixture-driven when integrated.
- Add320 dp and200% font, including combined case. Set configuration before compose, and verify actual density/fontScale plus semantic bounds. Capture primary actions after scrolling, not only screen tops. Test snackbar-present lower Tonight separately; don't hide it with existing6000ms settle (`73`).
- Replace fragile text index selectors (`58,62,66,68`) with labelled/test-tagged navigation/action nodes where duplicate labels arise. Keep real rendering and looper advancement (`77–88`), await typed states/visible UI rather than arbitrary sleeps.
- Add meaningful semantics assertions for slider names/units/state, one toggle action per SwitchRow, curve summary and primary-action visibility. Add saved-state recreation (including process-style restoration), noon expiry and scheduled-theme boundaries/zone changes. `AppViewModel.kt:57–59` stateIn currently uses graph.scope; switch to viewModelScope as required and ensure one outcome collector/deduplication path.
- Root will run `SCREENSHOT_TAG=after` after integration. Label output as Robolectric renders; inspect PNGs and semantic bounds before declaring no clipping. Physical TalkBack traversal, Samsung accessibility font behaviour, actual touch targets, vibration/system haptic setting, permissions return, setup nudge/restore, and interop remain supervised phone acceptance work.

# Reconciliation: approved design vs build brief and code

This compares the approved design (`source/nocturne-final-design.published.html`, described in `DESIGN-REFERENCE.md`)
with these files in `Willzy12h/bedjet-android` on branch `claude/nocturne-redesign-foundation` at commit 42e6ba2:

- `docs/redesign/BUILD-PACKETS.md` (the build brief, packets P2–P8 and Final)
- `docs/redesign/CODE-MAP.md`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Palettes.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/plan/HeatScale.kt`, `core/.../store/Settings.kt` and
  `app/.../ui/Theme.kt`, where a finding depends on them

Line numbers are for that commit. Nothing in the repository was changed. Where this document recommends a fix, it is
a recommendation for whoever owns the file. Foundation files (`Palettes.kt`, `HeatScale.kt`, `Settings.kt`) belong to
Claude's branch, and the brief is Claude's document.

## How to read the status column

| Status | Meaning |
|---|---|
| **Approved** | The design states it, either drawn or in the "Locked decisions" note. Build it as drawn. |
| **Brief decision** | The brief deliberately goes further than the design, or departs from it, for a stated reason. Build the brief. |
| **Assumption** | Neither the design nor the brief settles it. Someone filled the gap, and the value needs sign-off. |
| **Conflict** | The design and the brief or code disagree, and one of them has to change. |

## Summary

| # | Topic | Where | Status | Recommended resolution |
|---|---|---|---|---|
| R1 | Ember level bounds | HeatScale.kt L10–11 | Conflict | Change the bounds to 31° / 36° / 41° |
| R2 | Side-stripe tint strength and layering | Palettes.kt L54–55, L94–98; brief P3.1 | Conflict | Use separate tint alphas per element, drawn over the surface |
| R3 | Subtitle overflow | Brief P3.1 L76, P3.6 L86 | Brief decision | Keep wrapping; it is a deliberate departure from the design |
| R4 | Night bar corner radius | Brief P3.4 L84 | Conflict | Use 10dp outer / 3dp inner |
| R5 | "No gradients anywhere" | Brief P3.1 L77, Final L155 | Conflict | Allow the ramp gradient in the night bar's Middle only |
| R6 | Palette pickers | Brief P2.2 L65–68; Settings.kt L23 | Conflict | Add a name label; use ring-only selection; decide the row split |
| R7 | Menu and sheet colours | Palettes.kt L64, L70–72 | Assumption | Use #232740 for menus; sign off the invented sheets |
| R8 | `bad` colour | Palettes.kt L64 | Conflict | #FF9B91, plus an End night text colour |
| R9 | Overcast hairline | Palettes.kt L78 | Conflict | Split into a hairline token and an edge token |
| R10 | Missing tokens (boost, ok) | Palettes.kt; Theme.kt L95, L100 | Missing | Add them, or rule them out |
| R11 | Fan shape and temperature shape UI | Brief P4.1 L93–101 | Conflict | Decide between sliders and the drawn chart editor |
| R12 | Rows with no matching app feature | Design Settings and Home | Missing | Treat as design-only; confirm before building |
| R13 | GitHub row wording | Brief P6.2 L134 | Conflict | Use the design wording |
| R14 | Widget layout | Brief P7 L143–146 | Conflict | Use the design's 4-tile grid |
| R15 | Turbo step fan slider | Brief P4.2 L103 | Conflict | Follow the brief (no slider); the design's slider is decoration |
| R16 | "Long" vs "Long heat" | Brief P4.3 L104 | Brief decision | Use "Long heat" everywhere, including the segment |
| R17 | Cooldown phase and offer card | Brief P5 L117–127 | Approved / Assumption | Step and once-only offer approved; 22° and card wording need sign-off |
| R18 | Theme mode order | Brief P2.1 L62; Settings.kt L21 | Conflict | Show System / Light / Dark / Scheduled |
| R19 | History and morning check-in | Brief P4.4 L109 | Unspecified | The brief owns it; there is no drawn screen |
| R20 | Long heat auto-rejoin claim | Design Profile editor caption | Check | Verify against the code before shipping the wording |
| R21 | Hard-coded colours that break on Overcast | Design (see DESIGN-REFERENCE §11) | Approved, with a known defect | Map each one to a token |
| R22 | Things that already match | — | Approved | No action |

---

## R1. Ember level bounds (Conflict)

- **Design.** The Ember scale strip labels Warm 26–30, Hot 31–35, Hotter 36–40 and Turbo 41–43. Drawn examples:
  - 30° → h1
  - 32° and 33° → h2
  - 36°, 37° and 38° ("Long heat 38°") → h3
  - Turbo → h4
  - The Running screen's "Heat 30.5°" → h2

  (The JS `heatCol` interpolation points are 26/32/37/42. They drive only the strip's gradient, not the step colours.)
- **Code.** `HeatScale.kt` L10–11 has `BOUNDS = listOf(60, 68, 76)` half-degrees, which is 30°, 34° and 38°. The
  results differ from the design in two places:
  - 30° becomes level 2, where the design uses 1;
  - 38° becomes level 4, where the design uses 3.

  33° gives level 2 and 36–37° give level 3, both as drawn.
- **Resolution.** Set the bounds to the design's labels: 61 (30.5°, so the drawn 30.5° stays h2), 72 (36°) and
  82 (41°). Turbo stays 4. Update the KDoc and the HeatScale tests.
- **Assumption to sign off:** whether 30.5° is Warm or Hot. The scale labels skip from 30 to 31, but the drawn
  Running screen colours 30.5° as Hot, so this document recommends that 30.5° is Hot.

## R2. Side-stripe tint strength and layering (Conflict)

- **Design.**
  - Every tile draws the glass surface first, then a flat layer of the stripe colour `--c` on top, at:
    - 10% for a tile;
    - 24% for a selected or "on" tile;
    - 9% for a step row;
    - 8% for a profile card.
  - A selected tile also gets a 1.5dp border: in `--c`, or in the accent colour for a profile card.
  - On Overcast every tile also gets a 1dp `edge` border (#AAB0BA). See DESIGN-REFERENCE §6.
- **Code.** `Palettes.kt` L54–55 has one `tint()` at alpha 0.14 (dark) or 0.16 (light), and `Modifier.sideStripe`
  (L94–98) draws only that tint, with no surface under it.
- **Brief.** P3.1 L74–77 says "flat soft tint" without giving values.
- **Resolution.**
  - Replace `tint(stripe)` with `tint(stripe, level)`, where level is Tile 0.10, On 0.24, Step 0.09 or Profile 0.08.
  - Draw `surface` first, then the tint.
  - Add the selected border, and the Overcast edge (see R9).
  - The alphas are the same in every palette; the design does not vary them for Overcast.

## R3. Subtitle overflow (Brief decision)

- **Design.** Tile subtitles are one line with an ellipsis ("…").
- **Brief.** P3.1 L76 and P3.6 L86 say the subtitle wraps to a second line and must not be ellipsised, and P3.6
  tests for it.
- **Resolution.** Keep the brief. Truncated subtitles hide schedule detail and fail at large font sizes. Tiles grow
  in height to fit. This departs from the design on purpose, and reviewers should not log it as a defect.

## R4. Night bar corner radius (Conflict)

- **Design.**
  - Main night bar: outer corners 10dp, inner corners 3dp between segments, with a 2dp gap.
  - Thin bar (Home tile): 4dp outer, 2dp inner.
  - The Locked decisions note says the outer ends match the Start button's corners. The Start button's radius is
    14dp; the drawn bar uses 10 because it is shorter.
- **Brief.** P3.4 L84 says "squarer bar ends (corner radius about 2dp)".
- **Resolution.** Build it as drawn: 10/3dp, and 4/2dp for the thin bar. Change P3.4 to match.

## R5. "No gradients anywhere" (Conflict)

- **Design.** The night bar's Middle segment uses a ramp gradient from the start step's colour to the end step's
  colour, wherever the temperature or fan changes during the night. The Locked decisions note scopes "no gradients"
  to tiles. The editor's temperature chart uses discrete steps, not gradients.
- **Brief.** P3.1 L77 says "No gradients anywhere", and the Final check (L155) asks for a search for leftover gradient
  brushes.
- **Resolution.**
  - Change P3.1 to "No gradients on tiles or surfaces".
  - Allow exactly one gradient: the night bar Middle ramp, in `NightCurve.kt`.
  - Make the Final search exclude that one call site.

## R6. Palette pickers (Conflict)

- **Design (Settings › Appearance).**
  - The "Night palette" row shows six dots: the dark palettes.
  - The "Day palette" row shows only Overcast.
  - A text label names the current palette.
  - The selected dot has an accent ring and no tick.
- **Brief.** P2.2 L65–68 asks for seven dots in each row, a ring plus a tick, and seven content descriptions.
- **Code.** `Settings.kt` L23 lets any palette fill either slot.
- **Resolution.**
  - Add the palette name label.
  - Use the ring for selection. A tick is acceptable as an accessibility addition, but it is a brief decision.
  - Content descriptions stay, as in the brief.
- **Open decision:** six dark palettes plus one light (as drawn), or seven in each row (as in the brief and code).
  This document recommends the drawn split. It is simpler, and a dark palette in the Day slot defeats the point of
  the slot. If both rows offer all seven anyway, mark that as a brief decision.

## R7. Menu and sheet colours (Assumption)

- **Design.**
  - Menus use the fallback `--menubg` #232740 in every dark palette.
  - Forest, Graphite and Plum do not set `--sheetbg`, so they inherit Midnight Ink's #171A2C.
  - Overcast sets its own.
- **Code.**
  - `Palettes.kt` L64 sets `menu = sheet` for every dark palette.
  - L70–72 give Forest #13211B, Graphite #141414 and Plum #1F1428 as sheets. These are raised grounds the code
    invented; the comment at L68 says so.
- **Resolution.**
  - Menu: use #232740 for the dark palettes. Overcast's menu (#EEF0F3) matches the design.
  - Sheets: the invented colours are reasonable, because #171A2C reads blue on Forest and Plum. They are not in the
    design, though, so label them as assumptions and get sign-off.

## R8. `bad` colour (Conflict)

- **Design.**
  - Destructive and error colour #FF9B91, used for Delete step and error text.
  - The End night button text is #FFB3A8.
- **Code.** `Palettes.kt` L64 has `bad = 0xFFFF6B6B`.
- **Resolution.** In the dark palettes, set `bad = #FF9B91`. Add `badSoft = #FFB3A8`, or use `bad` for End night as
  well; a single colour is acceptable. Overcast's #C62F2A (L80) is not in the design, so treat it as an assumption.
  Both design colours are too pale on Overcast (see R21).

## R9. Overcast hairline (Conflict)

- **Design.** Overcast uses two different tokens:
  - `--hair` rgba(20,23,29,.12), which is `0x1F14171D`, for dividers;
  - `--edge` #AAB0BA, the 1dp tile border.
- **Code.** `Palettes.kt` L78 sets `hairline = 0xFFAAB0BA`, merging the two. As a result, dividers come out too heavy.
- **Resolution.**
  - Set `hairline = 0x1F14171D`.
  - Add an `edge: Color?` token: #AAB0BA on Overcast, and `null` (no border) on the dark palettes.

## R10. Missing tokens (Missing)

- **Design.**
  - `--boost` #FF6FA0: the Turbo/boost badge.
  - `--ok` #3DDC84: the connected and success colour.
- **Code.**
  - `Palettes.kt` has neither.
  - `Theme.kt` has `connected` #5ED39A (dark, L95) and #1E8A55 (light, L100).
- **Resolution.**
  - Add `boost`.
  - For `ok`, keep the Theme.kt greens unless someone signs off the change. The design's #3DDC84 has not been
    contrast-checked on Overcast, and #1E8A55 has.

## R11. Fan shape and temperature shape UI (Conflict)

- **Design (Profile editor).**
  - Fan: a segmented control (Steady / Ease down / Quiet middle) above a fan chart. Its dots and edges can be dragged
    to set speeds and minutes.
  - Temperature: unchanged in behaviour ("works as before", the existing R1 shapes Stay / Once / Twice / Gently):
    equal blocks from From to To, set with − and + or by dragging the first or last block.
  - Example drawn and captioned: "40% while you settle, 20% while you sleep, then 45% for the hour before the
    wake-up starts".
- **Brief.** P4.1 L93–101 uses sliders. Its suggested defaults are settle 50% / 30 min, asleep 25% and rise
  45% / 40 min.
- **Resolution.**
  - Build the segmented control as drawn.
  - For the editing surface: dragging on the chart is what the design shows. Sliders are an acceptable first
    implementation only if the user signs them off. Either way, the brief's defaults are assumptions, because the
    design's numbers are an illustration, not defaults.
  - The design says the temperature side "works as before", so restyle the existing temperature controls; do not
    redesign them.

## R12. Rows and actions with no matching app feature (Missing)

An exact-text search of the app's Kotlin and `strings.xml` found no match for these design labels:

- Health Connect
- Licences
- How Nocturne works
- Check for updates
- Widgets match the app
- Alarms from other apps
- Test sleep and wake
- Share profile
- Move up
- Auto-start
- Bedtime reminder
- End night
- Change wake
- Nearby devices
- Unrestricted battery
- "Stop your bedtime routine?"
- Long heat (the brief's P4.3 L104 adds this one)

Some of these may exist under other wording. Resolution:

- Map each one to an existing feature where there is one, and use the design's wording.
- Where there is no feature, do not build one. List the row as "design only, no feature", leave it out, and raise it
  with the user.

## R13. GitHub row wording (Conflict)

- **Brief.** P6.2 L134: "Source code on GitHub".
- **Design.** Title "Nocturne on GitHub", subtitle "Project page and downloads".
- **Resolution.** Use the design wording. Keep the brief's URL and its behaviour when the link can't be opened.

## R14. Widget layout (Conflict)

- **Design.**
  - The widget is a 4-column grid of tiles (Turbo, Heat, Cool, Off), each showing only its name.
  - Above the grid: "24°" and "Winter warm · 22:30".
  - Background: the palette ground at 84%, with blur.
  - A "Widgets match the app" toggle appears in Settings.
- **Brief.** P7 L143–146 asks for side-stripe rows.
- **Not in the design:** `SingleActionWidget`.
- **Resolution.**
  - Build `QuickControlsWidget` as the drawn grid. Glance can't blur, so use a solid fill at 84%.
  - `SingleActionWidget` is unspecified: use one tile, as drawn.
  - The toggle is not in the brief (see R12).

## R15. Turbo step fan slider (Conflict)

- **Design.** The Turbo step editor shows a fan slider at 100%.
- **Brief.** P4.2 L103 says Turbo is fixed at 10 minutes and 43°, shown as text with no sliders.
- **Resolution.** Follow the brief. The firmware fixes Turbo, so a slider would mislead.

## R16. "Long" vs "Long heat" (Brief decision)

- **Design.** The step editor's segmented control says "Long". Elsewhere the design says "Long heat".
- **Brief.** P4.3 L104 says "Long heat" everywhere.
- **Resolution.** Use "Long heat" everywhere. If the segment can't fit it, abbreviate it in the segment only, and give
  it the content description "Long heat".

## R17. Cooldown phase and offer card (Approved in part, Assumption in part)

- **Design (Profile editor caption, approved).** "Every new profile now has one step after it: Cool at fan 30% for
  10 minutes, then off. It is an ordinary step, so you can change it, drag it or delete it."
- **Design (Locked decisions, approved).** "New profiles get Cool, fan 30%, 10 minutes, then off. Existing profiles
  are offered it once."
- **Brief.** P5 L117–127 matches both, and adds details the design does not give:
  - the temperature, 44 half-degrees (22°);
  - the offer's form and wording: a card, "Add a 10-minute Cooldown after waking?", with Add and No thanks and one
    checkbox per profile.
- **Resolution.** Build the step and the once-only offer, both approved. The 22° temperature and the card's layout and
  wording are brief assumptions: build the card in the side-stripe tile style, and get sign-off on its wording and
  on 22°.

## R18. Theme mode order (Conflict)

- **Design.** System / Light / Dark / Scheduled.
- **Brief and code.** P2.1 L62 and the `ThemeMode` enum (Settings.kt L21) list System / Dark / Light / Scheduled.
- **Resolution.** Display the modes in the design's order, and keep the enum as it is.

## R19. History and morning check-in (Unspecified)

- **Design.** History is drawn as a list of nights. The caption calls it a suggestion. There is no morning check-in
  dialog and no detail screen for a night.
- **Brief.** P4.4 L109 changes the check-in proposal text.
- **Resolution.** Restyle the existing screens with the tokens and tiles. Do not invent a layout.

## R20. Long heat auto-rejoin claim (Check)

- **Design (caption of "Long heat, 5 hours").** "At 38° and fan 40% the BedJet stops Heat after 4 hours on its own.
  Nocturne starts it again straight away, so you get the full 5 hours." The Locked decisions note adds that where
  the BedJet has its own time limit, the app joins the steps back to back.
- **Resolution.** Check this against the BLE and runner code before showing the wording to users. If the behaviour
  differs, change the wording, not the behaviour.

## R21. Hard-coded colours that break on Overcast (Approved, with a known defect)

The design hard-codes several dark-only colours, and they look wrong in the Overcast renders. The defect is in the
design, and the Overcast render shows it as drawn:

- End night #FFB3A8
- the sheet handle
- the slider track
- Delete step #FF9B91
- the drag card #262A45
- the white now-marker

Resolution: map each one to a token (`bad`, `hairline`, `surface2`, `sheet`, `ink`), and do not copy the hex values.
DESIGN-REFERENCE §11 lists them.

## R22. Things that already match (Approved)

- Dark palettes: `primary = ink` (the Start button).
- Overcast: primary #2F93DC, accent #1F7CC2, menu #EEF0F3, sheet #DDE1E6, and ink #14171D / #444B57 / #6B7280.
- The glass, glass2 and hair alphas for the six dark palettes (Palettes.kt L68–75).
- Turbo is always level 4.
- `ThemeMode` scheduled logic is unchanged.

## Unspecified in the design (do not invent)

- the morning check-in dialog;
- the History detail screen for one night;
- confirmation dialogs other than "Stop your bedtime routine?";
- failed or unknown states for tiles (only the pending dot and the dimmed offline state are drawn);
- behaviour at large font sizes;
- landscape and tablet layouts;
- `SingleActionWidget`;
- empty states (no profiles, no history);
- the layout and wording of the Cooldown offer for existing profiles (offering it once is approved, see R17).

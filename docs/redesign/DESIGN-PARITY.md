# Design parity: make the app match the approved design

The P8 renders (Midnight Ink and Overcast) keep the old app's layout with new colours. The approved design
changes the layout of every main screen. This brief covers closing that gap. The design is the source of truth:
the images in `reference/screenshots/as-published/`, the HTML in `reference/nocturne-final-design.published.html`
and the per-screen specs in `reference/DESIGN-REFERENCE.md`. `CLAUDE-REVIEW.md` decisions still apply.

## Gaps seen in the renders

| Screen | Design (image) | App now |
|---|---|---|
| App bar | 4 tabs: Tonight, Profiles, History, Settings (`01-home-tonight/01`) | 5 tabs including Remote |
| Tonight, top | "BedJet 3 · connected" dot line, then a large 24° reading with "Air from the BedJet / Idle" | Demo banner, "Temperature unavailable" strip, small "Tonight 07:00" header and status lines |
| Tonight, card | One card: "TONIGHT · <PROFILE>" + Change, segmented strip, Bed / summary / Wake row, full-width "Start at 22:30" | Separate "The night" bar chart card, profile chips row, "Reorder sections" control |
| Tonight, quick presets | "QUICK PRESETS" + Edit, 3×2 stripe tiles with icon, name and detail (Turbo, Heat, Cool, Dry, Boost, Off) | Not on Tonight; remote functions sit in the Remote tab |
| Running (`01-home-tonight/02`) | As in the image | "Stop night" pill with bar chart and snackbar-style status |
| Profiles (`02-profiles/01`) | Title row with Import and "+ New"; cards with star, Tonight/Start pill, ⋯ menu, segmented strip, times and weekday dots | Cooldown offer card at the top, plain cards with timeline rows |
| Settings (`06-settings/01`) | Grouped cards, rows with an icon tile, title, live summary ("Connected · slot 3", "Scheduled · Midnight Ink / Overcast", "All 4 allowed" ✓) and chevron | Plain rows with static subtitles, no icons |
| History (`07-history/01`) | As in the image | Empty-state text and a disabled export link |

Codex should also compare the editor, through-the-night, guided-setup, Overcast and widget images in the same way.
Where an image and `DESIGN-REFERENCE.md` disagree, `RECONCILIATION.md` decides.

## Assumptions (made for the owner; flag any you can't keep)

1. **Remote tab is removed.** Its actions become the Tonight quick presets. Anything the presets can't express
   (a manual temperature or fan change during a night) moves into the running-state controls or a sheet opened
   from a preset tile. No BedJet capability is lost.
2. **"Reorder sections" is removed from Tonight.** If the setting must stay, it moves to Settings › Appearance.
3. **Demo mode** shows in the status-line position ("Demo · no BedJet connected" plus Exit), not as a separate
   banner and strip.
4. **The Cooldown offer** becomes a dismissible card below the profile list (or a one-time sheet), not the first
   thing on Profiles. The wording and behaviour stay as decided.
5. **The "The night" bar chart** is replaced by the design's segmented strip inside the Tonight card. The detailed
   timeline stays available behind Change or a tap on the strip.
6. **Settings summaries are live values.** Where a value doesn't exist yet, use the nearest true one. Never show
   made-up numbers.
7. Behaviour, data, BLE, alarms, backups and accessibility labels stay as they are. This is a layout and visual
   change only.

## Done when

- For every image in `as-published/`, the app's capture of the same state matches it in layout, hierarchy, text,
  icons and colour. Small font-rendering differences are fine.
- Captures are saved with `SCREENSHOT_TAG=parity` and paired against the design images in a short table in
  `GPT-HANDOVER.md`.
- The full gate passes with lint at no more than 24 warnings. Tests that asserted the old layout are updated, not
  deleted, and the behaviour they covered is still tested.

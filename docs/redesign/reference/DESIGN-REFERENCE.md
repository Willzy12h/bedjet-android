# Nocturne redesign: design reference

**Source:** https://claude.ai/artifact/EXWxySVCprLys7T3YHiiZj ("Nocturne Final Design", Round 4: Overcast + side stripe).
The artefact version exported here is `1793135496-96b1`.

**What this is:** a written description of the approved design, taken from the artefact's own HTML/CSS/JS in
`source/nocturne-final-design.published.html`. Every value below is quoted from that file. Where this document and
the HTML disagree, the HTML wins. "Unspecified" means the artefact does not define it; it does not mean "leave it
out".

**Units:**
- The design is drawn on a 300×640 CSS-px phone.
- Read CSS px as **dp** for sizes, radii and spacing, and as **sp** for text.
- Real phones are 360–412 dp wide. Keep the sizes and let widths stretch (see §9).

**Related files:** screenshot index and provenance in `screenshots/README.md`; differences from the build brief in
`RECONCILIATION.md`.

---

## 1. Structure, section order and navigation

### 1.1 Artefact page order

This is the order of the design, not of the app:

| # | Section (eyebrow) | Heading | Phones |
|---|---|---|---|
| 0 | Colour | Ember red, deeper as it gets hotter | Ember scale strip and two example night bars |
| 1 | Home · layout A | Tonight's card, and the three states | Before bed · Running · Sending · Offline, softer |
| 2 | Profiles | Profiles list and presets | Profiles list (⋯ menu open) · Edit presets (sheet) |
| 3 | Profile editor | P2, with the after-waking cooldown and the step editor | Editor · Drag and drop · Add a step (sheet) · Step editor (sheet) · Long heat, 5 hours (sheet) · Turbo (sheet) |
| 4 | Through the night | R1, with a fan shape | Easing off, twice · Hot, stepping down once · Cool, warming gently (all sheets) |
| 5 | First run and new profiles | Guided setup | 1 Phone settings · 2 Bluetooth off (sheet) · 3 Find the BedJet · 4 Getting into bed · 5 Through the night · 6 Waking up · 7 Name and save |
| 6 | Settings | Settings, with Help and about | Settings home · Appearance · Permissions · Profiles and backups · Notifications · Night and wake-up · Help and about |
| 7 | History tab | History, the one tab we hadn't drawn | Nights |
| 8 | By day · Overcast | The main screens in the day palette | JS clones of the first phone of sections 1, 2, 3, 4, 6 and 7, in Overcast |
| 9 | Palettes | Seven palettes: six for night, one for day | Home and a home-screen widget for each of the 7 palettes |
| 10 | Locked | Everything decided | 9 decision notes (§12) |

The header lists these approved decisions: Ember red · Home A · P2 editor · R1 shapes + fan shapes · Any step length ·
Guided setup · Settings list · Overcast by day · Side-stripe tiles · Cooldown offered once · Public GitHub link.

### 1.2 App navigation

**Bottom bar.** Four tabs, in this order, each with an icon above its label:

| Index | Label | Icon |
|---|---|---|
| 0 | Tonight | `moon` |
| 1 | Profiles | `list` |
| 2 | History | `hist` |
| 3 | Settings | `gear` |

**Where the bottom bar shows:**
- Shown on: Home, the Profiles list, History, Settings home, and every phone in the Palettes section.
- Not drawn on: the editor, Settings sub-pages, guided setup, or any sheet's backdrop.
- When a phone has both the bar and a sheet, the bar sits under the dimmer.

**Back and actions.**
- Sub-pages use a text link at the top left: "‹ Settings" or "‹ Profiles" (`.link`).
- A right-hand action sits in the same row, for example "Save" or "Recheck".
- In guided setup the back link is the word "Back" (no ‹), with "New profile · 1 of 4" or "2 of 4" on the right.

**Sheets.**
- A sheet rises from the bottom over a dimmer. The screen behind is shown at reduced opacity: .5, or .4 in setup.
- A sheet has a handle, a title on the left and a text action ("Done" or "Cancel") on the right.

**Where each screen opens from (as stated in the captions):**
- Home → "Change" (the profile for tonight).
- Home → "Edit" → the Quick presets sheet.
- A preset row's › → the step editor.
- Profiles → tap a card → the editor. Long-press reorders. ⋯ opens the menu.
- Editor → the "Through the night" row's › → the Through the night sheet.
- Adding a step → the Add a step sheet → "Next: set it up" → the step editor.
- History → tap a night → "shows its steps and notifications". **That detail screen is unspecified.**

---

## 2. Exact wording, screen by screen

Use the text exactly as written, including the "·", "→" and "…" characters. Times and numbers are sample data.

### 2.1 Home / Tonight

**Before bed** (21:48)
- Status line: "BedJet 3 · connected" with a green dot.
- Big reading: "24°". Beside it: "Air from the BedJet" (muted) and "Idle" (faint).
- Card header: "Tonight · Summer comfort" (label) and "Change" (link).
- Night bar ticks: "Bed 22:30" · "Cool 20° → 24°" · "Wake 06:45".
- Button: "Start at 22:30".
- Section header: "Quick presets" with "Edit" (pencil icon, 13px).
- Tiles (name / subtitle):
  - Turbo / "10 min"
  - Heat / "32° · 30 min"
  - Cool / "22° · 30 min"
  - Dry / "28° · 30 min"
  - Boost / "Turbo→Heat"
  - Off / (blank line kept, `&nbsp;`)

**Running** (02:40)
- Status line: "Winter warm · running" with a green dot.
- "31°". Beside it: "Heat 30.5°" (bold, `--h2`, 16px heat icon) and "Fan 30% · step 2 of 3".
- Night bar with a now-marker at 40%. Ticks: "22:30" · "Now 02:40" · "07:00".
- Step row: "Next: Heat 33° at 06:30" / "Wake-up from your Clock alarm".
- Two buttons, side by side: "Change wake" and "End night". "End night" uses text colour `#ffb3a8`.
- "Quick presets" / "Edit" with three tiles:
  - Turbo (no subtitle);
  - **Heat, selected (`.on`)** / "30.5°";
  - Cool (no subtitle).
- Caption: tapping a preset during a night first asks **"Stop your bedtime routine?"**. The dialog itself is not drawn.

**Sending** (21:50)
- Status line: "Sending…" with a pulsing amber dot. The caption says "Connecting…" uses the same treatment.
- "24°". Beside it: "Heat…" (bold, `--h2`) and "Waiting for the BedJet".
- The card has no Start button in this state.
- The Heat tile reads "Heat…" / "Sending" and has the pending dot.
- Caption: the tile "lights up only once the BedJet confirms."

**Offline, softer** (21:50)
- Status line: Bluetooth-off icon (14px, `--off`) and "Offline · last seen 21:12".
- Text: "No reading while the BedJet is out of reach" (muted, 13px).
- Compact card:
  - Bluetooth-off icon in a 34px rounded square with 16% cool tint;
  - **"Bluetooth is off"** / "Turn it on to reach your BedJet";
  - a "Turn on" button (`.btn.sm.soft`).
- All six tiles are dimmed (`.dim`). The caption says they stay tappable.

### 2.2 Profiles

**Profiles list** (20:15)
- Title "Profiles". Buttons: "Import" (with the import icon) and "+ New" (primary, pill).
- Cards:
  1. "Summer comfort": chip **"Tonight"** (selected chip), ⋯, a thin bar, "22:30 → 06:45", day dots M T W T F on and S S off. The card is selected: `.prof.on`, stripe `--cool`.
  2. "Winter warm": chip "Start", ⋯, a thin bar, "22:30 → 07:00", days S S on. Stripe `--h2`.
  3. "Afternoon nap": chip "Start", a single `--h1` bar, card opacity .6. Shown behind the open menu.
- Each card has a star icon (`fav`, in `--fav`) before its name.
- ⋯ menu items, each with its icon: Edit (`edit`), Duplicate (`copy`), Share profile (`share`), **Delete** (`trash`, bad colour).
- Caption: "Tap to edit, long-press to reorder, ⋯ for Edit, Duplicate, Share profile and Delete."

**Edit presets** (sheet "Quick presets", action "Done"). Each row has a grip, a swatch icon, a name, a subtitle and ›:

| Name | Subtitle |
|---|---|
| Turbo | "43° · 10 min" |
| Heat | "32° · fan 30% · 30 min" |
| Cool | "22° · fan 40% · 30 min" |
| Dry | "28° · fan 40% · 30 min" |
| Boost | "Turbo, then Heat" |
| Off | "Always last" (grip hidden, no ›) |

The sheet ends with a dashed add row: "+ Add a profile as a preset".

### 2.3 Profile editor

**Editor**
- Top row: "‹ Profiles" … "Save". Title "Winter warm".
- A 44px stepped bar chart. Bar heights follow temperature.
- Ticks: "Bed 22:30" · "Wake 07:00 · alarm" · "Off 07:10".
- Groups (label, then total):
  - "Start 40 min":
    - Turbo / "Warm the bed fast" / "10 min" (stripe `--turbo`);
    - Heat 36° / "Fan 40%" / "30 min" (stripe `--h3`).
  - "Through the night 7 h 20": Heat 32° → 29° / "Steps down twice · quiet fan" / › (ramp icon, no grip).
  - "Wake up · before alarm 30 min": Heat 33° / "Fan 50%" / "30 min".
  - "After waking 10 min": "Cool · fan 30%" / "Then off" / "10 min".

**Drag and drop**
- The dragged card is lifted:
  - rotated −2°, offset 8, −14;
  - shadow `0 14px 28px rgba(0,0,0,.55)` and a 1.5px border in its colour;
  - background **`#262a45`** (hard-coded; see §11).
- A 3px `--fav` line marks where it will land.
- Caption: "Screen readers get Move up and Move down instead."

**Add a step** (sheet, action "Cancel")
- Segmented control: "Start" (selected) · "Wake up" · "After waking".
- Tiles (name / subtitle):
  - Turbo / "43° · 10 min"
  - **Heat, selected** / "32° · 30 min"
  - Long heat / "30° · any length"
  - Cool / "22° · 30 min"
  - Dry / "28° · 30 min"
- Hint: "These are starting points. You set the exact temperature, fan and length next: anything from 1 minute to 12 hours. Turbo is always 10 minutes."
- Button: "Next: set it up".

**Step editor** (sheet "Start · step 3", action "Done")
- Segmented control: "Turbo" · "Heat" (selected) · "Long" · "Cool" · "Dry". **Note: the label here is "Long", not "Long heat".**
- Temperature: − "32°" + (46px, `--h2`) with "Temperature" underneath.
- "Fan" … "30%", with a slider at 30%.
- "How long": − "0 h 45 min" + (24px).
- Chips: "30 min" · **"45 min"** (selected) · "1 h" · "2 h" · "Type a time".
- "Runs 23:10 – 23:55".
- Footer: "Delete step" (colour `#ff9b91`) and "The night adjusts to fit".
- Caption: − and + move in **5-minute steps**, the chips jump to their value, and "Type a time" accepts any hours and minutes. Back-to-back Heat steps are allowed.

**Long heat, 5 hours** (sheet "Start · step 2", profile "Deep winter")
- "Long" is selected. "38°" in `--h3`. Fan 40%.
- "5 h 0 min". Chips: 1 h · 2 h · 4 h · **5 h** · Type a time.
- "Runs 22:40 – 03:40".
- Info card: "At 38° and fan 40% the BedJet stops Heat after 4 hours on its own. Nocturne starts it again straight away, so you get the full 5 hours."
- Caption: any length up to 12 hours.

**Turbo** (sheet "Start · step 1")
- "Turbo" is selected. "43°" in `--h4`, with no − or +, and the line "Turbo is always 43°".
- "Fan" … "100%", with a slider at 100%.
- "How long" shows "10 min" with no − or +.
- Hint: "The BedJet runs Turbo for 10 minutes at a time. To keep warming, add a Heat step after it."
- Caption: "Temperature and length are shown, not offered."

### 2.4 Through the night (sheet "Through the night", action "Done")

**Layout:**
1. "At 23:10" with a − / + stepper, "→", then "By 06:30" with a − / + stepper. The steppers show 32px temperatures.
2. "How should it get there?" with four shape tiles: **Stay** (1 block) · **Once** (2) · **Twice** (3) · **Gently** (6). Each shows a stair pictogram.
3. The temperature chart (`rchart`).
4. "Fan" … "Any temperature shape".
5. Segmented control: **Steady** · **Ease down** · **Quiet middle**.
6. The fan chart (`fchart`).

**The three examples:**

| Example | Temperature | Shape | Fan |
|---|---|---|---|
| Easing off, twice | 32° → 29° (`--h2` → `--h1`) | Twice | Quiet middle: 40% "Settle", 20% "Asleep", 45% "Rise". Changes at 00:10 and 05:30. |
| Hot, stepping down once | 37° → 32° | Once | Steady 30%, "All night" |
| Cool, warming gently | Cool 20° → 24° (`--cool` → `--cool2`) | Gently | Ease down 40% → 25%. Chart caption: "40% → 25%, with the temperature blocks" |

**Captions:**
- "Easing off, twice": "40% while you settle, 20% while you sleep, then 45% for the hour before the wake-up starts."
- Section lede: the night is split into **equal blocks** from From to To, set with − / + or by **dragging the first or last block**. Fan speeds are set by **dragging the dots up or down**, and change times by **dragging the edges**.

### 2.5 Guided setup

**1 · Phone settings**
- Progress bar 1/7. Title "A few phone settings first". Subtitle "Each one keeps your nights reliable. You can change them later in Settings."
- Rows (title / subtitle / action):

| Row | Subtitle | Action |
|---|---|---|
| Nearby devices | To find and talk to your BedJet | ✓ (ok) |
| Notifications | Wake-up buttons and send failures | "Allow" (primary pill) |
| Alarms & reminders | Wake-up on the minute | "Open" |
| Unrestricted battery | Stops Samsung pausing Nocturne | "Open" |

- Footer: "Continue" (primary) and "Skip for now".

**2 · Bluetooth off** (sheet over "Find your BedJet")
- Bluetooth-off icon tile: 56px, radius 18, cool tint 22%.
- "Bluetooth is off" / "Nocturne talks to your BedJet over Bluetooth. Turn it on to look for it."
- Buttons: "Turn on Bluetooth" (primary) and "Not now".

**3 · Find the BedJet**
- Progress 2/7. Title "Find your BedJet 3". Subtitle "Keep the BedJet app closed on other phones while we look."
- Scan rings.
- Results:
  - "BedJet 3 · Bedroom" / "Strong signal", with a "Choose" button (primary);
  - "BedJet 3 · 4C:1F" / "Weak signal", row opacity .7, with a "Choose" button (plain).
- Link: "Try the demo BedJet instead".

**4 · Getting into bed** ("New profile · 1 of 4")
- Title "How should bed feel when you get in?"
- Options:
  - **Toasty fast** / "Turbo 10 min, then Heat 34°" (selected);
  - Gently warm / "Heat 30° for 30 min";
  - Cool and fresh / "Cool 20° for 30 min";
  - Nothing special / "Go straight to the night".
- Button: "Next".

**5 · Through the night** ("2 of 4")
- Title "And while you sleep?"
- Options:
  - **Warm, easing off** / "Heat 32° → 29°" (selected);
  - Stay warm / "Heat 30° all night";
  - Cool, getting milder / "Cool 20° → 24°";
  - Cool all night / "Cool 22°, fan 35%".
- Button: "Next".

**6 · Waking up** ("3 of 4")
- Title "When do you wake up?"
- Options: **Follow my phone alarm** / "Next: 07:00 from Clock" (selected); Same time every day / "Pick a time".
- "Wake me with" tiles: **Warmth** / "30 min before" (selected); Cool air / "30 min before"; Nothing / "Off at alarm".
- Note: "Then 10 minutes of cool air, and off."
- Button: "Next".

**7 · Name and save** ("4 of 4")
- Title "Here's your night". A night bar ending in the cool cooldown. Ticks: "Bed 22:30" · "Wake with alarm".
- Fields: "Name" (Winter warm), "Bed time" (− 22:30 +), "Days" (M–F on).
- Footer: "Save profile" (primary) and "Fine-tune in the editor" (link).

### 2.6 Settings

**Settings home.** Title "Settings", then eight rows in four cards:

| Card | Row | Subtitle | Trailing |
|---|---|---|---|
| 1 | BedJet 3 · Bedroom | Connected · slot 3 | › |
| 2 | Night and wake-up | Clock alarm · auto-start off | › |
| 2 | Appearance | Scheduled · Midnight Ink / Overcast | › |
| 2 | Permissions | All 4 allowed | ✓ (ok, 15px) and › |
| 3 | Profiles and backups | Export, import, snapshots | › |
| 3 | Notifications | Last 2 months | › |
| 3 | Health Connect | Off | › |
| 4 | Help and about | Version 1.0 · updates on GitHub | › |

**Appearance**
- Segmented control: System · Light · Dark · **Scheduled**.
- A card with:
  - (moon icon, `--fav`) "Night from" with chip "20:00";
  - (sun icon, `--dry`) "Day from" with chip "07:00";
  - a 10px 24-hour strip: 7 parts `#3a3470`, 13 parts `#ced2d8`, 4 parts `#3a3470`.
- "Night palette": a card showing "Midnight Ink" and **6 dots**: `#1a1f3a` (selected), `#183428`, `#000`, `#33204a`, `#4a2614`, `#34466b`.
- "Day palette": a card showing "Overcast" and **1 dot**: `#ced2d8` (selected).
- Rows: "Units" / "°C" / ›; and "Widgets match the app" / "Same palette on the home screen" / toggle on.
- Caption: "Widgets follow along."

**Permissions**
- Top row: "‹ Settings" … "Recheck". Subtitle "Nocturne needs these to run your nights on time."
- Rows:
  - Nearby devices / Allowed ✓
  - Notifications / Allowed ✓
  - Alarms & reminders / Allowed ✓
  - Unrestricted battery / **"Samsung may pause Nocturne"** (warn) / "Fix" (primary)
- Second card:
  - Samsung battery tips / "Sleeping apps and unused apps" / ›
  - Test sleep and wake / "Five-minute test night" / ›

**Profiles and backups**
- Export profiles / "Choose which, then save or share"
- Import profiles / "From a file someone shared"
- Full backup / "Profiles, presets and settings" / "Export" button
- Restore from file / "You check it before anything changes"
- "Snapshots" … "Snapshot now". Rows, each with a "Restore" link:
  - "Today 08:00" / "Automatic · daily"
  - "Yesterday 21:14" / "Automatic · profile edited"
  - "\"Before winter\"" / "Yours · 3 Oct"

**Notifications**
- "Today":
  - Wake-up started / "Heat 33° · from Clock alarm" / 06:30 (stripe `--h2`)
  - Night started / "Summer comfort" / 22:30 (stripe `--cool`)
- "This week":
  - **Didn't send Cool** / "BedJet out of range" / Sat (stripe `--warn`, btoff icon)
  - Wake-up moved to 07:15 / "Your alarm changed" / Thu (stripe `--fav`)
- "Earlier" / "34 more, up to 2 months back" / ⌄
- Bottom card:
  - Quiet night status / "Silent notification while a night runs" / toggle on
  - Wake-up buttons / "Snooze warm · Extend cool · Stop" / ›
- Caption: no Clear button; the last two weeks show in full, older entries fold under "Earlier", and anything past 2 months drops off.

**Night and wake-up**
- Alarm source / "Clock · next 07:00" / ›
- Alarms from other apps / "Also follow alarms from other clock apps" / toggle off
- Auto-start at bed time / "With a cancel notification" / toggle off
- Bedtime reminder / "30 min before" / toggle on
- Morning check-in / "\"How did the night feel?\"" / toggle on
- BedJet slot / "Slot 3 · originals saved" / ›
- Supervised fan check / "Done for this BedJet" / ›

**Help and about** (tagged "new")
- App card: 46px icon tile (radius 14, gradient `#3a3470` → `#14172b`, moon in `--fav`), "Nocturne", "Version 1.0 · up to date".
- Rows:
  - Check for updates / "Last checked today" / ›
  - **Nocturne on GitHub** / "Project page and downloads" / external-link icon
  - How Nocturne works / "Profiles, wake-up and widgets" / ›
  - Diagnostics / "Share a log if something goes wrong" / ›
  - Licences / "Open-source parts" / ›
- Footer: "Works with BedJet 3 only."

### 2.7 History

Title "History". Night cards (`.prof` style, no stripe colour set):

| Night | Chip | Profile | Times |
|---|---|---|---|
| Last night | "Just right" | Summer comfort | 22:31 – 06:55 |
| Sunday | "Too warm" | Winter warm | 23:02 – 07:40 |
| Saturday | **"1 didn't send"** (chip text `--warn`) | Winter warm | 22:45 – 07:30 |

Then "Earlier nights" / "Up to 2 months" / ⌄.

Captions:
- "The bar shows what actually ran. A grey gap marks a missed command (`--glass2` segment). The chip is your morning check-in answer."
- The lede calls History a **suggestion** that "needs checking against what the app records today."

### 2.8 Widget (Palettes section)

- A rounded panel over a sample wallpaper.
- Top row: "24°" (30px display) on the left; on the right, a green dot and "Winter warm · 22:30".
- Four tiles in one row, **names only, no subtitles**: Turbo · Heat · Cool · Off.

### 2.9 Morning check-in

**Unspecified.** The artefact shows only:
- the check-in answers as History chips ("Just right", "Too warm");
- the Settings toggle "Morning check-in" / "\"How did the night feel?\"".

No check-in prompt, dialog, notification or proposal screen is drawn.

---

## 3. Colour

### 3.1 Shared mode colours (`:root`, every dark palette)

| Token | Value | Use |
|---|---|---|
| `--h1` | `#ff8257` | Ember 1: Warm, red-orange |
| `--h2` | `#f45a40` | Ember 2: Hot, clear red. Also `--heat` |
| `--h3` | `#dc3b32` | Ember 3: Hotter, deeper |
| `--h4` | `#c0202e` | Ember 4: deepest. Also `--turbo` |
| `--cool` | `#4fb3ff` | Cool |
| `--cool2` | `#9dd6ff` | Lighter cool (warmer cooling) |
| `--dry` | `#e6c35c` | Dry; pending dot |
| `--off` | `#9aa3b6` | Off / idle |
| `--boost` | `#ff6fa0` | Boost preset |
| `--ok` | `#3ddc84` | Connected dot, ✓ |
| `--warn` | `#ffcf5c` | Warnings, "didn't send" |

**Ember scale labels** (Colour section): Cool "18–26°" · Warm "26–30°" (h1) · Hot "31–35°" (h2) · Hotter "36–40°" (h3) ·
Turbo "41–43°" (h4). The lede adds: "Warm 26–30° is red-orange, 32° is a clear red, 36° and up is deeper, and Turbo
at 43° is the deepest."

**Where colours come from:**
- In the static screens, each step's colour is chosen by hand from these levels. The examples: 30° → h1, 32° → h2, 33° → h2, 36° → h3, 37° → h3, 38° → h3, Turbo → h4.
- The night ramp chart uses a continuous mix: 26° = h1, 32° = h2, 37° = h3, 42° = h4, with `color-mix` interpolation between them. Cool goes from `--cool` at 19° to `--cool2` at 25°.

### 3.2 Phone tokens per palette

Empty cells inherit the Midnight Ink (`:root`) value. Listed in the order the Palettes section shows them.

| Token | Midnight Ink (root) | Overcast | Forest | Graphite OLED | Plum | Autumn · Rust | Winter · Storm |
|---|---|---|---|---|---|---|---|
| `data-pal` | (none) | `overcast` | `forest` | `graphite` | `plum` | `rust` | `storm` |
| `--phbg` ground | `#0b0d18` | `#ced2d8` | `#08120e` | `#000` | `#120a18` | `#160d09` | `#0f1626` |
| `--phglow` top glow | `#1a1f3a` | `#d9dce1` | `#183428` | `#161616` | `#33204a` | `#4a2614` | `#34466b` |
| `--glass` surface | `rgba(255,255,255,.075)` | `#e2e5e9` | `rgba(210,255,230,.07)` | `rgba(255,255,255,.07)` | `rgba(240,220,255,.075)` | `rgba(255,220,190,.075)` | `rgba(220,232,255,.085)` |
| `--glass2` surface 2 | `rgba(255,255,255,.12)` | `#d4d8de` | `rgba(210,255,230,.12)` | `rgba(255,255,255,.12)` | `rgba(240,220,255,.12)` | `rgba(255,220,190,.12)` | `rgba(220,232,255,.13)` |
| `--edge` 1px card edge | (unset = transparent) | `#aab0ba` | | | | | |
| `--hair` divider | `rgba(255,255,255,.07)` | `rgba(20,23,29,.12)` | `rgba(210,255,230,.08)` | `rgba(255,255,255,.08)` | `rgba(240,220,255,.08)` | `rgba(255,220,190,.08)` | `rgba(220,232,255,.08)` |
| `--ink` | `#f1f3f9` | `#14171d` | `#eef6f1` | `#f3f3f3` | `#f5effa` | `#fbf1e9` | `#eef3fb` |
| `--ink2` | `#bcc2d4` | `#444b57` | `#b5c8bc` | `#bdbdbd` | `#c9bcd6` | `#d4c1b2` | `#c3cfe2` |
| `--ink3` | `#858ca2` | `#6b7280` | `#7e9587` | `#858585` | `#8f7fa0` | `#9c8574` | `#8a9ab4` |
| `--fav` accent | `#b48cff` | `#1f7cc2` | `#7fdca6` | `#dcdfe6` | `#d6a6ff` | `#ffb35c` | `#aac4ff` |
| `--pri` Start button | (unset → `--ink`) | `#2f93dc` | | | | | |
| `--prifg` on primary | `#12152a` | `#fff` | `#0c1a13` | `#000` | `#1a0f24` | `#1e120b` | `#111a2c` |
| `--sheetbg` | `#171a2c` | `#dde1e6` | (inherits `#171a2c`) | (inherits) | (inherits) | `#24160f` | `#1b2438` |
| `--menubg` | (unset → `#232740`) | `#eef0f3` | | | | | |
| `--bad` | (unset → `#ff9b91`) | `#c62f2a` | | | | | |
| `--warn` | `#ffcf5c` | `#9a6a00` | | | | | |
| `--cool` / `--cool2` | `#4fb3ff` / `#9dd6ff` | `#2b8ee0` / `#7fc1f2` | | | | | |
| `--dry` | `#e6c35c` | `#b88c12` | | | | | |
| `--off` | `#9aa3b6` | `#7d8696` | | | | | |

Palette captions (descriptions in the Palettes section):

| Palette | Caption |
|---|---|
| Midnight Ink (default) | "Night default. Deep navy with a lilac accent." |
| Overcast (default) | "Day default. Soft blue-grey with firm edges, no white anywhere. Sky-blue Start button." |
| Forest | "Very dark green with a mint accent." |
| Graphite OLED | "True black, so an OLED screen can switch pixels off. Silver accent." |
| Plum | "Deep aubergine with a soft violet accent." |
| Autumn · Rust | "Burnt umber with an amber accent." |
| Winter · Storm | "A soft, cloudy deep blue with a periwinkle accent." |

Ember (`--h1`–`--h4`), `--boost` and `--ok` are **the same in every palette, Overcast included**.

### 3.3 Hard-coded colours (not tokens)

| Where | Value | Notes |
|---|---|---|
| Phone background | `radial-gradient(120% 60% at 50% 0%, var(--phglow) 0%, var(--phbg) 60%)` | The glow is the one intended gradient on the page background |
| Dimmer | `rgba(5,6,12,.55)` | All palettes |
| Sheet shadow | `0 -10px 30px rgba(0,0,0,.4)` | |
| Sheet handle | 36×4, radius 2, `rgba(255,255,255,.2)` | Nearly invisible on Overcast (§11) |
| Slider track | 6px, radius 3, `rgba(255,255,255,.12)` | Faint on Overcast (§11) |
| Slider thumb | 18px circle, `#fff`, shadow `0 2px 6px rgba(0,0,0,.4)` | |
| Toggle off knob | `#c9cede`; on knob `#fff` | |
| Now marker | 3px `#fff`, glow `0 0 8px #fff` | |
| "End night" text | `#ffb3a8` | Dark palettes only; unreadable on Overcast (§11) |
| "Delete step" text | `#ff9b91` | Same value as the dark `--bad` fallback |
| Drag-lifted card | background `#262a45` | Dark only (§11) |
| Menu shadow | `0 10px 30px rgba(0,0,0,.5)` | |
| Chart labels on heat blocks | `#fff`; on cool blocks `#0d2238` | |
| Chart end handles | 22×5, radius 2.5, `#fff` | Draggable first and last blocks |
| Fan chart | Segments `color-mix(--fav 34%, transparent)` (28% when more than 3 segments), radius 3; dots r 5, `#fff` | |
| Help app icon | `linear-gradient(140deg, #3a3470, #14172b)` | |
| Appearance 24 h strip | night `#3a3470`, day `#ced2d8` | |
| Palette dots | Each palette's `--phglow` (Graphite uses `#000`) | |
| Widget wallpaper (sample only) | Dark: `linear-gradient(140deg, #2c3a5e, #6b4a63 55%, #b9735c)`. Light (`.wall.lt`, Overcast): `linear-gradient(140deg, #c9d6e8, #e9d9cf 60%, #f1c7a8)` | Stands in for the phone's wallpaper. Not part of the widget. |

---

## 4. Typography

| Role | Font | Size / weight / other |
|---|---|---|
| Display (big readings, titles) | Bricolage Grotesque | 700. `.big`: line-height .95, letter-spacing −.02em, tabular numbers |
| Body | Figtree | Phone base 12.5px |
| Home reading | display | 64px |
| Step / Turbo temperature | display | 46px |
| Through-the-night From/To | display | 32px |
| Setup bed-time stepper | display | 30px |
| Widget reading | display | 30px |
| Duration value | display | 24px |
| Screen title (Settings, History, Appearance…) | display | 700 22px |
| Profiles / editor title | display | 700 20px |
| Setup question title | display | 700 22px, line-height 1.15 |
| Sheet title | body | bold 15px |
| `.lbl` section label | body | 10.5px, 700, uppercase, letter-spacing .07em, `--ink3` |
| Tile name | body | 700 12px, line-height 1.25 (widget 10.5px) |
| Tile subtitle | body | 500 10px, `--ink2`, single line with ellipsis |
| Row title (`.stp .tx b`, `.srow .tx b`) | body | 12.5px (`.srow` 600), single line with ellipsis |
| Row subtitle | body | 11px, `--ink2`, single line with ellipsis |
| Row trailing (`.r`) | body | 11px (`.stp`) / 12px (`.srow`) |
| `.link` | body | 600 11.5px, `--fav`, no wrap |
| `.conn` status line | body | 11.5px, `--ink2` |
| Chip | body | 11px (`.qk` chips 10.5px) |
| Segment | body | 600 11px |
| `.ticks` | body | 10px, `--ink3` |
| `.hint` | body | 10.5px, `--ink3`, line-height 1.35, centred |
| Nav label | body | 10.5px; icon 18px |
| Status bar | body | 11px, `--ink2` |
| Chart text | Figtree | 9–11px |

---

## 5. Spacing, radii, borders and dimensions

### 5.1 Phone and page

- **Phone frame:** 300×640, radius 38, with a 7px `#05060b` bezel and shadow `0 18px 40px rgba(0,0,0,.35)`.
- **Status bar:** padding 10 22 4.
- **Screen:** padding 6 14 0, vertical gap 10 (8 on Settings, History and the editor).
- **Bottom nav:** padding 8 10 14, with a 1px `--hair` top border. The current tab is `--ink`; the others are `--ink3`.

### 5.2 Components

| Component | Spec |
|---|---|
| `.card` | Radius 18, padding 12, background `--glass`, inset 1px `--edge` |
| Settings card | Padding 4 10. Rows (`.srow`): padding 9 2, gap 11, with a 1px `--hair` divider between rows |
| `.srow .ic` | 30×30, radius 10, `--glass2`; icon 16px in `--c` (default `--ink2`) |
| `.btn` | Radius 14, padding 10, 600, `--glass2`, `--ink` text |
| `.btn.pri` | Background `--pri`, or `--ink` if `--pri` is unset; text `--prifg` |
| `.btn.sm` | Pill (radius 999), padding 6 12, 12px |
| `.btn.soft` | Cool 20% over transparent; text cool 55% mixed with ink |
| `.chip` | Pill, padding 4 9, `--glass2`, `--ink2`. `.sel`: background `--ink`, text `--prifg`, 600 |
| `.seg` | Background `--glass`, padding 4, radius 12, gap 4. Items: padding 6 0, radius 9. `.sel`: background `--ink`, text `--prifg` |
| `.pm` stepper button | 34×34 circle, `--glass2`, 19px 600. `.pm.s` is 26×26, 15px |
| `.tg` toggle | 34×20, radius 10, off `--glass2`. Knob 14px at 3px inset. On: `--fav` with the knob at left 17 |
| `.days` | 20px circles, 10px text, gap 4. Off: `--glass2` / `--ink3`. On: `--fav` 38% / `--ink` |
| `.pdots` | 18px circles, gap 5, inset 1px `rgba(255,255,255,.2)`. Selected: `0 0 0 2px var(--phbg), 0 0 0 3.5px var(--fav)` (a gap, then an accent ring; **no tick**) |
| `.shape` | Padding 8 4, radius 12, `--glass`, 600 10.5px `--ink2`. `.sel`: `--c` 22%, inset 1.5px `--c`, `--ink` text. Stair icon 44×26 |
| `.add` | 1.5px dashed `--glass2`, radius 14, padding 8, 11.5px `--ink2` |
| `.menu` | Width 170, right 18, background `--menubg` (fallback `#232740`), 1px `--edge`, radius 14, padding 6. Items: padding 8 10, radius 9, 600, icon 16px `--ink2`, gap 9. `.bad` uses `--bad` (fallback `#ff9b91`) |
| `.sheet` | Pinned to the bottom, full width, radius 24 24 0 0, padding 10 16 18, gap 11, `--sheetbg`. The `top` offset varies per screen (70–170) |
| `.scan` | 120×120; three 2px `--cool` rings expanding over 2.4s, staggered .8s; 34px Bluetooth icon |
| Progress (setup) | 4px bars, radius 2, gap 4. Done: `--fav`. To do: `--glass2` |
| `.dot` | 7px. `--ok` when connected. `.pend`: `--dry`, pulsing 1.2s |
| Icons | 18px default (`svg.i`), 16px in tiles and rows, 17px in step swatches, 13px in widget tiles |

---

## 6. Side-stripe tiles, rows and cards

The Locked note reads: preset tiles, steps and profile cards "have a 4px stripe down the left side in their mode or
main colour, over a flat, soft tint of that colour. No gradients." The `--c` custom property sets the colour.

### 6.1 How the stripe is drawn

- The stripe is an inset box-shadow, `inset 4px 0 0 var(--c)`, so it follows the rounded corner.
- The tint mixes `--c` with `--glass`: `color-mix(in srgb, var(--c) N%, var(--glass))`.
  - On dark palettes `--glass` is translucent, so this equals the `--glass` surface over the ground, with `--c` drawn on top at N% alpha.
  - On Overcast `--glass` is opaque, so it is simply N% `--c` mixed into `#e2e5e9`.
- A second inset shadow, 1px `--edge`, gives the firm outline on Overcast. It is transparent on dark palettes.

### 6.2 Tint strength and selected state per component

| Component | Radius | Padding | Tint N | Selected |
|---|---|---|---|---|
| `.tile` (preset tile, setup choice) | 12 | 9 6 9 11 | **10%** | `.on`: tint **24%**, plus an inset 1.5px `--c` border |
| `.stp` (step row, preset row, setup option, notification) | 14 | 9 10 | **9%** | `.sel`: an inset 1.5px `--c` border (tint unchanged) |
| `.prof` (profile card, history night) | 18 | 12 | **8%** | `.on`: an inset 1.5px **`--fav`** border (not `--c`) |
| `.stp .sw` swatch | 10 (30×30) | — | `--c` 22% over transparent; icon 17px `--c` | — |

### 6.3 Tile layout (`.tile`)

- A two-column grid: `auto` for the icon (16px, in `--c`) and `minmax(0,1fr)` for the name. Column gap 7.
- `small` (the subtitle) spans both columns on its own line, with 2px top margin.
- **The subtitle is one line: `white-space:nowrap; overflow:hidden; text-overflow:ellipsis`.** The design never wraps it. Every sample subtitle fits. See RECONCILIATION R3.
- Grid: 3 columns, gap 8. The widget uses 4 columns, gap 6, tile padding 8 3 8 8, and 13px icons.

### 6.4 Tile states

| State | Look |
|---|---|
| Default | 10% tint, 4px stripe |
| Selected / active (`.on`) | 24% tint, 1.5px `--c` border. Used for the active Heat during Running, and for choices in Add a step and setup |
| Pending (`.pd`) | A 7px `--dry` dot at the top right (7px from the top, 8px from the right), pulsing 1.2s. The name gets "…" and the subtitle reads "Sending" |
| Disabled / offline (`.dim`) | Opacity .5. The caption says it stays tappable |
| Failed / unknown / confirmed | **Unspecified for tiles.** "Confirmed" is the default look, reached after pending: the caption says it "lights up only once the BedJet confirms". The only failure visuals in the design are in Notifications ("Didn't send Cool", stripe `--warn`) and History (chip "1 didn't send" in `--warn`, plus a `--glass2` gap in the bar) |

---

## 7. Night bar and charts

### 7.1 Tonight bar (`.tl`)

- Height 30. Segments in a flex row with a 2px gap and min width 4. Each segment's width is proportional to its duration (`flex`).
- **Radii:** inner joins 3; outer ends 10 (first `10 3 3 10`, last `3 10 10 3`, a lone segment 10).
- **Thin variant** (profile and history cards): height 12, inner 2, outer 4.
- **Colour:** each segment is its step's colour. The Middle ramp is a horizontal gradient:
  - heat: `linear-gradient(90deg, var(--h2), var(--h1))`;
  - cool: `--cool` → `--cool2`.
  - Typical order: Turbo h4 · hot start h3 · ramp · wake-up h2/h1 · cooldown `--cool`.
- **Now marker:** an absolute 3px white bar, 5px taller than the bar at top and bottom, radius 2, glow.
- **Ticks** under the bar: 10px `--ink3`, spaced between.
- **Animation:** segments with `.grow` scale in from the left over .9s. Reduced motion turns this off.

### 7.2 Editor chart

- 44px tall, with segments aligned to the bottom. Heights by temperature: 100, 84, 72, 64, 56, 70, 36%.
- The ramp is drawn as **three discrete steps** (h2, h2/h1 50% mix, h1), not as a gradient.
- Corners: first `8 3 2 8`, middle `3 3 2 2`, last `3 8 8 2`.

### 7.3 Temperature chart (`rchart`, viewBox 268×106)

- n equal blocks with a 4px gap and radius 5. Temperatures are rounded to 0.5°.
- Block height is `16 + (t − (min − 3)) / ((max + 1) − (min − 3)) × 58`, on a baseline at y = 86.
- Each block's temperature label sits inside it at its top left, 700, 10–11px.
- A 22×5 white handle sits above the **first and last** blocks only.
- Time labels:
  - n ≤ 3: a time under each block, plus the end time.
  - Otherwise: start · "every X h YY" · end.

### 7.4 Fan chart (`fchart`, viewBox 268×104)

- One segment per fan speed, placed by fraction of the night, with 2px gaps. Height is `8 + fan / 100 × 52` on a baseline at y = 72.
- A white dot (r 5) on top of each segment.
- When there are 3 segments or fewer:
  - the "%" value above each segment;
  - a name under each segment (Settle / Asleep / Rise / All night);
  - a change time and a 2×8 tick at each edge.
- Ease down (more than 3 segments) instead shows "40% → 25%, with the temperature blocks".

### 7.5 Stair pictogram (`svg.stairs`)

- n bars with rx 2, falling left to right (rising when `data-up` is set).
- Colour mixes from c1 to c2.

---

## 8. Menus, sheets and dialogs

| Item | Defined in the design | Not defined |
|---|---|---|
| Profile ⋯ menu | Yes: §2.2, §5.2 `.menu`. Shown open below the second card | Menu placement rules |
| Quick presets sheet | Yes | Reorder behaviour beyond the grip |
| Add a step sheet | Yes | — |
| Step editor sheet (Heat / Long / Turbo) | Yes | Cool and Dry versions (assume the Heat layout with their colours) |
| Through the night sheet | Yes | — |
| Bluetooth-off sheet (setup) | Yes | — |
| "Stop your bedtime routine?" confirmation | Named in a caption only | **Its look is unspecified** |
| Delete-profile confirmation, Restore confirmation, Type-a-time input, Change-wake, End-night confirmation, Snapshot-now naming, Export picker, Import review | — | **Unspecified** |
| Toasts / snackbars / Undo | — | **Unspecified** ("Undo" and "Empty and error states" are listed under "Before build" as still to do) |

---

## 9. Responsive behaviour

The artefact draws every screen at one size, a 300-wide phone. The rules below come from the CSS, not from drawings
at other widths.

**Layout**
- Content fills the width: tiles are a `1fr` grid, cards are full width, and rows use `margin-left:auto` for their trailing parts.
- On wider phones, tiles get wider; the column count stays at 3 (4 on the widget).
- Long text truncates on one line with "…": tile subtitles, row titles and subtitles, `.conn`, `.link`, chips, segments and ticks.

**Fixed sizes.** Steppers, swatches, toggles and dots keep their pixel sizes.

**Sheets**
- Height is set by `top` in the mock-up.
- On a real device, a sheet should wrap its content and scroll if it is taller than the screen. **Unspecified** in the design.

**Large fonts / font scale:** unspecified.

---

## 10. Accessibility in the design

What the design states:
- **Reduced motion** turns off the bar grow, the pending dot pulses and the scan rings (`@media (prefers-reduced-motion: reduce)`).
- **Drag and drop** has "Move up" and "Move down" alternatives for screen readers.
- **Colour is not the only signal:** each stripe tile or row carries its mode name or temperature as text; History chips are words; the pending state also changes the text ("Heat…", "Sending").
- **Offline tiles** are dimmed but still tappable.
- **Overcast** has "firm edges, no white anywhere", so cards keep a 1px `--edge` outline on the light page.

**What the design does not define:** content descriptions, touch-target sizes, focus order and TalkBack wording. The
design's visual sizes are below 48dp in places: 34px steppers, 26px small steppers, 20px day dots, a 34×20 toggle,
18px palette dots and small chips. **Keep the visual size and extend the touch target to 48dp.**

---

## 11. What is illustrative rather than real app behaviour

The artefact is a static mock-up. Nothing in it is interactive: no tap, drag, toggle or scroll does anything.

1. **Sample data.** All times, temperatures, names, the "slot 3" device, "4C:1F", snapshot names, notification entries, "34 more", "Version 1.0 · up to date", the battery "82%" and the status-bar clock are examples.
2. **The status bar** ("20:00 ●●● 82%") stands in for Android's own and is not app UI.
3. **The widget wallpaper** (`.wall`) and the widget's `backdrop-filter: blur(8px)` mimic a home screen. Glance can't blur. Draw the widget background as `--phbg` at 84% opacity.
4. **The "By day · Overcast" section is automated.** The JS copies the first phone of each section into Overcast unchanged, so any hard-coded dark values come along. Those copies are a check that the tokens hold up, not separately designed light screens.
5. **Screens never drawn in Overcast,** and hard-coded values that break there:
   - "End night" `#ffb3a8` (unreadable on the light page);
   - the sheet handle and slider track (`rgba(255,255,255,…)`, faint);
   - "Delete step" `#ff9b91` (low contrast);
   - the drag card `#262a45`;
   - the white now-marker.

   The `palette-applied-render/overcast` images show this. Treat these as **unspecified for Overcast**: use the tokens (`--bad`, `--ink3`/`--hair`-based tracks and handles, `--glass2` lifted card) rather than copying the dark values.
6. **Dragging** (chart blocks, fan dots, fan edges, profile and step reorder) is described in captions only.
7. **History** is labelled a suggestion "to check against what the app records today".
8. **Rows that may not exist in the app yet** (Health Connect, Check for updates, How Nocturne works, Licences, Test sleep and wake, Auto-start, Bedtime reminder, Alarms from other apps, Widgets match the app, Units…) are design proposals. See RECONCILIATION R12 before building any of them.
9. **Help and about** "Version 1.0 · up to date / Last checked today": the update check and its wording are sample text.

---

## 12. Locked decisions (verbatim from the artefact)

- **colour · Ember red:** "One function maps temperature to colour for bars, tiles, steps, charts and widgets. Cool stays blue."
- **shape · Squarer bar ends:** "Outer ends match the Start button's corners. Inner joins stay square."
- **shape · Side stripe:** "Preset tiles, steps and profile cards have a 4px stripe down the left side in their mode or main colour, over a flat, soft tint of that colour. No gradients."
- **steps · Any length:** "1 minute to 12 hours per step. Turbo fixed at 10 minutes. Heat limits from the BedJet are handled by joining steps back to back."
- **ramp · Fan shapes:** "Steady, Ease down or Quiet middle, independent of the temperature shape."
- **cooldown · After waking:** "New profiles get Cool, fan 30%, 10 minutes, then off. Existing profiles are offered it once."
- **palettes · Seven palettes:** "Midnight Ink by night and Overcast by day as defaults. Forest, Graphite OLED, Plum, Autumn · Rust and Winter · Storm as extras. Overcast uses a sky-blue Start button (#2f93dc) and a deeper blue for cool (#2b8ee0). Widgets follow the palette."
- **links · GitHub:** "The repo is public. Help and about links to the project page for now; where releases live is decided later."
- **finish · Before build:** "Empty and error states, Undo, an accessibility pass, History mapped to what the app records, and every sheet checked in the light palettes."

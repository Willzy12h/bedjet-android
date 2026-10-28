# Screenshots

There are 91 PNGs, all 2× (DPR 2). Each phone is cropped to its 300×640 frame, which gives 600×1280 px. The full
list is in `_files.txt`.

## Provenance: read this first

| Folder | What it is | Counts as an artefact screenshot? |
|---|---|---|
| `full-page/` | The whole artefact page, in the viewer's light and dark page chrome. The phones are identical in both. | **Yes** |
| `as-published/` | Every phone and section exactly as the artefact page draws it, cropped from the page. That includes the 6 Overcast phones the page itself shows in "By day · Overcast" and the 7 palettes in "Palettes". | **Yes** |
| `palette-applied-render/overcast/` | Every phone from sections 1–7, rendered from the **same source** with one change: `data-pal="overcast"` set on the phone's container. That uses exactly the mechanism the page uses for its own Overcast clones. **The artefact does not show these phones in Overcast.** | **No.** These are renders derived from the source. Treat them as a guide to how the tokens apply, not as approved screens. |

Every image was rendered with Playwright Chromium from `source/nocturne-final-design.offline.html`. That file differs
from the published page only in that its fonts load from local copies instead of Google Fonts. The settings are a
1280×900 viewport, `reducedMotion: 'reduce'` and a wait for `document.fonts.ready`. Reduced motion means every
animation is shown in its settled state. The script is described in `../source/README.md`.

Known defect: in the palette-applied Overcast renders, some elements keep dark-only hard-coded colours, such as End
night, the sheet handle, the slider track, Delete step, the drag card and the white now-marker. That is how the
source draws them. DESIGN-REFERENCE §11 and RECONCILIATION R21 cover it. Do not copy those pixels.

## Coverage by screen

Key:

- **A**: artefact screenshot (in `as-published/`)
- **D**: palette-applied render only, not in the artefact (in `palette-applied-render/overcast/`)
- **—**: not in the design; unspecified

| Screen | Midnight Ink | Overcast |
|---|---|---|
| Home · Before bed | A | A (`08…/01-home`) and D |
| Home · Running | A | D |
| Home · Sending | A | D |
| Home · Offline | A | D |
| Profiles list | A | A (`08…/02-profiles`) and D |
| Profiles · Edit presets | A | D |
| Profile editor | A | A (`08…/03-profile-editor`) and D |
| Editor · Drag and drop | A | D |
| Editor · Add a step (sheet) | A | D |
| Editor · Step editor (sheet) | A | D |
| Editor · Long heat, 5 hours (sheet) | A | D |
| Editor · Turbo (sheet) | A | D |
| Through the night · Easing off, twice | A | A (`08…/04-through-the-night`) and D |
| Through the night · Hot, stepping down once | A | D |
| Through the night · Cool, warming gently | A | D |
| Guided setup, steps 1–7 | A | D |
| Settings home | A | A (`08…/05-settings`) and D |
| Settings · Appearance | A | D |
| Settings · Permissions | A | D |
| Settings · Profiles and backups | A | D |
| Settings · Notifications | A | D |
| Settings · Night and wake-up | A | D |
| Settings · Help and about | A | D |
| History · Nights | A | A (`08…/06-history`) and D |
| Home in each of the 7 palettes | A (`09…`) | A (`09…/02-overcast--home`) |
| Quick-controls widget in each of the 7 palettes | A (`09…`) | A (`09…/02-overcast--widget`) |
| Colour section and Locked decisions section | A (`00…`, `10…`) | — (page chrome) |

## Not in the design (unspecified: no screenshot can exist)

- the morning check-in dialog;
- the History detail screen for one night;
- confirmation dialogs other than "Stop your bedtime routine?";
- failed or unknown tile states (only pending and dimmed offline are drawn);
- behaviour at large font sizes;
- landscape and tablet layouts;
- empty states;
- `SingleActionWidget`;
- the layout and wording of the Cooldown offer card;
- widgets on any surface other than the drawn quick-controls grid.

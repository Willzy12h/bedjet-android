# Interactive demo

`index.html`, `demo.css` and `demo.js` are a hand-written HTML version of Nocturne's main screens. They run in any
browser with no build step, using a pretend BedJet and a demo clock that can play through a night. The page says it is
not the app itself, and it is not generated from the app's code, so it drifts as the app changes.

It is published by GitHub Pages from `main` (Settings → Pages → Deploy from a branch → `main`, `/docs`) at
<https://willzy12h.github.io/bedjet-android/demo/>. `docs/.nojekyll` makes Pages serve the files as they are.

## Updating it

- The stamp is in `index.html` on the `<html>` element: `data-build` (the app's `versionName`), `data-commit` (the
  commit on `main` the demo was checked against) and `data-generated` (date and time, UTC). Update all three whenever
  the demo is changed.
- Screens are functions in `demo.js` (`PAGES.*` for pages, `OVERLAYS.*` for sheets and dialogs). Colours come from
  `PALETTES` in `demo.js`, which mirror `Palettes.kt`. Presets mirror `core/.../plan/Presets.kt`.
- The fonts in `fonts/` are copies of the app's Figtree and Bricolage Grotesque (SIL Open Font Licence, see
  `docs/licences`).
- To try it locally: `python3 -m http.server --directory docs/demo` and open <http://localhost:8000>.

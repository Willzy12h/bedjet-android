# Working on Nocturne

Instructions for coding agents (Claude, Codex and others) working in this repository.

## The interactive demo

`docs/demo/` is a hand-written HTML version of the app, published with GitHub Pages and linked from the README. It is
not generated from the app, so it goes out of date when the interface changes.

When a change noticeably alters what a user sees or does (a new or removed screen, tab, setting or control; changed
labels, flows or colours; a new night profile feature), **ask the maintainer whether the demo should be updated too**
before finishing. Don't update it unasked, and don't ask for small internal or styling-only changes. If it is updated,
follow [docs/demo/README.md](docs/demo/README.md), including the build/commit/date stamp in `docs/demo/index.html`.

The screen gallery (the `gallery` branch) is different: CI re-renders it automatically after each change to `main`, so
it needs no manual step.

## Conventions

- User-facing text is in UK English.
- Before pushing changes to app or core code, run the tests the change affects, for example
  `./gradlew :core:test` for core or `./gradlew :app:testDebugUnitTest --tests '*AlarmRuleTest*'` for one area.
  CI runs the full suite, lint and the release checks on every pull request.
- Never commit signing keys or other secrets.
- For an authorised phone-testing beta, set a version code higher than the current public release before running candidate CI. Deliver the Nocturne release-signed APK so it installs over the existing public beta.
- When you bump `release.properties`, add the new version's saved data under
  `core/src/test/resources/saved-data/<versionName>/`: a `profiles.json` with "Summer, cooler" edited (days 6 and 7,
  fan start 70) and an own profile `own-1` on days 1 to 3, a `settings.json` with working slot 1 and Fahrenheit on, and
  a `backup.json` of the same. `SavedDataCompatibilityTest` then checks every later release still reads it.

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
- Run `./gradlew :core:test` and `./gradlew :app:testDebugUnitTest` before pushing changes to app or core code.
- Never commit signing keys or other secrets.

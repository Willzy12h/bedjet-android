# Contributing to Nocturne

Thanks for helping. Issues and pull requests are welcome.

## Reporting a problem

Open an issue that says what you did, what you expected and what happened. If the app misbehaved, save the file from
**Settings › About › Diagnostics** and attach it. It holds your settings, recent night events and, if the app
has crashed, the last crash trace. It never contains your Health Connect data.

## Making a change

1. Fork the repository and branch from `main`.
2. Build and test with JDK 17 or newer and the Android SDK (API 36):

   ```bash
   ./gradlew :core:test
   ./gradlew :app:testDebugUnitTest
   ```

   Both must pass before you open a pull request. CI runs the same tests, plus lint and the release checks.
   For a coverage report of both suites, run `./gradlew koverHtmlReportUnitTests` and open
   `build/reports/kover/htmlUnitTests/index.html`. CI uploads the same report with each build. It doesn't fail on
   low coverage.
3. Keep each pull request to one change, and describe what it changes for someone using the app.

## Conventions

- User-facing text is in UK English, in short plain sentences. Use contractions ("can't", "doesn't"), sentence case for
  labels, and the app's own words for modes (for example "Long heat", not the BedJet's internal names).
- Strings live in `app/src/main/res/values/`, in files grouped by topic. Code style is in `.editorconfig`.
- Anything that sends a command to the BedJet is confirmed by the BedJet's own report before the app says it worked.
  New controls should keep that rule.
- Never commit signing keys or other secrets.
- If a change alters what people see or do, say so in the pull request, as the HTML demo in `docs/demo/` may need
  updating too.

## Releases

Releases are built, signed and published by CI when a version bump merges into `main`. See
[Build from source](README.md#build-from-source) for the details and [CHANGELOG.md](CHANGELOG.md) for past releases.

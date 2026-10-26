# Nocturne final fixes — verification and delivery

**Date:** 25 October 2026. **Branch:** `gpt/nocturne-final-fixes`. **Base:** `c9befe66f0b78fff4fe9da15d217895ca4b48eee` (`claude/nocturne-final-candidate`, with the earlier work integrated).

This round addresses A1/IR-31, A2 and U-B4. The implementation and targeted RED → GREEN cycles are complete. The final gate and release-build results are recorded below. The final chat handover supplies the containing commit, downloadable test APK, APK SHA-256 and test-certificate SHA-256.

**Physical acceptance: NOT RUN.** No phone, Samsung, BedJet 3, TalkBack or physical haptic result is claimed. The new [phone test guide](phone-test-guide.md) contains blank PASS/FAIL/NOTES columns for the owner.

## Changes

| Task | Result | Automated coverage |
|---|---|---|
| A1 / IR-31 | Settings renders the resource-backed uncertainty explanation only when `SlotBackup.uncertain` is true, in matching original rows, read-only From backup rows and the restore confirmation dialogue. | [`UncertainOriginalsSettingsTest`](../../app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UncertainOriginalsSettingsTest.kt): six cases, uncertain/certain in each surface. Dialogue assertions require a dialogue ancestor. |
| A2 | Core user-facing temperature errors use exhaustive `Mode.displayName` labels matching the app. The same fix covers the additional enum-derived name in backup validation. Machine enum names and wire codes retain their existing representation. | [`PlanErrorModeNameTest`](../../core/src/test/kotlin/io/github/willzy12h/nocturne/core/plan/PlanErrorModeNameTest.kt): five reachable modes; [`BackupModeNameTest`](../../core/src/test/kotlin/io/github/willzy12h/nocturne/core/store/BackupModeNameTest.kt): one Gentle heat validation error. |
| U-B4 | The Simple timeline uses **Wake tonight only** for an override at width ≤320 dp and font scale ≥2.0. The existing full label remains elsewhere; no layout modifiers change. | [`TonightWakeLabelLayoutTest`](../../app/src/test/kotlin/io/github/willzy12h/nocturne/ui/TonightWakeLabelLayoutTest.kt): eight actual Activity/text-layout cases, including both Simple palettes at 320 dp / 200% and six unaffected controls. |

## RED → GREEN evidence

Tests were written and executed against the previous production behaviour before each fix. Infrastructure start-up failures are not counted as behavioural RED evidence.

The [captured test-evidence summary](final-fixes-test-evidence.json) preserves the exact failure assertions and per-suite totals extracted from the RED/GREEN XML reports.

| Task | Observed RED | Observed GREEN |
|---|---|---|
| A1 | Six tests ran: the three uncertain cases failed because the warning node was absent/not displayed; all three certain controls passed. | Six passed, zero failures/errors/skips. |
| A2 | Six tests ran and all six failed on the expected error wording: lower-case `heat`, `cool`, `dry`, `turbo`, `extended heat`, and backup validation's `Extended heat`. | Full core suite: 175 passed, zero failures/errors/skips, including all six new cases. |
| U-B4 | Eight cases ran: both Simple palettes at 320 dp / 2.0 failed with `Simple wake title splits 'Tonight-only' within a word: [Tonight-onl, y wake, time] expected:<0> but was:<1>`. The six controls passed. | Eight passed, zero failures/errors/skips. |

The two app suites ran together: RED **14 total / 5 failed / 9 passed / 0 errors / 0 skipped**, then GREEN **14 total / 14 passed / 0 failures / 0 errors / 0 skipped**. Neither suite is an opt-in capture test. The tests use Robolectric SDK 34; the Settings fixture uses GraphLifecycle directly and the layout fixture uses U06Fixture's GraphLifecycle.

The exact Gentle heat compiler assertion changed from actual `extended heat cannot honour this profile's temperature limits` to expected `Gentle heat cannot honour this profile's temperature limits`. The backup assertion changed `Extended heat allows 19 °C to 33.5 °C` to `Gentle heat allows 19 °C to 33.5 °C` within the complete validation error.

### A2 reachability and representation

| Mode | Core display label | Generic temperature-refusal coverage |
|---|---|---|
| STANDBY | Off | Returns before the temperature-intersection check; cannot reach this message. |
| HEAT | Heat | Maximum profile temperature 44 half-degrees, below the mode's minimum 45. |
| TURBO | Turbo | Direct-core input with minimum and maximum 87 reaches the generic error. The editor/import validator rejects those out-of-range limits; the ordinary dedicated Turbo refusal remains unchanged. |
| EXTENDED_HEAT | Gentle heat | Minimum profile temperature 68, above the mode's maximum 67. |
| COOL | Cool | Minimum profile temperature 63, above the mode's maximum 62. |
| DRY | Dry | Maximum profile temperature 39, below the mode's minimum 40. |
| WAIT | Pause | Returns before the temperature-intersection check; cannot reach this message. |

The core search found no other enum-derived user-facing wording beyond `PlanCompiler.temperatureFor` and `BackupValidator`'s temperature error. Stored observations and History CSV machine mode names were not converted into presentation labels.

## Required gate

```sh
./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

**Gate result: PASS**, exit code 0, **51.18 seconds** (Gradle: 50 seconds; 59 tasks, five executed and 54 up-to-date).

| Gate component | Exact result |
|---|---|
| Core JUnit 5 | **175 total / 175 passed / 0 failures / 0 errors / 0 skipped**, across 24 XML suites. |
| App Robolectric | **889 total / 851 passed / 0 failures / 0 errors / 38 skipped**, across 88 XML suites. |
| Debug assembly | PASS. |
| Debug lint | **0 errors, 14 warnings and 1 hint: 15 findings total. No new findings introduced by this patch.** |

The 38 skips are the existing opt-in captures: `ScreenshotCapture` 14, `U06NormalCaptureTest` 12 and `U06LargeTextCaptureTest` 12. This round adds six core cases and fourteen app cases. The complete suites executed successfully before assembly initially encountered five missing pinned runtime JARs; after restoring the dependency cache, the final exact gate reused those successful test outputs.

**Lint count precision and cache provenance:** the report separates 14 `Warning` findings from one `Hint`; it is not literally 15 severity-`Warning` findings. The warnings are `UseKtx` ×11, `OldTargetApi` ×1, `SpecifyJobSchedulerIdRange` ×1 and `ObsoleteSdkInt` ×1. The hint is `AutoboxingStateCreation`. Every reported source/configuration file is byte-identical to the integrated base.

Dependency-cache setup also produced nine tool/dependency-version advisories (`AndroidGradlePluginVersion` ×2 and `GradleDependency` ×7). A successful offline run with that warmed lint metadata reported **0 errors / 23 warnings / 1 hint**. That report was preserved. The final offline gate regenerated lint analysis after preserving the complete generated lint cache and results outside the checkout, yielding the 14-warning/one-hint count above. No rules were disabled, no suppressions added and no dependency versions changed. The warmed-metadata advisories remain valid observations; this patch does not claim to fix them.

Toolchain: Gradle 8.14.3, JDK 17, Android SDK 36 revision 2 and Build Tools 35.0.0. The workspace's missing SDK/JDK and Robolectric images were restored before testing; downloads were checked against their published checksums. The actual gate uses the pinned dependency cache offline. No build-script or dependency-version change is part of this patch.

## Minified test APK

The owner requested an unsigned minified assembly check followed by a separately signed test build using the existing environment-based signing configuration:

```sh
./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease
```

**Initial unsigned minified assembly: PASS.** The cache warm-up completed `minifyReleaseWithR8`, resource shrinking and `assembleRelease` successfully. The unsigned APK was 4,493,291 bytes. The separate offline `:app:assembleRelease` check also passed in **28.95 seconds** (Gradle: 28 seconds; 51 tasks, three executed and 48 up-to-date). After the final source commit, the unsigned APK is rebuilt so its embedded `META-INF/version-control-info.textproto` records that exact revision; the test-signed APK must match all of its non-signature ZIP entries. Signing then produces `nocturne-test-<shortsha>.apk` for that commit.

The test build uses a new throwaway `nocturne-test.jks` outside the repository, alias `nocturne-test`, RSA 2048, validity 3650 days and subject `CN=Nocturne Test`. Only this new key is supplied through `NOCTURNE_KEYSTORE`, `NOCTURNE_KEYSTORE_PASSWORD`, `NOCTURNE_KEY_ALIAS` and `NOCTURNE_KEY_PASSWORD`. The final delivery requires `apksigner verify --verbose --print-certs`, an exact match to the generated certificate's SHA-256 and an APK SHA-256. The private key is never committed or delivered. No production release is published.

R8/resource-shrinking assembly and signature verification establish build/signing results only; they do not establish minified runtime behaviour on a phone.

## Review, documentation and retained limits

A fresh read-only source/test/phone-guide review found no Critical, Important or Minor findings. The coordinator verified the final gate, the documentation links/dispositions and the retained physical NOT RUN results. Signed-APK identity is checked after this source commit; physical behaviour remains the owner's unexecuted acceptance work.

IR-31 is closed as the requested presentation fix. **KL-01 remains:** v1 did not record empty slots, and an absent old record cannot establish the true pre-existing state. Warning text cannot reconstruct that information. Unknown/other-device originals remain read-only under G9.

U-B4 is closed for the tested layout. **U-B5 remains open:** native wake-picker dial numeral readability still needs the owner's phone check, including the largest font setting. The existing user interface, confirmation ownership and truthful state distinctions retain their established design.

Current dispositions are in [completion review](nocturne-completion.md), [known limitations](known-limitations.md), [docs2 re-pin sections 4 and 13](docs2-repin.md) and [HANDOVER](../HANDOVER.md). Historical reports retain the findings and counts that applied at their own source pins.

# Nocturne final fixes implementation plan

> **For agentic workers:** use the test-first and verification workflows. The three independent regressions may be prepared in parallel; the coordinator runs Gradle serially and records each RED before its production change.

**Goal:** close A1/IR-31, A2 and U-B4, then deliver a separately signed test APK and an unexecuted phone checklist.

**Architecture:** retain the current Compose UI and command ownership. Surface the existing uncertainty flag, share core mode labels for user-facing errors, and constrain the wake-label fix to the affected Simple-view presentation.

**Tech stack:** Kotlin, Compose, Glance, Gradle 8.14.3, core JUnit 5 and app Robolectric JUnit 4 at SDK 34.

**Spec:** the owner's 25 October 2026 final-fixes assignment in this conversation. Base `c9befe66f0b78fff4fe9da15d217895ca4b48eee`; publish only `gpt/nocturne-final-fixes`.

## Global constraints

- No rebase, force-push, merge, release publication or production-signing change.
- Keep the existing Midnight Ink, Daylight, Simple view, five tabs, cards, curve and navigation.
- UK English; Android text in `res/values`; core messages remain Kotlin strings.
- Preserve pending, failed, unknown and confirmed states; no physical verification claims.
- Use GraphLifecycle for app fixtures that own Graph/repository state.

## Review focus

- A warning in the underlying list must not satisfy the restore-dialog assertion.
- Certain originals must remain unlabelled in all three places.
- The v1 empty-slot provenance limit remains after IR-31 closes.
- Core display labels must not alter enum names, wire codes or stored values.
- The compact wake-label fix must preserve ordinary-width/font presentation and leave U-B5 open.

## A1 — uncertain originals

- [x] Add `UncertainOriginalsSettingsTest` for uncertain/certain matching rows, From backup rows and the restore dialogue; capture RED.
- [x] Add the `settings_original_uncertain` resource and conditional lines in `SettingsScreen.kt`; capture GREEN.
- [x] Update the completion record and the specified open-limitations lists, retaining missing v1 empty-slot provenance.

## A2 — core mode labels

- [x] Add `PlanErrorModeNameTest` covering every mode reachable through the generic temperature refusal and `BackupModeNameTest` for imported-profile errors; capture RED.
- [x] Add the exhaustive core mode display label, use it in the two user-text paths and preserve machine identifiers; capture GREEN.

## U-B4 — Simple wake-label wrapping

- [x] Reproduce the 320 dp / 200% Simple-view title with real Compose text layout; capture RED.
- [x] Apply a narrowly scoped text/resource change; prove complete words and unchanged ordinary presentation, then capture GREEN.
- [x] Mark U-B4 fixed in current backlog documentation; retain U-B5 device acceptance.

## Integration and delivery

- [x] Run the owner's exact offline four-task gate; record XML totals and lint errors/warnings.
- [x] Run minified `:app:assembleRelease` and confirm R8/resource shrinking and a separate offline assembly pass.
- [ ] Rebuild from the final commit with a new throwaway test keystore outside the repository.
- [ ] Verify the APK with `apksigner`, record its SHA-256 and test-certificate SHA-256, and bind the filename to the source commit.
- [x] Write `docs/review/phone-test-guide.md` with blank PASS/FAIL/NOTES cells and explicit NOT RUN status.
- [x] Review the final source/test/documentation diff.
- [ ] Commit, publish only the authorised branch and verify its remote SHA.

The remaining delivery steps intentionally follow the final source commit. Their final SHA, signature, APK hash and remote-ref results are recorded in the delivery handover; unchecked items here are the pre-commit checkpoint, not physical acceptance results.

# Nocturne redesign remediation register

Starting branch: `gpt/nocturne-redesign-final-review`.
Starting SHA: `e4be250fb9f340800fa89aea8e358d5bddbf5054` (remote fetched and verified).
Working branch: `gpt/nocturne-redesign-remediation`.

This is an active remediation record. It does not claim a passing gate or independent sign-off.

## Baseline and environment

| ID | Severity/type | Feature/file | Reproduction | Cause | Action/commit | Verification |
|---|---|---|---|---|---|---|
| ENV-01 | Environment | Local Gradle/Git execution | Initial Git fetch and Gradle bootstrap | Restricted process cannot open the sockets needed by Git and Gradle workers | Authorised commands run with reviewed socket access; repository settings unchanged | `git fetch origin --prune` exited 0; remote start remains e4be250 |
| ENV-02 | Environment | Copied Gradle transform cache | Exact gate before tests: unreadable transform metadata at `7fddc756b2f9c6a981b6731e7e469949` | A concurrent environment cache-copy process modified Gradle transform files during execution | Use an isolated `/tmp` Gradle cache outside that synchronised workspace | Fresh baseline reached all 1,708 app cases |

## Assumptions

- The approved design supersedes pre-redesign control placement. Obsolete selectors move to current entry points; behavioural and accessibility coverage remains.
- The owner's explicit whole-app ownership and work-alone instruction supersedes older Claude-only files and skill suggestions to delegate.
- Physical BedJet, Samsung, installed widgets, device battery behaviour and actual TalkBack remain untested unless subsequently performed.

## Findings and verification

Baseline execution and failure classification are in progress. No failure or warning has been accepted as resolved merely because a focused retry passed.

### ENV-03 — verification process memory (high; environment)

The clean baseline compiled successfully, but the Gradle daemon disappeared as the test workers started. The 8 GiB cgroup recorded two OOM kills; the separate Kotlin daemon retained 2.3 GiB with a 4 GiB heap, alongside Gradle and three test workers. No test result is inferred from this aborted run. The dedicated scratch Gradle user home now limits the Gradle heap to 2 GiB and runs Kotlin compilation in process; the requested command and all test cases remain unchanged. Evidence: `baseline-clean-cache.log` and its exit-1 receipt.

## Reproduced source baseline

The exact requested command at the starting SHA exited **1**: core **313/313** passed; app **1,708 total, 1,460 passed, 207 failed, 0 errors, 41 opt-in capture skips**. Lint was run separately because the app test failure stopped the gate; it exited **0** with **22 warnings and 4 hints**, with the existing broad resource suppression still present. The complete list of 207 failing cases is [baseline-failures.csv](evidence/remediation/baseline-failures.csv). Each case remains open until its current behaviour is verified; historical counts are not used as the baseline.

The first migrated test compile exposed a helper typed as `ComposeContentTestRule`, incompatible with `createEmptyComposeRule`. It was corrected to the shared `ComposeTestRule`; this compile failure is not regression RED evidence.

### DEP-01 — API 31/33 Robolectric cache (high; test infrastructure)

Six legacy health containment/security cases could not start because their instrumented Android dependencies were missing. The official `12-robolectric-7732740-i7` and `13-robolectric-9030017-i7` JARs and POMs were downloaded and verified against Maven Central SHA-512 files, then installed in the local Maven cache. Neither SDK coverage nor cases were removed. A reproducible preparation script and the successful test counts will be added with the build remediation.

## Regression RED before production changes

All runs in this section used the original production sources at e4be250 with migrated selectors or new regression tests. Receipts include the working source-tree digest where available. Test harness changes dismiss the actual approved floating feedback surface before tapping a covered control; they neither sleep nor change production feedback behaviour. The `DemoImportPreviewInvalidationTest` is untouched and all four of its baseline cases passed.

| ID | Severity | Feature / affected source | Reproduction and root cause | Intended remediation | RED evidence |
|---|---|---|---|---|---|
| UI-01 | High | Tonight state / TonightScreen, TonightParts | Saved unknown or manual night was announced as running; disconnected quick tiles lacked disabled semantics | Derive running name and enabled state from confirmed owner/device evidence | RemediationStateSafetyTest, focused-red-compiled (3 failed) |
| UI-02 | Medium | Quick command feedback / TonightParts, AppViewModel | UI can miss brief busy emissions; unrelated 15-second fallback retains Sending after completion, and can expire while queued | Clear pending by identity when its own submitted operation finishes; retain existing command owner | QuickPendingStateTest completedPresetClearsSending, pending-red (1 failed); ActiveIndicatorTest (6 failed) |
| UI-03 | High | Editor / EditorParts, ProfileEditor | Two-neighbour drag reorders during the gesture and loses the intended target; new numeric steppers lost range/unit semantics | Commit the measured row displacement once on release; restore adjustable semantics and full spoken mode names | ProfileEditorInteractionTest and AccessibilitySemanticsTest baseline / focused-red-compiled |
| UI-04 | High | Large text / EditorParts, ProfileEditor, ProfilesScreen | Fixed one-row layouts clip modes, middle temperature, profile actions; Add step body has no scrolling | Adapt columns and stack value controls at large text, allow full labels and sheet scrolling | EditorLargeTextReachabilityTest, editor-large-red (6 failed); ProfilesEditHitTest focused-red-compiled |
| UI-05 | High | Rendered contrast / TonightParts, EditorParts | Overcast caption 3.185:1; small Start text 3.318:1; lifted editor row 1.373:1 | Use legible palette ink and approved button typography; use a light lifted surface in Overcast | RedesignTextContrastTest focused-red-compiled and focused-red-harness, actual rendered text/layout and native View.draw |
| UI-06 | Medium | Navigation / MainActivity | Setup request replayed on activity recreation after being consumed | Save the handled request counter alongside the navigation state | P8ConsumedSettingsRequestTest setup recreation, focused-red-harness |
| UI-07 | High | Remote / RemoteScreen | Reconnect/Bluetooth presentation removed from Remote | Restore existing status and Bluetooth command entry points | BluetoothBannerTest / RemoteReconnectPresentationTest original baseline |
| UI-08 | Medium | Profiles preview / ProfilesScreen, EditorParts | Compact preview removed full spoken wake/fan path; short-test profile identity absent from compact card | Preserve visual design and restore complete accessible description and test identity | TonightAcceptanceTest, QuietMiddleDescriptionTest, P8MiddleSummaryTest, ShortTestTonightIntegrationTest baseline / focused-red-compiled |

These rows are not GREEN claims. The production fixes and their final commits/results follow in subsequent entries. Test migration retains behaviour (saved drafts, alarm fallback, target capture, wake edits, limits, confirmation and persistence) through the current four tabs, Tonight Change and Remote link. Old section-collapse/reorder and persistent global banner expectations are obsolete under the approved compact design.

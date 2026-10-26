# UX self-review pass 1 — integration and verification

Base: `367968bddc390e5b2bb12876fc1662ffa07e47c3`. The preserved implementation checkpoint is `0eddd188540df9d918c9c2e69701ae6e72f9a918`. This is a self-review, not independent sign-off or device acceptance.

## Completed gate evidence

`ux-full-gate-1-restored` completed with exit 1 after 1,040.86 seconds. Core: 201 tests, 201 passed, no failures, errors or skips. App: 1,180 tests, 1,105 passed, 37 failures, no errors, 38 expected opt-in capture skips. Debug assembly completed. Lint and release assembly did not complete in that invocation. The preceding interrupted invocation has no retained completion evidence and is not counted.

The 37 failures were examined individually. Five unchanged History revision tests exposed real input interception by the newly interactive shared curve. The fix makes phase interaction opt-in on Tonight and the editor; History and Profiles retain passive previews. The other failures involved obsolete action/header selectors, required positive Bluetooth fixtures, the new light confirmation, or the richer contextual warning. Their assertions remain tied to the intended behaviour rather than being removed. UNKNOWN/manual Stop remains intentionally available behind its guard; a Stop button does not assert confirmation.

`ux-review-pass1-red` stopped before executing its selected regressions: lint found 1 error, 25 warnings and 2 hints, and test compilation found an invalid explicit import. It is lint RED, not behavioural RED. The import was corrected. New behavioural regressions run separately before their corresponding production fixes.

## Integration findings

| ID | Defect or reproduction to complete | Regression and intended boundary |
| --- | --- | --- |
| INT-01 | Settings describes every Boost, including the Turbo-only ten-minute option, as Turbo followed by Heat. | `SettingsBoostCopyTest.everyBoostLengthDescribesItsActualTurboAndHeatDurations` checks all five actual durations on Settings. Reuse the already tested shared description. |
| INT-02 | Generic Boost shortcut/runtime/preview labels omit the actual mode, and quiet-notification help still calls Stop night an End button. | `PhoneControlCopyTest.nonDurationBoostEntriesStillNameTheTurboMode` reads the declared shortcut label; the Stop copy test also checks the help. Resource-only corrections preserve command routing. |
| INT-03 | The reminder's start preview reads the core diagnostic `refusalReason` directly, exposing `OUTSIDE_WINDOW` instead of the new Android explanation. | `NightDurationResourcesTest.reminderPreviewExplainsTheDurationInsteadOfShowingItsDiagnosticCode` opens the real activity and retains no-positive-start/no-write assertions. Use the existing typed resource mapper. |
| INT-04 | New lint findings: a StateFlow read inside Glance composition; intentional private style override without metadata; unused resources; preview root overdraw; plural candidates; one preferences KTX suggestion and one boxed-float hint. | Actual `lintDebug` output is RED. Observe the flow, use the documented override metadata and the preview style, remove unreferenced copy, retain the previews' surface using a rounded drawable, use plurals/KTX/float state. No ignore, baseline or disabled test is introduced. |
| INT-05 | The historical “Tonight lower” screenshot still scrolls to Off, which U1 moved to the top Quick section. | Add an assertion that the actual lower Start action is displayed before capturing, run it against the old scroll target, then fix only that target. The existing 28 baselines remain immutable; the gate requires all 102 images and their metadata. |

The UI reviewer records guard/editor truth, stale editor confirmation, the tap-to-save Bluetooth gap and the omitted Release relocation in `ux-self-review-pass1-ui.md`. The runtime reviewer records retry warning context and idle local wake saving in `ux-self-review-pass1-alarm.md`. The widget/runtime reviewer records native layout, lifecycle, origin/receipt checks and lint corrections in `ux-self-review-pass1-widget-runtime.md`. Each report distinguishes an executed failure from a source hypothesis.

## Complete-file review coverage

The root reviewed the base diff and complete contents of `App.kt`, `data/Repository.kt`, `platform/HistoryCsv.kt`, `platform/PhoneHandover.kt`, `platform/SleepOverlay.kt`, the main manifest, `IdleProcessTimersTest`, `IdleHandoverTest`, `ScreenshotCapture`, the changed control resources and the design/implementation/evidence documents. The review includes unchanged surrounding code. New test-run exclusions preserve diagnostic records while keeping them out of ordinary sleep views, CSV, Health overlays, check-ins and profile suggestions. No Health writer was introduced. Durable repository publication and first-original protection remain intact.

The release reviewer inspected all four changed workflow/helper files and the full 50-model serialization fixture inventory; `release_manifest.py` was also reviewed by root. The new private widget activity has an exact manifest entry. Screenshot inventory/metadata validation was strengthened, and no historical PNG was replaced. The APK serialization check is structural DEX/mapping inspection; it is not execution of the APK on a device.

The UI report covers all 22 changed UI production files and related resources/tests. The alarm/core report covers 47 files and dependencies. The widget/runtime report covers every changed widget file, service/Bluetooth/notification paths and related tests. A later pass must re-read the entire base diff and all touched files after fixes; this first-pass register is not a clean-review claim.

## Scope decisions

- Keep owner/service/UI budgets at 90/100/115 seconds. Fire-and-forget delivery has separately bounded undelivered and owner phases; do not describe their sum as a proved 115-second total.
- Keep normal nights at one to eighteen hours, with explicit reasons and a separate confirmed five-minute Settings equipment test. No phone alarm is set or modified by that test.
- Keep R4 Health writing and R5 backup change preview as explicitly Missing in the requested code cross-check. They are new product capabilities, not defects to introduce during this loop.
- Do not label physical receipt, Samsung, launcher, TalkBack, overnight, reboot or force-stop acceptance as verified. All execution evidence here is unit/Robolectric or build/static inspection.

## Subsequent execution

RED, repair commits, focused GREEN, full-gate counts and the next review pass are appended after they complete. Fresh selected suite results are used for focused counts; stale XML from earlier invocations is never summed as a new run.

### Preserved first-review checkpoint and corrected P1

`b1c3a813fd115f2e9345c5e1fcc7888ba167ef77` preserves the first-review repairs and the owner's corrected P1 regressions. It is an explicitly incomplete checkpoint, not a full-gate success. The earlier “Turbo 15m” example is superseded by the actual sequence: Turbo 10m followed by Heat 5m. `BoostSequenceLayoutTest` exercises every configured duration on both app screens in all four looks at 320 dp / 200% text. The widget uses a compact both-mode label and a full duration description.

The first focused GREEN/sequence-RED invocation, `ux-review-pass1-green-sequence-red`, ran 85 cases: 69 passed, 16 failed, zero errors/skips. All 17 new guard/editor/radio/menu cases and the local Bluetooth-off wake-save, Settings Boost and typed duration-refusal repairs passed. The genuine P7 retry warning mismatch was reproduced only after correcting its null-creator fixture: the attempted fallback was 07:00 but the warning named the superseded 08:30 alarm. The subsequent owner repair propagates the actual prepared wake through the request-specific execution and durable retry context.

### Integration of the later Temporary feature

The owner's additional feature requirement is integrated from fixes2 `655993d2ea3d5021a2fab75ecf7db5f77d699f7e`, descending from the same original base. No pushed commit was rebased. The merge preserves the stronger exact confirmed-Off journal repair, Bluetooth admission, marked five-minute test, typed refusal resources and current UX layout. The original branch base remains `367968bddc390e5b2bb12876fc1662ffa07e47c3`.

`ux-temporary-merge-red` ran the full core suite: **240 passed, zero failures/errors/skips**, including all 34 `TemporaryNightTest` cases and both new short-upload/clock-sync boundary cases. The app variants stopped at a missing exhaustive Temporary branch in `ProfileEditor`; the mechanical null-source branch was added. This invocation supplies no app behavioural RED.

`ux-temporary-merge-behaviour-red` then completed in **595.10 seconds**, exit 1:

- App: **295 tests, 274 passed, 21 failed, zero errors/skips**, across 25 fresh selected suites.
- The P1 matrix passed: `BoostSequenceLayoutTest` 8, `WidgetResponsiveLayoutTest` 28, `BoostCommandMappingTest` 1 and `PhoneControlCopyTest` 5 — **42/42**. `SettingsBoostCopyTest` also passed its complete duration loop.
- F1/F2/F3 controls passed: `ManualCancellationSettlementTest` 13, `RestoreOriginalCaptureTest` 7, `UnchangedAlarmObservationTest` 18 and `RuntimeFixRoundTest` 35.
- P7 `RetimeNotificationTest` passed **64/64**, including the actual-prepared-wake regression. `BluetoothOffTest` passed 14/14.
- Temporary owner/boundary/service/native cases passed 24 + 9 + 5 + 6; the four existing large-text Compose Temporary cases passed.
- Eleven new UI failures establish false running/wake claims (eight), an imported Turbo duration inconsistent with its ten-minute control, a fabricated Temporary source-phase index, and an empty idle More menu.
- Eight real P4 widget failures establish the omitted Temporary choice/captured-preset route; one specifically finds equal PendingIntent identities for different rendered presets. Existing nine confirmation controls pass.
- Two incoming dispatch fixtures retain obsolete Boost and three-argument guard selectors. Their command/effect assertions are retained while aligning the selectors with the current contract.
- Release serialization: **2 tests, 1 passed and 1 failed**, with a valid missing-`ActiveNight.temporary` field assertion. The fixture repair adds the non-default Temporary value/model and all three new enum alternatives, retaining default/older cases: **51 models / 112 cases**, awaiting GREEN.

Logs, source checkpoint, fresh XML and selected-suite summaries are retained under `/tmp/nocturne-gpt11-evidence`. Two thread snapshots during the longer run showed progress to different test classes; no worker was stopped and no deadlock or passing count was invented from those snapshots.

The incoming Temporary UI/core and the actual responsive-widget integration have separate complete-file review notes. A further concrete R38 source finding concerns retained legacy widget intents: `PlatformActionActivity` can bypass a tracked Off/Stop confirmation and generic Off can ignore a captured old identity. Six actual-activity regressions have been added before its production fix. Generic emergency-Off owner semantics remain unchanged. Native 320 dp / 200% dialog geometry is also being tested before any geometry repair. These remaining regressions and the full integrated gate are still required.

### Native/retained-intent regression run

`ux-temporary-ui-native-red` completed in **252.40 seconds**, exit 1: app **73 tests, 50 passed, 23 failed, zero errors/skips**. Release serialization passed **2/2** with the expanded **51-model / 112-case** inventory. The corrected editor source selection, empty More behaviour and all ten dispatch cases passed. Widget feedback/freshness passed **12 + 2** cases; the new confirmation route passed **15/17**, with two ending/result cases still under investigation.

Five of six retained-platform regressions give behavioural RED: opening tracked Off/Stop connects without confirmation, and an old queued Off can affect a newer night. The existing queued End identity control passes. Native geometry reproduces inaccessible/clipped choices at 320 dp / 200%; two other native checks stop at a missing fixture selector and do not establish contrast RED. Four native truth tests now pass their original wording assertions, but their final full-device-state assertion exposes the fixture's automatic Demo sensor tick; a deterministic link will retain that full-state check. Seven Compose tests stop at Robolectric's legacy bitmap renderer and need native graphics before new Stop-label/imported-ending assertions can supply valid RED. These failures are not counted as proof of those new behaviours.

`ScreenshotCapture.dark` passes the repaired lower-section anchor. `featureViews` correctly fails to find an idle More menu with all its features disabled. Its setup now enables the existing Lull handover option so the required handover capture remains meaningful; no production menu or capture assertion is weakened.

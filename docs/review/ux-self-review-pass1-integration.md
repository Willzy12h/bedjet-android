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

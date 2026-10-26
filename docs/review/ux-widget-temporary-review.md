# P4 widget Temporary integration — implementation and verification ledger

Prepared against the merged 655993d feature plus the UX widget route. The initial eight real-route regressions were observed RED in `ux-temporary-merge-behaviour-red`, then the shared native dialog and captured preset transport were integrated. The fresh `ux-temporary-native-fixture-red-2` XML passes 17/17 confirmation cases (37.414s), 12/12 command-feedback cases and 2/2 freshness cases, with zero errors/skips. Subsequent focused cycles verify native geometry/contrast, day/night launcher previews and idle preset capture. The diagnostic `ux-timed-policy-red-capture-green` result was NativeAction 7/7, IdleQuickCapture 2/2, NativeLayout 7/7 and WidgetProvider 4/4, alongside eight deliberate role/cap RED tests. The subsequent `ux-final-capture-and-timed-policy-green` passed all **38/38**, zero failures/errors/skips, in **204.16s**, including those four suites, StopTrackingCapture 4/4, RecoveryNotificationActions 2/2, ImportedTemporaryDuration 3/3, ImportedPresetRoleSettings 4/4 and TimedPresetLimit 5/5. This is focused verification; the separately recorded complete gate is not inferred from it.

## Observed integration gap

The incoming feature added Temporary choices to the former `PlatformActionActivity` widget route and to ShortcutActivity. P4's actual rendered Quick/Single widgets use `WidgetConfirmationActivity`, which still showed only replacement confirmation. Their parameters also omitted `CommandRequest.actionPreset`. The initial run was 17 cases: all nine existing guards passed; seven new cases stopped at the missing Temporary choice and the eighth found identical Intent URIs for different presets.

## Captured request route

1. At widget rendering, reuse `captureQuickAction` to obtain the original request's exact preset; copy that preset into the original command request without setting `replacementConfirmed`. Preserve the rendered target, night identity, profile and WIDGET origin. Do not duplicate the incoming command-to-preset/cap mapping.
2. Serialize that optional preset in Glance action parameters and the private confirmation Bundle using `BackupCodec`/`ActionPreset.serializer()`, with a 1,024-character decode bound. Direct TEMPORARY parameters require a valid preset, identity and target. The private Quick-choice transport deliberately retains imported presets so the shared helper can derive its capped Temporary choice; the owner validates the selected command before writing. The preset is part of confirmation Intent data, preventing a later preset from replacing an older reviewed PendingIntent.
3. In `WidgetConfirmationActivity`, reuse `captureQuickAction` and `showTemporaryActionDialog` for the eligible captured Quick commands. Do not supply `openTonight`; cancellation finishes the overlay, and acceptance submits the chosen request through `WidgetDelivery`. Preserve the existing lifecycle handoff: finish after submission, not before pending feedback is stored or the application-owned job exists.
4. Keep OFF, END_NIGHT, STOP_TRACKING and START_NIGHT on their existing guards. STOP_TRACKING remains `hardware=false`; OFF retains the under-owner identity check. Keep the excluded/translucent manifest route and explicit Open app button behavior.
5. The shared dialog accepts the widget's existing dark/light context; Shortcut uses the same Settings/current-system-mode palette policy. The transport integration initially made no dimension changes. The subsequent observed native RED/fix/GREEN cycle below verifies actual widget action targets of 56dp, Shortcut targets of 48dp and body/button text of 16sp at 200% scaling, with complete scroll-reachable text in the actual window.
6. Retain the incoming caps: direct Turbo 10 minutes, configurable Boost 30, other supported temporary modes 120. Duration edits change only the captured preset's minutes. The owner still prepares/validates under its mutex and requires fresh receipt evidence.

Mechanical compatibility already applied with root authorization: WidgetPresentation maps Block.TEMPORARY to the existing Temporary resource; TemporaryActions uses shared boostDescription in its two former runtime_boost branches. The P1 helper shows each numeric mode duration correctly and uses `Turbo→Heat` with complete exact descriptions for fixed widget labels.

| Route boundary | Source location | Preserved fact |
| --- | --- | --- |
| Render capture | `widget/WidgetPresentation.kt:10` | Shared `captureQuickAction` captures the original guarded End preset; no replacement approval is added. Idle entries retain shared no-dialog semantics. |
| Shared choice derivation | `ui/TemporaryActions.kt:25` | Only the Temporary copy is duration-capped; End retains the original requested preset. |
| Parameter transport | `widget/WidgetActions.kt:36` and `:46` | Preset, rendered target and night/revision survive serialization; decoded origin is WIDGET. |
| Private native overlay | `widget/WidgetConfirmationActivity.kt:29` and `:50` | Restores the preset and invokes the shared dialog with no Open Tonight callback. |
| Intent identity | `widget/WidgetConfirmationActivity.kt:89` | Widget, command, profile, target, night/revision and encoded preset distinguish PendingIntents. |
| Captured wording | `widget/WidgetActions.kt:180`; `widget/WidgetPresentation.kt:162` | Captured Boost/Turbo labels and descriptions use captured minutes. Named Warm/Cool entries remain unchanged. |
| Owner delivery | `widget/WidgetActions.kt:195` and `:224` | Widget feedback is stamped before handoff; WIDGET/SubmissionTarget reach the existing service/owner; Stop tracking remains local. |

## Real-route regression coverage added before adaptation

`WidgetConfirmationTest` now has 17 cases: the original 9 guard/race cases are unchanged; 8 new cases use native buttons from actual composed RemoteViews, the actual confirmation Intent/activity, real graph/owner/demo writes and per-widget feedback.

- Both Quick and Single widgets offer Temporary/Stop night (historical test identifiers call the latter End); cancellation writes nothing and launches no main Activity.
- Direct Turbo and Boost expose 10/30 minute SeekBar caps.
- A changed preset produces a distinct confirmation Intent identity.
- Quick and Single keep the preset captured before the overlay opens, even with another settings edit while open; confirmed Temporary keeps the original night/wake and reports its absolute Temporary phase span.
- End uses the rendered preset after settings change.
- A queued Temporary approval cannot replace a newer night revision.
- A target changed before opening cannot receive the old End action (real permission/adapter preconditions enabled so an OFF adapter cannot hide the target check).

In `ux-temporary-ui-native-red`, six of the eight new cases and all nine prior guards pass. Quick/Single confirmed Temporary keeps the captured preset, original night/wake and absolute phase span; caps, distinct URI, cancellation, and the queued newer-night refusal also pass. The two outstanding failures are investigated as follows:

- `aChangedTargetBeforeTheWidgetChoiceOpensCannotReceiveItsEndAction`: the fixture enables Bluetooth and switches to a real target, but never delivers the service Intent recorded by Robolectric. `BedJetService.runOwned` therefore waits at the actual handoff and widget state remains PENDING. The fixture now delivers that recorded Intent through a real `BedJetService` controller, following `RuntimeServiceTest`. FAILED, zero writes, unchanged original night and no main Activity assertions remain.
- `widgetEndUsesTheRenderedPresetAfterSettingsChange`: the waiter accepted CONFIRMED while the original night was unchanged and main work was still queued. It previously accepted any stored state. The waiter now captures the feedback request UUID before opening and requires a new request UUID as well as the expected result. The End row/positive label are asserted before approval. Captured Dry mode, 15-minute timer, ended night and no main Activity assertions remain. No widget production correction was made for this failure.

Both fixture corrections are confirmed by the subsequent 17/17 GREEN in `ux-temporary-native-fixture-red-2`.

## Native window diagnostic boundary

That same run's four native layout/contrast tests failed. The widget text bounds reused the rectangle passed to `View.requestRectangleOnScreen`; Android mutates that rectangle while walking its ancestors, so the test added the vertical position twice. The corrected test passes a copy and retains the original local rectangle for its screen-space assertion. Every character, line, scroll reachability, containment, no-ellipsis and target assertion is retained.

The independent contrast result is valid: Shortcut inherited its translucent Activity theme and produced black choice/positive/Cancel text on a black dialog surface (1.0:1). Both widget palettes passed their sampled color checks. The shared helper now supplies Shortcut's default native dialog with the same Settings/current-system-mode-aware Material palette as widgets. Captured ending-action eligibility also reaches the native refusal, enablement and click guard.

`ux-temporary-reviewed-green-native-red` ran five native cases: the contrast check passes across both Shortcut and widget palettes; four geometry/minimum cases fail, with zero errors/skips. The independent measurements establish an 18dp duration touch surface, a 48dp widget Stop row, 55dp widget buttons, and 52px widget body/button text where the Android 14 conversion of 16sp at 200% requires 56px. Actual 640×1280 window captures also show the scrolling body consuming the available height and leaving the native action panel at zero height. All 12 captures, their SHA256 manifest and the native XML are preserved under that run's `native-dialog-review` directory, outside the strict screenshot inventory.

The subsequent scoped repair gives the actual SeekBar a 48dp/56dp layout height, retains native choice views through a delegating adapter with the required minimum, sets body/button text to 16sp, and lets only the custom body panel shrink with layout weight. The panel is located through the actual list/scroll shared ancestry; there are no private resource lookups, reflected fields or hardcoded window dimensions. Native list selection, captured requests, cancellation, button callbacks and all geometry assertions remain.

`ux-native-and-recovery-green` confirms this repair: 76/76 selected tests, zero failures/errors/skips; fresh NativeLayout XML is 5/5 (21.416s), and WidgetConfirmation remains 17/17. All twelve archived Shortcut/Quick/Single × DARK/LIGHT × Temporary/Stop windows were visually reviewed. Choices and action buttons remain within the real window; each body line and the duration touch surface can be revealed by the production scroll container. No enlarged test window is used.

The subsequent presentation review found two separate seams outside that Temporary-window repair. In `ux-final-presentation-red`, Shortcut fallback contrast passes, but its idle Boost title and some action labels lose text at 200%; both actual launcher preview layouts have 1.074:1 glyph contrast under Android night configuration. The scoped fixes retain fallback wording/owner behavior and make the preview background follow its own native theme.

`ux-last-capture-boundaries-red` confirms those presentation fixes: NativeLayout 7/7 (43.230s), WidgetProvider 4/4 (0.262s), zero errors/skips. The actual preview glyph ratio is now 4.609:1 in both day/night configurations, with 11.943:1 button-label contrast. All six new fallback windows and four preview captures were visually reviewed; they remain separate diagnostics, not screenshot-baseline replacements. The full 29-case batch is not GREEN (23 pass / 6 fail): this partition's new idle Shortcut capture regression correctly finds Turbo10 + Heat20 written after a 15-minute approval while Settings changes to30. That separate capture defect is resolved in the next cycle below. The full integrated gate remains pending throughout.

## Idle approval capture: actual service RED and GREEN

`TemporaryNativeActionTest.idleShortcutBoostRunsTheFifteenMinutesReviewedBeforeSettingsChanged` opens the actual idle Shortcut dialog with a 15-minute Boost, checks its Turbo10 + Heat5 preview, changes Settings to30 and cancels. It requires no service, no physical-state change and no MainActivity. It then repeats the preview/edit sequence, approves the native button and delivers the recorded Intent to the real BedJetService. After a SHORTCUT-attributed CONFIRMED outcome, the test reads the controller's stored programme and requires exactly Turbo10 + Heat5, with Settings still30 and no tracked night or MainActivity. Its initial failure was the actual uploaded Turbo10 + Heat20, not a timeout or label-only assertion.

The fix reuses the shared capture once before Shortcut chooses its tracked-night or idle route (`ui/ShortcutActivity.kt:38`). The fallback description reads the reviewed request's preset (`:68`) and approval submits that same request (`:72`). `ui/TemporaryActions.kt:48` now retains the original preset on idle captures as well as tracked-night captures. Target, night/revision and origin remain attached; no second command-to-preset mapping or later Settings recapture is added.

Fresh `ux-timed-policy-red-capture-green` XML verifies the pair: NativeAction **7/7** (18.106s, including the actual 10+5 upload case at 3.241s), IdleQuickCapture **2/2** (9.190s), NativeLayout **7/7** (39.480s) and WidgetProvider **4/4** (0.223s), all with zero failures/errors/skips. The app pair covers queued Boost capture and rejection of an unsupported imported idle Warm preset. Local StopTrackingCapture **4/4** and RecoveryNotificationActions **2/2** also pass. The whole 200.66s batch remains **38 cases / 30 pass / 8 deliberate role/cap failures**, so these focused results are not presented as a passing integration gate.

## P1 evidence and font-fixture correction

In immutable `ux-review-pass1-green-sequence-red`, the four requested P1 classes ran 42 cases: 28 passed and 14 failed. Thirteen failures establish the absent long app labels, absent both-mode widget labels and absent native Shortcut sequence preview. One new widget test instead stopped early on `‹ must retain 16sp type`; that is not label RED.

The early font assertion divided pixels by scaledDensity. Android 14 applies nonlinear scaling; the official [Android 14 guidance](https://developer.android.com/about/versions/14/features#non-linear-font-scaling) and [TypedValue API](https://developer.android.com/reference/android/util/TypedValue#applyDimension(int,float,android.util.DisplayMetrics)) require the platform SP conversion. The test now checks actual button textSize against `TypedValue.applyDimension(COMPLEX_UNIT_SP, 16f, button.resources.displayMetrics)` and logs both pixel values on its first render. Every character/line, no-ellipsis, 56dp touch, containment, overlap and control-budget assertion remains.

The fresh P1 XML in `ux-temporary-merge-behaviour-red` is GREEN: `BoostSequenceLayoutTest` 8/8, `WidgetResponsiveLayoutTest` 28/28, `PhoneControlCopyTest` 5/5, `BoostCommandMappingTest` 1/1 — exactly 42/42, zero errors/skips. The widget log records actual 28px and required 16sp-converted 28px at 200% (scaledDensity 2.0).

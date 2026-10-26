# P4 widget Temporary integration — implementation and verification ledger

Prepared against the merged 655993d feature plus the UX widget route. The initial eight real-route regressions were observed RED in `ux-temporary-merge-behaviour-red`, then the shared native dialog and captured preset transport were integrated. The latest `ux-temporary-ui-native-red` run passes 15/17 confirmation cases, 12/12 command-feedback cases and 2/2 freshness cases. The two confirmation fixture corrections below await a rerun; complete GREEN is not claimed.

## Observed integration gap

The incoming feature added Temporary choices to the former `PlatformActionActivity` widget route and to ShortcutActivity. P4's actual rendered Quick/Single widgets use `WidgetConfirmationActivity`, which still showed only replacement confirmation. Their parameters also omitted `CommandRequest.actionPreset`. The initial run was 17 cases: all nine existing guards passed; seven new cases stopped at the missing Temporary choice and the eighth found identical Intent URIs for different presets.

## Captured request route

1. At widget rendering, reuse `captureQuickAction` to obtain the original request's exact preset; copy that preset into the original command request without setting `replacementConfirmed`. Preserve the rendered target, night identity, profile and WIDGET origin. Do not duplicate the incoming command-to-preset/cap mapping.
2. Serialize that optional preset in Glance action parameters and the private confirmation Bundle using `BackupCodec`/`ActionPreset.serializer()`, with a 1,024-character decode bound. Direct TEMPORARY parameters require a valid preset, identity and target. The private Quick-choice transport deliberately retains imported presets so the shared helper can derive its capped Temporary choice; the owner validates the selected command before writing. The preset is part of confirmation Intent data, preventing a later preset from replacing an older reviewed PendingIntent.
3. In `WidgetConfirmationActivity`, reuse `captureQuickAction` and `showTemporaryActionDialog` for the eligible captured Quick commands. Do not supply `openTonight`; cancellation finishes the overlay, and acceptance submits the chosen request through `WidgetDelivery`. Preserve the existing lifecycle handoff: finish after submission, not before pending feedback is stored or the application-owned job exists.
4. Keep OFF, END_NIGHT, STOP_TRACKING and START_NIGHT on their existing guards. STOP_TRACKING remains `hardware=false`; OFF retains the under-owner identity check. Keep the excluded/translucent manifest route and explicit Open app button behavior.
5. The shared dialog accepts the widget's existing dark/light context. Native geometry remains a separate observed-RED/fix cycle owned by the UI reviewer; this transport integration made no dimension changes. Required widget action targets remain 56dp with 16sp text.
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

- Both Quick and Single widgets offer Temporary/End; cancellation writes nothing and launches no main Activity.
- Direct Turbo and Boost expose 10/30 minute SeekBar caps.
- A changed preset produces a distinct confirmation Intent identity.
- Quick and Single keep the preset captured before the overlay opens, even with another settings edit while open; confirmed Temporary keeps the original night/wake and reports its absolute Temporary phase span.
- End uses the rendered preset after settings change.
- A queued Temporary approval cannot replace a newer night revision.
- A target changed before opening cannot receive the old End action (real permission/adapter preconditions enabled so an OFF adapter cannot hide the target check).

In `ux-temporary-ui-native-red`, six of the eight new cases and all nine prior guards pass. Quick/Single confirmed Temporary keeps the captured preset, original night/wake and absolute phase span; caps, distinct URI, cancellation, and the queued newer-night refusal also pass. The two outstanding failures are investigated as follows:

- `aChangedTargetBeforeTheWidgetChoiceOpensCannotReceiveItsEndAction`: the fixture enables Bluetooth and switches to a real target, but never delivers the service Intent recorded by Robolectric. `BedJetService.runOwned` therefore waits at the actual handoff and widget state remains PENDING. The fixture now delivers that recorded Intent through a real `BedJetService` controller, following `RuntimeServiceTest`. FAILED, zero writes, unchanged original night and no main Activity assertions remain.
- `widgetEndUsesTheRenderedPresetAfterSettingsChange`: the waiter accepted CONFIRMED while the original night was unchanged and main work was still queued. It previously accepted any stored state. The waiter now captures the feedback request UUID before opening and requires a new request UUID as well as the expected result. The End row/positive label are asserted before approval. Captured Dry mode, 15-minute timer, ended night and no main Activity assertions remain. This fixture diagnosis will be confirmed or rejected by the rerun; no widget production correction was made for this failure.

Both fixture changes are ready for the parent's next batch. No GREEN claim is based on static inspection.

## P1 evidence and font-fixture correction

In immutable `ux-review-pass1-green-sequence-red`, the four requested P1 classes ran 42 cases: 28 passed and 14 failed. Thirteen failures establish the absent long app labels, absent both-mode widget labels and absent native Shortcut sequence preview. One new widget test instead stopped early on `‹ must retain 16sp type`; that is not label RED.

The early font assertion divided pixels by scaledDensity. Android 14 applies nonlinear scaling; the official [Android 14 guidance](https://developer.android.com/about/versions/14/features#non-linear-font-scaling) and [TypedValue API](https://developer.android.com/reference/android/util/TypedValue#applyDimension(int,float,android.util.DisplayMetrics)) require the platform SP conversion. The test now checks actual button textSize against `TypedValue.applyDimension(COMPLEX_UNIT_SP, 16f, button.resources.displayMetrics)` and logs both pixel values on its first render. Every character/line, no-ellipsis, 56dp touch, containment, overlap and control-budget assertion remains.

The fresh P1 XML in `ux-temporary-merge-behaviour-red` is GREEN: `BoostSequenceLayoutTest` 8/8, `WidgetResponsiveLayoutTest` 28/28, `PhoneControlCopyTest` 5/5, `BoostCommandMappingTest` 1/1 — exactly 42/42, zero errors/skips. The widget log records actual 28px and required 16sp-converted 28px at 200% (scaledDensity 2.0).

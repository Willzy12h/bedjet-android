# UX self-review pass 1: widgets, Bluetooth, service and notifications

Date: 2026-10-26. Reviewer: UI implementation agent, independently reviewing the widget/runtime implementation. Base: `367968bddc390e5b2bb12876fc1662ffa07e47c3`; integrated checkpoint: `0eddd188540df9d918c9c2e69701ae6e72f9a918`, plus the current gate-remediation working changes. Review used the full current contents of the files below and their base diff, with cross-boundary callers traced where needed. No Gradle command was started by this reviewer; root owns the serial verification batch.

## Findings and disposition

No additional concrete production defect was found in the assigned widget, Bluetooth, service and notification scope. Captured identities, raw-receipt outcomes, local-only admission, bounded ownership and receiver/deadline cleanup remain consistent in the inspected routes. This is a source review, not a claim that the whole branch passes its gate or that a physical Samsung launcher/BedJet was exercised.

The restored full gate at the checkpoint failed overall: 1,180 app tests, 37 failures, 38 skipped. Its immutable evidence directory is `/tmp/nocturne-gpt11-evidence/ux-full-gate-1-restored`. The 14 widget/service suites listed below account for **169 tests, zero failures, zero skipped** in that run. The separate `RuntimeServiceTest` positive-handoff fixtures failed because their adapter remained OFF; the runtime agent has corrected those preconditions and root will rerun them. Later added regressions are not included in the checkpoint's 169-test count.

### Reachable local-only routes

| Route | Admission result |
|---|---|
| Tonight/More Stop tracking | `AppViewModel.run` passes `hardware = false` to `act`; it enters the existing owner without the service or radio preflight. |
| Widget Stop tracking | `WidgetDelivery` calls `runOwned(hardware = false)` and retains WIDGET attribution, captured target/night checks and the local outcome. |
| Confirmed external shortcut Stop tracking | `ShortcutActivity` calls `BedJetService.start`; `permitted`, submission and delivery radio checks explicitly exempt STOP_TRACKING. |
| Settings Release BedJet | `PhoneHandoverMenuItems` calls `handover.release()` directly; `releaseForOtherPhone` uses the local owner path with no radio admission. Take back does use hardware because it obtains a fresh device report. |
| Legacy `PlatformActionActivity` helper | Its `runOwned` default is hardware work. The only current production creator is the bedtime reminder's START_NIGHT route. The base widget action list did not expose STOP_TRACKING either. The legacy-widget origin test constructs that local command for attribution coverage; it does not establish a reachable local-only UI defect. No speculative helper change was made. |

Evidence: `ui/AppViewModel.kt:348,396–416`; `widget/WidgetActions.kt:199`; `ui/ShortcutActivity.kt:51`; `service/BedJetService.kt:53–54,158–160,218–223,252–254`; `ui/features/FeatureSections.kt:129`; `night/NightActions.kt:1562–1575`; `platform/BedtimeReminders.kt:44`; base `widget/Widgets.kt:52–65,83`.

### Gate-remediation changes made by this reviewer

The separate UI regression was validated by the five existing `HistoryRevisionsTest` failures. An unconditional phase pointer handler and click/accessibility actions on the shared curve intercepted History card taps and changed clickable ancestry. `NightCurve` now defaults to passive phase interaction, and only ProfileEditor/Tonight explicitly opt in. `NightTimeline` already gates row clicks on a non-null edit callback and needed no change. The History tests remain unchanged. A real Profiles preview regression was added before the implementation edit; positive editor/timeline interaction tests remain intact. Root's focused run is still the verification authority for this correction.

Only confirmed fixture assumptions were adjusted: fixed-header alarm text is asserted displayed and clicked without `performScrollTo`; the four-look geometry helper uses its existing `scroll = false` option; the wake picker matches its exact time-only button text rather than a multi-text phase header; and the positive Haptics service fixture enables Bluetooth. Exact text/policy, destination, native picker values, persisted values, 48dp/200% geometry and admission/completion haptic assertions remain. `AccessibilitySemanticsTest` was not changed: its finalized XML names the three earlier RuntimeService Bluetooth exceptions as suppressed causes before that test began.

## Production and resource inventory

Paths in this table are relative to `app/src/main/`.

| File | Full-file review result |
|---|---|
| `kotlin/io/github/willzy12h/nocturne/ble/BluetoothAvailability.kt` | Reads the adapter and permission state without connecting. The explicit radio-OFF refusal is separate from existing CONNECT admission. No replay or poll is added. |
| `kotlin/io/github/willzy12h/nocturne/ble/BluetoothAdmission.kt` | Receiver is limited to submission/admission lifetime; interrupted requests cannot become valid merely because the adapter returned. `check`, `admit` and idempotent `close` share synchronization. |
| `kotlin/io/github/willzy12h/nocturne/service/BedJetService.kt` | Submission and delivery preserve target/origin/feedback. Missing or cancelled handoffs do not gain a foreground-start obligation. Temporary admissions transfer once, have bounded expiry and close on refusal, cancellation or completion. Local STOP_TRACKING bypasses Bluetooth. Re-time metadata resolution defers radio checks; the ended-night fast return requires both journals to be absent. Owner/service/caller budgets remain 90/100/115 seconds. |
| `kotlin/io/github/willzy12h/nocturne/notify/Notifications.kt` | Stop and Resume use captured Activity confirmation intents. Background radio errors share a 60-second notice claim; APP/SETTINGS feedback is not duplicated. Check BedJet retains the warning's target. Wake/resume observers have deadlines only while relevant notices exist. Short-test copy retains test identity. |
| `kotlin/io/github/willzy12h/nocturne/widget/WidgetActions.kt` | Ordinary actions use callbacks. Per-widget request stamps prevent an old completion replacing a newer Pending result; other-target results are hidden. Pending precedes work, expired/interrupted Pending is Unknown, and only a receipt outcome becomes Confirmed. Delivery waits for the shared bounded owner; its deadline child ends after settlement. Widget Off uses the strict captured-identity remote path; Stop tracking remains local. |
| `kotlin/io/github/willzy12h/nocturne/widget/WidgetConfirmationActivity.kt` | The direct, translucent confirmation keeps the rendered target/night and selected dynamic profile. Cancellation sends nothing. Approval submits through WidgetDelivery and closes the overlay; it does not launch MainActivity or waive owner identity checks. |
| `kotlin/io/github/willzy12h/nocturne/widget/WidgetPresentation.kt` | Pages keep all actions reachable across capacities; resize retains the nearest action in the same group. Wake/phase labels preserve UNKNOWN/FAILED/PENDING precedence and the exact displaced-night slot-write rule. Historical mode/temperature and phase time spans avoid a live-state claim. Boost descriptions use the shared Turbo/Heat helper. |
| `kotlin/io/github/willzy12h/nocturne/widget/WidgetUpdater.kt` | Data changes are debounced; only finite tracked-plan boundaries create delayed redraws. Clearing/changing the night cancels its old boundary flow. No unconditional minute timer or connection demand is introduced. |
| `kotlin/io/github/willzy12h/nocturne/widget/Widgets.kt` | One/two/three action budgets and in-place chevrons are preserved; larger text uses one full-width action. Labels/values/actions retain 16sp/20sp/56dp. Ordinary controls use callbacks, guarded controls use only the confirmation overlay, and Open app is explicit. Rendering reads state without connecting. |
| `res/layout/quick_controls_widget_preview.xml` | Preview uses resource text and the compact 56dp control geometry; it is not a fabricated screenshot baseline. |
| `res/layout/single_action_widget_preview.xml` | Same compact preview geometry and readable type; no interaction or background work is attached to the preview layout. |
| `res/values/strings_bluetooth.xml` | Refusal, interruption and enable-request messages distinguish radio-off from a replayable command. Text is resource-backed. |
| `res/values/strings_widgets.xml` | Pending/Confirmed/Failed/Unknown/local wording, phase/report times and full accessibility descriptions remain distinct. A confirmation tick is reserved for a confirmed outcome. |
| `res/xml/quick_controls_widget.xml` | Two-by-one default, both-axis resize, real preview layout and `updatePeriodMillis=0`. |
| `res/xml/single_action_widget.xml` | Optional/reconfigurable single action, both-axis resize, real preview layout and no periodic provider update. |

## Test and evidence inventory

Paths below are relative to `app/src/test/kotlin/io/github/willzy12h/nocturne/`. Counts refer only to matching immutable XML from the restored full gate, not current/stale build-output totals.

| File | Checkpoint tests | Full-file review focus |
|---|---:|---|
| `service/BluetoothOffTest.kt` | 13 pass | Owner/submission/delivery OFF checks, off-on interruption, cancellation receiver cleanup, one background notice and local widget Stop. |
| `service/OriginFeedbackTest.kt` | 4 pass | Original exception/timeout propagation without duplicate notification; truthful timeout wording. |
| `service/RadioOffRetimeTest.kt` | 13 pass | Unchanged/filtered alarm metadata settles without hardware; actual changes refuse without replay; queued interruption and UI paths; unreachable radio-on device retains the distinct retry policy. |
| `service/RecoveryNotificationActionsTest.kt` | 2 pass | Actual confirmation PendingIntents, cancel sends nothing, accepted Stop settles through the service and fresh receipt, Resume uses its captured route. |
| `service/RetimeNotificationTest.kt` | 63 pass | Full file reviewed, including durable terminal history, one-notice ownership, newer-observation preservation, quiet no-write timeouts, superseded journals, contextual target/wake warning and read-only Check BedJet. The later runtime-agent regression is outside this checkpoint count. |
| `service/RuntimeServiceTest.kt` | 3 originating fixture failures | Full current file reviewed. Only the positive real-service delivery setups gained adapter-ON preconditions; cancellation, stale target, service ownership and UNKNOWN persistence assertions stay. Their uncaught OFF exceptions explain the later Accessibility before-test failure. |
| `service/ServiceEntrySafetyTest.kt` | 8 pass | Null/denied service start, ordinary PendingIntent delivery, cancellation, permission revocation, foreground promotion rejection and successful owner ordering. |
| `widget/WidgetCommandFeedbackTest.kt` | 12 pass | Real owner/receipt flow, frozen cached status rejection, per-request feedback, callback lifetime, cancellation, deadline cleanup and different-target rendering. |
| `widget/WidgetConfirmationTest.kt` | 9 pass | Actual RemoteViews confirmation target, manifest overlay properties, guarded replacement/Off/Stop, cancellation, captured-night/target changes before and after approval. |
| `widget/WidgetInPlaceActionTest.kt` | 5 pass | Actual RemoteViews callback and Activity destinations, explicit Open app, in-place chevrons. |
| `widget/WidgetPagingTest.kt` | 2 pass | Persisted page state renders the expected controls at each capacity; all commands remain reachable with no connection or activity. |
| `widget/WidgetProviderTest.kt` | 2 pass | Actual provider XML resize/default/preview/zero-update metadata. |
| `widget/WidgetResponsiveLayoutTest.kt` | 27 pass | Production RemoteViews geometry, actual text metrics/ellipsis, all pages and widget sizes at 100%/200%, type/touch budgets, explicit app area and Boost description. |
| `widget/WidgetTruthLabelsTest.kt` | 7 pass | Night confidence precedence, absolute planned-phase span, exact matching slot uncertainty and unrelated-slot positive control. |
| `widget/WidgetUpdateCadenceTest.kt` | 2 pass | No idle redraw loop; phase-boundary event and cancellation of future events when tracking clears. |

`docs/review/ux-widget-evidence.md` was read in full. Its implementation-stage RED/GREEN ledger clearly distinguishes harness corrections, source fixes, fresh requested XML counts and physical-device limitations. Its integration seams match the current shared helper/hardware argument.

Additional caller traces: `ui/ShortcutActivity.kt`, `platform/PlatformActionActivity.kt`, `platform/BedtimeReminders.kt`, `platform/PhoneHandover.kt`, `widget/SingleActionConfigActivity.kt`, the local/owned paths in `ui/AppViewModel.kt` and `ui/features/FeatureSections.kt`, and owner/connected/release methods in `night/NightActions.kt`.

## Handoff and limits

### Lint follow-up before the focused behavior batch

The subsequent `ux-review-pass1-red` lint run reported `StateFlowValueCalledInComposition` in `Widgets.kt`, `PrivateResource`/`UnusedResources` for the native Glance button style, `Overdraw` on the two widget preview roots, and the unused `widget_result_action` string. These are separate from the checkpoint behavior results above.

- `ActionButton` now collects `activeNight` as Compose state. It still captures the command through `widgetRequest`, and confirmation delivery retains the existing target/night identity checks.
- The native style retains sentence case, zero letter spacing and end ellipsis. It declares the lint diagnostic's supported `tools:override="true"` metadata for the deliberate override of pinned Glance 1.1.1. That AAR's `glance_button.xml` and generated button layouts reference the exact style. Both preview buttons now share it, so the app resource also has direct XML references. No lint ignore or baseline was added. The existing 27 native `WidgetResponsiveLayoutTest` cases, including actual 200% text/geometry checks, remain unchanged and must pass again in the parent batch.
- Both launcher-hosted preview surfaces now use a shared 16dp rounded drawable matching the live Glance widgets. They retain the light/dark background colors; their corner regions remain transparent. This avoids treating a full rectangular preview background as an Activity window background and does not remove the widget's readable surface. The [official Overdraw rule](https://googlesamples.github.io/android-custom-lint-rules/checks/Overdraw.md.html) documents the inexact layout-to-Activity inference behind the original warning.
- Removed only the unreferenced `widget_result_action` string. No feedback label or outcome changed.

Static XML parsing and `git diff --check` passed for this patch. No Gradle run was started by this reviewer; lint and behavior GREEN remain pending the parent batch.

- Root will batch the curve/fixture verification and repeat the whole gate; this reviewer did not claim a new GREEN run. `git diff --check` passed after the scoped edits, and `HistoryRevisionsTest` has no working diff.
- Unimplemented suggestion, explicitly outside this round's P1 Boost-option naming: customised Quick Warm/Cool presets could later include their actual configured mode in their action labels/descriptions. These are existing named presets; no relabelling or behavior change was made in this loop.
- Widget layout evidence uses native SDK 34 RemoteViews at the documented dp sizes. Samsung launcher behavior and physical BedJet operation still require the existing phone-test handoff. Fixed labels are checked in full; arbitrarily long saved profile names may ellipsise in fixed widget bounds, with the complete action available through accessibility.

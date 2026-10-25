# fixes2 — BLE and foreground status behaviour

## Baseline inspected before changes

Source: `1c67c0a28084238377c7675e6dc25d0444fe2c22`. These are code observations, not measurements on a phone or BedJet.

- `MainActivity.onStart/onStop` calls the ViewModel's visible/hidden methods. A visible screen takes a `LinkSessions` hold and connects the selected unit; explicit commands use a separate balanced connection hold through `NightActions`.
- A ready connection remains while any holder needs it. The old idle grace is **5 seconds** after the last holder releases. A connection attempt with no remaining holder is cancelled; explicit release for another phone disconnects immediately.
- `AppViewModel.status` eagerly collects sourced status for the lifetime of the ViewModel, including while its activity is hidden. It filters the selected device, but has no UI rate limit.
- GATT enables status notifications on connection. Received notifications drive updates; there is no periodic full-status polling service. Split notifications can trigger a remainder read when the GATT operation queue is free and more than **2 seconds** has passed since the previous remainder read. This continues during the idle grace. Incoming notification frequency is determined by the unit/stack and has not been measured.
- The process runtime observes raw sourced status for takeover handling and optional platform observations. Manual confirmation uses raw receipts; its fallback disconnects/reconnects under the command owner and waits for a fresh target report, including programme progress when required.
- Wake/end signals schedule local delivery, not a second hardware controller. Alarm observations/re-time retries, explicitly requested commands, eligible charger jobs and recovery work obtain a connection when they need hardware. Re-time retries use up to ten-minute spacing bounded by the wake deadline; an enabled charger job is periodic at 15 minutes and eligibility/grace checks precede starting. A tracked programme runs on the BedJet without a permanent phone connection.
- Optional Lull handover scanning runs only for a ready, verified compatible connection with permission; it does not create a connection by itself. No general idle reconnect loop was found.

## Requested change and decisions

Keep command receipts and hardware ownership unthrottled. Limit the screen's displayed status to visible activity lifetime and at most one update per second, with an immediate initial snapshot. Stop idle split-status enrichment when no screen/command holder needs it. Set idle disconnect grace to **30 seconds**; an active connection holder must protect its work, and returning to the foreground cancels pending release. Explicitly requested commands and pending recovery retain their existing ability to connect.

The 30-second grace is a connection reuse window, not an assertion of continuous background monitoring. Background command confirmation still requires full status; switching off reads needed for it would violate the existing truthfulness contract.

## Implemented behaviour

| Boundary | Before | After |
|---|---|---|
| Visible activity | Connection held; every selected-target report can reach the UI. | Connection held; immediate initial snapshot, then at most one displayed status update per second. This also bounds displayed temperature updates. |
| Hidden activity | The ViewModel keeps collecting and exposing full status. | The presentation subscription is cancelled and its value becomes null. Returning shows the selected target's latest snapshot immediately. |
| Last connection holder releases | Disconnect after 5 seconds; split-status reads continue during that grace. | Disable new split-status remainder reads and routine platform observation saves; disconnect after 30 seconds. Returning before then reuses the connection. |
| Background owner | Its connection hold protects its work. | The same hold restores full-status demand and protects work even after the activity hides. Its release starts the 30-second grace only if no other holder remains. |
| Raw confirmation | Raw sourced reports, receipt order and a fresh-read fallback. | Unchanged. Only presentation is throttled. A remainder enriches its original head receipt; it does not create a new confirming receipt. |
| Idle background | No general polling/reconnect service. | No new polling/reconnect service. Scheduled work, explicit commands and pending recovery connect when they require hardware. |

An already-started status read may finish after the last holder releases. Cancelling an in-flight GATT exchange would retire the transport and could disrupt confirmation. Raw notifications remain available during the grace so a late target Standby can settle a valid Off intention. `Graph` therefore continues calling `NightActions.observeStatus`; only routine `PlatformRuntime.observe` persistence is demand-gated. The connection is dropped once the grace expires without another holder.

The existing **UI 115 s > service 100 s > owner 90 s** budgets are unchanged. The status-demand switch neither replaces the command-owner mutex nor delays raw receipts or the explicit fresh read. There is no claim that Android supplies notifications at a particular frequency or delivers scheduled work at an exact time.

Implementation: [LinkSessions](../../app/src/main/kotlin/io/github/willzy12h/nocturne/night/LinkSessions.kt), [GattLink](../../app/src/main/kotlin/io/github/willzy12h/nocturne/ble/GattLink.kt), [AndroidBedJetLink](../../app/src/main/kotlin/io/github/willzy12h/nocturne/ble/AndroidBedJetLink.kt), [BedJetLink](../../core/src/main/kotlin/io/github/willzy12h/nocturne/core/link/BedJetLink.kt), [AppViewModel](../../app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt) and [PlatformRuntime](../../app/src/main/kotlin/io/github/willzy12h/nocturne/platform/PlatformRuntime.kt).

## Verification

- `ble-red`: **10 tests, 6 assertion failures, 0 errors** before implementation. Failures demonstrated the old five-second disconnect/reconnect, an idle split-status read, hidden-screen exposure and unbounded displayed temperature updates. Four ownership/return controls passed.
- `ble-green-2`: **73 passed, 0 failures/errors/skips** across the three new suites and existing LinkSessions, GattLink, AndroidBedJetLink, FreshReadBoundary and Admission suites. The preceding GREEN attempt failed Kotlin type inference before tests; it is not counted as behavioural evidence.
- `ble-telemetry-red`: **2 tests, 1 assertion failure, 0 errors**. A hidden idle report changed the durable automation observation; the hidden-owner positive control passed. The implementation adds the holder-demand guard without gating late raw Off settlement.
- `ble-telemetry-green-2`: **14 passed, 0 failures/errors/skips**, covering all four new suites and the existing two GattStatusReceipt cases. The hidden-owner test was strengthened during independent review to await a distinguishable remainder and require equality with the head's captured receipt. Its preceding run passed that case and both telemetry cases, but an idle fixture hid during the final part of connection acquisition and correctly cancelled that unfinished connect. The fixture now awaits the suspending acquisition before testing established-connection grace; no production change was made for that race.
- The final integration gate is recorded in [fixes2 verification](fixes2-verification.md).

Tests: [ForegroundConnectionTest](../../app/src/test/kotlin/io/github/willzy12h/nocturne/night/ForegroundConnectionTest.kt), [ForegroundStatusTest](../../app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ForegroundStatusTest.kt), [IdleStatusDemandTest](../../app/src/test/kotlin/io/github/willzy12h/nocturne/ble/IdleStatusDemandTest.kt) and [IdleTelemetryTest](../../app/src/test/kotlin/io/github/willzy12h/nocturne/platform/IdleTelemetryTest.kt). The connection timing tests use virtual time; the UI tests use the real ViewModel lifecycle methods; the GATT tests use scripted callbacks. They do not measure a phone's battery use.

Physical notification frequency, battery consumption, Samsung lifecycle behaviour and BedJet radio operation remain **NOT RUN**.

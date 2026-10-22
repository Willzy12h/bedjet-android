# Nocturne hardening register

Branch `claude/nocturne-hardening`, based on `claude/lucid-mayer-i1h2gy` @ `22766fd`.

**About the IDs.** The attached `2026-10-22-nocturne-review-action-plan.md`, with its B01–B20 / U01–U09 / Q01–Q03 IDs, was not available in this session: it wasn't in the message, on disk, or in the repo. This register was rebuilt from the implementation brief, `docs/review/claude-second-review.md` (cited as "2R #n" / "2R m#n") and source inspection. The IDs here (R, U, F, V) are this register's own, and do not correspond to the review's B/U/Q numbers. Add the action plan to the repo to have them cross-referenced.

**Status values:**
- `open`
- `in progress`
- `implemented`
- `auto-verified`: automated tests pass
- `hw-pending`: needs supervised phone and BedJet checks
- `hw-verified`
- `blocked`
- `obsolete`: with evidence

Owner is the workstream: W1 BLE, W2 Persistence, W3 Runtime/alarms, W4 UI/accessibility, W5 Features/platform, W6 Independent review, L Lead.

## Reliability and correctness (R)

| ID | Item | Source | Owner | Status | Commit | Tests | Hardware check |
|---|---|---|---|---|---|---|---|
| R01 | Reject callbacks from obsolete GATT connections, wrong attributes, and failed operations or discovery | brief; 2R m#3 | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.aRetiredSessionsLateConnectionCallbackIsIgnored, aLateWriteCallbackFromAPreviousSessionDoesNotCompleteTheCurrentWrite, aCallbackForTheWrongCharacteristicFailsTheOperation, failedServiceDiscoveryFailsTheConnection | late callbacks |
| R02 | After an in-flight timeout or cancellation, retire the session; same-GATT late writes can't be told apart | brief | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.aTimeoutRetiresTheSessionAndTheNextOperationReconnects, cancellingAnOperationInFlightRetiresTheSession | |
| R03 | Session-owned GATT, characteristics, waiters, split status and jobs | brief | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.* (fake port; `GattLink.Session`), aConnectionCallbackBeforeConnectReturnsStillCarriesOn | |
| R04 | Close a cancelled attach; no orphaned GATT | 2R #4 | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.aCancelledAttachClosesItsGatt | |
| R05 | An abandoned connect must not complete after the last holder releases | 2R #3 | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | LinkSessionsTest.aConnectThatFinishesAfterTheLastReleaseIsDisconnectedAtOnce, theLastReleaseCancelsAConnectStillInProgress, anotherHolderKeepsTheConnectGoing, cancellingAnAcquireKeepsTheHoldersBalanced; NightActionsTest (holds) | disconnect / reconnect |
| R06 | An old status or remainder read must not satisfy a new start confirmation | 2R #5 | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.anOldProgramStepDoesNotConfirmANewStartButAFreshOneDoes | readback |
| R07 | Permission loss, platform exceptions, CancellationException kept, pending state cleaned on initiation throw | brief; 2R m#4 | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.aSecurityExceptionFromAWriteBecomesNotConnectedAndLeavesNothingPending, aSecurityExceptionFromConnectingOrScanningBecomesNotConnected, theBackgroundRemainderReadSwallowsPlatformExceptions; AndroidBedJetLinkTest | permission recovery |
| R08 | Bounded remembered-device recovery (late 133 / timeouts) without picking another BedJet | brief | W1 | auto-verified | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.status133RetriesTheSameAddressThenFailsWithoutTryingAnotherBedJet, aLateConnectionTimeoutIsRetriedOnTheSameAddress, withNoTargetConnectAsksForSetup | |
| R09 | Active night stores profile snapshot, device id, applied revisions, wake instant/source, confirmed wake boundary, actual end | brief; 2R #6 | W3 | open | | | |
| R10 | "Next night" edits, profile deletion and reusable-profile edits don't change an active night | brief; 2R #6 | W3 | open | | | |
| R11 | Automatic re-time uses the snapshot and can't rewrite after the confirmed wake boundary | brief | W3 | open | | | |
| R12 | "Update tonight" keeps the wake instant and source unless explicitly changed | brief; 2R m#2 | W3 | open | | | |
| R13 | Tonight, History, notifications and widgets show applied state | brief | W3 / W4 | open | | | |
| R14 | Remote Off or mode takeover ends the night; LED/beeps don't | 2R #2 | W3 | open | | | physical / remote takeover |
| R15 | Reconcile physical-remote / other-phone changes from status; normal programme progression isn't takeover | brief | W3 | open | | | physical remote |
| R16 | Serialise expiry, replacement, restore-slot, target change and commands; reject stale actions by night/revision id | brief; 2R m#5 | W3 | open | | | |
| R17 | Local transitions (stop tracking, expiry) work without Bluetooth | brief | W3 | open | | | |
| R18 | `exclusiveUntil` timeout race can leak the lock or a hold | 2R m#1 | W3 | open | | | |
| R19 | Durable original device/slot backup, including an empty-slot marker, before any overwrite; abort if it can't be saved; covers Start, Boost, pinning and restore | brief | W2 + W3 | open | | | original-slot restore |
| R20 | Separate demo and real state; in-flight command bound to its target; demo switch during a night | brief; 2R m#10 | W2 + W3 | open | | | |
| R21 | Explicit replacement-device selection; handle active work before a device change | brief | W1 + W4 | in progress (W1 link side implemented: target provider, `rememberedAddress()`, `discover()`; W4 selection UI open) | `fix(ble): session-owned BedJet transport and connection ownership` | GattLinkTest.connectsToTheTargetAndAnswersQueries, discoverListsBedJetsWithoutConnecting; AndroidBedJetLinkTest | |
| R22 | Import validation (schema, ranges, ids, durations, times, size) before any change; coherent restore with a pre-import snapshot; no hardware start or target change | brief | W2 | open | | | |
| R23 | Publish state only after a durable write; I/O off the main thread; unreadable state exposed | brief; 2R m#9 | W2 | open | | | |
| R24 | Update credentials kept out of exports, diagnostics, cloud backup and device transfer | brief; 2R m#12 | W2 | open | | | |
| R25 | Tested migrations; 60-day history, 20 automatic snapshots, manual snapshots kept until deleted | brief | W2 | open | | | |
| R26 | Persist the intended change before writing to the BedJet; confirmed / pending / failed / missed / unknown states | brief | W3 | open | | | interrupted writes |
| R27 | Whole operation bounded under service ownership; no long rewrites inside a receiver | brief | W3 | open | | | screen-off / Doze |
| R28 | One coalesced, durable pending re-time with request id and deadline; best-effort retry | brief; 2R #7 | W3 | open | | | screen-off / Doze |
| R29 | Recheck eligibility after acquiring the lock and connection, and before writing; expire missed wakes | brief | W3 | open | | | |
| R30 | No past-wake re-arming or duplicate notification/log on snooze/extend; manual-run expiry; end-versus-extend race | 2R #1; brief | W3 | open | | | wake/end timing |
| R31 | Separate wake/end scheduling; startup/reboot/permission reconciliation without replaying past wakes | brief | W3 | open | | | |
| R32 | Check foreground-service prerequisites before promotion; demo needs no BLE permission | brief | W3 | open | | | permission recovery |
| R33 | One alarm-source and window policy for start and re-time (Samsung/Google attribution, unknown source, 1–16 h from the original start) | brief | W3 | open | | | |
| R34 | Supported night-length range against the 32-step limit; refuse or warn on very long nights. **New finding:** starting at 06:34 with a 07:00 default planned a ~24.5 h night (seen in the baseline screenshots) | brief; baseline | W3 | open | | | |
| R35 | Truthful temperature/fan limits; Turbo mustn't silently break a claimed maximum | brief | W3 | open | | | |
| R36 | Start-now headline, curve and actual run share one preview | 2R #8 | W3 / W4 | open | | | |
| R37 | Exported shortcuts can't issue unconfirmed hardware commands for other apps; launcher use kept | brief | W3 | open | | | |
| R38 | One replacement policy across UI, widgets, shortcuts, notifications and slot restore (Off stays easy) | brief | W3 / W4 | open | | | |
| R39 | Stable release signing; never publish a debug-signed release | brief | L | open | | | upgrade install |
| R40 | Shrinking and resource optimisation, with serialization, widgets and restore verified | brief | L | open | | | minified install |

## UI and usability (U), in the existing design

| ID | Item | Source | Owner | Status | Commit | Tests |
|---|---|---|---|---|---|---|
| U01 | Quick actions stay reachable while the snackbar shows | brief | W4 | open | | |
| U02 | "Use tonight" is temporary; add "Follow schedule" | brief | W4 + W3 | open | | |
| U03 | Widgets resolve dynamic profiles at tap time; fixed or deleted profiles handled clearly | brief | W5 | open | | |
| U04 | Unsaved editor drafts and navigation survive recreation | brief | W4 | open | | |
| U05 | TalkBack semantics for switches, sliders and the chart (names, units, text alternatives) | brief; 2R m#11 | W4 | open | | |
| U06 | Contrast, touch targets, narrow layouts, large text | brief | W4 | open | | |
| U07 | Observations scoped correctly (no leaked collectors); queued and running commands shown | brief; 2R m#7 | W4 | open | | |
| U08 | Show widget/shortcut/update outcomes where they were started; no duplicate feedback | brief; 2R m#8 | W4 + W5 | open | | |
| U09 | History records BedJet notices, Remote failures, applied revisions, takeover and recovery | brief | W3 + W4 | open | | |
| U10 | Show next-night edits, and pending versus confirmed wake changes | brief | W4 | open | | |
| U11 | Strings moved to resources (UK English), plurals, units, a11y text | brief / F14 | W4 | open | | |

## Quality-of-life features (F)

| ID | Feature | Owner | Status | Commit | Tests | Hardware check |
|---|---|---|---|---|---|---|
| F01 | Tonight-only wake override (phone alarm untouched; precedence override > alarm > default; reset; survives restart; expires) | W3 + W4 | open | | | |
| F02 | Resume the rest of the night after stop/replace (short-lived; no Turbo replay; never overrides a newer action) | W3 + W4 | open | | | |
| F03 | Bedtime reminders (opt-in, no duplicates, permission handling, opens the start preview) | W5 | open | | | |
| F04 | Charger auto-start (opt-in, off by default, bedtime window, chosen BedJet, minimum night, once per night, suppressed when unsafe, easy to cancel) | W5 | open | | | supervised |
| F05 | Scheduled theme (time-based, overnight ranges, time zone/DST, equal start/end defined; widgets follow) | W4 | open | | | |
| F06 | Quick Settings tile | W5 | open | | | |
| F07 | Richer widget (name, confirmed next wake, current/last-known mode with freshness) | W5 | open | | | |
| F08 | First-run setup (permissions, Samsung guidance, explicit BedJet choice, separate demo, supervised check, re-check on return) | W4 + W1 | open | | | supervised |
| F09 | Morning check-in with validated suggestions the user accepts | W4 + W3 | open | | | |
| F10 | Health Connect sleep overlay (optional, minimum permissions, absence/revocation handled) | W5 | open | | | |
| F11 | History CSV export (units, time zones, escaping, privacy) | W5 | open | | | |
| F12 | Phone hand-over: explicit release, suspended auto-reconnect, reconcile on return; Lull-compatible request where supported | W5 + W1 | open | | | both directions |
| F13 | Haptics (restrained, respects system settings, accepted versus confirmed) | W4 | open | | | |
| F14 | Translation readiness (= U11) | W4 | open | | | |
| F15 | Second-slot profile pinning (explicit slot, preview, backup, readback/restore, active-programme protection) | W3 + W4 | open | | | readback |
| F16 | Recovery and observability (confidence indicators, Stop tracking, diagnostics export without secrets, optional quiet night-status notification) | W3 + W4 | open | | | |

## Verification and release (V)

| ID | Item | Owner | Status |
|---|---|---|---|
| V01 | Injected clocks, controlled schedulers, transport/fault fakes across the regression scenarios in the brief | all | open |
| V02 | Migration and backup round trips | W2 | open |
| V03 | Before/after real-app renders: Dark, Light, Simple, narrow, large text | L / W4 | baseline captured |
| V04 | Minified release build with a test signing key (not production) | L | open |
| V05 | Samsung + BedJet 3 acceptance checklist | L | open |
| V06 | Independent review (W6) and fix pass | W6 / L | open |

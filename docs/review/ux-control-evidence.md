# GPT-11 — control labels, recovery, Bluetooth and Dry

Base: `367968bddc390e5b2bb12876fc1662ffa07e47c3` (the final pushed Step A commit).

## Executed test-first evidence

`ux-root-policy-red` ran the real existing paths before the fixes: `DryModeRangeTest` **3 tests, 2 assertion failures**; `PhoneControlCopyTest` and `BluetoothOffTest` **6 tests, 6 assertion failures**, no errors or skips. The other Dry mode limits were the passing control. Failures showed a 40 °C Dry plan, generic Boost labels, inconsistent Resume/End wording, a disabled adapter still delivering a service request, a UI handoff waiting for its service timeout, and the owner returning the generic connection reason.

Focused core Gradle runs retain older XML files in their results directory. Counts here use only the requested fresh suites and the Gradle test summary, not a sum of stale files copied alongside them. Full gate counts are recorded separately.

## P1 — what Boost sends

`NightActions.boostLocked` compiles `Mode.TURBO` with `TurboPolicy.TURBO_THEN_HEAT`. The configured duration options are 10, 15, 20, 25 and 30 minutes. All start with at most ten minutes of Turbo; any remaining duration is Heat. There is no cooling Boost option in this base. The separate Quick Turbo command is ten minutes.

The owner’s latest correction supersedes the original label implementation. The Turbo-only option reads “Turbo 10m”; longer app controls name both parts, for example “Turbo 10m + Heat 5m”. Compact widget controls use “Turbo→Heat” and full content descriptions name each exact duration. No longer option is labelled “Turbo 15m”. `BoostSequenceLayoutTest`, `WidgetResponsiveLayoutTest`, `BoostCommandMappingTest` and `PhoneControlCopyTest` passed all 42 selected cases after this correction, including 320 dp / 200% text. Accessibility descriptions name the ten-minute Turbo segment and any following Heat segment rather than describing the whole run as continuous Turbo. The mapping test covers every configured Boost duration and its actual compiled modes. There are no non-Turbo Boost options to relabel in this implementation; the separate Cool controls retain their own mode labels.

## P10 — why the live remote stops at 31 °C

The live Remote slider reads `DeviceStatus.minTemperature` and `maxTemperature`, not `Mode.temperatureRange`. `Codec` decodes the maximum from status byte 14, in half-degrees Celsius. Therefore a reported maximum of 62 produces the 31 °C slider limit. This is a code-path diagnosis; no packet capture from the user's BedJet has been inspected and no hardware limit is claimed as tested.

The planning model had an independent `DRY -> 40..80` range (20–40 °C), inherited from the upstream Lull model. The manufacturer's BedJet 3 **220–240 V manual, revision 2.0, page 20**, documents Dry up to **31 °C**. The model now uses `40..62`. Existing saved profiles retain their data, and compilation clamps an older 40 °C Dry target to 31 °C. The other requested mode maxima remain unchanged. Live controls retain the device-reported limits.

Primary source: [BedJet user manuals](https://bedjet.com/pages/user-manuals), linking the [BedJet 3 220–240 V manual](https://cdn.shopify.com/s/files/1/0750/6693/files/BedJet_3_FR.pdf?v=1760031052). The temperature table labels Dry's maximum as 31 °C. Upstream provenance: [Lull Models.swift at f98821a](https://github.com/rkanjani/lull/blob/f98821a6f53cbe80c00fc1ba11396a9433b322f0/BedJetKit/Sources/BedJetKit/Models.swift).

## P3 — adapter state and command delivery

The adapter is read through `BluetoothAdapter.isEnabled`, after checking Nearby devices permission. A visible activity's Bluetooth broadcast is only a signal to re-read the adapter; its extras cannot assert that the radio is enabled. Service entry and owner admission each check again before connecting or writing. Refused manual requests are not persisted or replayed when Bluetooth returns.

Background origins share a named 60-second notification window. The notice claim is stored independently of command delivery; foreground APP/Settings requests use their own in-app result. Returning Bluetooth withdraws that notice, clears the banner, and reconnects for a visible screen without resubmitting any failed action.

Android sources: [Bluetooth setup](https://developer.android.com/develop/connectivity/bluetooth/setup), [BluetoothAdapter](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter), and [context-registered broadcasts](https://developer.android.com/develop/background-work/background-tasks/broadcasts). The Bluetooth enable request uses `ACTION_REQUEST_ENABLE` after the existing Nearby devices permission flow. Bluetooth broadcasts can come from privileged system applications, so the lifecycle-scoped receiver uses the documented exported registration while trusting only a fresh adapter read.

## Further executed cycles

`ux-root-ui-red`: **6 tests, 5 assertion failures**. The three Bluetooth banner/permission/intent cases had no Enable Bluetooth button, Resume had no correctly named action, and a confirmed fan change emitted the unwanted bubble. The failed/unknown feedback control passed.

`ux-root-controls-green-and-boost-description-red`: core **3/3 passed**; app **13 tests, 10 passed, 3 failures**. The new actual-Remote Boost description test failed as intended. Two remaining failures were harness timing/expectations: Android records its permission-controller activity in `nextStartedActivityForResult` (the test now specifically prohibits `ACTION_REQUEST_ENABLE` until permission is granted), and the Resume test saw confirmed active-night publication before the following runtime save completed (it now waits for the request's busy flag to clear before retaining the same null-offer assertion). No production resume or permission bypass was added for those harness failures.

`ux-root-idle-red`: **3 tests, 3 assertion failures**. The idle notification observer read the clock four times in three minutes instead of just its initial event; a hidden idle demo ticked five times in five seconds; and the demo kept ticking after its one-minute run had ended. The fixes subscribe to actual demand/programme/deadline state and stop their timers when no work needs them.

`ux-root-timers-green-scanning-preview-red`: **6 tests, 4 passed, 2 assertion failures**. The three process timer regressions passed, as did receiver lifecycle and fresh-adapter-state checks. The additional failures reproduced optional Lull scanning during idle grace and four hidden pin-preview clock reads in twenty seconds. Scanning now follows the existing session demand, and the pin preview's existing five-second clock runs only while RESUMED.

## Validation still in progress

`ux-root-local-stop-red-controls-green`: core **3/3 passed**; app **39 tests, 28 passed, 11 failures**. The two new behavioural failures reproduced local widget Stop tracking incorrectly requiring Bluetooth and the missing Stop night action during an uncertain manual run. Nine older service-positive cases were blocked earlier by Robolectric's default disabled adapter; their fixtures now explicitly enable it, retaining their promotion/cancellation/timeout assertions. The local route still uses the command owner and bound, while skipping hardware/service delivery. Stop night remains guarded and requires a fresh Off receipt to end tracking.

`ux-root-expired-offer-red`: **6 tests, 5 passed, 1 assertion failure**. The positive control proved a resume notice disappeared at expiry, but its retained offer kept the clock timer running until the original wake time (five reads became twelve during the following three minutes). The timer now uses the earlier of offer expiry and original wake as one deadline. Idle scanning and hidden preview tests passed after their fixes.

`ux-root-controls-and-idle-green`: core **3/3 passed**; app **53 tests, 52 passed, 1 failure**. The remaining failure read the asynchronously saved notice after a fixed 20 ms and observed zero rather than one. It now awaits that publication before retaining the same exact-one assertion; notification claiming remains synchronous and unchanged. All service entry/timeout controls, local Stop tracking, guarded Stop night, Resume, fan feedback, Boost description, idle timers/scanning/preview, origin rules and P5 tests passed. The added tracked-night P5 test verifies that grace still releases at 30 seconds without changing the active night or its wake/end alarms.

The final focused GREEN runs, integrated gate, review findings and exact file/line references are recorded in the GPT-11 handover once executed. None of this evidence is hardware, Samsung, TalkBack or on-device verification.

`ux-root-boost-command-red`: the actual owned Boost mapping test failed for the 10-minute option because its success text claimed a Heat continuation that did not exist. The owner now uses the same resource-backed description as the accessibility label. `ux-root-boost-and-notice-green`: **12 selected tests passed** (actual owner Boost mapping for all five configured durations, three copy cases, real button accessibility, and seven Bluetooth entry/dedup/local-owner cases). The exact-one notice check also passed with publication awaited.

The first `ux-root-recovery-notification-red` invocation did not exercise its assertions: JUnit rejected a non-void `@Before` method. That fixture was corrected before re-running RED; it is not counted as evidence of a production defect. The next regression set also covers Bluetooth switching off and back on before owner admission or service delivery, to prevent an old queued request from running after the radio returns.

`ux-root-bluetooth-interruption-and-recovery-red`: **14 tests, 7 passed, 7 assertion failures**. Three requests incorrectly survived a radio interruption (queued owner, UI handoff, background service delivery); two lifecycle cases proved there was no temporary interruption listener; the notification Stop action bypassed confirmation, and Resume's positive button said “Send command”. The fix adds a per-request receiver only until owner admission/cancellation, carries it through service delivery, and bounds undelivered background starts to the existing 115-second submission budget. It never retries an invalidated request. Android's `ACTION_STATE_CHANGED` is a [protected system broadcast](https://android.googlesource.com/platform/frameworks/base/+/af575b9/core/res/AndroidManifest.xml); its Off/turning transition can invalidate a queued action, while the visible banner continues to read `BluetoothAdapter.isEnabled` directly. Current Off and a completed interruption have distinct resource-backed reasons. Background notices share the existing deduplication window. Notification Stop now requires its captured-night confirmation; Resume keeps its existing route with its exact action label.


## Integrated radio/re-time correction

The new adapter preflight initially rejected local alarm metadata and could retain a refused changed wake as a retry. `ux-radio-retime-integration-red-2` reproduced 13 assertion failures in 26 cases (zero errors). `ux-radio-retime-integration-green` then passed all 124 selected service/re-time/origin cases. Re-time paths retain their per-submission interruption watch, but check the radio only if the owner needs a connection. A Bluetooth refusal clears that exact pending attempt and its retry; a radio-on but unreachable BedJet retains the existing safe retry policy. An empty retry after a night ended returns without a Bluetooth warning. No command is silently replayed when the adapter returns.

The temporary background admission receiver has a bounded undelivered phase of at most 115 seconds followed, if delivery occurs, by the owner's separate at-most-90-second admission/operation phase. The UI `runOwned` route retains its outer 115-second bound; service and owner budgets remain 100 and 90 seconds. These are live-request event receivers, not periodic idle listeners. The combined background lifetime must not be described as a single proved 115-second budget.

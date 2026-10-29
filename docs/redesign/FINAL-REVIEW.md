# Nocturne remediation: final self-review

> **Current correction round:** see [INDEPENDENT-FIX-REGISTER.md](INDEPENDENT-FIX-REGISTER.md) for the independent findings, fixes and new verification. The results and capture files below record the original `146501a69814f28d3879ca835bb93d2442b455a1` review base (application source `c9704ee`). They are historical evidence, not verification of the new fixes or a hardware sign-off.

Date: 29 October 2026. Branch: `gpt/nocturne-redesign-remediation`.
Starting SHA: `e4be250fb9f340800fa89aea8e358d5bddbf5054`.
Verified application/test SHA: **c9704ee91b4d5642a90f7cf0b7f9d22b9ce619dd**.

**Final automated verification passed. This is the implementer's self-review, not independent sign-off.** Claude must form an independent conclusion after reviewing the delivered source and evidence. The earlier report, including its 238 failures and 24-warning budget, is preserved verbatim under `evidence/remediation/historical/FINAL-REVIEW.md`; neither is the current acceptance criterion.

## Review scope and method

The review covered the complete redesign range `56bfbcd^..HEAD`, the remediation range `e4be250..HEAD`, and adjacent owners that decide whether a visible action is safe or truthful. Changed production-path hashes are recorded in `evidence/remediation/reviewed-production-paths.sha256`. Review was repeated after material import/share, pending-state, manifest, accessibility and navigation changes. This is bounded code review plus automated/native evidence, not a claim that all possible faults are disproved.

Genuine defects were reproduced before production changes. Existing failed cases provided RED where appropriate; new regressions cover failures exposed by review. Obsolete selectors moved to the approved entry points while preserving behavioural assertions. Old collapse/order/global-banner expectations were replaced with assertions about the approved compact four-tab UI and compatible stored data. No tests were removed, disabled, quarantined or reduced to existence-only assertions to manufacture a passing gate.

## Findings and closure

The detailed identifiers, severity, root cause, reproduction, fixes and verification are in [REMEDIATION-REGISTER.md](REMEDIATION-REGISTER.md). The baseline contains **207 reproduced app failures**, rather than the historical 238; each case is reconciled in [baseline-failures.csv](evidence/remediation/baseline-failures.csv). [warnings.csv](evidence/remediation/warnings.csv) preserves the 210 observed warning/hint/error diagnostics, including additional issues exposed during remediation.

| Area reviewed | Findings repaired and safeguards checked | Evidence / independent scrutiny |
|---|---|---|
| Tonight and quick controls | Running/active labels require owner and fresh receipt evidence. Disabled controls expose semantics. Per-request completion clears Sending; queued operations retain busy. Confirmed old Boost journals do not hide later fresh Off/Heat. | UI-01/02/09/15/17/18; RemediationStateSafety, QuickPendingState, ActiveIndicator, Tonight acceptance and U06 suites. Recheck stale/failed/unknown receipts and running-night confirmation. |
| Remote and reconnect | Restored existing reconnect and Bluetooth presentation, runtime limits and confirmation routes. Status connection uses the same receipt-backed mapper. | UI-07/14; RemoteReconnectPresentation, RemoteRuntimeLimits, BluetoothBanner and owner/security suites. Physical radio behaviour remains untested. |
| Navigation and drafts | Setup requests park interrupted drafts; consumed requests do not replay on Activity recreation. Fresh ViewModel restoration must accept new requests after counter reset. Existing launch-intent consumption and multiple parked draft identities remain. | UI-06/23; P8ConsumedSettingsRequest, NavigationRestore on API 34/36, current alarm-link tests. Review process-death versus retained-ViewModel cases separately. |
| Profiles and phase editor | Whole-card Edit/overflow/New and cooldown remain reachable. Measured drag commits once. Steppers expose units/ranges, picker values retain the draft, Turbo limits and legacy durations are truthful. Large text stacks/scrolls without lost controls. | UI-03/04/08/11/20; ProfileEditorInteraction, P4EditorBehaviour, EditorTypedDuration, EditorLargeTextReachability, ProfilesEditHit, PickerInitialTime and U06 semantics. |
| Import, share and persistence | Profile-only merge preserves unrelated local data and creates a recovery snapshot. Existing idle admission/fingerprint/atomic writer retained. Combined profile limits validated. Share freezes the requested profile and session revision, survives recreation and propagates cancellation. Backup schema remains 3. | DATA-01/02; five ProfileFileFlow regressions, Repository/ImportPreviewRepository and related import transaction/security cases. DemoImportPreviewInvalidationTest remains unchanged and enabled. |
| Confirmations, target identity and owners | Reviewed AppViewModel, NightActions, OperationAdmission, LinkSessions, GattLink, tracked/temporary night owners and BedJetService handoff. UI changes reuse the owners rather than issuing independent BLE writes. Captured device/session/night identity and pending/unknown/failed/confirmed distinctions remain. | Existing target-switch, stale-confirmation, cancellation, reconnect, original-slot protection and service tests in the full gate. Review these safety boundaries independently; host simulation cannot prove firmware behaviour. |
| History, setup and recovery | Full labels and issue/comfort pills, scrollable check-in explanations, non-overlapping suggestion actions, single remembered-device tint and accessible recovery actions. History's “issue” avoids falsely declaring uncertain writes failed. | UI-12/13/22; HistoryLargeTextReachability, CheckInSuggestion, U06 loss/restore and native stripe tests. Keep/Apply touch paths assert persisted outcomes. |
| Palette and interactive states | Readable text/indicator ink without changing protected colour/tint values. Selected chips and mode subtitles remain legible; selected Add-step/shape boundaries contrast with adjacent fills. Opaque menus, Overcast edges/light drag surface, and agreed dark sheets retained. | UI-05/10/17/19/21; actual native pixels and TextLayoutResult checks across seven palettes; final 41-case capture set and reference pairing. |
| Widgets, background and battery | Reviewed action identity, app/open/paging controls, background owners, service lifetime and existing refresh/battery rules. No new polling or background feature. Existing widget stripe rows and unknown/last-report wording retained. | Widget action/responsive/Glance cases plus process/service/idle-timer suites. Installed launcher, Samsung battery policy and overnight physical tests remain untested. |
| Toolchain and manifest fidelity | Official missing SDKs provisioned; API 31/33 coverage retained, API36 added. Final merged manifest supplies actual receiver declarations to Robolectric. Native requested-window-size handling fixes the host dialog measurement loop. | DEP-01/02/03; PlatformManifest, AutoStartDelivery, UatAlarmIdentity across31/33/34/36, three unchanged typed-duration regressions. JDK/AGP/SDK upgrades require independent device scrutiny. |
| Warnings and dead code | Broad app lint configuration removed. Current APIs, resource plurals, unboxed state, Modifier order, launcher qualifiers and unused resources corrected. Unreachable old UI removed; schema fields retained. | WARN-01/02, CLEAN-01; WARNING-AUDIT lists every one of 38 retained annotations and why the warning is inapplicable or requires a legacy API. No budget-based suppression. |

The first full remediation run still had 20 app failures; those failures were diagnosed and fixed. Subsequent render review found additional genuine defects, including the 51-pixel overlap between History suggestion actions. The exact gate attempted from `fbb2a6a` was deliberately stopped with exit 143 before app results when the selection-contrast issue was discovered. Neither attempt is misreported as a passing gate.

## Final verification

```sh
./gradlew --offline --no-daemon --max-workers=3 -I docs/redesign/verification.init.gradle clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

| Gate | Result |
|---|---|
| Process exit / stable source | 0; source unchanged throughout; dbe37daa47686e6ff20c17d58191d5e7aca3738eb52cbd9e4457ed2834a8c4e8 |
| Core | 313 passed, 0 failures, 0 errors, 0 skips |
| App | 1,805 total; 1,764 passed, 0 failures, 0 errors, 41 opt-in skips |
| Ordinary-gate skips | 41, exclusively the four documented opt-in capture classes |
| Separate opt-in captures | 41 passed, 0 failures, 0 errors, 0 skips; exit 0 |
| Lint | 0 errors, 0 warnings, 0 hints |
| Project compiler/build warnings | 0 |
| External final-gate diagnostics | 0 warning diagnostics |

Final evidence: `evidence/remediation/verification/`, with raw logs, actual process receipts, JUnit/lint XML, case/skip counts and source hashes. Checks parse the reports rather than trusting the final console line. Capture screenshots were produced with the same final application/test source and preserved before the clean gate removed build outputs.

## Visual assessment

[DESIGN-CAPTURE-PAIRING.md](DESIGN-CAPTURE-PAIRING.md) accounts for all 59 published images, alongside the approved HTML. [P8-AUDIT.md](P8-AUDIT.md) documents native capture scope and every accepted difference. 1,003 PNGs (536 unique), 45 contact sheets, 5 lossless capture ZIPs, 10 reference-pairing sheets and 10 final-change review sheets.

No pixel-perfect claim is made. Sample profile names, temperatures/times, receipt state, genuine safety copy, native platform chrome, large-text wrapping and scrolling differ from the static mock. Existing setup and widget functionality follows the final decisions, not unimplemented illustrative controls. Screenshots are generated by Robolectric Native graphics, not a physical phone. Scrollable top/bottom captures intentionally show different viewports; that is not hidden content loss.

## Remaining limitations and assumptions

No known actionable source defect, failing test or project-owned warning remains after the repeated self-review and final gates. This conclusion is bounded by the exercised source paths and host/native evidence; independent review and the physical scenarios below remain outstanding.

The final source has not been exercised on physical BedJet hardware, a Samsung device, actual TalkBack, an installed launcher widget or an overnight battery/background session. Real Health Connect provider permissions, Android alarm delivery, radio reconnect/firmware acknowledgements, physical screen insets/keyboard and OEM process death remain device-validation work. TargetSdk 36 is tested in host coverage but not certified against those physical behaviours. The requested gate builds the debug variant; release-specific R8 shrinking, production signing and distribution were not executed.

The external Kotlin 2.0.21 daemon-session warning appeared in some aborted/intermediate runs. WARNING-AUDIT identifies its exact finish-handler source and automatic cleanup; it was not filtered or silently deleted. The table above states whether it appeared in the final gate. Tooling/physical limitations are distinct from fixable source defects.

Assumptions are explicit in GPT-HANDOVER: approved current UI outranks obsolete selectors; generic new after-wake/Turbo defaults do not migrate saved plans; History uncertainty remains honest; Profiles Import is a profile-only merge; accessibility changes preserve palette/design intent. No new design-only features or backup schema were introduced.

## Independent review request

Claude should independently review the final branch and exact delivery SHA, rerun the unchanged gate/captures, inspect the RED/GREEN trail and narrow warning exceptions, and challenge the review matrix above. Prioritise data/session isolation, target identity, queued cancellation, running-night safety, navigation after true saved-state restoration, meaningful selected-state contrast and 320dp/200% interaction. Any independent sign-off must come from that review; this document does not grant it.

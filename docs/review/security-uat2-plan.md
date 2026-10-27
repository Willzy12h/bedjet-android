# Security completion and UAT round 2 implementation plan

## Scope and constraints
Continue `gpt/nocturne-final` from 2572427eff40eda05be7a00372c1639e91834e62, fast-forward only. PR #4 stays open and unmerged. No tags, releases, secret reads or production-signing changes. Keep APP-01 enforcement, receipt-only confirmation, target/night capture and the single command owner. UK English and resource-based app text. Core JUnit5; app JUnit4/Robolectric and GraphLifecycle.

## Security completion
- [x] Inspect current PR and CI: the full run 40483749235 failed 22 app cases, although 280 core cases and the focused security gate passed.
- [ ] Correct the safe Standby fan-setting regression; retain all fan range checks and enforce heating duration constraints for running modes.
- [ ] Make GATT start-barrier fixtures answer the newly mandatory safe-programme query without releasing their held clock/start writes. Keep all barrier assertions.
- [ ] Update cancellation/foreign-receipt fixtures to request an in-range temperature. Model the duration-preservation test's unsafe setpoint as an external unit change, not a forbidden app write.
- [ ] Run affected tests and all security tests. Remove temporary recovery machinery after the verified source is published.
- [ ] Pass the full debug/test/lint and release, manifest, serialization/R8, screenshot and permanent TEST-signature gates. Hand over the security checkpoint before UAT implementation.

## UAT implementation
- [ ] U1: selected-device/current-report connection presentation in the fixed header; text plus shape and accessible contrast; preserve top-right temperature. Tests for each state and stale/unselected reports.
- [ ] U2: identify known Samsung package variants without changing alarm acceptance. Use creator UID package mapping only when it provides an unambiguous legitimate signal; never guess from the phone manufacturer. Show raw creator and the neutral fallback in Settings/diagnostics. The actual user's creator value is unknown until phone diagnostics are supplied.
- [ ] U3: remove idle reserved BusyIndicator space; display work without moving Quick controls. Test idle layout and identical Quick bounds while busy.
- [ ] U4: top-of-Tonight reactive setup checklist, required/optional completion, optional dismissal, Settings reopening and resume rechecks. Keep demo separate from pairing a real unit.
- [ ] U5: six Quick modes with persisted editable defaults, lossless Warm migration, mode-derived labels, two-column accessible layout and owner-routed Temporary/resume or End tonight consent. Validate settings and final writes against APP-01.
- [ ] Each item has named behavioural RED before implementation and GREEN after it. Self-review the UAT diff once, correcting defects only.
- [ ] Full gate and assembleRelease pass; versionCode exceeds the security APK; verify the permanent TEST certificate and report APK hash, version, CI and PR state.

## Review focus
No false Connected on a stale or different target; no inferred Samsung ownership from an installed package alone; no checklist-triggered commands; no loss of saved Quick values; no UI bypass of command limits or tracked-night approval. Device, Samsung, Binder and TalkBack claims require actual execution, not JVM tests.

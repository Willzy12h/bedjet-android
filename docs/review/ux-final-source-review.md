# Final UX source self-review

## Source and review coverage

The team reviewed the complete files and cumulative diff from UX base **`367968bddc390e5b2bb12876fc1662ffa07e47c3`** for **216 changed files** at **`0d39864fb28372aa9c0c9e6b76cabe7b5c4da79a`**, tree `f0f05839fe500ca09ea63a22831eba05d510a28d`: **175 app/core files** (70 runtime/core and 105 UI/widget) and **41 documentation/release-gate files**. The aggregate per-file SHA-256 inventory has no missing files or hash drift at that checkpoint. The final supplemental review covers the exact plural-resource/caller delta and documentation clarifications described below. No remaining concrete production defect was identified within that reviewed scope. This is team self-review, not independent sign-off or device acceptance.

Consult [PR #3](https://github.com/Willzy12h/bedjet-android/pull/3) and the accompanying execution metadata and 0–12 APK handover for the final tested source, complete-run results, artifact/signing hashes and merge identifiers. Source-specific results below retain their own tested inputs; they do not assign results to a later checkpoint. The complete file/hash inventory accompanies that delivery record.

## Gate3: command success with a lint acceptance blocker

`ux-combined-full-gate-3` at `0d39864fb28372aa9c0c9e6b76cabe7b5c4da79a` completed with **exit 0 in 1,272.72 seconds**. Core passed **240/240** with zero failures/errors/skips. App passed **1,304 of 1,342 cases**, with **38 expected opt-in capture skips** and zero failures/errors. Debug assembly, lint and release assembly all executed successfully; all **634 tracked input hashes** remained unchanged.

Acceptance was nevertheless blocked by one new `PluralsCandidate`: the duration-limit string formatted a number followed by “minutes”. Lint reported **0 errors, 15 warnings and 1 hint**, compared with the preserved **14 warnings/1 hint** baseline. The [gate JSON](ux-gate-at-0d.json) explicitly records `acceptance_gate_passed: false`; command exit 0 is not restated as policy acceptance. The unsigned release output is not evidence for a later signed test APK or its separate artifact checks.

## Minimal plural correction and focused verification

Only two production files change for this correction:

- `night/CapturedActionPreset.kt`: `timedPresetRefusal(context: Context, preset: ActionPreset): String?` retains the same Turbo-only condition and shared `Limits.TURBO_MINUTES == 10`. Its string lookup becomes `getQuantityString`, using that same limit as the quantity and formatting argument.
- `res/values/strings_temporary_runtime.xml`: `runtime_turbo_duration_limit` becomes a plural resource with `one` and `other` entries. Its current ten-minute English wording remains unchanged.

The complete files and exact diff were reread. No old `R.string.runtime_turbo_duration_limit` reference remains. There is no feature, timer, owner, protocol, serialization or command-policy change, no lint suppression and no changed test assertion.

`ux-turbo-plurals-green` completed with **exit 0 in 175.10 seconds**. Its unchanged selected suites passed **12/12**, zero failures/errors/skips: `TimedPresetLimitTest` **5/5**, `ImportedTemporaryDurationTest` **3/3**, and `ImportedPresetRoleSettingsTest` **4/4**. Fresh lint is **0 errors, 14 warnings, 1 hint**; its exact finding multiset matches the preserved baseline with no new findings. All **634 tracked hashes** stayed unchanged. This is the focused correction result; complete-run and artifact results for the final source checkpoint belong to the delivery record.

## Documentation clarification and preserved evidence

KL-05 in [known limitations](known-limitations.md) and the Widget presentation row in the [phone cross-check](ux-phone-cross-check.md) now state that an old scheduled palette can persist in an **idle live process as well as an absent process**. A real repository/link/plan-boundary redraw or another actual render reevaluates the palette; crossing theme time alone does not. Plan-boundary observation requires a running process. Root retained this documented limitation; no user acceptance or new timer is claimed. Both applied rows exactly match the reviewed proposal.

The handover and current widget/cross-check introductions refer to the final delivery record instead of carrying a soon-stale blanket execution status. Historical source-pinned ledgers, failed runs, old review-time pending observations and physical NOT RUN qualifications are retained. The earlier [d8 gate JSON](ux-gate-at-d8.json) remains byte-identical to the failed run, including its five failures and debug/lint/release tasks not reached. [Fixture evidence](ux-full-gate-fixture-evidence.md) preserves the distinction between the untraced original 1→3 clock result, later Repository trace, deliberate 1→4 minute-loop mutation, exact restoration and 8/8 focused GREEN.

Physical BedJet receipts, phone-away return, Samsung launcher/Doze, TalkBack, reboot/force-stop, overnight timing and battery acceptance are not established by these reviews or host runs. R4 Health Connect writing and R5 import preview/blanket queued-restore exclusion remain **Missing**. Follow the [phone guide](ux-phone-test-guide.md) for separately recorded device checks.


## Subsequent visual review: remembered name is not connection evidence

The complete `ux-combined-full-gate-4` at **`14e373a0552b0642ba54a6d5541a9d755e8daef5`** passed in **1,224.72 seconds**, exit 0: core **240/240**; app **1,342 total, 1,304 passed, 38 expected skips**, zero failures/errors; lint **0 errors / 14 warnings / 1 hint**, with an exact baseline finding multiset. All five requested tasks, including release assembly, completed and all **636 tracked hashes** were unchanged. The [exact historical gate record](ux-gate-at-14e.json) remains separate from the next source checkpoint.

The subsequent 102-image host review exposed a concrete wording defect in Settings: `SettingsScreen.kt:136` always renders `settings_connected_unit` around `AppViewModel.bedJetName()`. That name can be a remembered, target-bound display name without a current connection or receipt (`AndroidBedJetLink.kt:267`). The original **“Connected unit”** label therefore asserted more than this data proves. The passed 14e gate and its test APK do not close this later finding; the 14e APK is not the delivery candidate.

The initial test-only reproduction, `ux-settings-selected-red`, failed its single intended rendered-text assertion in **103.27 seconds**. During review, the first proposed **“Selected BedJet”** label was rejected because a selected target can still have no name, causing the existing **“none yet”** fallback to imply no selection. The minimal final wording is **“BedJet name: %1$s”**. It accurately describes the named, unnamed-selected and Demo cases without changing connection behaviour.

The final unchanged assertions first failed against the old production resource in **`ux-settings-name-red`**: **2 tests / 2 intended failures / 0 errors or skips**, **63.37 seconds**. `MoreReleasePlacementTest.rememberedNameDoesNotClaimConnection` requires **“BedJet name: BEDJET-BEDROOM”**; `aSelectedUnnamedBedJetShowsTheNameFallbackWithoutLosingItsSelection` requires **“BedJet name: none yet”**. Both use the actual Settings screen and repository, preserve the selected target, and assert no READY connection or current device report.

Only after those failures, one production resource value changed. **`ux-settings-name-green`** then passed the complete class **6/6**, including the existing Release/Take back owner control, with zero failures/errors/skips in **75.23 seconds**. Fresh lint remains **0 errors / 14 warnings / 1 hint**, exactly matching the baseline. The focused source delta and XML are archived with that run. Review of the exact resource/test change and the target-name binding found no further concrete defect in this seam. No timer, transport, owner, receipt, haptic or signing behaviour changed.

The full cumulative review is carried forward by unchanged-file hashes and this supplemental complete-file/delta review. The next full gate, fresh capture comparison, test APK and merge are reported with their actual source in the final delivery record and [PR #3](https://github.com/Willzy12h/bedjet-android/pull/3); this source note does not pre-claim those executions. The host wake-picker dial remains crowded at 200% and Samsung readability remains a phone check. Legacy Remote images show the disconnected Demo state rather than proving connected-control appearance. No device or independent sign-off is claimed.

# Temporary night actions implementation plan

**Goal:** Offer a timed Turbo/Heat/other supported action during a running night, automatically returning to the underlying schedule at its original wake, alongside the existing end-night action.

**Architecture:** One composite on-device programme, issued by NightActions, with the same night record ID, a new revision and therefore a new `NightIdentity`. Optional immutable temporary metadata preserves the base and expanded prefix. No phone resume timer. Captured typed requests cross UI/service/widget/shortcut boundaries.

**Tech stack:** Kotlin, kotlinx.serialization/coroutines, Compose, core JUnit 5, app Robolectric JUnit 4 SDK 34.

**Spec:** `docs/superpowers/specs/2026-10-26-temporary-night-actions-design.md`.

## Global constraints

- Use the tested fixes2 source `e114cc25481731e052a0abc564e74775666ec47e` as the local starting checkpoint; preserve the independently advanced remote work, including `367968b`, and publish only `gpt/nocturne-fixes2` by normal fast-forward.
- Do not touch production signing, merge PRs, rebase or force-push.
- Tests first: save actual failing assertions, then passing results. Compilation errors are not behavioural RED evidence.
- All new app text in resources, UK English; pending/failed/unknown/confirmed stay truthful.
- No hardware, Samsung or TalkBack verification claims.
- All Gradle work uses `/workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py`, which serialises the shared build environment and strips signing variables.
- Final publication creates an immutable code commit as a normal child of the latest reviewed remote, with the exact tested local tree, then a documentation child naming the code SHA/results. Move the ref once with expected-head protection and `force=false`. The old prepared `173edc8…` object was never published and is superseded; it is not a required ancestor.

## Review focus

Exact target/night ownership; immutable presets and base schedule; no replayed Turbo; old/new wake and clock-step guards; 32-step capacity; cancellation at durable publication boundaries; no background delayed START; receipt/fresh-read confirmation; original alarm and pending observation semantics; clear planned-return wording and accessible choices.

## Task 1 — Core composite planner and durable transaction

- [x] Add core regressions for temporary prefix plus absolute remaining schedule, repeated action, no wake drift, Turbo/gap handling, limits/capacity/DST, refusal confidence/identity and alarm re-time.
- [x] Execute them against the old behaviour/API through minimal test scaffolding and record observed RED; do not count a missing-symbol compile failure.
- [x] Add optional `TemporaryOverride` to ActiveNight, `Block.TEMPORARY`, `ChangeKind.TEMPORARY` and `NightRunner.prepareTemporary(night, expected, preset)`.
- [x] Keep the base immutable; preserve expanded prefix for re-time; update resume offer handling and all pure-core enum consumers.
- [x] Add a first-PENDING-publication cancellation regression; move publication inside the guarded transaction and bound request-specific durable cleanup.
- [x] Run focused core tests to GREEN, then the complete core suite. Commit only owned core files and record exact evidence labels.

Evidence: `temp-core-red` 26 tests / 23 assertion failures; `temp-core-boundaries-red` 7 / 7; `temp-core-full-green` **224 passed**, 0 failures/errors/skips. Core commit `b00fcbb3daeaa10c5e3a1500f424a82cbd5d8d4d`, integrated as `17fb5ce`. The complete core run includes 32 temporary, five publication-cancellation and 187 existing cases.

## Task 2 — Command owner and service handoff

- [x] Add app regressions for captured temporary requests, queued stale target/identity, input validation, single confirmed composite, pre/post-upload takeover, cancellation, and pending alarm handoff.
- [x] Observe RED with a minimal route before implementing dispatch.
- [x] Extend CommandRequest with optional immutable `actionPreset`; add Command.TEMPORARY and a capture helper, strict identity and slot backup barrier. Honour the same captured preset on the existing End-tonight quick routes; old callers retain their settings-based defaults.
- [x] Run prepareTemporary and apply under the existing owner; include temporary in confirmed-programme repair and schedule/History settlement without terminal events.
- [x] Extend BedJetService intent encoding/decoding with fail-closed preset validation; preserve origin and timeout hierarchy.
- [x] Map new planner/eligibility reasons to string resources. Add a small shared presentation/preset helper for UI and native entry points if needed; avoid duplicating policy.
- [x] Verify pre-write pending-alarm handoff through confirmed-publication restart, preserve quiet pending notification state, and repair only an existing History applied revision without invented confirmation/terminal events or BLE replay.
- [x] Observe a genuine write-failure RED showing that failed local History revision repair can block pending-alarm restoration.
- [x] Make local History revision repair best effort for ordinary exceptions while propagating cancellation.
- [x] Verify the History write-failure/alarm-restoration regression GREEN in the complete app test run; keep overall gate status separate from its packaging result.
- [x] Run meaningful focused owner/service regressions to GREEN; commit only owned runtime/service/helper/resource files.

Evidence: owner RED 9 / 5; service RED 5 / 5; `temp-owner-integration-red` **32 tests, 30 passed, two captured-wording failures**; `temp-owner-restart-red` **3 tests / 1 genuine restart-handoff failure**. The invalid notification fixture with an unformatted `%1$s` is excluded; corrected `temp-owner-notification-red-valid` had **1 test / 1 genuine failure**, followed by `temp-owner-history-red` **2 tests / 1 missing-History-revision failure**. All these regressions, recovery exclusions and idempotence pass in **`temp-owner-complete-green`: 140 passed, 0 failures/errors/skips, 359.77 s**. Source commit **`3c18c2bbf16c880168635ac2f6644be4bcef89d9`** contains the six owned files. The later **`temp-history-io-red`: 1 test / 1 assertion failure, 0 errors/skips, 64.40 s** reproduced an escaped `IOException` aborting alarm restoration. Its exact regression is now **GREEN** in attempt 2's full app execution: **24/24 TemporaryActionBoundariesTest cases passed**, fresh XML timestamp **2026-10-26T01:31:07.373Z**. That app-test result is independent of the invocation's failed packaging and is not inferred from the earlier 140-test run. See the [verification record](../../review/temporary-actions-verification.md) for exact suite counts.

## Task 3 — Actual UI choice and related entry points

- [x] Add actual Compose/native dialogue tests for Temporary/End/dismiss, captured presets, eligibility/refusal text and 320 dp/200% scrolling.
- [x] Observe RED against the current replacement-only prompt.
- [x] Extend NightGuard without changing unrelated recovery prompts; wire Tonight quick actions, Remote mode/Boost and widgets/shortcuts to typed requests.
- [x] Provide one-action duration controls and truthful planned return text on the running night. Off keeps its existing ending action and tracked-night confirmation; it never offers temporary return. Keep wake Snooze/Extend manual and reconnect UI visible.
- [x] Add string resources, Block.TEMPORARY curve/text alternatives and appropriate accessibility labels.
- [x] Run focused UI tests and unchanged U06 Remote regressions to GREEN; commit only owned UI/platform/test/resource files.

Evidence: actual-choice RED **6 / 6** across four Compose looks and two native entries; Simple temporary-label RED **6 / 1**; further captured End/limits/confidence regressions recorded. `temp-ui-progress-green` passes **2 / 2** for the actual ViewModel mode-plus-step-0 ending boundary. The wider control run had **27 tests, 26 passed and one fixture scheduling failure**; its curve, accessory, reconnect and unchanged U06 cases all passed, with the captured-ending fixture corrected in the subsequent two-case GREEN. **`temp-ui-feature-green` had 28 tests, 27 passed and one fixture timing failure, 164.92 s**: the stale-dialogue test mutated the revision before its first composition. The fixture now establishes the selected/enabled Temporary choice before mutation without changing production behaviour or weakening stale-action assertions. **`temp-ui-feature-green-2`: 28 passed, 0 failures/errors/skips, exit 0, 109.91 s**. UI commit **`5ccd8654b27526c4687605d216c676617f66c77f`** is integrated as **`4613d9c91795afe5e98091209ad702156f4e89b9`**. Midnight choice and scrolled Simple Daylight screenshots were inspected at 320 dp/200% text as host-rendered evidence only. The temporary duration slider changes only the temporary request; Quick/native End retains the original captured duration, while ordinary Remote End adds no timer.

## Task 4 — Integrate, independently review and publish

- [x] Integrate isolated commits and resolve shared resource/enum contracts.
- [x] Close the independent review against the spec and record remaining findings.
- [x] Add phone-test-guide rows and document BLE behaviour (the composite runs without an added phone wake or connection).
- [x] Check the current remote branch and preserve concurrent changes before normal publication.
- [x] Run exact gate: `./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
- [x] Run the same flags with `:app:assembleRelease`; inspect the unsigned output without altering signing.
- [x] Count XML totals/passes/skips/failures/errors; compare lint IDs/severity/messages/paths; record immutable source hashes through the full gate.
- [x] Confirm all 273 source hashes still match after release in both main source and clean build.
- [x] Create the portable gate/release JSON record for the final documentation child.
- [x] Update HANDOVER with final code SHA/tree, files, RED/GREEN, protocol citations, BLE before/after, counts, chosen temporary-action defaults and remaining physical verification.
- [x] Fast-forward only the authorised branch with expected-head protection; report exact published SHA and a device-test readiness verdict based on observed results.

The exact gate **PASSED** as `temporary-clean-full-gate` in `/workspace/scratch/6cc8f67848d1/nocturne-gate-clean`: exit 0, **1,034.75 s**, **59 actionable tasks, all 59 executed**. Fresh core **224 passed**, 27 XML suites; fresh app **1,069 total, 1,031 passed, 38 skipped**, 110 XML suites; **0 failures/errors**. Debug assembly passed. Lint remains **0 errors / 14 warnings / 1 hint**, with no new or removed findings by ID/severity/message/normalised path. Both main source and clean build match all **273 inputs** after the gate.

Final local code **`4d2f2dcf0c72999299997f129d47a536c054b45f`** and immutable GitHub code **`a4411b1cb7fc2769d2e11d84685e3748b3f371d8`** have the exact tested tree **`7e52367f6df981fa6d904b3d027ad9832412ff5b`**. The GitHub commit is a normal child of latest verified remote **`367968bddc390e5b2bb12876fc1662ffa07e47c3`**; delivery preserves it beneath the documentation child on the authorised branch. Separate unsigned `temporary-clean-release` **PASSED**, exit 0, **144.22 s**. The completed feature verification and [portable gate/release record](../../review/temporary-actions-gate-results.json) are included in the documentation child. The delivery inventory is **86 paths: 68 non-documentation and 18 documentation**.

Release capture qualification: the wrapper's log is partial. Matching `daemon-completion.txt` identifies daemon 204494's clean-build invocation and records **BUILD SUCCESSFUL in 2m 23s; 51 actionable tasks, 48 executed, 3 up-to-date**. The fresh unsigned APK is **4,568,127 bytes**, SHA-256 **`fffd4034e629b4dfaf981e6ac38f040570ae14c7197a452f98c00148aab20a23`**. ZIP CRC passed and manifest/DEX entries are present. `apksigner` exits 1 with the expected unsigned-output non-verification; release signing was explicitly unconfigured and production signing untouched. Both source copies still match **273/273 hashes after release**.

First attempt `temporary-final-full-gate` failed in **106.23 s**, exit 1, at generated debug DEX merging. Its fresh core result was **224 passed, 0 failures/errors/skips**; app unit execution and lint were not reached. D8 found the same 38 generated classes in a new project DEX jar and an obsolete jar; `dex-diagnosis.json` preserves the evidence. Only generated debug DEX/incremental/merged outputs and test-result directories were cleared before the exact retry. All 273 source hashes remained unchanged. This environment-artifact failure is not product RED and did not complete the gate checkbox; the later clean gate supplied that evidence.

Second attempt `temporary-final-full-gate-2` failed in **916.42 s**, exit 1, on the same generated DEX collision. Its app tests actually executed: **1,069 total, 1,031 passed, 38 skipped, 0 failures/errors**, 110 fresh XML suites, including the History I/O GREEN. Core was **`UP-TO-DATE`**, reusing the 224 passed actually executed in attempt 1; copied 01:21 UTC core XML is not a second fresh execution. Lint analysis began but its report did not complete. Obsolete output reappeared after cleanup, so the next exact gate uses the clean immutable archive with unchanged source.

Independent read-only review of that exact tested tree found no remaining concrete blocker in the requested feature and known-fix scope. The reviewed boundaries include History I/O/cancellation, durable alarm handoff and pending notice, captured identity/preset, duration-only edits, split/clamped previews, UNKNOWN/manual return suppression and programme-step-0 ending. The clean gate subsequently passed, including fresh execution of the History I/O regression.

**READY FOR DEVICE TEST.** The phone-guide rows remain supervised future checks marked **NOT RUN**. Historical local `e114cc2` and concurrent remote `367968b` gates stay separately pinned; current counts come from the successful clean gate, and the separate unsigned release passed. Documentation and portable JSON are complete. Delivery uses the authorised branch with expected-head protection and `force=false`, and the verified final head is reported separately. No device, Samsung, TalkBack or signed/minified-release runtime verification is claimed.

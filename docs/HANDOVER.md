# Nocturne — source handover

## GPT-11 UX source handover

The `gpt/nocturne-ux` continuation starts from the pushed Step A tip **`367968bddc390e5b2bb12876fc1662ffa07e47c3`** and incorporates the later fixes2 Temporary feature through **`655993d2ea3d5021a2fab75ecf7db5f77d699f7e`**. The [final source self-review](review/ux-final-source-review.md), [integration history](review/ux-self-review-pass1-integration.md), [phone cross-check](review/ux-phone-cross-check.md) and [next phone checks](review/ux-phone-test-guide.md) describe the implementation, evidence and limits.

Normal alarm selection explains its **1–18-hour** window; the explicitly confirmed five-minute Settings equipment test changes no phone alarm. A configured Boost longer than ten minutes uses **Turbo 10 minutes, then Heat for the remainder**. Temporary return is part of the uploaded BedJet programme and requires physical phone-away testing. The stopping choice is **Stop night**. Reviewed Off/Stop requests retain their captured night identity; only unconfirmed emergency Off keeps its existing same-target identity exemption. R4 Health Connect writing and R5 import preview/blanket queued-restore exclusion remain Missing.

For final execution JSON, tested-source and merge identifiers, the installable test APK, its hash/certificate and the complete 0–12 delivery handover, consult [PR #3](https://github.com/Willzy12h/bedjet-android/pull/3) and the metadata accompanying the APK. The user's instructions require the existing test certificate and authorise merge after completion; no production release is authorised. Historical source-pinned results below apply only to their recorded checkpoints. Host evidence does not establish physical phone or BedJet acceptance.

## Historical incoming fixes2 Temporary handover

The following source-pinned handover and counts are retained as historical evidence for the incoming Temporary feature. References to current delivery, completion or publication in this section describe that earlier fixes2 checkpoint, not the GPT-11 UX integration.

**READY FOR DEVICE TEST — 26 October 2026. Full gate and unsigned release PASSED.** The current work adds **Temporarily, then resume** alongside **End tonight** to the original F1–F5, protocol, alarm and BLE/reconnect fixes. The tested immutable GitHub code commit is **`a4411b1cb7fc2769d2e11d84685e3748b3f371d8`**, tree **`7e52367f6df981fa6d904b3d027ad9832412ff5b`**, identical to local code commit **`4d2f2dcf0c72999299997f129d47a536c054b45f`** by tree. It is a normal child of preserved remote **`367968bddc390e5b2bb12876fc1662ffa07e47c3`**. Delivery is restricted to `gpt/nocturne-fixes2`, through a documentation child of that tested code commit.

The exact clean gate passed in **1,034.75 seconds**, exit 0: **224 core passed; 1,069 app total, 1,031 passed, 38 skipped; 0 failures/errors; lint 0 errors, 14 warnings, 1 hint, with no new or removed findings**. Separate unsigned release passed in **144.22 seconds**, exit 0. Debug assembly passed, and both the source workspace and clean build copy retain all **273/273** non-documentation hashes after release. Use [temporary-action verification](review/temporary-actions-verification.md) for the complete file inventory, actual RED/GREEN evidence, failed-attempt provenance, release-capture qualification and chosen defaults. Its [design](superpowers/specs/2026-10-26-temporary-night-actions-design.md) is authoritative. All requested host checks are complete. The documentation child preserves the tested code and records its delivery evidence. Historical gates below retain their own source pins.

## Current temporary-action decisions

Temporary uploads one composite programme: the requested interval followed by the remaining immutable night schedule. It keeps the same night record ID, with a new revision and therefore a new `NightIdentity`; the original wake stays fixed and elapsed phases are skipped. A repeated action uses the retained underlying schedule. Return is planned on the BedJet; there is no later phone START, resume alarm, permanent connection or new confirmation event just because the phone clock reaches the planned return.

Eligible actions capture the target, exact night/revision and actual preset. Quick actions begin with saved durations; Remote temporary modes begin at 10 minutes, clamped to mode/profile limits; Turbo alone is limited to 10 minutes, while configured Boost can continue as Heat. The duration control changes only the temporary request. Quick/widget/shortcut **End tonight** retains the original captured preset and duration; ordinary Remote mode **End tonight** keeps its existing mode-only behaviour without adding a timer. Dismissal sends nothing. Off keeps its existing ending action and tracked-night confirmation; it never offers temporary return. Wake Snooze/Extend, sliders, Run for, Lights and Beeps retain their meanings.

Only confirmed, non-manual, current-target nights with a usable immutable snapshot and enough pre-wake/32-step capacity are eligible. Pending/failed/unknown/stale cases must refuse before writes. Raw receipt/fresh-read confirmation and UI/service/owner budgets of **115/100/90 seconds** remain authoritative. Stop tracking is local; physical Off or a replacement programme can stop the stored continuation, while a physical fan/temperature change may leave it running. No physical behaviour has been verified here.

The lights/beeps comparison needed no codec reversal: `setLed(true/false)` remains `0x46/0x47`; `setMuted(true/false)` remains `0x48/0x49`; byte 27 uses `0x10` for LED enabled and `0x01` for beeps muted, so Remote's **Beeps** switch displays `!beepsMuted`. Sources: immutable [Lull command encoder](https://github.com/rkanjani/lull/blob/f98821a6f53cbe80c00fc1ba11396a9433b322f0/BedJetKit/Sources/BedJetKit/Codec.swift#L33-L34), [Lull status decoder](https://github.com/rkanjani/lull/blob/f98821a6f53cbe80c00fc1ba11396a9433b322f0/BedJetKit/Sources/BedJetKit/Codec.swift#L137-L142) and [ESPHome status layout](https://github.com/esphome/esphome/blob/06bd92c3880c575a366f7b8e6fb77f74fd200937/esphome/components/bedjet/bedjet_codec.h#L103-L115). Matching-report switch tests pass; physical toggle rows remain **NOT RUN**.

| Current evidence | Status |
|---|---|
| Core temporary planner and first-PENDING cancellation | **224 passed, 0 failures/errors/skips** in `temp-core-full-green`; core source `b00fcbb3daeaa10c5e3a1500f424a82cbd5d8d4d`, integrated as `17fb5ce`. This is the complete core run, not the final combined gate. |
| Owner/service integration | `temp-owner-complete-green`: **140 passed, 0 failures/errors/skips**, exit 0, **359.77 s**, at source `3c18c2b…`. Includes 9 owner, 23 temporary boundary, 5 service and 103 retained cancellation/receipt/alarm/runtime cases. Earlier captured-wording, restart, quiet-notification and existing-History revision REDs now pass. |
| History I/O follow-up | `temp-history-io-red`: **1 test, 1 assertion failure, 0 errors/skips**, exit 1, **64.40 s**. The repair catches ordinary exceptions while propagating cancellation. The exact regression passes in the final clean gate's **24/24 TemporaryActionBoundariesTest cases**. Its first GREEN was in attempt 2's app execution; that earlier invocation still failed packaging. |
| UI / native entry points | `temp-ui-feature-green-2`: **28 passed, 0 failures/errors/skips**, exit 0, **109.91 s**: 4 dialogue, 10 dispatch, 6 native, 2 captured-ending and 6 curve cases. UI source `5ccd8654b27526c4687605d216c676617f66c77f` is integrated as `4613d9c91795afe5e98091209ad702156f4e89b9`. The first 28-case attempt had one fixture timing failure; the corrected fixture established the already-open selected/enabled Temporary state before mutating the revision, retaining the stale-action assertions without a product change. |
| Independent source review | Read-only closure against tested tree `7e52367…` found no remaining concrete blocker in the requested feature and known-fix scope. The subsequent full gate and separate unsigned release passed. |
| Final core / app execution | `temporary-clean-full-gate` freshly executed **224/224 core tests**, 27 XML suites, and **1,069 app tests: 1,031 passed, 38 skipped**, 110 XML suites. Both modules have **0 failures/errors**. All **59 actionable tasks executed**. |
| Debug / lint / source integrity | Debug assembly **PASSED**. Lint **0 errors / 14 warnings / 1 hint**; exact multiset comparison by ID, severity, message and normalised path found **no new or removed findings**. Both source and clean build retain **273/273 matching input hashes** after gate and release. |
| Unsigned release | `temporary-clean-release` **PASSED**, exit 0, **144.22 s**. Matching daemon completion records **2m 23s**, **51 actionable tasks: 48 executed, 3 up-to-date**. The wrapper's captured log is partial; completion is supported by its exit code, the matching daemon log and the fresh inspected APK. |
| Publication | Tested code `a4411b1…` and its documentation child are delivered only on `gpt/nocturne-fixes2`, preserving remote `367968b…` through a normal fast-forward with `force=false`. The final documentation head is reported separately after remote verification. |

The 140-test owner run verifies the pending-alarm handoff through a confirmed revision/restart, truthful quiet-notification state and existing-History revision repair without invented confirmation or terminal events. The later History I/O case now has observed RED and GREEN in the complete app test execution. Host-rendered Midnight choice and scrolled Simple Daylight schedule screenshots were inspected at 320 dp/200% text, with readable text and both action buttons visible. This is Robolectric visual evidence, not a physical accessibility check. App-facing text stays in resources and UK English; the retained Remote card and immediate control-field updates remain part of the complete app regression coverage.

The first two gate attempts remain recorded: **106.23 s** and **916.42 s**, both exit 1 on a generated DEX collision. D8 found `androidx.activity.compose.R` in a new project DEX jar and an obsolete jar with the same 38 defined classes; `temporary-final-full-gate/dex-diagnosis.json` preserves the evidence. Dependencies/build configuration were unchanged from the passed original checkpoint. Attempt 2's full app tests passed, while core was `UP-TO-DATE` from attempt 1 and lint did not finish. The clean immutable archive, with no prior build directories and identical input hashes, then passed the entire gate. The failed packaging attempts remain separate from product RED evidence and the successful clean run.

The final release APK is `app/build/outputs/apk/release/app-release-unsigned.apk` in the clean build copy: **4,568,127 bytes**, SHA-256 **`fffd4034e629b4dfaf981e6ac38f040570ae14c7197a452f98c00148aab20a23`**. ZIP CRC inspection passed and `AndroidManifest.xml`/`classes.dex` are present. `apksigner` returned exit 1 with `DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`, consistent with the explicitly unconfigured release signing. Production signing was untouched. The wrapper log stops early; `temporary-clean-release/daemon-completion.txt` identifies the matching clean-build invocation and its successful completion, and `release-inspection.json` retains that limitation. This is packaging/artefact evidence, not installation or minified-runtime verification.

Immutable GitHub code commit **`a4411b1cb7fc2769d2e11d84685e3748b3f371d8`** is already created as a normal child of **`367968bddc390e5b2bb12876fc1662ffa07e47c3`**, with the exact tested tree. This documentation child names that code SHA and the complete results. The authorised publication uses expected-head protection for `367968b…` and `force=false`; the delivered documentation head is verified and reported separately. The old prepared object **`173edc81ccdb97b262aff6a91dfb6d536f1784b6` was never published and is superseded**; it is neither the final source nor a required ancestor.

## Historical local fixes/reconnect checkpoint

The original local fixes/reconnect source at **`e114cc25481731e052a0abc564e74775666ec47e`** passed its own full gate and separate unsigned release. [fixes2 verification](review/fixes2-verification.md) preserves that immutable evidence, its original 52-file inventory, protocol citations and BLE before/after. Its historical prepared-object/publication discussion does not designate the final temporary-feature commit. The following results belong only to that original source.

The work addresses F1 manual cancellation, F2 the captured restore original, F3 unchanged alarm observations, F4 the long restore preview and F5 Off/Pause fan alternatives. Lights/beeps already match the cited public protocol and have explicit command/bit/UI-confirmation tests. The alarm explanation preserves the existing unknown-source default (**true**) and saved choice; Android exposes only the **next** alarm, not a multi-alarm list. Null creator and outside-window rejection are possible code paths, not a diagnosed Samsung fault.

Hidden UI collection stops, and the last connection holder starts an approximately 30-second disconnect grace. Owner work retains raw status demand and its connection hold. Only actual/ambient sensor temperatures are sampled at most once per second; mode, setpoint, fan, timer, accessory, programme-progress and limit fields update immediately. Sampling the whole status had delayed existing U06 mode/switch feedback; the unchanged controls reproduced **8 assertion failures in 8 tests**. The correction passed a separate **22-test GREEN**, including the unchanged mode/timer, switch and slider assertions.

The user's later amendment keeps the same target's last-known Remote UI visible through app-switch reconnect, with **Last reported**, the real connection indicator and current-report control gating. Its focused combined run passed **23 tests, 0 failures/errors/skips**, including the actual Remote screen and receipt safeguards. The later sampling GREEN also passes the actual Remote reconnect cases. The earlier hidden-value-null expectation is superseded by the retained-UI requirement, while receipt-based confirmation and **UI 115 s > service 100 s > owner 90 s** remain unchanged.

Completed targeted evidence includes **104 runtime review-boundary tests**, **82 subsequent unchanged-observation/expiry tests**, **14 F4/F5/uncertainty tests**, **12 core + 4 app protocol tests**, **55 alarm tests**, **18 Settings/diagnostics integration tests**, **73 BLE demand tests**, **14 idle-telemetry/receipt tests**, **23 reconnect tests**, **18 lifecycle expectation tests** and **22 final sampling/control-feedback tests**, all passing within their named runs. These suites overlap and are not a full-suite total. The alarm GREEN follows an explicit **22-test / 1-failure** saved-override regression; the correction preserves the clickable saved-override wording and diagnostic reason without weakening existing wake-feedback assertions.

The [52-path changed-file inventory](review/fixes2-verification.md#changed-file-inventory) describes that historical checkpoint. Its tree is **`41a0b7ccba8393b459e26377299f6355d0ceb433`**. All **259 tracked non-documentation input hashes** remained unchanged through its gate/release. **`fixes2-full-gate` passed**, exit 0, **848.80 seconds**; **`fixes2-release` passed**, exit 0, **157.22 seconds**. That source does not contain the temporary feature.

The temporary-action work described at the top extends this checkpoint and has its own passing full gate, unsigned release and immutable code pin. The combined delivery uses a documentation child of the tested code pin above.

## Historical local gate and release results

The exact gate passed on `e114cc2`. The same commands must be executed again for the final temporary-feature source:

```sh
./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The separate release command also passed under label `fixes2-release`:

```sh
./gradlew --no-daemon --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease
```

| Full-gate check | Exact result |
|---|---|
| Core tests | **187 total, 187 passed, 0 failures/errors/skips**. |
| App tests | **995 total, 957 passed, 38 skipped, 0 failures/errors**. |
| Debug assembly | **PASSED**. |
| Lint | **0 errors, 14 warnings, 1 hint**; no new or removed findings against the baseline by ID, severity, message and path. |
| Input integrity | **259 / 259 non-documentation hashes unchanged**, including after release assembly. |
| Release assembly | **PASSED**, exit 0, **157.22 seconds**; unsigned APK, **51 actionable tasks: 48 executed, 3 up-to-date**. |
| Publication boundary | Not published as a separate final delivery; prepared object `173edc8…` is unused and superseded. The final combined code and delivery boundary are recorded above. |

That historical unsigned release output was `app/build/outputs/apk/release/app-release-unsigned.apk`, **4,523,115 bytes**, SHA-256 **`ef6ac3fe25d2ee98fd53bba6ba93b680f9088ab5cbb963f14676e8d1f43f4307`**. This hash is not the future combined-feature artefact. Production signing remains outside this work.

The supplied base counts — core **175**, app **889 total / 38 skipped**, lint **0 errors / 14 warnings + 1 hint** — remain historical comparison values. All physical Samsung, BedJet, TalkBack, installation, battery and minified-APK runtime results remain **NOT RUN**; use the [phone-test guide](review/phone-test-guide.md) for the supervised checks.

## Historical concurrent remote gate

Remote `367968bddc390e5b2bb12876fc1662ffa07e47c3`, tree `f1d7b8638693d1d161891ae331c8e68d868f0dff`, has a separate preserved gate: **187 core passed; 1,001 app total, 963 passed, 38 skipped; 0 failures/errors; lint 0 errors / 14 warnings / 1 hint**. Its combined gate/release run took **922.48 seconds**. [Concurrent verification](review/fixes2-concurrent-verification.md) and the [retained JSON](review/fixes2-gate-results.json) identify that source and evidence. Those are the concurrent continuation's results, not a current test union or temporary-feature gate.

Its additional alarm/display tests are preserved in the integration. `temp-remote-union-green` passed **50 focused cases** on the retained production behaviour; that preservation check does not verify the new temporary feature. The later user's retained-Remote instruction remains authoritative over the older clear-on-hide behaviour.

## Historical final-fixes base

The preceding `gpt/nocturne-final-fixes` work started from integrated base `c9befe66f0b78fff4fe9da15d217895ca4b48eee`, which includes the earlier workstreams. It closed IR-31's individual uncertainty labels, replaced core enum spellings in user-facing mode errors, and fixed U-B4's narrow, large-text wake title. [Final-fixes verification](review/final-fixes-verification.md) records that earlier gate and release result; it is not a gate for fixes2. The following source pins and acceptance limits are retained as historical context.

## Earlier docs2 source references and ownership

The earlier documentation-only re-pin started from `gpt/nocturne-docs2` at `ab6f1e1f83a587f24f258ebb2e753b6c86e15354` and inspected these references independently. They had not been merged into that documentation branch at that checkpoint. The [re-pin record](review/docs2-repin.md) retains those immutable citations; its source-review and historical test counts are not a current integration result.

| Reference branch | Exact reviewed commit | Scope |
|---|---|---|
| `gpt/nocturne-runtime2-final` | `db781861c4ca7e19d21ce7045e65dfcf3bba0dc4` | D2 confirmation, M1/M5 notices, M2 Off ordering, D4 Take back and D5 Resume policy. |
| `gpt/nocturne-haptics` | `4f14651f1c3389e73825a398e7a0fc25dfaa5404` | In-app haptics, charger refusals, diagnostics, terminal re-time History, START attribution and later receipt safeguards. |
| `gpt/nocturne-gaps` | `11ee133354261a0ac4c9470d8de722c6b48e260c` | UI fixes, bounded IR-17 coverage, contrast, History presentation, backup-original visibility and remaining layout limits. |
| `gpt/nocturne-u06` | `66825099193256ad64d08f087cb0b4d08250a4b3` | Owned U06 layout work. Contrast evidence is separately pinned to the gaps branch. |

During that earlier docs2 pass, GPT-5 changed documentation only, preserved the owned register rows and left integration decisions with Claude. That restriction describes the earlier pass, not the integrated final-fixes base. The candidate at `262c52f`, F1 approval at `c15c515` and U06 adjacent-row merge conflict remain historical checkpoints in the [earlier reconciliation](review/docs2-reconciliation.md#merge-compatibility-one-known-documentation-conflict).

## Integrated-base behaviour and earlier evidence

[nocturne-completion.md](review/nocturne-completion.md) is the authoritative IR-01–IR-32 record. **IR-31 is fixed:** Settings displays `settings_original_uncertain` only for an uncertain record in the matching originals list, read-only **From backup** group and restore dialogue. [`UncertainOriginalsSettingsTest`](../app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UncertainOriginalsSettingsTest.kt) passed **6/6**, covering uncertain and certain records on all three surfaces after three genuine missing-warning failures. The separate **KL-01** limitation remains: missing v1 empty-slot provenance cannot be recovered.

**U-B4 is fixed:** [`TonightScreen.wakeOriginLabel`](../app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt) uses **“Wake tonight only”** for a timeline at width ≤320 dp and font scale ≥2. [`TonightWakeLabelLayoutTest`](../app/src/test/kotlin/io/github/willzy12h/nocturne/ui/TonightWakeLabelLayoutTest.kt) passed **8/8** after the two Simple 320 dp / 200% cases demonstrated within-word splits. The six ordinary-theme, normal-font and wider-layout controls pass. Native picker readability remains U-B5, **OPEN** for phone acceptance.

**A2 core mode wording is fixed:** plan and backup errors now use readable mode names, including **Gentle heat**. The core suite passed **175/175**, with no failures, errors or skips; the six added cases are [`PlanErrorModeNameTest`](../core/src/test/kotlin/io/github/willzy12h/nocturne/core/plan/PlanErrorModeNameTest.kt) (**5**) and [`BackupModeNameTest`](../core/src/test/kotlin/io/github/willzy12h/nocturne/core/store/BackupModeNameTest.kt) (**1**). These results and their execution scope are recorded in [final-fixes verification](review/final-fixes-verification.md); they do not supply translated core messages.

IR-17 retains bounded named-test coverage. IR-07 deliberately sends one terminal notice when a pending pre-wake re-time expires. See [items 1–4](review/docs2-repin.md#1-authoritative-ir-record) for the earlier source review and the IR-31 update.

The requested runtime changes are implemented at `db78186`. The newest displaced programme receives the Resume offer; an already manual adjustment preserves a valid offer without extending its expiry, and an expired offer is never revived. Boost displaces a programme only when it interrupts a running programme. Quick Off retains confirmation during a tracked night because it ends that programme. Pending, failed, unknown and confirmed remain distinct. See [runtime policy](review/docs2-repin.md#5-runtime-policy) and [Quick Off](review/docs2-repin.md#12-quick-off-confirmation).

At `4f14651`, a live in-app request gets a light tick at command-owner acceptance and a confirm haptic only when that same request returns a BedJet-confirmed outcome. Other origins are silent. An otherwise eligible charger start blocked by unreadable data starts nothing and records at most one PROBLEM notice/notification per bedtime window, without a History night, CSV row or morning feedback. Diagnostics include unreadable files, blocked originals, runtime reason and night confidence. Terminal re-time failure paths write History evidence. START captures and passes the alarm time and creating app to preparation; completed History does not retain that creating-app identity. See [items 6–10](review/docs2-repin.md#6-in-app-haptics).

For U06 contrast wording, after-wake bars meet the tested **3:1** threshold, Quick buttons are identifiable at **3:1** by border or fill, and decorative card borders are exempt from that control-boundary requirement. These are production-token checks in the gaps branch's named `ContrastTest` methods, not rendered-device or general accessibility verification. U06's own branch supplies its separate layout work and register row. See [contrast scope](review/docs2-repin.md#11-u06-contrast-wording).

## Known limitations and backlog

These are limitations or future work, not fixed defects. [Known limitations](review/known-limitations.md) gives each reason, source pin and acceptance boundary.

| Item | Remaining limitation / decision |
|---|---|
| KL-01 | Missing v1 empty-slot provenance cannot be reconstructed. The IR-31 warning fix does not recover omitted originals. |
| KL-05 | Widget theme refresh can lag while the app process is absent. |
| G9 | Unknown or other-device originals are visible read-only; restoring an explicitly chosen source still needs command-owner/repository work. |
| U-B1 | History draws the last revision if it can be parsed, otherwise the originally recorded steps; there is no revision-by-revision curve view. |
| U-B2 | Background outcomes are not queued for later in-app display. |
| U-B3 | A ±1 °C Cool check-in option is not implemented; current Cool/Dry proposals change both fan endpoints by 10 percentage points. |
| U-B5 | **OPEN:** native picker dial readability remains a phone acceptance check. |
| Smart wake | Wake timing driven by the room sensor is not built. |
| D1 | Dialogue taps give haptic feedback through the activity's host view, rather than the dialogue's own view. |
| Charger origin | The outer-timeout fallback sets CHARGER in code, but the saved notification log drops origin; that metadata is source-inspected only. This is an accepted limitation. |
| Alarm provenance | START uses the alarm creator for policy, but History has no permanent creating-app field. |

Unmeasured BLE timing, background scheduling, Lull background advertising, Health Connect access, English-only core messages, unresolved Off tracking and the notice save-to-post gap remain in the limitations record. A saved reason or History event does not establish notification delivery.

## Documents to use

- [Temporary-action verification](review/temporary-actions-verification.md): current combined-feature code pin, full gate/release, file inventory, core/owner/UI RED/GREEN and the guarded publication boundary.
- [Temporary-action design](superpowers/specs/2026-10-26-temporary-night-actions-design.md) and [implementation plan](superpowers/plans/2026-10-26-temporary-night-actions.md): authoritative behaviour and evidence-backed progress.
- [Concurrent verification](review/fixes2-concurrent-verification.md): preserved source-specific gate from remote `367968b`; separate from both local checkpoints and the current feature.
- [fixes2 verification](review/fixes2-verification.md): tested fixes/reconnect checkpoint, per-item evidence, exact full-gate results, release/publication status, protocol citations, alarm policy and BLE behaviour.
- [Final-fixes verification](review/final-fixes-verification.md): historical base targeted evidence, full gate and release results.
- [Phone-test guide](review/phone-test-guide.md): APK delivery and supervised phone checks.
- [Source re-pin and coverage](review/docs2-repin.md): the earlier 13-item review, immutable references and current IR-31/U-B4 dispositions.
- [Completion dispositions](review/nocturne-completion.md): authoritative IR-01–IR-32 record; historical gate results remain labelled.
- [Known limitations](review/known-limitations.md) and [recovery procedures](review/recovery-procedures.md): pending work and practical recovery steps.
- [Supervised acceptance](review/samsung-bedjet-acceptance.md): install and signer checks first, explicit BedJet selection, originals and restore, then runtime/platform/UI cases. Every physical result is **NOT RUN**.
- [Release instructions](review/release-instructions.md) and [owner production settings](review/release-production-settings.md): TEST-ONLY versus production artefacts and version/hash/signer checks, at their stated release-source pins.
- [Register](review/register.md): owned rows and historical base dispositions; fixes2 integration must reconcile its current evidence without overwriting historical pins.
- [Earlier reconciliation and gate](review/docs2-reconciliation.md): the c15c515 documentation pass, not a new result for these references.

## Verification and remaining decisions

The earlier docs2 re-pin was documentation only: no Gradle run was performed for that pass, and its checks covered allowed paths, preserved register rows, source-link existence and line bounds, and cross-document consistency. Its named tests described inspected assertions. The final-fixes host results above are subsequent executed evidence for that base; [final-fixes verification](review/final-fixes-verification.md) records its full gate and release outcome. Neither record supplies current fixes2 counts.

**READY FOR DEVICE TEST.** Independent source review, the exact full clean gate and separate unsigned release have passed; all 273 source hashes match after release in both copies. Local `fixes2-evidence/temporary-final-gate-summary.json` and the [portable gate/release record](review/temporary-actions-gate-results.json) contain the completed evidence. The complete delivery inventory contains **86 changed paths: 68 non-documentation and 18 documentation**, comprising the 84 paths in the tested tree and two new documentation records. The delivered documentation is a child of tested code **`a4411b1cb7fc2769d2e11d84685e3748b3f371d8`** on the authorised branch; its final head is reported separately after the guarded fast-forward and remote verification. Historical `e114cc2` and `367968b` results remain separate. Implementation choices are recorded above; no source-policy change is needed for a null alarm creator. The source explanation and diagnostics support the outstanding supervised phone investigation.

All supervised Samsung, BedJet, TalkBack, physical haptic, installation and minified-APK runtime checks remain **NOT RUN**. The integrated base and host fixes do not establish those results. The remaining work is the recorded backlog and supervised acceptance, including KL-01's original-slot inventory/read-back and U-B5's native picker readability.

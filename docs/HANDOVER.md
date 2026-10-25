# Nocturne — current handover

**Final-fixes handover — 25 October 2026.** `gpt/nocturne-final-fixes` starts from integrated base `c9befe66f0b78fff4fe9da15d217895ca4b48eee`, which includes the earlier workstreams. The current changes close IR-31's individual uncertainty labels, replace core enum spellings in user-facing mode errors, and fix U-B4's narrow, large-text wake title. Use [final-fixes verification](review/final-fixes-verification.md) for the current gate and release results, and the [phone-test guide](review/phone-test-guide.md) for delivery and supervised checks. All physical results remain **NOT RUN**.

## Earlier docs2 source references and ownership

The earlier documentation-only re-pin started from `gpt/nocturne-docs2` at `ab6f1e1f83a587f24f258ebb2e753b6c86e15354` and inspected these references independently. They had not been merged into that documentation branch at that checkpoint. The [re-pin record](review/docs2-repin.md) retains those immutable citations; its source-review and historical test counts are not a current integration result.

| Reference branch | Exact reviewed commit | Scope |
|---|---|---|
| `gpt/nocturne-runtime2-final` | `db781861c4ca7e19d21ce7045e65dfcf3bba0dc4` | D2 confirmation, M1/M5 notices, M2 Off ordering, D4 Take back and D5 Resume policy. |
| `gpt/nocturne-haptics` | `4f14651f1c3389e73825a398e7a0fc25dfaa5404` | In-app haptics, charger refusals, diagnostics, terminal re-time History, START attribution and later receipt safeguards. |
| `gpt/nocturne-gaps` | `11ee133354261a0ac4c9470d8de722c6b48e260c` | UI fixes, bounded IR-17 coverage, contrast, History presentation, backup-original visibility and remaining layout limits. |
| `gpt/nocturne-u06` | `66825099193256ad64d08f087cb0b4d08250a4b3` | Owned U06 layout work. Contrast evidence is separately pinned to the gaps branch. |

During that earlier docs2 pass, GPT-5 changed documentation only, preserved the owned register rows and left integration decisions with Claude. That restriction describes the earlier pass, not the integrated final-fixes base. The candidate at `262c52f`, F1 approval at `c15c515` and U06 adjacent-row merge conflict remain historical checkpoints in the [earlier reconciliation](review/docs2-reconciliation.md#merge-compatibility-one-known-documentation-conflict).

## Current behaviour and evidence

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

- [Final-fixes verification](review/final-fixes-verification.md): current targeted evidence, full gate and release results.
- [Phone-test guide](review/phone-test-guide.md): APK delivery and supervised phone checks.
- [Source re-pin and coverage](review/docs2-repin.md): the earlier 13-item review, immutable references and current IR-31/U-B4 dispositions.
- [Completion dispositions](review/nocturne-completion.md): authoritative IR-01–IR-32 record; historical gate results remain labelled.
- [Known limitations](review/known-limitations.md) and [recovery procedures](review/recovery-procedures.md): pending work and practical recovery steps.
- [Supervised acceptance](review/samsung-bedjet-acceptance.md): install and signer checks first, explicit BedJet selection, originals and restore, then runtime/platform/UI cases. Every physical result is **NOT RUN**.
- [Release instructions](review/release-instructions.md) and [owner production settings](review/release-production-settings.md): TEST-ONLY versus production artefacts and version/hash/signer checks, at their stated release-source pins.
- [Register](review/register.md): owned rows and historical base dispositions; current final-fixes evidence is linked above.
- [Earlier reconciliation and gate](review/docs2-reconciliation.md): the c15c515 documentation pass, not a new result for these references.

## Verification and remaining decisions

The earlier docs2 re-pin was documentation only: no Gradle run was performed for that pass, and its checks covered allowed paths, preserved register rows, source-link existence and line bounds, and cross-document consistency. Its named tests described inspected assertions. The final-fixes host results above are subsequent executed evidence; [final-fixes verification](review/final-fixes-verification.md) records the full gate and release outcome without carrying historical counts forward.

All supervised Samsung, BedJet, TalkBack, physical haptic, installation and minified-APK runtime checks remain **NOT RUN**. The integrated base and host fixes do not establish those results. The remaining work is the recorded backlog and supervised acceptance, including KL-01's original-slot inventory/read-back and U-B5's native picker readability.

# Nocturne — current documentation handover

**Reference pin — 25 October 2026.** This update starts from `gpt/nocturne-docs2` at `ab6f1e1f83a587f24f258ebb2e753b6c86e15354`. It describes the four source references below, read independently. They have **not** been merged into the documentation branch, and this is not evidence for a combined candidate. The [re-pin record](review/docs2-repin.md) gives immutable code and test references. All physical results remain **NOT RUN**.

## Source references and ownership

| Reference branch | Exact reviewed commit | Scope |
|---|---|---|
| `gpt/nocturne-runtime2-final` | `db781861c4ca7e19d21ce7045e65dfcf3bba0dc4` | D2 confirmation, M1/M5 notices, M2 Off ordering, D4 Take back and D5 Resume policy. |
| `gpt/nocturne-haptics` | `4f14651f1c3389e73825a398e7a0fc25dfaa5404` | In-app haptics, charger refusals, diagnostics, terminal re-time History, START attribution and later receipt safeguards. |
| `gpt/nocturne-gaps` | `11ee133354261a0ac4c9470d8de722c6b48e260c` | UI fixes, bounded IR-17 coverage, contrast, History presentation, backup-original visibility and remaining layout limits. |
| `gpt/nocturne-u06` | `66825099193256ad64d08f087cb0b4d08250a4b3` | Owned U06 layout work. Contrast evidence is separately pinned to the gaps branch. |

Claude retains review, integration and merge decisions. GPT-5 changes documentation only and publishes only to `gpt/nocturne-docs2`. **The register is unchanged by this re-pin:** U06, GAPS-*, HAPTICS-* and runtime2 rows remain with their owners. The candidate at `262c52f` and F1 approval at `c15c515` are historical checkpoints. The earlier U06 adjacent-row merge conflict remains in the [historical reconciliation](review/docs2-reconciliation.md#merge-compatibility-one-known-documentation-conflict); no new merge result is claimed here.

## Current behaviour and evidence

[nocturne-completion.md](review/nocturne-completion.md) is the authoritative IR-01–IR-32 record. IR-17 has bounded named-test coverage, rather than a blanket verification claim. IR-07 deliberately sends one terminal notice when a pending pre-wake re-time expires, not a series of expiry notices. IR-31 remains open: individual uncertain originals still lack a Settings label, and legacy v1 empty-slot provenance cannot be recovered. See [items 1–4](review/docs2-repin.md#1-authoritative-ir-record).

The requested runtime changes are implemented at `db78186`. The newest displaced programme receives the Resume offer; an already manual adjustment preserves a valid offer without extending its expiry, and an expired offer is never revived. Boost displaces a programme only when it interrupts a running programme. Quick Off retains confirmation during a tracked night because it ends that programme. Pending, failed, unknown and confirmed remain distinct. See [runtime policy](review/docs2-repin.md#5-runtime-policy) and [Quick Off](review/docs2-repin.md#12-quick-off-confirmation).

At `4f14651`, a live in-app request gets a light tick at command-owner acceptance and a confirm haptic only when that same request returns a BedJet-confirmed outcome. Other origins are silent. An otherwise eligible charger start blocked by unreadable data starts nothing and records at most one PROBLEM notice/notification per bedtime window, without a History night, CSV row or morning feedback. Diagnostics include unreadable files, blocked originals, runtime reason and night confidence. Terminal re-time failure paths write History evidence. START captures and passes the alarm time and creating app to preparation; completed History does not retain that creating-app identity. See [items 6–10](review/docs2-repin.md#6-in-app-haptics).

For U06 contrast wording, after-wake bars meet the tested **3:1** threshold, Quick buttons are identifiable at **3:1** by border or fill, and decorative card borders are exempt from that control-boundary requirement. These are production-token checks in the gaps branch's named `ContrastTest` methods, not rendered-device or general accessibility verification. U06's own branch supplies its separate layout work and register row. See [contrast scope](review/docs2-repin.md#11-u06-contrast-wording).

## Known limitations and backlog

These are limitations or future work, not fixed defects. [Known limitations](review/known-limitations.md) gives each reason, source pin and acceptance boundary.

| Item | Remaining limitation / decision |
|---|---|
| IR-31 | Individual uncertain originals lack a Settings label. Claude's labelling fix follows the freeze. Missing v1 empty-slot provenance cannot be reconstructed. |
| KL-05 | Widget theme refresh can lag while the app process is absent. |
| G9 | Unknown or other-device originals are visible read-only; restoring an explicitly chosen source still needs command-owner/repository work. |
| U-B1 | History draws the last revision if it can be parsed, otherwise the originally recorded steps; there is no revision-by-revision curve view. |
| U-B2 | Background outcomes are not queued for later in-app display. |
| U-B3 | A ±1 °C Cool check-in option is not implemented; current Cool/Dry proposals change both fan endpoints by 10 percentage points. |
| U-B4 | “Tonight-only wake time” can wrap within a word at 320 dp / 200% text. |
| U-B5 | Native picker dial crowding remains a device acceptance check. |
| Smart wake | Wake timing driven by the room sensor is not built. |
| D1 | Dialogue taps give haptic feedback through the activity's host view, rather than the dialogue's own view. |
| Charger origin | The outer-timeout fallback sets CHARGER in code, but the saved notification log drops origin; that metadata is source-inspected only. This is an accepted limitation. |
| Alarm provenance | START uses the alarm creator for policy, but History has no permanent creating-app field. |

Unmeasured BLE timing, background scheduling, Lull background advertising, Health Connect access, English-only core messages, unresolved Off tracking and the notice save-to-post gap remain in the limitations record. A saved reason or History event does not establish notification delivery.

## Documents to use

- [Source re-pin and coverage](review/docs2-repin.md): all 13 requested items, immutable references and verification limits.
- [Completion dispositions](review/nocturne-completion.md): authoritative IR-01–IR-32 record; historical gate results remain labelled.
- [Known limitations](review/known-limitations.md) and [recovery procedures](review/recovery-procedures.md): pending work and practical recovery steps.
- [Supervised acceptance](review/samsung-bedjet-acceptance.md): install and signer checks first, explicit BedJet selection, originals and restore, then runtime/platform/UI cases. Every physical result is **NOT RUN**.
- [Release instructions](review/release-instructions.md) and [owner production settings](review/release-production-settings.md): TEST-ONLY versus production artefacts and version/hash/signer checks, at their stated release-source pins.
- [Register](review/register.md): preserved owner rows and historical base dispositions. Read the reference branches' owned rows with this prose until integration.
- [Earlier reconciliation and gate](review/docs2-reconciliation.md): the c15c515 documentation pass, not a new result for these references.

## Verification and remaining decisions

This is a documentation-only update. **No Gradle run is needed or performed.** Checks cover allowed paths, unchanged register rows, source-link existence and line bounds at the named SHAs, and consistency across the IR record, recovery steps, limitations and acceptance expectations. Named tests describe inspected assertions; no test suite was executed for this re-pin.

There is no new hardware, Samsung, TalkBack, on-device, CI or minified verification. All supervised results remain **NOT RUN**. Claude must integrate the independent workstreams, retain their owned register rows, arrange the post-freeze IR-31 label fix and decide the remaining backlog. Source inspection does not establish combined-runtime or physical acceptance.

# Nocturne — current engineering handover

**Version pin — 25 October 2026.** This document describes `c15c515a4dabbeae719c12323b6a6c0639c8406e`. GPT-2r changes are in progress for D2 confirmation, M1/M5 notices, M2 Off ordering, D4 Take back and D5 Resume offer. **Those changes and haptics are NOT reflected here.** Claude will assign a later re-pin after GPT-2r and haptics land. All physical results remain **NOT RUN**.

## Branch state and ownership

This is the GPT-5 documentation handover from 24 October 2026, corrected following Claude's review on 25 October 2026. Branch/review assignments below come from the owner's workstream brief; they are not inferred from a passing test suite.

| Work | State |
|---|---|
| Candidate | `claude/nocturne-final-candidate` @ `262c52fc7a2416f6b5d93a7abafb11079ec29d76` — the nominated candidate checkpoint. |
| F1 | `gpt/nocturne-f1` @ `c15c515a4dabbeae719c12323b6a6c0639c8406e` — approved in the owner's brief; this is GPT-5's exact code base. See [F1 implementation evidence](review/f1-implementation.md). |
| GPT-5 | `gpt/nocturne-docs2` — documentation reconciliation and acceptance preparation. Only `docs/review/**` and this file change. |
| Final review and merge | Claude owns both. GPT-5 does not merge, open a PR, or push to the candidate or `main`. |

The nominated candidate checkpoint and F1 approval do not imply that all open workstreams have been integrated. Later branch tips are outside this source review. The U06 merge preview still has one adjacent-row conflict in the register despite restoring its U06 row to the base. The [follow-up record](review/docs2-reconciliation.md#merge-compatibility-one-known-documentation-conflict) gives the exact row selection for Claude; the runtime-candidate preview is clean.

## Open workstreams

| Workstream | Remaining responsibility / order |
|---|---|
| GPT-2r | Runtime follow-ups: D2–D6, D8, M1, M2, M3 and M5, with their register/completion text. GPT-5 preserves that runtime scope. |
| GPT-4 | Independent source audit. |
| GPT-6a | U06 delivered on `gpt/nocturne-u06` @ `66825099193256ad64d08f087cb0b4d08250a4b3`, approved by Claude and pending integration. Its register row is left at the `c15c515` base so the owning branch supplies the disposition. |
| GPT-6b | Haptic timing and origin work; F13 remains partial. Run after GPT-2r. |
| GPT-3 | Integration and the final combined gate, last after the other workstreams and Claude's reviews. |
| Claude | Final engineering pass, disposition of source discrepancies and merge decisions; V06 remains pending. |

## Documents to use

- [Register](review/register.md): current scoped dispositions, with historical checkpoints labelled.
- [GPT-5 reconciliation and gate evidence](review/docs2-reconciliation.md): every changed register row, source references, test counts and limits.
- [Known limitations](review/known-limitations.md): storage provenance, background work, platform limits, unmeasured timing, unresolved Off and notice-loss behaviour.
- [Recovery procedures](review/recovery-procedures.md): user steps and consequences for unreadable data, unfinished restore, lost originals, unresolved writes, UNKNOWN nights and refused imported-original restores.
- [Release instructions](review/release-instructions.md): TEST-ONLY versus production artefacts and version/hash/signer checks.
- [Samsung + BedJet 3 acceptance](review/samsung-bedjet-acceptance.md): ordered supervised plan; every physical result remains **NOT RUN**.
- [Production settings](review/release-production-settings.md) and [release candidate evidence](review/release-candidate-report.md): owner configuration and historical release evidence, kept separate from this pass's debug gate.
- [Frozen runtime contract](review/task-4-implementation.md) and [completion dispositions](review/nocturne-completion.md): unchanged by GPT-5.

## Source findings for Claude

The scheduled theme is implemented; the remaining process-absent widget refresh limit is documented. Source: `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/Settings.kt:62–74`; `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/WidgetUpdater.kt:47–59`.

R13 remains partial: the History curve selects the last confirmed revision, but expanded step text still uses the original steps. Source: `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/HistoryScreen.kt:78–98,176–177`; `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightChanges.kt:25–31`.

R33/R36 remain partial: the UI preview supplies the known alarm creator, whereas START supplies only its time. With unknown alarm sources disabled, the shared resolver can accept that alarm in the preview and fall back at START. This is source-traced, not a newly executed regression. Source: `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt:202–205`; `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:168–175`; `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt:39–45`; `core/src/main/kotlin/io/github/willzy12h/nocturne/core/plan/NightPreview.kt:17–33`.

R38 remains partial: Quick Off still asks for confirmation while a night is tracked, although the runtime policy exempts Off from replacement confirmation. Claude should decide the intended UI treatment. Source: `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt:325–329`; `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightGuard.kt:27–39`; `app/src/main/kotlin/io/github/willzy12h/nocturne/night/RuntimeState.kt:59–68`.

A consumed re-time failure can lose its notice if the process ends after saving but before posting. The reason is durable, but is not universally displayed for a still-CONFIRMED night. KL-13 records the visibility gap at this pin for Claude/GPT-2r. Source: `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt:576–579,607–641`; `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt:124–133`.

## Verification and release boundary

The required gate is `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; the 24 October run and its host-startup qualifications are recorded in the [reconciliation evidence](review/docs2-reconciliation.md). No code, tests, resources, Gradle or workflow files are changed. The 25 October correction checks documentation and merge compatibility; it does not rerun that gate or claim CI or minified verification. A debug gate does not establish a fresh minified or production APK.

Production environment settings, signing identity and tag protection require owner evidence. Physical installation, same-signer upgrade, Samsung behaviour, BedJet timing, Lull interoperability, Health Connect provider behaviour and TalkBack are **NOT RUN**. Identify the exact APK before starting the supervised plan; retain a separate wake alarm and an awake supervisor.

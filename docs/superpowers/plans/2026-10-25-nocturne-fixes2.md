# Nocturne fixes2 implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Each behavioural change requires an observed failing test, its minimal implementation and an observed passing test before integration.

**Goal:** Close review findings F1–F5, verify lights/beeps, explain alarm selection and reduce unnecessary foreground/background BLE work.

**Architecture:** Retain NightActions as the sole command owner. Bind restores to captured originals; settle interrupted manual requests durably. Keep raw, sourced status and receipts available to commands while limiting foreground presentation and idle background status reads.

**Tech stack:** Kotlin, Compose, Gradle 8.14.3, core JUnit 5, app Robolectric JUnit 4 at SDK 34.

**Spec:** The owner's fixes2 instruction, based on the independent review of `1c67c0a28084238377c7675e6dc25d0444fe2c22`; existing design `docs/superpowers/specs/2026-10-21-nocturne-design.md` remains authoritative for unchanged UI.

## Global constraints

- Start at `1c67c0a28084238377c7675e6dc25d0444fe2c22`; publish only `gpt/nocturne-fixes2`, fast-forward only. No rebase, PR merge, main/claude push or production signing changes.
- UK English; new app-facing text in `res/values`; truthful pending/failed/unknown/confirmed states.
- Owner 90 s, service 100 s, UI 115 s remain unchanged. Raw confirmation receipts and fresh fallback must survive lifecycle changes.
- Host tests and assembly do not establish device, Samsung, TalkBack, physical lights/beeps or battery results.
- Independent work uses isolated worktrees; Gradle runs are serialised to preserve evidence and limit memory use.

## Review focus

- Cancellation after send but before confirmation must settle the same pending request without undoing a later confirmation or deleting valid Off intention.
- Import may replace an original while a restore is queued or preparing; the confirmed payload must never silently change.
- Unchanged alarms must remain quiet even for unknown/manual nights; genuinely changed alarms retain failure evidence.
- Hiding/showing or switching target during an owner operation must not disconnect it, recycle a receipt or show another target's status.
- Null alarm creators, rejected sources, out-of-window alarms, profile-only wake and overrides need truthful explanations and useful setting links.

## Tasks

- [x] **Runtime F1–F3:** Add cancellation tests during confirmation and fresh fallback, queued-original replacement and unchanged unknown/manual alarm tests. Capture RED, implement in NightActions (plus the minimal restore caller), capture GREEN. Add genuine-change controls.
- [x] **UI F4–F5:** Test a 32-step uncertain restore preview at 320 dp / 200% and semantic Off/Pause/Turbo descriptions. Capture RED, make the body scrollable and use mode capabilities in description resources, capture GREEN.
- [x] **Lights/beeps:** Verify commands and status bits against a public BedJet 3 protocol implementation. Add independent packet/bit and switch tests; correct reversals after observed RED if found. Record sources and supervised phone checklist rows.
- [x] **Alarm explanation:** Verify nextAlarmClock semantics, trace source/window policy, add reason-bearing diagnosis and an always-present Tonight source line with targeted settings navigation. Test accepted Samsung, null/disallowed source, window rejection, no alarm and override/profile-only cases before implementation. Include the reason in diagnostics.
- [x] **BLE before/after:** Document baseline connection holders, grace, status notifications/remainder reads and wake triggers before changes. Test foreground/hidden presentation, <=1 Hz actual/ambient sensor-temperature updates with immediate control fields, 30-second idle release, foreground return and background owner protection. Implement lifecycle demand without throttling command receipts or fresh reads. Capture RED/GREEN.
- [x] **Integration:** Review each patch, run relevant cross-component tests, then the exact four-task gate and unsigned `:app:assembleRelease`. Check fresh JUnit/lint counts, no new lint findings, clean diff/signing scope and independent final review.
- [x] **Handover/publish:** Update current handover, disposition caveats, phone checklist, protocol references, RED/GREEN evidence and BLE before/after. Commit and push only the authorised branch; verify the remote SHA.

The later app-switch amendment keeps the same target’s Remote content visible while reconnecting, labels retained readings **Last reported**, and enables controls only after READY and a current report. It does not resume hidden live collection or feed the presentation cache into raw confirmation.

The subsequent temporary-action feature and final combined evidence are recorded in [temporary-action verification](../../review/temporary-actions-verification.md). Tested code `a4411b1cb7fc2769d2e11d84685e3748b3f371d8`, tree `7e52367f6df981fa6d904b3d027ad9832412ff5b`, passed 224 core tests and 1,069 app tests (1,031 passed, 38 skipped), with no failures/errors, debug assembly, unchanged lint findings and unsigned release assembly. Physical acceptance remains NOT RUN.

## Implementation decisions

- Preserve the existing alarm-source policy and persisted/default setting. Expose the unknown-source choice and explain rejection; do not silently change it for a null creator.
- Keep the existing theme/navigation and add only the requested small source explanation and targeted navigation.
- Background commands may obtain the full status they need; idle background grace must not perform continuous status-enrichment reads.
- Reuse the existing connection-holder mechanism; do not introduce a second hardware command owner or a polling service.

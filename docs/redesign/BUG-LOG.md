# Redesign bug review log

## Pass 1 — P2 commit 2837190 and in-progress P3 working tree

Reviewed the changed theme consent, palette selection, device controls, Home reading,
quick controls, profile cards and timeline against their prior behaviour and nearby
state sources. No new concrete regression was established in this bounded static pass.
The in-progress phase and future changes require further review.

No hardware, on-device or TalkBack verification was performed. No builds were run for
this review. Existing P3 failures and corrections are recorded in GPT-HANDOVER.md.

| ID | Severity | Evidence / file | Reproduction or test | Status | Verified fix evidence |
| --- | --- | --- | --- | --- | --- |
| — | — | No established finding in this pass | — | — | — |

## Pass 2 — P3 phase gate investigation

| ID | Severity | Evidence / file | Reproduction or test | Status | Verified fix evidence |
| --- | --- | --- | --- | --- | --- |
| P3-001 | Unassessed; application defect unconfirmed | Full gate 5 logged 1,630 app cases, one failure and 38 skips. `AppSmokeTest.completedNightCheckInRecordsComfortWithoutChangingAProfile` failed at line 289; the XML lookup found zero Morning check-in nodes. | The absence did not reproduce in 15 isolated AppSmoke tests with a temporary diagnostic. The diagnostic was removed; the test now waits for the History button using the existing bounded service/looper helper, retaining all assertions. No production logic changed. Gate 6 executed 1,630 app cases with zero failures and 38 skips, including this test. The application timing cause remains unconfirmed. | Validation passed with the bounded wait; no production bug claimed. Source cause remains unconfirmed. | Gate 7 passed the full suite, including this test. |
| P3-002 | Build reporting issue; not established as an app defect | Gate 6 failed on 12 XML report writes. Stack traces show `TestOutputStore.Reader.doRead` → `JUnitXmlResultWriter.writeOutputs`, with `EOFException` / Kryo buffer underflow. | Build runtime review checked the installed Gradle API and bytecode. An external init now sets app JUnit `includeSystemOutLog` and `includeSystemErrLog` to false and disables optional HTML; test case outcomes and failure traces are retained. Source, build and release configurations are unchanged. | Local reporting workaround verified; underlying output-store cause remains an unresolved tooling limitation. | Gate 7 produced complete readable XML and passed. |

Gate 7 exited successfully: 308 core tests passed; 1,630 app cases had zero failures and 38 skips; lint reported zero errors and 20 warnings. Build successful in 11m 7s. No open blocking P3 findings.

## Pass 3 — P4 in-progress editor review

| ID | Severity | Evidence / file | Reproduction or test | Status | Verified fix evidence |
| --- | --- | --- | --- | --- | --- |
| P4-001 | Moderate | `ProfileEditor.kt`: changing fan limits updates only `limits`; the new `FanSlider` clamps the displayed thumb but not the stored speed or its text/semantics. | Choose Quiet middle (50/25/45), then raise minimum fan to 60%. The stored values and labels remain below the limit while slider thumbs appear at 60%. Exercise Steady and Ease too, including equal limits; compiler clamps speeds when producing steps. | Resolved; full P4 gate 3 passed. | Initial RED reproduced the issue; all nine P4 editor tests passed, including limit reconciliation and five-percent lattice checks. |

A further bounded review of P4 timeline and check-in messages established no additional high-impact finding. Focused run: 12 core tests passed; 84 app cases included 83 passes and one intentional RED for a missing preview message, subsequently restored before the full gate. Lint reported zero errors and 21 warnings.

## Pass 4 — Combined P2–P4 lifecycle and receipt review

Static review found no new concrete battery, lifecycle or stale-confirmation regression.
The new Home reading gates the displayed receipt on live status and READY; the quick
active marker retains its existing receipt-evidence predicate. The new Settings
relocation coroutine is tied to composition and triggered by an interaction and viewport
change. This is not a battery measurement or device verification.

## Pass 5 — P4 full gate timeouts

| ID | Severity | Evidence / file | Reproduction or test | Status | Verified fix evidence |
| --- | --- | --- | --- | --- | --- |
| P4-002 | Verification blocking; application defect unconfirmed | P4 full gate 2: 309 core tests passed; 1,652 app cases, 11 failures and 38 skips. Ten failures reported `AppNotIdle`: seven new P4 editor cases, two existing `ProfileEditorInteraction` cases and one `StripeTiles` case. Full XML: `../evidence/p4-phase-gate-2`. | `LongHeatMessageTest` lacked ViewModel/Graph cleanup; cleanup now matches existing fixtures, but its relationship to these failures is unproven. Gate 3 runs with two workers and external `forkEvery=50` to bound per-worker state; case assertions remain unchanged. | Verification resolved in gate 3; underlying long-lived-worker idling cause remains unproven. | Gate 3 passed with fixture cleanup, two workers and external `forkEvery=50`. |
| P4-003 | Verification blocking; application defect unconfirmed | P4 full gate 2: `TonightLayout.pausedTonight...` timed out waiting for the resume alarm. Full XML: `../evidence/p4-phase-gate-2`. | Investigate separately from the `AppNotIdle` failures; gate 3 is running. | Verification resolved in gate 3; no application defect established. | Gate 3 passed with fixture cleanup, two workers and external `forkEvery=50`. |

Gate 2 did not produce a fresh lint result or full Gradle success footer. These timeouts
are not established as hardware or production defects.

P4 gate 3 exited successfully: 309 core tests passed with no skips; 1,652 app cases had zero failures and 38 existing skips; lint reported zero errors and 21 warnings. All XML was valid. Build successful in 13m 8s; 43 tasks, five executed and 38 up-to-date. The long-lived-worker idling cause remains an unproven tooling limitation, not an established application defect.

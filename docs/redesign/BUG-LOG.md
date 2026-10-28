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

## Pass 6 — P5 cooldown review

Bounded static review of the in-progress offer, profile creation, repository transaction,
and view-model bridge established no new concrete regression. The offer changes profiles
only on Add, declines through a settings-only write, checks selected after-wake snapshots
and session revision, and labels the change as future nights only. Checkbox rows have a
48dp minimum. Copy retains the source profile; the new-preset path applies the default.
No build, device or accessibility-service verification was performed in this pass.

## Pass 7 — P5 transaction and session re-review

Reviewed the latest repository and UI paths plus transaction and session tests. No new
concrete finding was established: Add uses the existing journalled multi-file write,
selected after-wake snapshots and session revision are checked under serialization,
and the tests cover stale selections, restore, demo round trips and write failure.
This was a static review; tests were not run in this pass.

## Pass 8 — completed-phase review during P5 verification

- P2/P3 state and palette-consent review established no concrete regression; no code or tests
  were changed in that review.
- P4-004 (open for P8): an imported/legacy Turbo phase can retain a stored temperature other
  than 86 half-degrees. `ProfileEditor.kt` displays that stored value, while
  `PlanCompiler.phaseSteps` compiles Turbo at the protocol's fixed 43°C. Add a failing display
  regression and show the effective fixed temperature. Preserve legacy chained durations and
  their existing chain policy; do not rewrite the protected compiler or silently migrate data.
  Static evidence only at this point; P5 source is frozen for its full gate.

## P5 gate follow-up

The first full gate executed 311 core tests and 1,663 app cases (38 existing skips).
Seven app failures were navigation selectors assuming an initially visible profile Edit
button; those selectors now scroll by profile ID. Seven were `P4EditorBehaviourTest`
Compose idling timeouts. No fresh lint result or success footer was available.
All 33 focused follow-up cases passed, including the nine unchanged P4 editor cases.
An experimental bounded frame-settling helper did not resolve the timeouts and was removed.
The second full gate had seven failures: six idling timeouts and one backup/demo fixture
race. That fixture now holds only the staged import Job, rather than an arbitrary graph
dispatch; its four checks pass in the subsequent UI run. All editor checks passed there too,
but two theme-confirmation idling checks and a paused-Tonight alarm refresh timed out.
Temporary diagnostics were removed. The next full gate uses a fresh JVM per test class;
no assertions or timeouts changed. The exact shared-worker idling cause remains unconfirmed;
attempted attach diagnostics did not capture usable thread stacks.

Verification resolved under class isolation: all 311 core and 1,663 app cases passed with
38 existing screenshot skips. A follow-up of the complete task command retained those
up-to-date results, ran lint successfully (zero errors, 21 warnings) and produced the
success footer. The portable init script is committed for reproducibility. The cause of
the earlier shared-worker timeouts remains a limitation, not a proven application defect.

## Pass 9 — P6 review and verification

Static review of setup, Settings, History, palette changes and the cumulative runtime
paths found no additional concrete regression. Setup callbacks/order remain intact;
History labels ERROR events neutrally and retains their details. Native styling adds
no recurring work. No device or accessibility-service verification was performed.

| ID | Severity | Evidence / file | Reproduction or test | Status | Verified fix evidence |
| --- | --- | --- | --- | --- | --- |
| P6-001 | Moderate | The initial native styling used `setTextColor(int)`, flattening enabled/disabled button colours. | `P6NativeButtonStateTest` observed RED through the actual Temporary shortcut's End → unavailable Temporary → End choices. | Resolved. | Stateful colours pass the focused run and full P6 gate. |
| P6-002 | Moderate | Initial RED confirmed Forest shortcut and Midnight picker sheet colour mismatches; replacing native backgrounds then caused four existing 200%-text clipping failures. | `P6NativePaletteTest` and unchanged `TemporaryNativeLayoutTest` cover colour, callbacks, layout and contrast. | Resolved. | Tinting native drawables preserves their geometry/padding; all native checks pass in the full P6 gate. |
| P6-003 | Moderate | `U06PrimaryLayoutTest.settingsSlotAndBackupActionsHaveCompleteLabelsAndTargets` detected Slot 3 clipping in all four looks after new card padding. | Slot chips now wrap in a `FlowRow`, preserving actions and labels. | Resolved. | All four variants pass in the 47-case follow-up and full P6 gate. |
| P6-004 | Moderate | `U06ControlSemanticsTest.settingsRowsAreSingleToggleableTargets` detected an overly tall Quiet night status description in all four looks after new card padding. | The shared single-toggle row gives its description the full width below the title/switch header. | Resolved. | All four variants pass in the 47-case follow-up and full P6 gate. |

The 141-case focused run passed 133 and failed eight (P6-003/004 in four looks).
The follow-up passed all 47 selected cases, with zero skips and zero lint errors.
The complete P6 gate passed 311 core tests and 1,674 app cases with zero failures,
38 existing opt-in screenshot skips, zero lint errors and 25 warnings.
`BUILD SUCCESSFUL in 24m 15s`; no open blocking P6 findings.

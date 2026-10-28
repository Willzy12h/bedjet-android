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

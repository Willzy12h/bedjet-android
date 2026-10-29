# Verification evidence

The final run's raw log, process receipt, parsed summary and JUnit/lint reports are kept alongside this file. `regression-evidence.zip` preserves the baseline and intermediate RED/GREEN logs, receipts, available XML reports and diagnosed tool failures. Its run index distinguishes a failed assertion from a failed compilation, environment failure or deliberately interrupted run.

A receipt records the actual Gradle child-process exit code, command, UTC times, starting/ending Git commit and a SHA-256 of tracked/untracked application, core, Gradle, CI and build/init inputs. No pipeline exit status substitutes for the Gradle result. Documentation-only delivery commits may follow the verified application commit; their source digest remains identical. The final APK receipt records the delivery HEAD.

The ordinary clean gate must have zero failures/errors, only the 41 opt-in capture skips, zero lint issues and no actionable compiler/build warnings. The separate capture run must have 41 passes and zero skips. Each source digest must be unchanged throughout its run.

Some intermediate incremental output directories retain XML from earlier task selections. Summaries use the suite timestamp inside each XML against the actual run interval, record excluded prior-run files explicitly, and preserve their genuine original-run evidence. This prevents stale reports being mistaken for a current execution; it never filters by test outcome. The complete final clean gate re-executes every core/app case.

`baseline-failures.csv` in the parent directory reconciles every reproduced baseline failure with the current final test case. `warnings.csv` records every diagnostic inventory entry, including repeated occurrences of the separately explained external Kotlin daemon-session diagnostic. Tests were not disabled, removed, quarantined or weakened to obtain a result.

All screenshot PNGs and the reference pairing/contact sheets are in `../visual/`. They are native Robolectric evidence, not physical BedJet, Samsung, TalkBack or installed-launcher validation.

## Run index and historical report fidelity

`run-index.csv` lists every completed task command, its actual process exit and classification. Counts labelled `available_xml_*` are recomputed from distinct preserved suites whose internal timestamps fall in that command's interval; they combine modules where both ran. The raw console test summary is retained separately. A zero available-XML count means no suite report survived for that run, not that an unobserved test passed. Older raw summary JSON files are retained as historical artefacts; the final clean summary and timestamp-reconciled index take precedence over an earlier glob-based count.

`interrupted-full-gate-transport.json` records the separate execution-service disconnect. Its exit is unknown because the runner disappeared without a completion receipt. It is excluded from successful-run claims and is not silently assigned a fabricated Gradle exit code. Its partial log and daemon shutdown diagnostic are preserved in the archive.

`regression-evidence-manifest.csv` hashes every raw archive member. Each final screenshot window remains byte-for-byte identical to the native capture; visual review is documented separately in `../visual/VISUAL-REVIEW.md`.

## Verbatim whitespace in raw evidence

`git diff --cached --check` identifies four trailing-space lines in the preserved evidence: Gradle's `Daemon will be stopped at the end of the build ` line in each final log, and the exact blank-path Kotlin diagnostic in two run-index rows. These upstream bytes are intentionally retained. They are not source-format or compiler/lint warnings. The authored source/documentation format check passes when those three raw-evidence files are excluded; the original check result is recorded in `evidence-format-check.json`. No `.gitattributes`, warning filter or whitespace rewrite is used to conceal the diagnostic.

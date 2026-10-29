# Nocturne redesign remediation plan

Date: 29 October 2026. Branch: `gpt/nocturne-redesign-remediation`.
Starting source: `e4be250fb9f340800fa89aea8e358d5bddbf5054`.

## Authority and constraints

The owner's remediation brief supersedes the earlier file ownership restrictions and warning budgets. One implementer works alone. Preserve the approved four-tab design, Claude's accepted decisions, schema 3, existing command ownership and truthful receipt states. No new features, releases, PRs, merges, production-signing changes, force pushes or rebases. New visible copy belongs in resources and uses UK English.

All nine requested redesign documents were read in full before code changes. No `AGENTS.md`, `CLAUDE.md` or nested agent instruction file exists in this checkout or its workspace parent directories. Source/reference documents are binding where consistent with the owner's latest instructions.

## Execution

- [x] Reproduce the complete baseline and preserve every failure and warning, including actual process exits. Classify obsolete UI expectations separately from functional defects and harness/dependency failures.
- [x] Repair test infrastructure and deterministic fixtures, retaining API 31/33 security cases and all DemoImportPreviewInvalidation assertions. Do not change production to accommodate the import fixture.
- [x] Repair Tonight, quick controls, status/Remote and navigation regressions. Keep the segmented card, four tabs and current entry points; retain meaningful wake, safety, confirmation and accessibility assertions.
- [x] Repair Profiles, editor, draft restoration and picker regressions. Keep approved sheets and steppers; cover edits, save/discard, limits, phase selection and large text through current UI.
- [x] Repair recovery, setup, History, widget and remaining accessibility issues. Remove broad lint suppression and resolve project-owned warnings with narrowly justified exceptions only where inapplicable.
- [x] Review the complete redesign/remediation diff and related runtime, persistence and background paths; reproduce and fix every actionable finding.
- [x] Run all documented opt-in captures with `SCREENSHOT_TAG=remediation`. Inspect all palettes, both target sizes, sheets and interactive surfaces; preserve captures and an accurate reference pairing table.
- [x] Run the exact clean full gate after the final production/test change. Read every report and verify counts, skips and exit codes. Commit logical batches, push normally and build/inspect the final committed debug APK.
- [x] Update handover, final review, P8 audit and remediation register, with concrete evidence and independent-review instructions for Claude.

## Review focus

Target changes while a confirmation is open; receipt freshness while reconnecting; rapid demo/real transitions during imports; lifecycle and navigation changes while an editor draft exists; 320dp/200% controls, scroll reachability and rendered contrast. Each concrete finding receives RED/GREEN evidence before production changes.

## Evidence

`REMEDIATION-REGISTER.md` records classification, cause, change, commit and verification. The delivered evidence archive contains raw Gradle logs, process receipts, JUnit/lint reports and screenshot captures. The final report distinguishes implementer self-review from Claude's independent sign-off.

The final application/test source and gate are recorded in FINAL-REVIEW. The delivery manifest binds the final documentation/evidence commit to the post-gate debug APK build and its checksum. Physical and independent validation remain explicitly separate.

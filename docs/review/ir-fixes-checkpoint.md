# IR fixes — checkpoint (paused at owner's request)

- **Branch:** `claude/nocturne-ir-fixes`, from `origin/claude/nocturne-hardening` @ `7fffabc`. Not merged; `claude/nocturne-hardening` and `claude/nocturne-independent-review` untouched.
- **Plan:** `docs/superpowers/plans/2026-10-22-nocturne-ir-fixes.md` (Tasks 1–11).
- **Hardware:** none; no Samsung or BedJet 3 verification.

## Status

| Task | Scope | Commits | Review |
|---|---|---|---|
| 1 | IR-01: automatic rewrites read the BedJet first | `5ee7517` | Approved |
| 2 | IR-02: link bound to the chosen BedJet | `697f3e1` | Approved |
| 3 | IR-03: unreadable slot backups block slot writes | `1fa356b`, `db1b902` | Approved after fix round 1 |
| 4 | IR-04, IR-11 (transport), IR-18, IR-19, IR-02 follow-ups | `02b2797`, `d02463a`, `0798307`, `6d51b4f`, `a46dfc5` | Approved after fix round 1 |
| 5 | IR-05, 06, 08, 09, 10, 11 (admission), 20, 21, 23 | `7cf2c1f`, `19e967a`, `0af58e3`, `4f08ceb`, `ba0dcb0`, `fa2f1e1` | Approved after fix round 1 |
| 6 | IR-13, 15, 16, 28–32 (storage) | `35565f4` | **Needs fixes: 2 Important (see Resume). Fix round not started.** |
| 7 | IR-07, 12, 22, 24–27 (runtime noise, platform) | — | Not started |
| 8–10 | Task 5 UI rebuild (Astra's unpublished commits are not recoverable) | — | Not started |
| 11 | Register/ledger/handover/acceptance updates | — | Not started |

## Last verified gate
At `35565f4` (implementer run): core 155/155; app 286 (282 passed, 4 screenshot skips); assembleDebug OK; lint 0 errors, 23 warnings (the count includes online dependency-version checks).

## Resume
1. **Task 6, fix round 1** (resume here). Both items are Important:
   - **Snapshot restores can fail.** `BackupValidator.kt:122` checks slot programmes against the mode temperature ranges, so the app's own snapshots can become unrestorable. Validate against the codec limit (0..255), or apply the mode range to imports only. Test: a snapshot holding an out-of-range recorded original still restores.
   - **Originals can be lost after a failed rollback.** While `restore-pending.json` remains after a failed in-process rollback, slot writes are still allowed, and the next start rolls `slot-backups.json` back, losing the originals recorded in between. Refuse slot writes while the marker exists, or rewrite the marker to list only the stuck files. Add a test.
   - Then run a scoped re-review.
2. Then Tasks 7 to 11 per the plan, then the final whole-branch review.
3. Commits: committer is `Claude <noreply@anthropic.com>` (repo config), and the author is passed with `--author`.
4. Commits pushed before this rule took effect (`629f8f7` to `db1b902`) show the old committer.

## Ledger (copied from the git-ignored SDD workspace)
```
# SDD ledger — plan: docs/superpowers/plans/2026-10-22-nocturne-ir-fixes.md

Base: 7fffabc (origin/claude/nocturne-hardening). Plan commit: 629f8f7.
Spec: docs/review/independent-review-tasks-1-4.md + hardening plan Task 5 brief.

## Pre-flight scan
| Rows | Shared file/interface | Finding |
|---|---|---|
| T1/T5 | NightActions rewrite paths, confidence | T1 adds pre-write status check; T5 changes outcome semantics on pre-write failure — compatible (T1 mismatch = UNKNOWN takeover; T5 pre-write failure = keep night). |
| T1/T3 | NightActions refusal paths | T3 refusal is pre-write; T5 generalises. Compatible. |
| T2/T4 | GattLink | Sequential; T4 builds on T2 address checks. |
| T2/T9 | selectTarget | T9 adds useRealDevice option on top of T2's wait. |
| T5/T8 | NightActions.status vs confidence | T8 UI reads activeNight.confidence per IR contract note 1 after T5's slotWrite scoping. |
| T6/T10 | BackupValidator | T6 slot-backup validation; T10 theme/timestamp validation; different fields. |
| T7/T8 | widgets / outcomes | T7 refresh path; T8 UI outcome collector. Compatible. |
| T8/T9/T10 | ui/**, strings.xml | Sequential; each builds on previous. |
| Each task | self-consistency | Tests named match behaviour; no task creates and later contradicts a file. |

Ruling: Task 5 rebuilt from report rather than recovered commits — commits 7117c85/0691f86 are not on GitHub or this host — cost if wrong: duplicated effort if Astra later publishes them; this branch then supersedes and Astra must not merge its local branch blindly.
Ruling: implementers/reviewers on opus (owner's earlier instruction: opus, medium effort) — cost if wrong: more tokens than needed.

Task 1: dispatched implementer (opus) BASE=629f8f7 agent=a58260ce925413e89
Ruling: Resume is not pre-checked by IR-01 — it's a user-confirmed replacement (or no night exists), not a silent automatic rewrite — cost if wrong: a confirmed Resume could restart after a remote Off the user forgot about; reviewer may still flag.
Task 1: complete (commits 629f8f7..5ee7517, review clean — Approved, 0 Critical/Important)
Task 1: minor (deferred → carried into Task 5): pre-write status timeout catch also converts outer owned withTimeout; use withTimeoutOrNull in a status helper.
Task 1: minor (deferred → carried into Task 5): no test for status-timeout path (retry stays scheduled, no writes) nor override-clear-while-taken-over.
Task 1: minor (deferred): grace window not covered after final step end; register row could cite X-1/RT-1 sources.
Note: lint warning count varies 14 (implementer) vs 23 (lead, with online dependency checks); none attributed to touched files — reconcile in final gate.
Task 2: dispatched implementer (opus) BASE=5ee7517 agent=ad65c22105ea9e480
Task 2: complete (commits 5ee7517..697f3e1, review clean — Approved, 0 Critical/Important)
Task 2: minor (deferred → carried into Task 4): ChargerRun.kt:42 and NightTileService.kt:21 read unattributed link.status; switch to sourcedStatus+isFrom.
Task 2: minor (deferred → carried into Task 4): epoch check inside first-status wait; test timed-release expiry path; keep publish() flows consistent; pin AndroidBedJetLink.sourcedStatus forwarding in a test.
Task 2: minor (deferred → Task 11): register IR-02 wording ("attach fails" → attempt abandoned, loop moves to new target); report understated disconnect wait bound (worst ~OPERATION_MS; fails safe).
Task 3: dispatched implementer (opus) BASE=697f3e1
Task 3: review (697f3e1..1fa356b) Needs fixes — 1 Important (restore during unreadable period clears block without recording lost-at). Fix round 1 dispatched to implementer with Important + 2 adjacent minors (owned() !! NPE; refused re-time notification).
Task 3: minor (deferred): SlotBackupsUnreadableTest lacks resume/charger coverage; merge doesn't compare bundle savedAt; lost-at never reset after a restore supplies originals (document). STO-2 UI (dismiss) scheduled in Task 8 (IR-14).
Task 3: fix round 1/5 (3 addressed, 0 open; commits 1fa356b..db1b902)
Task 3: complete (commits 697f3e1..db1b902, review clean after fix round 1)
Task 3: minor (deferred → Task 6): slotBackupsLostAt KDoc should mention restore-while-unreadable; slot-backups-lost.txt to transfer exclusions (IR-32).
Checkpoint: full gate green at db1b902 (core 138, app 216/4 skipped, lint 0 err/23 warn); pushed origin/claude/nocturne-ir-fixes.
Task 4: dispatched implementer (opus) BASE=db1b902
Task 4: implementer DONE (db1b902..9042abf, 4 commits); NGT-2 final-check-under-ops-lock left for Task 5 (IR-11 admission). Review dispatched.
Ruling: unpushed Task 4 commits re-committed with committer Claude <noreply@anthropic.com> (stop-hook requirement), author kept Willzy12h; identical tree. SHA map 46cb91e→02b2797, b1c9348→d02463a, cfc76e0→0798307, 9042abf→6d51b4f. Already-pushed commits (5ee7517..db1b902) left unchanged — no force-push — cost if wrong: four earlier commits show Unverified on GitHub.
Task 4: review (db1b902..6d51b4f) Needs fixes — 1 Important (startPending stuck on abandoned start blinds takeover detection). Fix round 1 dispatched with Important + small items (IR-19 wait cap, KDoc, tile comment).
Task 4: minor (deferred → Task 11 register): status in write-to-ack window dropped by design; add to MTU-247 hardware check. Tile attribution untested. Controller to confirm NotConnected-after-upload UNKNOWN can't loop retime retries (check in Task 5 brief).
Task 4: fix round 1/5 (4 addressed, 0 open; commits 6d51b4f..a46dfc5)
Task 4: complete (commits db1b902..a46dfc5, review clean after fix round 1)
Task 4: minor (deferred): Session.startPending KDoc stale (mention endStartPreparation); cancel-after-prepare only controller-level tested.
Task 5: dispatched implementer (opus) BASE=a46dfc5
Task 5: implementer DONE (a46dfc5..ba0dcb0, 5 commits); flaky LinkSessionsTest.aTimedReleaseEndsByItself noted (carry to Task 7).
Task 5: review (a46dfc5..ba0dcb0) Needs fixes — 1 Important (adjust lacks captured night identity). Fix round 1 dispatched with Important + minors 1,2,4,5 + flaky LinkSessionsTest join.
Task 5: minor (deferred → Task 8 UI): PENDING manual night can only be stopped (Keep tracking refuses non-UNKNOWN); note task-4-implementation.md "manual snapshot may be CONFIRMED" now narrowed (Task 11 note).
Task 5: fix round 1/5 (6 addressed, 0 open; commits ba0dcb0..fa2f1e1)
Task 5: complete (commits a46dfc5..fa2f1e1, review clean after fix round 1)
Task 5: minor (deferred → Task 8 UI): second Remote adjust tapped during first's send returns STALE (revision in identity) — UI should show busy/disable or re-capture.
Task 6: dispatched implementer (opus) BASE=fa2f1e1
Task 6: implementer DONE (fa2f1e1..35565f4); review dispatched. Owner asked to finish current work and pause before next task (GPT prompt to review incoming).
Task 6: review (fa2f1e1..35565f4) Needs fixes — 2 Important: (1) BackupValidator.kt:122 mode-temperature range check can make the app's own snapshots unrestorable (use codec 0..255 limit, or mode-range only for imports, + test); (2) after an in-process failed rollback (restore-pending marker kept) slot writes stay allowed and the next start rolls slot-backups.json back, losing originals (refuse slot writes while restore-pending exists, or shrink marker to stuck files, + test). Fix round NOT dispatched — owner asked to stop.
Task 6: minor (deferred): start-up marker.delete() result unchecked; markSlotBackupsLost before marker write; start-up rollback on main thread (document); no crash-during-rollback test; deleting the named snapshot while marker pending; SnapshotStoreTest message wording; savedSlotLabel hard-coded copy.
```

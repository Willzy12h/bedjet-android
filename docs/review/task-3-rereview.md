- **Important 1: A prepared resume can start after its original wake — ADDRESSED.** `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt:149–151` clamps the request deadline to the earlier of offer expiry and original wake. The existing initial eligibility call at lines 163–165 and the execution check after backup at lines 180, 196–198 enforce that deadline strictly, rejecting equality too. A pre-write rejection after PENDING produces FAILED at lines 187–191. `core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightTemporalEligibilityTest.kt:59–92` covers both equality and later before apply and during backup, with zero writes/start packets, unchanged original slot, and the appropriate publication outcomes.
- **Important 2: Upload/readback can expire eligibility before slot start — ADDRESSED.** `core/src/main/kotlin/io/github/willzy12h/nocturne/core/night/NightRunner.kt:180–184,196–202` checks request deadline and the previous confirmed wake boundary after store/readback and at the final start hook. The clamped resume deadline participates in these checks. Because `writeMayHaveHappened` is already true, rejection publishes UNKNOWN at lines 187–191. The authorised optional synchronous hook at `core/src/main/kotlin/io/github/willzy12h/nocturne/core/link/BedJetController.kt:62–76` executes after syncClock and prepareForStart, immediately before issuing Codec.startProgram; an exception exits coroutineScope and cancels the confirmation child. `core/src/test/kotlin/io/github/willzy12h/nocturne/core/night/NightTemporalEligibilityTest.kt:93–146` verifies upload/deadline, readback/confirmed-boundary, resume/original-wake, and clock-sync/deadline windows, actual overwritten slot contents, UNKNOWN publication, zero start packets, and cancellation without waiting for the confirmation timeout.

### New Breakage in the Fix Diff

- None found. The default empty controller hook preserves existing source callers; the helper retains the previous RETIME/REAPPLY boundary policy and adds the missing execution checks. Register edits append evidence without claiming runtime or hardware completion.

### Out-of-Scope Observations

- None added. Existing Task 4 integration and pending hardware gates remain outside this scoped fix review.

### Checks and Evidence

- Read the task brief, Important findings 1 and 2, appended fix report, and supplied `5e0a072..479ca09` fix diff; checked the changed execution branches and exact source line locations. No Git commands or checkout mutations performed.
- Reviewed embedded RED/GREEN logs: two resume regressions fail before the clamp; four additional temporal regressions fail before the post-store/final-send guards; the focused command selecting NightTemporalEligibilityTest, NightTransactionsTest and ControllerTest reports BUILD SUCCESSFUL. The implementer reports 36 passing tests (6 temporal, 19 transaction, 11 controller), zero failures/errors/skips. The embedded final Gradle output establishes successful completion but does not itself print the aggregate count; that count remains a reported result, not an independently recounted one.
- No tests rerun: source inspection and supplied focused evidence leave no concrete unresolved doubt requiring another run. No broad-suite or hardware verification claimed; root's independent integration gate follows.

### Verdict

- **Fix round: All findings addressed, no new Critical/Important breakage.** No open findings in the authorised scope.

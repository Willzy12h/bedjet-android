# Temporary programme: immutable runtime/core review

Reviewed source: `655993d2ea3d5021a2fab75ecf7db5f77d699f7e`, whose feature implementation is `a4411b1cb7fc2769d2e11d84685e3748b3f371d8`. Both the feature branch and UX descend from Step A `367968bddc390e5b2bb12876fc1662ffa07e47c3`. This is a source review, not a new execution or hardware validation claim.

## Result

No new confirmed defect was found in the immutable temporary-programme core/owner implementation. The feature is implemented: it prepares and starts one composite stored programme containing a temporary prefix and the remaining original timeline, so its planned return does not require a later phone command. Its original widget/activity integration predates the responsive UX widget work and requires the separately owned integration review.

The merge must retain the UX branch's stronger exact-confirmed-ending cancellation settlement, short-test marker/duration context, Bluetooth admission boundaries, and current receipt checks. These are merge requirements rather than evidence that the temporary composition itself is broken.

## Complete review scope

Core files read in full: `TemporaryProgramme.kt`, `NightRunner.kt`, `ActiveNight.kt`, `NightChanges.kt`, `Profile.kt`, and `History.kt`; relevant `PlanCompiler.kt` dependency paths were also checked. Runtime review covered all feature hunks in `NightActions.kt`, the complete captured-preset validator, and the service payload serialization/decoding changes. The relevant native activity/shortcut and temporary UI files were inspected for handoff boundaries.

All methods in these feature/runtime regression classes were read:

| Class | Test declarations at immutable tip | What the assertions establish |
| --- | ---: | --- |
| `TemporaryNightTest` | 32 | Absolute tail/wake composition, repeated preparation against the immutable base, saved-profile limits, Turbo chain, alarm changes, malformed snapshots, serialization, simulated autonomous return, Off, execution expiry |
| `PreparedPublicationCancellationTest` | 5 | Exact pending ownership, cancellation at initial publication and confirmation, uncertain writes, bounded cleanup |
| `NightTemporalEligibilityTest` | 12 | Request/wake/clock/offset boundaries and truthful pre-write versus post-write outcomes |
| `TemporaryActionOwnerTest` | 9 | Captured preset and identity, local preview, refusal before unsafe work, one composite owner start |
| `TemporaryActionBoundariesTest` | 24 | Durable owner cancellation, physical takeover, actual simulated progression, alarm handoff, history recovery, receipt confirmation, captured labels/presets |
| `TemporaryActionServiceTest` | 5 | Positive intent payload and three real service-delivery malformed-payload refusals |

These are 87 reviewed test declarations, not a new pass count. Root owns execution of the merged tree.

## Verified source and test boundaries

### Turbo and repeated temporary actions

`TemporaryProgramme.prepare` explicitly expands Turbo through `TURBO_THEN_HEAT`, independent of a profile's repeat-Turbo setting. A 25-minute Boost has one 10-minute Turbo segment followed by 15 minutes of Heat. A standalone captured Turbo is validated separately with a 10-minute maximum. A temporary Boost may last up to 30 minutes because the remainder is Heat.

`TemporaryNightTest.boostAlwaysUsesOneTurboThenHeatEvenWhenTheProfileRepeatsTurbo` checks the exact modes, durations, and recorded prefix. The two repeated-interruption tests use the original `temporary.base` and show that a replacement temporary interval does not append the displaced interval. These repeated-action tests are preparation tests; they do not establish successive actual uploads, disconnects, and recovery for that scenario.

### Cancellation and confidence

The runner encloses the initial pending publication in its transaction, checks exact pending request ownership before/after upload and before START, preserves a durably committed exact confirmation, and bounds its non-cancellable repair to eight seconds. It does not overwrite a newer pending request during cleanup.

The app tests directly exercise temporary cancellation after durable pending publication, after an accepted programme fragment, and after confirmed publication. They assert, respectively, restoration of the prior durable night with no writes; UNKNOWN with no START after a possible write; and one exact confirmed revision, one START, and one history event. `aCachedMatchingFirstStepCannotConfirmTemporaryBeforeItsNewStartReport` also holds back the new report and verifies that a cached matching state cannot supply confirmation.

The incoming branch had a weaker manual Off ending repair: it compared a boolean confirmation, identity and STOPPED kind. The merge now preserves the UX `b1c3a81` captured `PendingEnd` value, exact equality with the durable ending journal, same pending request check, and eight-second repair. This prevents an unrelated ending journal from being completed by an old cancellation.

### Planned return while the phone is away

`theStoredProgrammeReturnsToTheOriginalTimelineWithoutAPhoneCommand` applies the real composite through the owner and advances `DemoBedJet` through the return, wake, and after-wake phases. It checks the returned controls and that no additional application packets or events occur. There is no scheduled phone resume command at the temporary return.

`aPhysicalOffAfterTemporaryConfirmationCannotBeRestartedAtThePlannedReturn` advances the demo after physical Off and verifies Standby, zero programme step, and no application send. This establishes the implementation's simulator behavior. It is not a claim of physical BedJet or phone-away hardware testing. The test reconnects for observation after advancing the simulator.

### Alarm changes and recovery

Core tests cover retaining only the remaining temporary Turbo/Heat, avoiding an elapsed prefix, earlier wakes trimming/removing the interval, and unchanged alarms producing no rewrite. The owner journals a pending alarm as an observation before the temporary upload, then transfers it to the newly confirmed revision. Existing newer matching inbox entries are preserved.

Boundary tests check successful alarm handoff, a newer inbox, cancellation after the confirmed runtime clear, restoration from the confirmed-on-disk crash boundary, and missing history repair. `recoverConfirmedRevision` repairs a known existing history record from a confirmed exact active revision without inventing an event; UNKNOWN/manual nights and absent records are not backfilled. A failed history save does not block wake/end/retry alarm restoration.

### Short-test coexistence

The immutable feature branch has no UX equipment-test marker. The merge retains both `ActiveNight.isTestRun` and `temporary`, both `Preparation.Refused.durationProblem` and `temporaryRefusal`, the normal 18-hour duration policy and fixed five-minute test compiler, and the no-resume-offer/no-override-clear rules for test runs. `stopTracking` retains the underlying original timeline for temporary nights while preserving the test-run exclusion.

The fixed short-test plan cannot provide the required ten-minute middle clock anchor for a temporary composite, so its current composition path refuses rather than rewriting the fixed short test. No speculative additional guard was added without a failing regression.

## Coverage qualifications and targeted follow-up

1. **Successful sub-minute timing:** the immutable successful preparation tests mostly use exact minutes. Durations and stored plans have minute granularity, while upload/clock synchronization take time. This alone is not proof that elapsed Turbo can restart; the runner already checks the first temporary step's end before START.

   Two controlled tests were added to the merged `TemporaryNightTest`, without a timing production change: `alarmRetimeCannotRestartExpiredTemporaryTurboAfterAShortUploadDelay` and `alarmRetimeCannotRestartExpiredTemporaryTurboAfterAShortClockSyncDelay`. They first apply a 25-minute Turbo→Heat programme to `DemoBedJet`, prepare RETIME one second before Turbo ends, and move time two seconds during a real upload/clock-sync command. The overall temporary interval and request deadline remain live. Assertions require zero START, retryable UNKNOWN, and Heat continuation. Root's merged `ux-temporary-merge-red` run subsequently passed both cases: `TemporaryNightTest` 34/34, zero failures/errors/skips. The first-temporary-step guard already prevents the suspected elapsed-Turbo restart, so no timing production change was needed. Minute-granularity planning remains qualified; no second-exact physical return claim is made.

2. **Positive service delivery:** the two positive `TemporaryActionServiceTest` methods assert the captured serialized intent payload/identity/origin. The three negative tests execute real `onStartCommand` rejection before foreground promotion. A positive temporary payload is not directly executed end-to-end through `onStartCommand` by those five tests. Generic owner/service tests and native handoff tests cover adjacent seams, but should not be described as that exact positive end-to-end case. **Merged coverage update:** the current `TemporaryNativeActionTest.shortcutCarriesTheCapturedTemporaryPresetAcrossTheServiceIntent` now calls actual `BedJetService.onStartCommand` and asserts receipt-confirmed completion, SHORTCUT origin, original wake and captured preset. That merged test closes the positive route gap across the combined suites; it passed in `ux-temporary-merge-behaviour-red`, where TemporaryNativeActionTest was 6/6 GREEN.

3. **Repeated actual temporary executions:** preparation is covered for shorter and longer repeated intervals, but a successive successful temporary execution followed by disconnect/restart is not directly covered by those two core tests.

## Other merge changes explicitly preserved

- F2 restoration still uses the captured original payload and rechecks it after backup preparation. The merged refusal clears only the exact slot intention and restores only prior unresolved slot evidence, retaining the existing refusal reason.
- F3 unchanged observations crossing wake remain quiet. The incoming common expiry helper contains the unchanged check, so a real pending request still expires truthfully while an unchanged observation cannot acquire a failure reason.
- P7's source-policy-change regression had a valid pre-fix failure: expected requested wake 07:00, warning said the superseded 08:30 alarm after the actual fallback programme upload became UNKNOWN. The merged runtime carries the actual prepared wake in `RetimeExecution`, updates only the matching durable pending request before upload, and passes it through terminal/timeout warning branches. Fresh merged app GREEN was then observed in `ux-temporary-merge-behaviour-red`: RetimeNotificationTest 64/64, including the exact policy-toggle case. The combined batch still had separately owned UI/widget failures.
- Captured Boost execution uses the shared `boostDescription(context, capturedMinutes)` helper; it does not revert to the incoming generic runtime Boost label.
- Receipt confirmation, WIDGET/background origins, 90/100/115-second owner/service/UI bounds, radio-off refusal, off/on admission invalidation, and CHECK_BEDJET remain in the merged owner paths.

## Review status

Four owned production conflict files and the two focused timing regressions are resolved/staged. Scoped whitespace checks pass and those production files contain no merge markers. No Gradle invocation or external push was performed by this review task. Root reported the merged core suite 240/240 GREEN; archived XML confirms TemporaryNightTest 34/34 and PreparedPublicationCancellationTest 5/5. App tests did not execute in that invocation because compilation stopped at a separate exhaustive-branch error. The subsequent app batch `ux-temporary-merge-behaviour-red` completed 295 tests with 274 passed/21 failed in the combined scope; TemporaryActionOwnerTest 9/9, TemporaryActionBoundariesTest 24/24, TemporaryActionServiceTest 5/5 and TemporaryNativeActionTest 6/6 all passed, alongside the F1/F2/F3/P7 runtime selections. The full integrated gate remains required before completion claims.

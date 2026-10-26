# Fixes2 alarm explanation evidence

Scope: explain the wake displayed on Tonight, preserve the existing alarm policy, expose the same decision in diagnostics, and link the explanation to the relevant setting. Baseline: `1c67c0a28084238377c7675e6dc25d0444fe2c22`.

## Source findings

The following Android API references were checked on 25 October 2026:

- [AlarmManager.getNextAlarmClock](https://developer.android.com/reference/android/app/AlarmManager#getNextAlarmClock()): returns the next upcoming alarm-clock event scheduled through `setAlarmClock`, or null when none is exposed. This is one event, not a list of alarms. Nocturne cannot skip an earlier rejected event and inspect a later Samsung alarm through this API.
- [AlarmManager.AlarmClockInfo](https://developer.android.com/reference/android/app/AlarmManager.AlarmClockInfo#getShowIntent()): the show intent opens or edits alarm details in the scheduling application; the trigger time is an epoch wall-clock time. Nocturne does not launch this intent to decide source trust.
- [PendingIntent.getCreatorPackage](https://developer.android.com/reference/android/app/PendingIntent#getCreatorPackage()): the system supplies the pending intent's original creator package, and the return value may be null. The original pending-intent creator does not prove which application handed that intent to another application. Nocturne retains its existing creator-based policy; the explanation does not strengthen that signal into verified provenance.

These are API contracts, not measurements of the owner's Samsung phone. In particular, a null creator on that phone remains a possible explanation, not a reproduced device fact.

## Code trace at the baseline

`night/AlarmSource.kt` reads `AlarmManager.nextAlarmClock` and obtains the nullable `info.showIntent?.creatorPackage`. Its `counts` flag checks only source acceptance. `core/plan/NightPreview.kt` then uses `AlarmPolicy.accept` to check the source and the night window before selecting the alarm. Exact durations of one and sixteen hours are accepted; shorter or longer durations are rejected. At Start, the anchor is the current start time. A running night's re-time anchors the window to its original start.

`acceptUnknownAlarmSource = false` rejects a missing creator, even if the owner expects the event to be a Samsung alarm. The persisted setting defaults to true; the null-creator rejection applies when it is off. A known Samsung package, `com.sec.android.app.clockpackage`, is in the default allow-list. The profile's `useAlarm = false`, a valid tonight-only override, an unapproved source and an out-of-window event are separate reasons for a profile or override wake.

The baseline Tonight screen shows a warning for an unapproved creator and a note for some missing alarms, but has no consistent explanation beneath the wake. It does not explain an accepted-source alarm outside the window. Running nights keep the stored `wakeAt`, which can differ from the currently exposed alarm while a change is pending, failed or not yet observed. The stored night has an origin enum but no permanent creator package, so a current creator must not be presented as the historical source of a saved wake.

## Implementation

[AlarmExplanation](../../app/src/main/kotlin/io/github/willzy12h/nocturne/night/AlarmExplanation.kt) supplies the decision for both the small Tonight line and the diagnostics helper. It calls the existing `AlarmPolicy.acceptsSource` and `AlarmPolicy.accept` methods; no core policy, default, allow-list or window boundary is changed. Current settings are checked again rather than trusting an older `NextAlarm.counts` value.

The decision is separate from the wake state. `ELIGIBLE` means the current alarm passes the current profile/source/window policy, not that a running BedJet has applied it. Preview wording can say “used”; running wording names the saved wake and then the current observation. Matching current/saved alarm times are labelled as matching, without claiming the current creator is historical provenance. Pending, failed, unknown and manual states have explicit descriptions. Confidence takes precedence over a manual-tracking flag, so an unconfirmed manual attempt does not claim that a manual programme is confirmed. A saved tonight-only override retains its policy even when a later manual attempt is uncertain. Running decisions prefer the saved profile snapshot and original start, and matching pending journal entries are checked against the night identity and target device.

[TonightScreen](../../app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt) places the wrapping line beneath the wake headline/stat tiles or beneath the simple timeline. [AlarmExplanationLine](../../app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AlarmExplanationLine.kt) preserves the existing palette and small-body typography, with a full-width target of at least 48 dp and an action label. Existing wake-origin labels and their 320 dp/200% compact title rule are unchanged. All new app-facing sentences and clock app names are in [strings_alarm_sources.xml](../../app/src/main/res/values/strings_alarm_sources.xml).

| Case | Action |
|---|---|
| Eligible, unknown or disallowed source | Open Settings at Alarms through `onOpenAlarmSettings`. |
| Alarm outside the fixed night window, while wake editing is allowed | Open the existing tonight-only wake picker. |
| No alarm or profile alarm opt-out | Open the selected profile editor. |
| Explicit override, while wake editing is allowed | Open the existing tonight-only wake picker; the existing clear-override control remains available. |
| Changed current alarm beside a saved wake | Open Alarms, without implying a profile edit applies to the running night. |
| Pending/unknown/manual wake that cannot be edited | Keep the existing command-owner restriction; the explanation opens source settings or the profile setting applicable to the decision. |

`AlarmSource.diagnostics` returns the decision, explanation text, wake state/origin/time, next alarm time and nullable creator, source-acceptance result, original window bounds, pending requested wake, matching-time flag and destination. It also records that only the next alarm is exposed. Root integration owns the `NightActions.diagnostics()` call and Settings destination; this patch does not edit those owned files.

## Executed verification

### RED: missing explanations on the unchanged baseline

The shared serial runner executed:

```text
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py \
  --project /workspace/scratch/6cc8f67848d1/nocturne-alarm-work \
  --label alarm-red -- \
  :app:testDebugUnitTest --tests '*AlarmExplanationTest*'
```

Result: **19 tests, 19 assertion failures, 0 errors, 0 skipped**, exit 1. Every case first verified the expected wake under the existing policy; the null-creator cases also verified source acceptance remained false/true with the setting. All 19 then failed at the absent explanation node. An exact failure was:

```text
java.lang.AssertionError: Failed: assertExists.
Reason: Expected exactly '1' node but could not find any node that satisfies:
(Text + EditableText contains 'Alarm 06:30 ignored: source unknown — tap to change' (ignoreCase: false))
```

The accepted-Samsung control failed on the missing `Next alarm 06:30 · Samsung Clock — used` node. This was an observed missing-behaviour RED, not a compilation failure. Raw evidence is in `fixes2-evidence/alarm-red/gradle.log`, `result.json` and `app/TEST-io.github.willzy12h.nocturne.ui.AlarmExplanationTest.xml` under the shared workspace.

### First implementation verification and callback fixture correction

`alarm-green` executed **35 tests: 32 passed, 3 failed, 0 errors, 0 skipped**. The 19 source/diagnostics cases, four source-line layout cases, eight existing wake-title controls and START creator-attribution test passed. Three new callback cases could not find their expected clock text because their fixture did not isolate the system time zone, while the alarm instants and expectations were UTC. The fixture now sets and restores UTC, as the explanation matrix already did. No production change was needed for those callbacks.

The captured client `gradle.log` stopped after resource processing, but the daemon log recorded the complete executed test task and its failures. The fresh JUnit XML timestamps identify this run; `fixes2-evidence/alarm-green/daemon-build-output.log` preserves the non-debug build output from `daemon-110247.out.log`. This attempt is not reported as an all-green run.

### RED: generic pending-operation wording

`alarm-pending-red` ran `:app:testDebugUnitTest --tests '*AlarmExplanationTest*' --tests '*AlarmExplanationActionTest*'`: **24 tests, 2 assertion failures, 0 errors, 0 skipped**. All three callback cases passed with UTC isolation. Only the new pending MANUAL and START cases failed, because the existing resource said “wake change pending” for both. Their exact missing-node assertion required:

```text
Saved wake 07:00 — change pending · Next alarm 06:30 from Samsung Clock
```

The implementation changes the one pending resource to the generic “change pending”; the explicit RETIME test remains. The START control subsequently uses a faithful new-start snapshot (no applied programme, no previous expected identity, saved/requested wake 06:30); the MANUAL control retains its previous requested wake. This does not change command policy or invent a second pending-kind flag.

### RED: confidence and saved override after a manual attempt

Independent review identified that a manual-tracking flag could hide `UNKNOWN` or `PENDING` confidence. `NightActions.manualChange` can leave that flag set after an unconfirmed attempt, so the explanation must preserve the uncertainty. The broader run labelled `alarm-final-green` was executed after adding those two regression tests and was **not green**: **45 tests, 2 assertion failures, 0 errors, 0 skipped**. Its failures were:

```text
unknownManualAdjustmentDoesNotClaimAConfirmedManualProgramme
java.lang.AssertionError: expected:<UNKNOWN> but was:<MANUAL>

pendingCommandOnAManualNightKeepsItsPendingBoundary
java.lang.AssertionError: expected:<PENDING> but was:<MANUAL>
```

A further control checked that uncertain manual tracking does not suppress the saved override policy. Before either production correction, `alarm-manual-policy-red` executed `:app:testDebugUnitTest --tests '*AlarmExplanationRuntimeTest*'`: **9 tests, 3 assertion failures, 0 errors, 0 skipped**. It reproduced both failures above and added:

```text
uncertainManualAttemptKeepsItsSavedOverridePolicy
java.lang.AssertionError: expected:<OVERRIDE> but was:<ELIGIBLE>
```

The correction gives pending, failed and unknown confidence precedence over confirmed manual wording, and removes the manual-flag exception from the saved `OVERRIDE` decision. It does not change runtime command handling or alarm policy. Both RED runs have executed-task logs, result metadata and JUnit XML in their named shared evidence directories. Independent read-only re-review confirmed that these narrow corrections resolve the finding and that the three added tests check both the decision state and the rendered wording; that review did not run builds.

### Final verification

The interrupted `alarm-reviewed-green` attempt produced no log, result metadata or JUnit XML and is excluded from verification evidence. The fresh run is labelled `alarm-reviewed-green-resume`.

`alarm-reviewed-green-resume` executed **54 tests, 1 assertion failure, 0 errors, 0 skipped**. All 46 alarm explanation, callback, layout, wake-title and START-attribution controls passed, including the confidence/override corrections. Seven of eight existing `WakeFeedbackTest` controls passed. The remaining control, `aRunningNightOnATonightOnlyWakeIsLabelledSoInEveryLook`, failed at line 100 with `java.lang.AssertionError: DARK simple=false`: the new generic no-alarm suffix appeared beside a saved tonight-only override, contrary to the existing override presentation contract. This run is not reported as all-green.

Before correcting that description, `alarm-override-red` ran `:app:testDebugUnitTest --tests '*AlarmExplanationTest*'`: **22 tests, 1 assertion failure, 0 errors, 0 skipped**. Only the new `SAVED_OVERRIDE_NO_ALARM` case failed:

```text
java.lang.AssertionError: Failed: assertExists.
Reason: Expected exactly '1' node but could not find any node that satisfies:
(Text + EditableText contains 'Saved tonight-only wake 07:00' (ignoreCase: false))
```

The one-line correction returns the saved override/state description when no current alarm exists to assess. The line remains present and clickable, the diagnostics reason remains `OVERRIDE`, and no existing wake-feedback assertion is weakened. The new control also checks the diagnostic text/state and opens the existing tonight-only picker.

The final run, `alarm-complete-green`, executed:

```text
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py \
  --project /workspace/scratch/6cc8f67848d1/nocturne-alarm-work \
  --label alarm-complete-green -- \
  :app:testDebugUnitTest \
  --tests '*AlarmExplanation*' \
  --tests '*TonightWakeLabelLayoutTest*' \
  --tests '*StartAlarmSourceTest*' \
  --tests '*WakeFeedbackTest*'
```

Result: **55 tests passed, 0 failures, 0 errors, 0 skipped**, exit 0, 114.30 seconds excluding the queue. The log records an executed `:app:testDebugUnitTest` task and `BUILD SUCCESSFUL`; the test task was not up-to-date. The fresh JUnit XML reports:

| Suite | Passed |
|---|---:|
| `AlarmExplanationTest` — UI wording, existing policy, diagnostics and wake picker | 22 |
| `AlarmExplanationRuntimeTest` — saved identity, original window, confidence and override controls | 9 |
| `AlarmExplanationActionTest` — source/profile/settings callbacks | 3 |
| `AlarmExplanationLayoutTest` — source line at 320 dp and 200% font scale in four looks | 4 |
| Existing `TonightWakeLabelLayoutTest` | 8 |
| Existing `StartAlarmSourceTest` | 1 |
| Existing `WakeFeedbackTest`, including its unchanged four-look override assertion | 8 |

Raw final evidence is in `fixes2-evidence/alarm-complete-green/gradle.log`, `result.json` and the seven `app/TEST-*.xml` files under the shared workspace. `git diff --check` and the staged equivalent are clean. Root integration retains ownership of the Settings destination/control, exported-diagnostics call and full combined gate.

The source-line geometry check uses the no-alarm paragraph in all four looks; the longer source/status paragraphs have semantic coverage at 411 dp. These checks do not establish every possible long-paragraph layout, physical Samsung behaviour, BedJet operation or TalkBack behaviour.


## Historical concurrent continuation from 367968b

The following section is preserved from `fixes2-alarm-evidence.md` at remote commit `367968bddc390e5b2bb12876fc1662ffa07e47c3` (tree `f1d7b8638693d1d161891ae331c8e68d868f0dff`). These are the separate continuation's recorded runs in `/workspace/scratch/93c42f112bf4`, including its deliberate manual-first replay and no-alarm override controls. They do not replace the local evidence above or validate the local `e114cc25481731e052a0abc564e74775666ec47e` tree or the later union. The union keeps the local `SAVED_OVERRIDE_NO_ALARM` rendered case and adds the remote runtime/manual-confidence cases; no current policy or formatter change is part of this preservation.

### RED: manual tracking must not hide confidence or saved policy

The historical run labelled `alarm-final-green` was **not green**: it executed **45 tests, 2 assertion failures, 0 errors, 0 skipped**, exit 1. `unknownManualAdjustmentDoesNotClaimAConfirmedManualProgramme` expected `UNKNOWN` but received `MANUAL`; `pendingCommandOnAManualNightKeepsItsPendingBoundary` expected `PENDING` but received `MANUAL`. All 21 source/diagnostics cases, three callback cases, four source-line layout cases, eight existing wake-title controls and the START creator-attribution control passed in that run.

The next historical run, `alarm-manual-policy-red`, executed **9 runtime tests, 3 assertion failures, 0 errors, 0 skipped**, exit 1. It reproduced both manual-confidence failures and added `uncertainManualAttemptKeepsItsSavedOverridePolicy`, which expected the saved `OVERRIDE` reason but received `ELIGIBLE`. The follow-up implementation preserves the saved origin even during a manual attempt and resolves `PENDING`, `FAILED` and `UNKNOWN` before considering confirmed manual tracking.

The original source checkout and its raw evidence remain untouched at `/workspace/scratch/6cc8f67848d1/nocturne-alarm-work` and `/workspace/scratch/6cc8f67848d1/fixes2-evidence`. Continuation work uses the isolated `/workspace/scratch/93c42f112bf4/alarm-work` checkout at the same baseline. Its copied work already contained the confidence-first implementation, so the new RED explicitly replays the earlier manual-first state branch; it does not claim that the copied implementation still had that bug. The exact replay is retained in `evidence/stepa-alarm-manual-precedence-red2/manual-first-replay.patch` in the continuation workspace.

The continuation tests also cover manual tracking while `NightStatus` reports `UNKNOWN` or `PENDING`, matching retime/inbox evidence before the status flow catches up, and the actual Tonight/diagnostics text for manual pending and unknown nights. They use JUnit4 Robolectric SDK 34 and the existing `GraphLifecycle` rule, directly or through `U06Fixture`.

### Continuation environment attempt

`stepa-alarm-manual-precedence-red` exited before Gradle could start, because the sandbox could not provide the local wildcard IP needed by its file-lock service. **No tests ran**; this is environment evidence, not an assertion RED. The same offline command was rerun with local socket access under a separate label.

### Fresh RED: replayed manual-first precedence

```text
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/alarm-work \
  --label stepa-alarm-manual-precedence-red2 -- \
  :app:testDebugUnitTest \
  --tests '*AlarmExplanationRuntimeTest*' --tests '*AlarmExplanationTest*'
```

Result: **35 tests, 9 assertion failures, 0 errors, 0 skipped**, exit 1. The 13-case runtime suite failed its seven manual-confidence cases: stored unknown confidence, a pending manual command, a saved override during an unknown manual attempt, unknown/pending runtime status, a matching pending retime, and a matching unread alarm observation. Each failed because the explanation returned `MANUAL` instead of `UNKNOWN` or `PENDING`. The 22-case Tonight/diagnostics matrix failed only `SAVED_MANUAL_PENDING` and `SAVED_MANUAL_UNKNOWN`, at the missing pending/unknown explanation node. Its other policy and saved-wake cases passed. There were no compilation errors.

The replay changed only the order of the manual state branch; it retained the saved-override policy correction. The production implementation is restored to confidence-first order before the GREEN run. Raw output and fresh JUnit XML are in `evidence/stepa-alarm-manual-precedence-red2/gradle.log`, `result.json`, and `app/` in the continuation workspace.

### Final targeted verification

```text
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/alarm-work \
  --label stepa-alarm-focused-green -- \
  :app:testDebugUnitTest \
  --tests '*AlarmExplanation*' \
  --tests '*TonightWakeLabelLayoutTest*' \
  --tests '*StartAlarmSourceTest*'
```

Result: **51 tests passed, 0 failures, 0 errors, 0 skipped**, exit 0; elapsed 79.09 seconds. The runtime and UI tests that failed during the replay now pass with confidence resolved before manual tracking.

| Suite | Tests | Result |
|---|---:|---|
| `AlarmExplanationRuntimeTest` | 13 | Passed |
| `AlarmExplanationTest` | 22 | Passed |
| `AlarmExplanationActionTest` | 3 | Passed |
| `AlarmExplanationLayoutTest` | 4 | Passed |
| `TonightWakeLabelLayoutTest` | 8 | Passed |
| `StartAlarmSourceTest` | 1 | Passed |

Fresh raw output, command/result metadata and JUnit XML are in `evidence/stepa-alarm-focused-green/gradle.log`, `result.json`, and `app/` in the continuation workspace. Gradle reported existing API/Gradle deprecations, the missing optional platform-tools package, and multiple Kotlin daemon sessions; the executed test task completed successfully. No signing configuration or device operation was used.

The source-line geometry check uses the no-alarm paragraph in all four looks; the longer source/status paragraphs have semantic coverage at 411 dp. These checks do not establish every possible long-paragraph layout, physical Samsung behaviour, BedJet operation or TalkBack behaviour. The root integration owns the full project test/build gate and the Settings navigation/diagnostics integration checks.

### Integration boundaries

- `TonightScreen` adds the optional final `onOpenAlarmSettings: () -> Unit` callback. Root integration opens Settings and scrolls to Alarms.
- `AlarmSource.diagnostics(Context, Settings, Profile?, ActiveNight?, RuntimeState, NightStatus, ZonedDateTime)` returns the alarm `JsonObject` used by root's `NightActions.diagnostics()` export.
- This Step A commit preserves the source acceptance defaults, allow-list and inclusive 1–16 hour window. The later P6 policy change is outside this commit.
- The existing tracked `AlarmSource.kt`/`TonightScreen.kt` work and the explanation component, resources, action tests and layout tests were copied intact from the original checkout. Continuation changes add confidence regression coverage, document the confidence precedence, and complete this evidence record.

### Integrated full-gate regression: a saved override with no alarm

Root's `stepa-full-gate` run exited 1. Within `WakeFeedbackTest`, **8 tests ran, 1 failed, 0 errors, 0 skipped**. `aRunningNightOnATonightOnlyWakeIsLabelledSoInEveryLook` failed at its existing line 100 assertion (`DARK simple=false`), which forbids the substring `No alarm set` while a running tonight-only wake is selected. The original assertion is retained.

The regression came from `AlarmExplanation.description`: after constructing the correct `Saved tonight-only wake` prefix, its generic current-alarm branch appended `No alarm set` whenever Android exposed no alarm. The preview override branch already avoided that irrelevant fallback. Running overrides need the same rule while preserving the saved state's pending/unknown confidence text.

The remediation is isolated at integrated snapshot `333a118a0fbe245a4e45b1ea0dc288dc49b79206` in `/workspace/scratch/93c42f112bf4/stepa-override-review`. Three new runtime regression cases cover a confirmed override, a pending override with its actual requested wake, and an unknown manual override, all with no current alarm. They retain assertions for reason, state and setting destination; the original `WakeFeedbackTest` supplies the real running-night regression across the four existing looks.

The first queued attempt, `stepa-override-fallback-red`, was stopped before acquiring the old shared build lock while root isolated the continuation runner from another session's active builds. It exited 143 and **ran no tests**. The rerun uses the continuation's separate offline cache and lock; no process or checkout from the original session was modified.

```text
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/stepa-override-review \
  --label stepa-override-fallback-red2 -- \
  :app:testDebugUnitTest \
  --tests '*AlarmExplanationRuntimeTest*' \
  --tests '*WakeFeedbackTest.aRunningNightOnATonightOnlyWakeIsLabelledSoInEveryLook'
```

Fresh RED: **17 tests, 4 assertion failures, 0 errors, 0 skipped**, exit 1, elapsed 108.45 seconds. The 16 runtime cases failed only the three new descriptions, each because the actual string appended ` · No alarm set` to the expected saved-state sentence. The original running-override UI test again failed at line 100. The other 13 runtime-confidence cases passed.

The correction returns the already-computed saved-state description when the reason is `OVERRIDE` and no current alarm exists. Confirmed overrides display `Saved tonight-only wake 07:00`; pending overrides retain `Saved wake 07:00 — change pending`; unknown overrides retain `Saved wake 07:00 — BedJet wake unknown`. Overrides with a current alarm keep the existing ignored-alarm explanation. No new resource text, source policy, confidence ordering, destination, or command behavior is introduced.

The raw RED evidence is in `evidence/stepa-override-fallback-red2/gradle.log`, `result.json`, and `app/` in the continuation workspace.

```text
python /workspace/scratch/93c42f112bf4/run_gradle.py \
  --project /workspace/scratch/93c42f112bf4/stepa-override-review \
  --label stepa-override-fallback-green -- \
  :app:testDebugUnitTest \
  --tests '*WakeFeedbackTest*' \
  --tests '*AlarmExplanation*' \
  --tests '*AlarmSettingsNavigationTest*'
```

Focused GREEN: **56 tests passed, 0 failures, 0 errors, 0 skipped**, exit 0, elapsed 111.20 seconds.

| Suite | Tests | Result |
|---|---:|---|
| `AlarmExplanationRuntimeTest` | 16 | Passed |
| `AlarmExplanationTest` | 22 | Passed |
| `AlarmExplanationActionTest` | 3 | Passed |
| `AlarmExplanationLayoutTest` | 4 | Passed |
| `AlarmSettingsNavigationTest` | 3 | Passed |
| `WakeFeedbackTest` | 8 | Passed |

The existing running-override assertion now passes across dark/light and simple/standard looks. The pending and unknown no-alarm override cases retain their confidence wording, and the existing source, diagnostics, callback, layout and navigation/export controls pass. Raw output, command/result metadata and fresh JUnit XML are in `evidence/stepa-override-fallback-green/gradle.log`, `result.json`, and `app/` in the continuation workspace. Existing API/Gradle deprecation warnings remain; no test errors occurred. Root owns the subsequent integrated full project gate and the other gate remediations. This formatter correction makes no device claim and does not change the Step A alarm policy.

# P6/P7 and the Settings short test — implementation evidence

Base: `367968bddc390e5b2bb12876fc1662ffa07e47c3` (completed Step A). Work is isolated in `ux-alarm`.

## Diagnosis and decisions

- Samsung Clock and Google Clock already appear in the default source allowlist. `Settings.acceptUnknownAlarmSource` already defaults to `true`; a saved explicit `false` must remain respected. Starting a normal night with an alarm five minutes away falls outside the one-hour minimum. This is a duration-policy refusal, not evidence of a Samsung attribution defect.
- Keep the normal minimum at one hour. Extend the shared upper bound from 16 to 18 hours in selection, compilation and validation. Explain a too-soon alarm explicitly.
- Android exposes only its next alarm to this API. Re-check must read it again and state its time, reported creator/source, applicable policy and the selected/saved fallback, even when the result has not changed.
- The explicit Settings test uses a separate five-minute, duration-based programme and the existing owner, slot backup, pending journal and confirmation receipts. It does not alter the normal night rule or run from a settings save.
- Test identity must survive restart in both active state and its diagnostic history record. Exclude these records from real sleep History, CSV and Health Connect overlay queries. Health Connect integration here is read-only; Nocturne does not insert sleep sessions.
- Existing Step A regression coverage quiets unchanged/ignored alarm observations at the wake boundary. P7 adds requested-wake and target context to genuine terminal warnings and a fresh read-only Check BedJet action; it must not replay a failed programme.

## Execution ledger

| Run | Purpose | Result |
| --- | --- | --- |
| `ux-alarm-policy-warning-red` | 18-hour boundary/compiler, explicit too-soon reason and contextual terminal warning regressions | Core 4 tests, 2 expected failures; app 3 tests, 3 expected failures; no errors/skips |
| `ux-alarm-short-test-red` | Explicit short test, marker/Health boundary, preserving the real wake override | Core 3 tests, 2 expected failures; runtime app 4 tests, 4 expected failures. Two additional UI failures were fixture navigation failures, excluded from feature-RED claims. |
| `ux-alarm-settings-ui-red` | Actual Settings controls after using the fixture’s supported compact navigation | 2 tests, 2 expected failures: missing Re-check alarm and Preview five-minute test controls; no errors/skips |
| `ux-alarm-focused-green` | Policy/compiler/night suites plus alarm UI, test run, receipt, expiry and sleep-boundary coverage | Core 88/88 passed. App 160/164 passed: three existing runtime/notification reason-consistency failures, one new test assuming UTC equals Android’s display zone. No errors/skips. |
| `ux-alarm-retime-final-green` | Re-check all affected warning/expiry/read-only recovery paths and the new test-run/UI checks after the corrections | App 94/96 passed. Two existing interrupted-cleanup assertions caught replacement of the original command reason with contextual display text. No errors/skips. |
| `ux-alarm-terminal-green` | Preserve the original terminal outcome and cache its complete warning separately through cleanup retries; add the fresh-observation attribution assertion before this queued run starts | App 95/96 passed. Both original cleanup contracts passed. One expected RED: a new observation must name its own requested wake, not the older uncleared retry. No errors/skips. |
| `ux-alarm-observation-green` | Verify fresh-observation attribution and the existing suites; regression verification of Last check during a blocked owner, after temporarily restoring only the former publication ordering | App 96/97 passed. All 63 terminal-warning tests passed. One expected RED: Last check still reported the previous absent alarm while the owner was occupied, although Android's fresh Samsung result had been read. No errors/skips. |
| `ux-alarm-final-green` | Restore publication before admission and verify all six affected suites together | App 97/97 passed; no failures/errors/skips. Gradle exit 0. |
| `ux-alarm-duration-red` | Typed core duration reasons and resource presentation through model previews, Start, running-wake override, owner exceptions, pin text and the actual Tonight refusal UI | Core 4 tests, 4 expected failures. App 13 tests, 7 expected failures (all 6 new cases plus the existing Tonight refusal case). No errors/skips. |
| `ux-alarm-duration-green` | Wire the two duration reasons to Android resources and retain unrelated planning, night and terminal-warning behaviour | App 79/79 passed. Core 96/97 passed; one retained assertion still required English guidance inside the core exception. No errors/skips. |
| `ux-alarm-duration-core-green` | Replace that core text assertion with the exact typed exception/reason, leaving the already-passing app production code unchanged | Core 97/97 passed; no failures/errors/skips. Gradle exit 0 (21.53s). |

Commands use `/workspace/scratch/93c42f112bf4/run_gradle.py`, the isolated GPT11 offline cache and shared serial lock. No device or Samsung hardware verification is claimed.

Counts include only the classes selected by that run, cross-checked against Gradle's executed-test summary. Copied XML for unselected classes can be stale and is excluded. The initial six-class warning/test-run candidate comprises `RetimeNotificationTest` (63), `RetimeExpiryBoundaryTest` (5), `UnchangedAlarmObservationTest` (18), `ShortBedJetTestRuntimeTest` (5), `AlarmAndShortTestSettingsTest` (3), and `AlarmWindowExplanationTest` (2). The later blocked-owner regression raises the last class to 3 and the candidate total to 97.

## Test-fixture correction

The first Settings UI attempt used `U06Fixture.tab` at 411dp/100%, where navigation has text instead of the compact content-description semantics that this helper selects. It failed at the tab selector before reaching either feature. The fixture then used its standard 320dp/200% configuration while the controls remained absent; the corrected run failed at the intended missing controls. The subsequent UI run passed all three cases, including actual positive confirmation. Cancel and Start test were checked for unclipped layout and at least 48dp touch bounds. No production assertion was weakened.

## Implemented behaviour and source map

Paths below are relative to the repository; `core/...` expands to `core/src/main/kotlin/io/github/willzy12h/nocturne/core` and `app/...` to `app/src/main/kotlin/io/github/willzy12h/nocturne`. Line references describe this isolated patch before root integration.

| Area | Source | Evidence |
| --- | --- | --- |
| Normal 1–18h policy and unchanged source preference | `core/.../plan/WakeResolver.kt:13`, `NightPreview.kt:17`, `PlanCompiler.kt:21`; `core/.../store/Settings.kt:32,51` | AlarmWindowUxTest 4; WakePolicyHardeningTest 7; WakeResolverTest 6; compiler/planning suites 22 |
| Too-soon explanation, confidence precedence and test label | `app/.../night/AlarmExplanation.kt:49,78,112,138` | Two reason cases in AlarmWindowExplanationTest; AlarmExplanationRuntimeTest 16; rendered explanation matrix 22; existing override feedback 8 |
| Fresh Android re-check and Settings limitation/help | `app/.../ui/AppViewModel.kt:219`; `app/.../ui/SettingsScreen.kt:193`; `app/.../night/AlarmExplanation.kt:158` | Real Settings UI reads a new Samsung alarm, reports too-soon/profile fallback, then reads its removal; the third AlarmWindowExplanationTest holds the owner to check immediate publication |
| Explicit test preview and positive confirmation | `app/.../ui/ShortTestControls.kt:25`; `AppViewModel.kt:242`; `app/.../night/NightActions.kt:57` | Preview/cancel sends nothing; positive confirmation starts a marked test through the owner; settings saves do not start it |
| Five duration minutes and receipt-backed publication | `core/.../plan/ShortBedJetTest.kt:10`; `core/.../night/NightRunner.kt:42`; `app/.../night/NightActions.kt:60,341` | ShortBedJetTestTest 3: fixed three-minute cooling/two-minute warming, no clock-time steps, PENDING before writes, later confirmation, active-night refusal |
| Durable test and end markers | `core/.../night/ActiveNight.kt:55`; `core/.../store/History.kt:42`; `app/.../night/RuntimeState.kt:88`; `NightActions.kt:505,593,616` | Runtime tests verify restart markers and both ordinary and interrupted test-end preservation of the real tonight override |
| Sleep boundary | `app/.../ui/AppViewModel.kt:78`; `app/.../platform/HistoryCsv.kt:32`; `SleepOverlay.kt:14`; `app/.../data/Repository.kt:263,280,298`; `ui/features/HistoryFeatures.kt:61` | Marked records absent from real History/CSV; Health client is not constructed; morning check-in is refused |
| Genuine terminal warning context | `app/.../night/RuntimeState.kt:36`; `NightActions.kt:864,1039,1079`; `app/.../service/BedJetService.kt:85` | Requested wake retained even for a profile fallback; warning and durable runtime reason agree; prior uncertainty is not misattributed as this new request |
| Check BedJet without replay | `app/.../night/NightActions.kt:85`; `app/.../notify/Notifications.kt:159`; `app/.../service/BedJetService.kt:204` | Fresh target report/reconnect; zero programme writes; pending re-time preserved; failed read does not reuse cache or promote UNKNOWN |
| Duration wording from Android resources | `core/.../plan/NightDurationProblem.kt:4`; `app/.../night/PlanMessages.kt:10`; `app/.../ui/AppViewModel.kt:263,273`; `ProfilesScreen.kt:172` | Four typed-reason tests and six resource-boundary tests; actual Tonight refusal remains covered by GapsCoverageTest |

The test uses Cool at 22°C / fan 30% for one Start minute and two Middle minutes, then Heat at 30°C / fan 30% for two Wake minutes. It has no After Wake phase. The confirmation names the captured target and slot, and the owner refuses a changed target/slot, an expired preview, or an existing tracked night. Saved profiles and the normal one-hour minimum remain independent of this explicit test route.

The P7 warning's target is the BedJet device identity: its verified name for that exact identifier, otherwise its address. The warning names the requested wake time and terminal reason. It does not claim an intended or applied wake mode/temperature. Check BedJet reports the newly received actual mode and fan separately, without replaying a programme or promoting prior uncertainty.

## Candidate corrections

The first combined run preserved three useful existing equality assertions: a terminal warning must match the saved runtime reason. Enriching only the notification broke that invariant. The terminal-settlement callback now receives the complete contextual reason. The next run caught a separate contract: the returned command outcome must retain the actual original hardware or refusal reason. `TerminalRetime` now retains that immutable outcome alongside its complete warning; cleanup reuses the warning rather than reconstructing it from changed state. The two existing cleanup tests retain their exact outcome assertions and additionally check runtime/notification equality. Single-notice and durable-history ordering remain intact.

The additional source-time regression takes its expected clock time from Android’s actual `AlarmSource` result, because the injected graph clock’s UTC zone need not equal Robolectric’s phone display zone.

The interrupted-journal test was then strengthened to check the requested time of a newer observation. It proved a real attribution defect: the new refusal was borrowing the older uncleared retry's wake. The observation path now supplies its fresh resolved wake explicitly, while a cleanup retry retains its original immutable warning.

Re-check publishes the completed Android read before waiting for any BedJet operation, so a later connection wait or timeout cannot leave the previous check result on screen. This ordering initially lacked a dedicated regression. A controlled-owner test was then added, and only the former post-operation publication ordering was temporarily restored. The executed test failed at the intended Last check assertion while the fresh Android source and still-blocked owner were both verified. The publication fix was then reapplied for the final run. This is later regression verification, not a claim that this particular RED preceded the initial implementation.

## Duration-error resource follow-up

The initial feature commit is `0398344`. Review identified changed duration literals in the pure-core preview/compiler and the matching rewrite refusal. The follow-up uses one two-value `NightDurationProblem` plus a specific `NightDurationException`, with optional reason metadata on the transient `Preview` and `Preparation.Refused` models. Core supplies a diagnostic code; `PlanMessages.kt` selects the Android resource. No English text comparison selects policy or presentation.

`NightDurationProblemTest` verifies the compiler, preview, start preparation and rewrite preparation keep the typed reason. `NightDurationResourcesTest` verifies the real AppViewModel preview/result, Start, running-wake override, owner exception and pin-preview presentation. The pin case deliberately supplies unrelated diagnostic text to prove the resource is selected by type. The existing `GapsCoverageTest.refusedPreviewExplainsWhyAndDisablesStart` now selects the resource text and still checks the disabled Start button and absent active night. Existing non-duration compiler errors retain their behaviour.

The first wider run passed all 79 app cases and caught one remaining core test that searched the exception's English message for “tonight-only”. `PlanningHardeningTest` now requires the specific `NightDurationException` and `TOO_LONG` value. The app resource tests still verify the complete guidance. No production code changed after those 79 app passes; the final core run checks the adjusted contract and the full selected 97-test core set.

Only the displayed duration wording changes to “Normal nights…”; the one-hour minimum, eighteen-hour maximum, outcome categories and zero-write refusal boundaries remain the same. This adds no serialized fields and requires no further release-serialization fixture changes.

## Integration and limits

- Root owns final integrated tests and release compilation, including Bluetooth-off guards. Both new hardware routes call the existing `connected` owner helper and inherit root’s new Bluetooth preflight.
- Four stored models gain backwards-compatible fields: `ActiveNight.isTestRun`, `NightRecord.isTestRun`, `PendingEnd.isTestRun`, and `PendingRetime.requestedWakeAt`. Root owns the matching release-serialization fixture updates. `ShortTestPreview`, `RetimeExecution` and `TerminalRetime` are transient.
- UI worker is using the saved profile name as the running-heading fallback so the ephemeral test snapshot appears as **Five-minute BedJet test**. The pending wake header should prefer `PendingRetime.requestedWakeAt` before its legacy `alarmAt` fallback.
- `CHECK_BEDJET` is the only new `NightActions.Command`. The short test is a distinct captured-preview API, not a generic widget/shortcut start command.
- App feature text and the changed duration wording are in resource files. The duration follow-up has its own executed RED/GREEN ledger; it is separate from the earlier 97-test feature gate.
- Health Connect remains read-only; no sleep record writer was added. Tests and results are host-side Robolectric/core evidence, not a physical BedJet or Samsung alarm-device validation.
- The existing Step A quiet handling remains covered by 18 unchanged-observation and 5 expiry-boundary tests. No physical KL13 event was available to reproduce; this patch addresses the proven policy/observation paths and genuine terminal reporting without claiming a hardware diagnosis.

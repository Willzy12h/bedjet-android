# Task 5 implementation checkpoint — GPT-6 Astra

## Status and integration boundary

Initial Task5 implementation is verified and committed as the integration checkpoint. The explicitly scheduled Task6 integration continuation remains required before independent Task5 review. No hardware has been tested. No push was attempted. Worktree `/workspace/scratch/ad4eb857ceea/nocturne-wt-task5`, branch `astra/nocturne-task5`, original base `9bc63a2c324996e3236245be0aa6ff13a25f8dbe`. Parent will align committed history to the published equivalent base and reviewed Task6; do not reset unfinished source.

## Implementation

- Preserved Midnight Ink, Daylight, typography, cards, curve, five tabs and Simple view. Local large-text corrections: wrapping two-column Quick buttons, minimum heights rather than fixed heights, scaled Simple timeline time column, named icon-only navigation at large font sizes. Normal navigation labels remain.
- Snackbar uses Short duration and measured bottom padding so lower actions remain above it. View-model observations use viewModelScope; running command label and queued count come from the coordinator. One outcome collector presents coordinator outcomes, including externally initiated commands while the UI is open; normal actions no longer emit a second copy.
- SavedStateHandle holds selected tab, serialised editor source/draft, and parked editor. Both recreation and a parcelled SavedStateHandle roundtrip preserve unsaved edits. `openTonight=true` on initial/new MainActivity intent selects Tonight and parks, rather than discards, the dirty draft; Resume editing draft reopens it. Existing confirmation identities remain captured, including save-and-reapply and Stop tracking dialogs.
- Temporary choice has the exact reset label “Tonight only · Follow schedule”. Preview headline/curve consume the same current NightPreview. The current-time tick refreshes a screen held across noon expiry. Tonight-only wake picker/clear/expiry is available both idle and running; active snapshot and pending/confirmed wake labels are distinct.
- Added confidence/manual-tracking display, Stop tracking explanation, Keep tracking, eligible Resume through shared replacement confirmation, re-check alarm and diagnostics/quiet-status UI. Profile overflow has explicit slot selection, preview, duration explanation and original backup restore; slot originals are filtered to the selected target (plus legacy unknown target).
- Setup flow reuses actual permission/system-screen rechecks, Samsung guidance, explicit bounded discovery and selection, separate demo, supervised coordinator fan check and durable completion; Settings can reopen it. Missing-target links lead to setup. Successful Forget clears setup-check state and routes back to selection. Approved narrow runtime exception: `selectTarget(null)` emits the exact “Forgot this BedJet. Choose a BedJet to connect to.” once; target ownership/refusals/clearing remain unchanged.
- Morning check-in records feedback against the completed night; a separate dialog proposes a validated Middle-only fan/temperature adjustment. Acceptance preserves unrelated current profile edits; it refuses deleted profiles, changed Middle/limits or already-applied feedback and never reconstructs a reusable profile from a historical snapshot. Profiles and linked acceptance event use existing atomic write/rollback machinery. Active nights are untouched.
- Added scheduled theme fields (22:00 dark / 07:00 light defaults), overnight/daytime/equal-times semantics, zone/DST resolution and UI time controls. Equal times is documented as always dark. MainActivity reevaluates its injected clock every second and updates system bars. Shared widget resolver uses full Settings; config activity adapter now uses Settings and graph.now(). Task6 owns widget scheduling.
- Sliders expose labels, units and state; switches have one row-level toggleable Switch role and 48 dp minimum; curve has chronological text alternative. Resource migration covers UI labels, units, a11y copy and event labels; queue/history plurals use Android quantity resources. UK English only. Runtime strings remain the frozen runtime source except the approved Forget addition.
- Accepted action and confirmed outcome invoke platform CLOCK_TICK and CONFIRM feedback through View.performHapticFeedback, without overriding system haptic settings. Physical vibration/TalkBack remain untested.
- Backup validator now rejects malformed consumed temporary-choice/wake timestamps and out-of-range scheduled-theme minutes before import.

## RED/GREEN evidence

All logs are retained in `/workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/`. All Gradle calls used the bounded shared-lock wrapper from the worktree, approved elevation for sockets and cached `--offline` execution. No caches were cleared. Focused runs used `:app:testDebugUnitTest --tests '<class or method>'` and/or `:core:test --tests '<class>'`.

| Behaviour | Actual RED evidence | Subsequent GREEN evidence |
|---|---|---|
| Reject malformed imported UI timestamps | `task5-red-import.log`, validator assertion failure | `task5-green-ui-first.log` |
| Switch row semantics | `task5-red-accessibility.log`, labelled toggle node missing | `task5-green-ui-first.log`, later all UI runs |
| Scheduled boundaries / temporary reset / UI controls / saved draft | `task5-red-ui-theme.log`, `task5-red-theme-choice.log` (3 core boundaries + UI reset failed) | `task5-green-ui-first.log` |
| Setup, curve text alternative | `task5-red-setup-curve-fixed.log`, missing expected nodes | `task5-green-setup-red-runtime.log` |
| Tile navigation preserves dirty draft | `task5-red-navigation-explicit.log`, Resume editing draft missing | `task5-green-setup-red-runtime.log`, all UI run |
| Recovery confidence, pin UI, one outcome | `task5-green-setup-red-runtime.log`, 14 tests / 3 expected new failures | `task5-green-runtime-red-checkin.log` |
| Check-in proposal / consistent acceptance | `task5-green-runtime-red-checkin.log`, 16 tests / 2 expected new failures | `task5-green-checkin.log` (check-in tests passed; independent async-theme fixture failed), subsequent UI runs |
| 320 dp / 200% Quick controls and slider semantics | `task5-green-resources-red-layout.log`, width below 128 dp and missing slider semantics | `task5-green-layout-nonincremental.log`, 18 UI tests passed |
| Running wake override still reachable | `task5-red-running-wake-extra-fixed.log`, missing running override; other two failures were fixtures as explained below | `task5-ui-and-renders.log` |
| Large-font named navigation | `task5-red-large-navigation.log`, missing named 48 dp Settings node | `task5-green-large-navigation.log` |
| Exact Forget outcome, single emission | `task5-red-forget.log`, ComparisonFailure expected exact wording vs generic selection-saved | `task5-final-focused-fixed.log` |

Representative exact recent commands:

```sh
SCREENSHOT_TAG=task5-review bash /workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/run-gradle.sh :app:testDebugUnitTest --tests '*NavigationAccessibilityTest' --tests '*ScreenshotCapture.combined320And200' --offline
bash /workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/run-gradle.sh :app:testDebugUnitTest --tests '*OutcomeTest.forgettingReportsSelectionNeededAsOneCoordinatorOutcome' --offline
bash /workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/run-gradle.sh :app:testDebugUnitTest --tests '*OutcomeTest' --tests '*ContrastTest' --tests '*UiHardeningTest.scheduledThemeUpdatesWhileScreenStaysOpen' --offline
bash /workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/run-gradle.sh :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline
```

`task5-ui-and-renders.log`: actual app Dark, Light, Simple Dark and combined captures plus UI regressions passed. `task5-green-large-navigation.log`: final large-text navigation and regenerated combined images passed (37 s). `task5-final-focused-fixed.log`: two coordinator-outcome tests, two contrast tests and held-open theme boundary passed (25 s). Contrast verifies normal/secondary text >=4.5:1, essential outlines >=3:1 on used surfaces, after-wake bars >=3:1 in both palettes. This verifies tokens/semantics, not physical accessibility.

### Fixture and host failures (not misreported as product RED)

- Initial theme run hit a Kotlin internal source-class error. Later stable-source compile reported duplicate/conflicting declarations despite exactly one source declaration per symbol (`task5-green-layout.log`). Parent authorised one frozen-source `-Pkotlin.incremental=false` diagnostic; it passed (`task5-green-layout-nonincremental.log`). Subsequent ordinary incremental focused runs and render run passed. No production workaround or cache deletion. Host cause remains unproven.
- The first navigation fixture sent an empty implicit Intent into onNewIntent, replacing the ActivityScenario launch identity; teardown hung. Preserved stack `task5-navigation-stack.log`; stopped only those exact test worker processes with approved TERM. Correct fixture clones the existing explicit Activity intent before adding the extra. The genuine missing-draft RED was then obtained and passed after implementation.
- Resource migration required TimelineTest to run Robolectric so Format can receive a real resource Context; assertions remain unchanged.
- Corrected test-only errors: protected onNewIntent invocation; captured Activity-derived graph before entering a repository worker; static ShadowAlarmManager setter; actual Settings property `themeMode`; async settings test waits for its UI node; noon simulation advances Compose mainClock; permission text exists twice (underlying settings and setup dialog), asserted both before/after rather than assuming one. Relevant original logs retained.
- Not every pure presentation/resource migration has a separately failing test. Haptic calls are implemented but not separately instrumented; physical behaviour remains pending.

## Actual app render evidence

PNG directory: `/workspace/scratch/ad4eb857ceea/nocturne-wt-task5/app/build/screenshots/task5-review/`.

Representative inspected files:

- `dark-01-tonight.png`
- `light-01-tonight.png`
- `simple-dark-01-tonight.png`
- `combined-320dp-200pct-tonight.png`
- `combined-320dp-200pct-primary.png`
- `combined-320dp-200pct-quick.png`

Native Robolectric rendering at API34, clock pinned BEFORE Activity composition to `2026-10-22T22:30:00Z`, Europe/London (23:30 local). Default flows 411 dp / 1x font; combined 320 dp / 2x font asserted in fixture; portrait height 891 dp. Demo explicit, setup completed, active night/temporary choice/override cleared, Bluetooth scan/connect and notification permissions granted, exact alarm allowed and battery optimisation exemption explicit. No physical phone, TalkBack, BLE or BedJet was used. Existing flow filenames retained; five capture methods skip unless SCREENSHOT_TAG set.

Visual inspection found the large-text nav label wrapping; that was fixed and rerendered before reporting. Start and Quick actions remain fully reachable/readable. Large-font mode intentionally retains tab icons with accessible names rather than broken multi-line labels; normal-font design unchanged.

## Required Task6 adoption after parent alignment (not stubbed)

The parent explicitly ordered Task6 integration first, then a Task5 continuation for these real APIs before Task5 independent review:

1. `BedJetService.runOwned(context, notifyFailures = false) { ... }` in AppViewModel origin-owned actions, with TimeoutCancellationException caught and displayed BEFORE generic CancellationException rethrow. Other exceptions visible once. Test denial/startup failure/operation timeout and preserve ownership, lastResult and cancellation. No false notification suppression exists in this checkpoint.
2. Import `ui.features.PlatformSettingsSection` and call `PlatformSettingsSection(model.graph)` at the marked Settings insertion immediately after Phone setup.
3. Import `HistoryExportButton`; place `item { HistoryExportButton(model.graph) }` after the History header.
4. Import `HealthSleepOverlay`; call `HealthSleepOverlay(model.graph, night)` below each card's curve.
5. Import `PhoneHandoverMenuItems`; move the More overflow outside `night?.let` in NightRecoveryControls so it is available idle and active. Call `PhoneHandoverMenuItems(model.graph) { more = false }`; show Re-check alarm only when a night exists. Use wrapping action layout at narrow/large font. There is no interim empty menu or feature stub.

6. Completed independently before alignment: consume `openTonight` and regress restored/later user navigation; see follow-up checkpoint below.
7. Completed independently before alignment: explicit atomic Demo-to-real selection and held-open configuration theme timer; see follow-up checkpoint below.

Task6 signature contract is exact as above; feature implementations own launchers/state/resources. `MainActivity` navigation extra is exact boolean `openTonight`; both creation and onNewIntent honour it over restored tab and preserve dirty drafts. No duplicate tile-only screen/control.

## Self-review and concerns

- Reviewed captured identities for resume/replacement/save-and-reapply/Stop tracking and target filtering for slot originals. No new UI command bypasses NightActions or service ownership.
- Reviewed profile consistency: accepting an old proposal must not overwrite unrelated profile edits or resurrect a deleted profile. Added a separate fresh-record stale-Middle refusal assertion as well as acceptance and deletion checks.
- Kept broad existing source organisation; AppViewModel and ProfileEditor remain substantial pre-existing files. New focused controls avoid a screen redesign.
- Remaining integration work above is mandatory before Task5 completion/review; parent is deliberately coordinating this dependency.
- Initial config-timer gap is resolved in the independent follow-up checkpoint below; Task6 still owns widget schedule refresh.
- Initial explicit Demo-to-real selection gap is resolved in the independent follow-up checkpoint below, preserving default coordinator caller semantics.
- Physical device access, Samsung permissions/battery policy, actual TalkBack navigation, haptics, slot readback and BedJet programme confirmation remain pending; no hardware claims.

## Full-gate fixture diagnosis

The first complete gate (`task5-full-gate.log`) failed: 175 app tests, 10 failures. Five were screenshot opt-out: TestWatcher.starting catches its assumption and still evaluates its wrapped statement, so the second @Before assumption produced TestCouldNotBeSkippedException. Replaced it with an outer TestRule that checks the opt-in before evaluating/launching the Activity. Three AppSmoke and one Appearance timeout used the existing after-composition evening clock; moved fixture clock initialisation before Activity creation without changing any behavioural assertions. Focused smoke/appearance/accessibility/screenshot-opt-out run passed in 40 s (`task5-gate-fixtures-fixed.log`), with screenshots correctly skipped.

The tenth failure was inherited by AccessibilityTest: a RuntimeServiceTest background autoSnapshot tried to move a file after its Robolectric test directory had been deleted. Exact XML preserved as `task5-full-gate-inherited-exception.xml`. Lead authorised narrow RuntimeServiceTest fixture correction: cancel, pump main looper until complete, and join the initial replaced App Graph scope, and the owned replacement scope in @After. No production persistence change, no swallowed exceptions. Focused evidence: `task5-fixture-lifetime.log`.

## Second full-gate diagnosis

`task5-full-gate-fixed.log`: 175 app tests, 169 passed / 1 failed / 5 screenshots skipped, 2 min 13 s. All Task5 UI and fixture corrections passed. Existing GattLinkTest.status133RetriesTheSameAddressThenFailsWithoutTryingAnotherBedJet failed the immediate `fake.ports.all { it.closed }` assertion at line66, after expected NotConnected and exact same-address retries had passed. Exact XML is `task5-full-gate-gatt-failure.xml`. Inspection found Session.shut completes its services Deferred on the FakeRadio binder thread before closePort; the resumed connect caller can observe the Deferred failure before that binder callback finishes closure, while repeated shut sees the already-set closed flag. This is a callback completion race, not evidence of wrong-device retries. Lead approved the existing FakeRadio.drain() callback-completion barrier after the expected exception and exact retries, before closure/final-state assertions. Added scans.isEmpty; preserved asynchronous callbacks and all existing assertions. No sleep/retry loop, inline callback substitution or BLE production change. Focused evidence `task5-gatt-barrier.log`.

## Resource lint correction

`task5-full-gate-final.log` completed all unit tests (142 core passes; 170 app passes / 5 optional screenshot skips) and debug assembly, then failed lint with 46 StringFormatMatches errors: extraction had used `%s` where call sites supplied integers. Exact report retained in `task5-lint-red.txt`. Corrected 37 distinct numbered resource placeholders to `%d`, retaining string placeholders for formatted temperature/time/text. Removed three unused extraction leftovers and made sliderDescription a Modifier extension (same semantics). No lint suppression. Focused lint plus formatting/accessibility/layout evidence: `task5-lint-focused.log` passed in 1 min 7 s, 0 errors / 4 warnings. The two new warnings were true plural candidates for saved step counts and boost minutes; converted both to quantity resources before the final gate. The other two are baseline OldTargetApi/ObsoleteSdkInt. Candidate report preserved in `task5-lint-plural-candidates.txt`.

## Full-gate result

PASS — `task5-full-gate-green.log`, **BUILD SUCCESSFUL in 3 min 8 s; 59 actionable tasks (24 executed, 35 up-to-date)**. Exact command was the four-task offline wrapper command shown above. Final XML totals: **142 core tests, zero failures/errors/skips; 175 app tests, zero failures/errors, 5 optional screenshot skips (170 passes)**. Debug APK assembled. Lint: **0 errors, 2 baseline warnings** (OldTargetApi and ObsoleteSdkInt); one informational Compose autoboxing suggestion. Compiler also reports the existing deprecated ViewList icon/Gradle deprecation notices; output is not claimed pristine. `git diff --check` passed.

Focused fixture lifetime (39 s), GATT barrier (21 s), and resource-format/semantics lint run (1 min 7 s) all passed before this complete gate. No production changes were made to accommodate host compiler/cache failures or test callback races.

## Changed files and commit

`b79085e` — `feat(ui): harden existing screens and night controls` (42 files, 1701 insertions, 373 deletions). Worktree clean immediately after commit. Trailer: `Implemented-By: GPT-6 Astra`.

Parent authorised completing the three independent follow-ups (Demo-to-real atomic selection, consumed navigation intent and config theme timer) in a second checkpoint before alignment; Task6 feature/API calls still wait for its reviewed merge.

- `app/src/main/kotlin/io/github/willzy12h/nocturne/data/Repository.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/CheckInPolicy.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Format.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/HistoryScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MorningCheckIn.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightCurve.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightGuard.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/NightRecoveryControls.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfileEditor.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/ProfilesScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RemoteScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SettingsScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SetupChecks.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/SetupFlow.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Theme.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/SingleActionConfigActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/strings_runtime.xml`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppSmokeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/AppearanceTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ble/GattLinkTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/service/RuntimeServiceTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/AccessibilityTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/CheckInTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ContrastTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/LargeLayoutTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/NavigationAccessibilityTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/OutcomeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/SavedDraftTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/TimelineTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UiHardeningTest.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/BackupValidator.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/History.kt`
- `core/src/main/kotlin/io/github/willzy12h/nocturne/core/store/Settings.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/store/BackupValidatorTest.kt`
- `core/src/test/kotlin/io/github/willzy12h/nocturne/core/store/ScheduledThemeTest.kt`
- `docs/review/register.md`

Approved out-of-primary-scope files: minimal core Settings/History/BackupValidator and tests, Repository check-in consistency, SingleActionConfigActivity theme adapter, NightActions + strings_runtime.xml exact Forget success copy, RuntimeServiceTest graph-lifetime fixture cleanup, and GattLinkTest callback-completion barrier authorised after exact full-gate failure diagnosis.

# Independent follow-up checkpoint (before Task6 alignment)

Parent authorised these existing scope items after checkpoint `b79085e`, without waiting for Task6 review:

- Explicit setup selection now calls `NightActions.selectTarget(..., useRealDevice = true)`. The new default-false option uses the existing owned block: validates a real target, refuses during an active night, disconnects the old link, then durably updates target and demoMode=false in one Repository settings publication. Existing callers, including Forget, keep their previous semantics. Failure cannot publish either half or a success outcome.
- MainActivity handles and consumes `openTonight` through one helper on both initial and new intents. The restored-tab regression now covers History → recreate → tile → Tonight → user History → recreate. Dirty-draft recovery remains covered independently.
- MainActivity and SingleActionConfigActivity share the injected-clock `rememberThemeTime` composition timer. The config activity now switches its actual rendered Surface at the scheduled boundary while held open. This is only its authorised theme adapter; no widget action behaviour changed.

## Follow-up RED/GREEN

Commands use the same bounded offline wrapper. `task5-followup-red.log`: actual config render remained Daylight after moving the clock across 22:00; the first setup fixture accidentally returned Job from @Before (fixed to Unit), and normal-font navigation lookup used a contentDescription that was merged with its visible label (changed to the visible label).

`task5-followup-red-fixed-fixtures.log`: explicit setup selection failed as expected: expected `[(ble:AA:BB:CC:DD:EE:FF, false)]`, actual `[(ble:AA:BB:CC:DD:EE:FF, true)]`. The active-night refusal and failed-save tests already passed and remain unchanged guards. The recreated-navigation assertion alone passed because ActivityScenario relaunches its original Intent; added an explicit assertion that the handled Activity Intent no longer carries the request, alongside the real navigation/recreation path.

`task5-followup-theme-selection-green-navigation-red.log`: atomic explicit selection, active refusal, failed save and actual config theme boundary passed; only the intended unconsumed navigation-extra assertion failed (“Handled tile navigation must not be replayed”). Then implemented consumption. Added default-caller preservation and invalid-real-target coverage. New fixtures cancel/join their owned graph jobs before storage teardown; no swallowed coroutine failures.

Focused final command:

```sh
bash /workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/run-gradle.sh :app:testDebugUnitTest --tests '*SetupSelectionTest' --tests '*ConfigThemeTest' --tests '*UiHardeningTest' --tests '*OutcomeTest' :app:lintDebug --offline
```

Focused PASS: `task5-followup-green.log`, BUILD SUCCESSFUL in 1 min 1 s; lint 0 errors / 2 baseline warnings. Full four-task gate is `task5-followup-full-gate.log`; final result and commit appended below when complete.

## Follow-up full gate and commit

PASS — `task5-followup-full-gate.log`: BUILD SUCCESSFUL in 2 min 27 s; 59 actionable tasks (5 executed, 54 up-to-date). **142 core passes; 177 app passes, 5 optional screenshot skips; debug assembly; lint 0 errors / 2 baseline warnings.** `git diff --check` passed.

Commit `6abebe6` — `fix(ui): finish setup and navigation transitions`. Truthful GPT-6 Astra trailer, configured user author, clean worktree. No push.

Changed files:

- `app/src/main/kotlin/io/github/willzy12h/nocturne/night/NightActions.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/AppViewModel.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/Theme.kt`
- `app/src/main/kotlin/io/github/willzy12h/nocturne/widget/SingleActionConfigActivity.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/ConfigThemeTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/SetupSelectionTest.kt`
- `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/UiHardeningTest.kt`
- `docs/review/register.md`

Ready for parent metadata alignment and reviewed Task6 merge. Remaining dependent implementation is limited to the four real feature composable placements (idle-accessible hand-over overflow) and AppViewModel notifyFailures=false plus visible operation-timeout feedback. No further optional broad gate will run while waiting. No hardware testing.

## Lead metadata alignment

Both clean task commits were preserved on `backup/task5-pre-published-alignment` and mechanically rebased from local base `9bc63a2c324996e3236245be0aa6ff13a25f8dbe` onto its published identical-tree equivalent `d7368a9b915f3fea1d46a293d7b2ee6351f5a57a`. The task commits are now `7117c85` and `0691f86f0603c8e34eecb9a2c38f861112615742`; old HEAD was `6abebe67773becf01e9f62eaa10f4d243c9b836a`. Before/after task tree is exactly `52b16be7b39c39299a791398e2ee8937209fd727`, and the worktree is clean. No source changed; the recorded gate remains applicable. Reviewed/published Task6 still must be merged before final API/UI wiring and Task5's independent review.

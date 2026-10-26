# GPT-5 documentation reconciliation and gate evidence

**Historical record — source `c15c515`, corrected through documentation commit `ab6f1e1`.** The tables and gate below describe the earlier documentation pass. The [25 October reference re-pin](docs2-repin.md) supersedes its current-behaviour statements for runtime, haptics and UI gaps. In particular, START now passes the alarm creator on the haptics reference, Quick Off confirmation is deliberate, terminal re-time failures have History evidence, and the newer haptics policy is implemented. The register itself is left unchanged in the re-pin because the owning branches supply their rows at integration. The old merge previews below are historical results, not previews of the new documentation commit.

## Scope and source

- Branch: `gpt/nocturne-docs2`.
- Exact base: `gpt/nocturne-f1` @ `c15c515a4dabbeae719c12323b6a6c0639c8406e`.
- Nominated candidate checkpoint: `claude/nocturne-final-candidate` @ `262c52fc7a2416f6b5d93a7abafb11079ec29d76`.
- Initial review: 24 October 2026; Claude review corrections: 25 October 2026. Approval/ownership assignments come from the owner's brief. Implementation statements come from the pinned code and reachable git history.
- Only `docs/review/**` and `docs/HANDOVER.md` change. No production code, tests, resources, Gradle or workflow changes are included.

All source lines below refer to the base above. Short paths `ui/`, `night/`, `ble/`, `platform/`, `service/`, `notify/`, `widget/`, `data/` and `App.kt` mean `app/src/main/kotlin/io/github/willzy12h/nocturne/`. Short `core/` paths mean `core/src/main/kotlin/io/github/willzy12h/nocturne/core/`. Full paths beginning `app/` or `.github/` are already repository-relative. Test class names resolve under the corresponding `app/src/test/kotlin/` or `core/src/test/kotlin/` package tree.

**Physical acceptance: NOT RUN.** Source inspection, host tests and Robolectric renders are separate from phone, Samsung, BedJet 3, Lull, Health Connect provider and TalkBack acceptance. No production settings, signing identity or current minified APK was verified in this pass.

## Ownership boundary

GPT-2r retains D2–D6, D8, M1, M2, M3 and M5. GPT-5 does not amend the register's existing runtime statements about Quick/Remote confirmation waits, queue-budget re-arming/refusals, Take back, Resume offer replacement, typed outcome origin, refusal/failure races, background re-time timeout/catch notices or Off end-request ordering. The overlapping R14–R16, R26–R29 and U08 rows remain byte-identical; F02 and F12 only receive presentation placement/status qualification. Their existing runtime assertions and tests remain intact. `nocturne-completion.md`, `task-4-implementation.md` and `f1-implementation.md` are unchanged.

U06 belongs to `gpt/nocturne-u06` @ `66825099193256ad64d08f087cb0b4d08250a4b3`: delivered on its own branch, approved by Claude and pending integration. GPT-5 restores the U06 register row verbatim to `c15c515` and removes it from the changed-row table below. U02–U05 and U07 retain GPT-5's reconciliation.

R33/R36's new source qualification concerns **alarm creator attribution**, not the typed command-outcome origin work owned by GPT-2. No implementation fix or new regression is claimed.

## Register rows changed

The table records the exact `c15c515` and reconciled Status cells after the 25 October corrections. There are 41 changed register rows; U06 is deliberately excluded. Where a status is identical, the change corrects the Item, Commit or Tests cell. All Commit subjects retained in the reviewed rows resolve in the base's reachable history; current named test classes and method references were checked. The obsolete R32 method reference was replaced with its current regression names. The F01 draft-discard test was moved to U04.

| Row | Previous status | Reconciled status | Source / evidence qualification |
|---|---|---|---|
| R09 | auto-verified (core v2 model; hardware pending) | auto-verified (core v2 model; hardware pending) | core/night/ActiveNight.kt:22–61, core/store/History.kt:28–40 |
| R11 | auto-verified; hardware pending | auto-verified; hardware pending | core/night/NightRunner.kt:56–68, core/night/NightChanges.kt:44–52 |
| R13 | in progress: runtime auto-verified; Task 5/6 presentation pending | partial: P5 confidence, manual-end labels and latest-revision curves are implemented; expanded History step text still uses the initial steps | ui/TonightScreen.kt:119–171, ui/HistoryScreen.kt:78–98,176–177, core/night/NightChanges.kt:25–31, widget/WidgetPresentation.kt:14–27 |
| R18 | auto-verified; hardware pending where listed | auto-verified; hardware pending where listed | night/NightActions.kt:90–124, night/LinkSessions.kt:50–61; current owner-token cleanup replaces the old exclusiveUntil implementation |
| R20 | auto-verified; hardware pending where listed | auto-verified for separate demo/real transport, device-bound originals and target guards; profiles/settings/history share one repository; physical acceptance NOT RUN | App.kt:40–58, night/NightActions.kt:997–1022; separate transport does not mean separate copies of all app data |
| R21 | in progress (W1 link side implemented: target provider, `rememberedAddress()`, `discover()`; W4 selection UI open) | implemented: explicit chooser and replacement-target route; a tracked night blocks a target change; physical selection NOT RUN | ui/SetupFlow.kt:26–50, ui/AppViewModel.kt:384–404, night/NightActions.kt:1008–1017 |
| R22 | auto-verified | auto-verified | data/Repository.kt:458–527, core/store/BackupValidator.kt:39–162 |
| R23 | auto-verified | auto-verified for serial normal writes and recovery guards; startup reads/recovery are synchronous; a committed unfinished restore can publish intended values while disk completion waits | data/Repository.kt:127–179,537–549,558–586,797–799; the off-main-thread assertion covers normal writer paths, not all startup I/O |
| R24 | auto-verified; diagnostics export (Settings > Diagnostics, NightActions.exportDiagnostics) carries no update token | auto-verified; diagnostics export (Settings > Diagnostics, NightActions.exportDiagnostics) carries no update token | data/Repository.kt:203–207,430–436, night/NightActions.kt:1091–1107 |
| R25 | auto-verified | auto-verified | core/store/History.kt:43–48, core/store/SnapshotStore.kt:45–66,92–94,172 |
| R32 | auto-verified; hardware pending where listed | auto-verified; hardware pending where listed | service/BedJetService.kt:30–46,93–104,142–153 |
| R33 | auto-verified (shared core policy and AlarmSource attribution) | partial: shared core source/window policy is implemented, but START omits the known alarm creator; when unknown sources are disabled it can fall back while preview/re-time accept that alarm | night/AlarmSource.kt:14–21, night/NightActions.kt:168–175,536–542, core/night/NightRunner.kt:39–45, core/plan/NightPreview.kt:17–33; source-traced app-path gap, no dedicated regression at this base |
| R34 | auto-verified (1–16 h start guard, 32-step guard) | auto-verified (1–16 h start guard, 32-step guard) | core/plan/NightPreview.kt:35–42, core/plan/PlanCompiler.kt:57–67; the old screenshot is not a current unbounded-night claim |
| R36 | in progress: shared preview/Start core auto-verified; Task 5 UI wiring pending | partial: shared start-now resolver and Tonight headline/curve wiring are implemented; the app START route omits alarm creator, so preview/Start equivalence is incomplete when unknown alarm sources are disabled | ui/TonightScreen.kt:173–199, ui/AppViewModel.kt:201–205, core/night/NightRunner.kt:37–53; R33 source qualification also applies: ui/AppViewModel.kt:202–205 passes creator, night/NightActions.kt:174–175 does not |
| R38 | in progress: runtime auto-verified; Task 5/6 presentation pending | partial: runtime replacement policy, widget/shortcut confirmation and active-night slot-restore refusal are implemented; Quick Off still asks during a tracked night, so uniform Off presentation needs Claude's decision | ui/NightGuard.kt:21–39, ui/TonightScreen.kt:325–329, platform/PlatformActionActivity.kt:30–58, ui/ShortcutActivity.kt:26–42, night/RuntimeState.kt:59–68 |
| R39 | open | implemented: explicit release signing with no debug fallback, validated test/production identities and environment-gated production job (protection rules set by the owner; not verified here); live owner settings UNVERIFIED; physical install/upgrade NOT RUN | app/build.gradle.kts:57–100, .github/workflows/build.yml:251–405; no fresh release build in this docs-only gate |
| R40 | open | implemented: R8/resource shrinking, post-R8 JVM serialisation execution and actual APK structure checks; installed minified APK, widgets and restore NOT RUN | app/build.gradle.kts:94–100, .github/workflows/release_serialization.py:438–453; separate JVM proof does not execute the APK |
| U02 | in progress: temporary choice migration/resolution auto-verified; Follow schedule UI pending | implemented: expiring Use tonight choice and Follow schedule control; core policy tested, no dedicated Follow schedule UI assertion at this base | core/store/Settings.kt:80–94, ui/ProfilesScreen.kt:88, ui/TonightScreen.kt:173–187,215–216 |
| U03 | auto-verified; device acceptance pending | auto-verified; device acceptance pending | widget/Widgets.kt:51–62, widget/WidgetPresentation.kt:8–12, night/NightActions.kt:168–175; dynamic choice is not frozen at widget configuration |
| U04 | auto-verified for new and existing profiles (Robolectric recreation) | implemented: saved tab/editor/draft state; new-profile recreation auto-verified; existing-profile and navigation recreation are source-supported only | ui/MainActivity.kt:125–158, ui/ProfileEditor.kt:68–71; the dedicated recreation assertion is UiFinalisationTest.kt:49–67; WakeFeedbackTest.discardingAnUnsavedNewProfileAddsNothing (relocated from F01; draft discard, not an additional recreation test) |
| U05 | auto-verified (Robolectric semantics only; TalkBack NOT RUN): slider label/unit/state on editor, Settings and Remote; switch rows row-toggleable 48 dp; curve text alternative existing | implemented semantics; Robolectric directly covers editor/Settings sliders and feature switches; Remote slider/curve alternatives source-inspected; TalkBack NOT RUN | ui/Accessibility.kt:16–31, ui/RemoteScreen.kt:78–91, ui/NightCurve.kt:58–65; direct assertions: AccessibilitySemanticsTest.kt:57–92 |
| U07 | auto-verified (Robolectric): stateIn in viewModelScope; running label and queued count from NightActions.busy on Tonight and Remote | auto-verified (Robolectric): stateIn in viewModelScope; running label and queued count from NightActions.busy on Tonight and Remote | ui/AppViewModel.kt:99–104, ui/TonightScreen.kt:230–239; collector lifetime is source-inspected, not established by a leak test |
| F01 | runtime auto-verified; Tonight labels the override as "Tonight-only wake time" in Midnight Ink, Daylight and both Simple looks (auto-verified) | runtime auto-verified; Tonight labels the override as "Tonight-only wake time" in Midnight Ink, Daylight and both Simple looks (auto-verified) | Unrelated draft-discard test moved to U04; F01 status and runtime evidence retained. |
| F02 | in progress: runtime auto-verified; Task 5/6 presentation pending | partial: Resume presentation is implemented for idle, kept-manual and replacement cases; GPT-2 retains the outstanding runtime review | ui/NightRecoveryControls.kt:37–64; existing runtime claims/tests are retained for GPT-2 |
| F03 | auto-verified; Task 5 placement and device acceptance pending | implemented: Settings placement and reminder preview route are present; device delivery/permission acceptance NOT RUN | ui/SettingsScreen.kt:178, ui/features/FeatureSections.kt:53–79, platform/BedtimeReminders.kt:36–52 |
| F04 | auto-verified; Task 5 placement and supervised acceptance pending | implemented: Settings opt-in placement and periodic best-effort auto-start; not immediate on plug-in; supervised acceptance NOT RUN | ui/features/FeatureSections.kt:81–83, platform/ChargerJobs.kt:55–60; known-limitations.md |
| F05 | open | implemented: local-time scheduled resolver, app, widgets and widget configuration use it; equal boundaries mean always dark; process-not-running refresh lag remains | core/store/Settings.kt:62–74, ui/MainActivity.kt:70–82, widget/Widgets.kt:69–72, widget/WidgetUpdater.kt:47–59, widget/SingleActionConfigActivity.kt:45–49, App.kt:42–48; no dedicated DST transition test; physical widget refresh NOT RUN |
| F06 | auto-verified; Task 5 Tonight navigation integration pending | implemented: tile opens Tonight and reads target-bound status; navigation integration is present; phone tile acceptance NOT RUN | platform/NightTileService.kt:18–52, ui/MainActivity.kt:58–62,86–89,144–149; app/src/test/kotlin/io/github/willzy12h/nocturne/platform/PlatformDeliveryTest.kt:48–53 checks openTonight=true and the MainActivity component; physical tile interaction NOT RUN |
| F08 | open | implemented: permission/re-entry checks, explicit chooser, separate demo, Samsung guidance and supervised fan-check route; physical setup NOT RUN | ui/SetupChecks.kt:46–52,73–97, ui/SetupFlow.kt:26–62, ui/SettingsScreen.kt:173–176 |
| F09 | auto-verified (Robolectric): comfort linked to the night; ±1 °C for heating Middles, ±10% fan for Cool/Dry, within limits; applied only on acceptance | auto-verified (Robolectric): comfort linked to the night; ±1 °C for heating Middles, ±10 percentage points of fan for Cool/Dry, within limits; applied only on acceptance | data/Repository.kt:323–349, ui/HistoryScreen.kt:130–158; 50% to 60% is a ten-percentage-point increase |
| F10 | auto-verified; Task 5 placement and provider acceptance pending | implemented: Settings permission toggle and History sleep overlay are placed; provider/device acceptance NOT RUN; historical-read limit documented | ui/features/FeatureSections.kt:63–65,84–89, ui/HistoryScreen.kt:67, ui/features/HistoryFeatures.kt:60–104; known-limitations.md |
| F11 | auto-verified; Task 5 placement and SAF device acceptance pending | implemented: History export, explicit identifier choice and file-picker route are placed; device file-provider acceptance NOT RUN | ui/HistoryScreen.kt:50, ui/features/HistoryFeatures.kt:30–57 |
| F12 | auto-verified; Task 5 idle overflow placement and two-phone acceptance pending | partial: idle More-menu placement is implemented; GPT-2r retains runtime follow-ups; two-phone acceptance NOT RUN | ui/NightRecoveryControls.kt:45–72, ui/features/FeatureSections.kt:100–134; runtime disposition retained for GPT-2r |
| F13 | open | partial — GPT-6b pending after GPT-2: accepted tick/confirmed pattern run on outcome completion, not tap acceptance; outcomes from other origins can vibrate | Source inspection only: night/NightActions.kt:90–117 emits the completed result; ui/AppViewModel.kt:113–127 forwards every origin; ui/MainActivity.kt:115–124 performs feedback; no dedicated haptic timing/origin tests at this base; physical haptics NOT RUN |
| F15 | in progress: runtime auto-verified; Task 5/6 presentation pending | implemented: explicit non-working slot, expiring/stale-safe preview, saved original and readback/restore guards are wired; physical readback NOT RUN | ui/ProfilesScreen.kt:92–98,140–191, ui/SettingsScreen.kt:116–121,265–284, night/NightActions.kt:850–934 |
| F16 | in progress: runtime auto-verified; Settings quiet-status toggle and diagnostics export placed (P5) | implemented (presentation): confidence/recovery banners, local Stop tracking wording, quiet-status toggle and truthful diagnostics export; runtime follow-ups remain scoped to GPT-2; physical acceptance NOT RUN | ui/TonightScreen.kt:96–105,124–141, ui/NightRecoveryControls.kt:74–80, ui/SettingsScreen.kt:205–209, ui/AppViewModel.kt:348–357 |
| V01 | **PASS — merged integration gate:** `:app:clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; core **20 suites/156 discovered/156 passed**, app **59 suites/472 discovered/466 passed/six existing ScreenshotCapture skips**, **0 failures/errors**. Debug assembly passed; lint **0 Error, 0 Fatal, 23 Warning, 1 Hint**. **7 min 21 s**, 60 tasks (56 executed/4 up-to-date), **197 input hashes unchanged**. `ManualWakeTitleTest` passed **4/4** unchanged from its genuine two-failure RED. Prior compilation failures before app tests are not product RED. Current raw evidence is in `followups-evidence/integration/`, detailed in completion review. Publication CI remains pending; screenshots were not rerun locally and V03 identifies the exact-commit CI evidence route. | **PASS — GPT-5 gate at c15c515:** `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; core **20 suites/156 discovered/156 passed**, app **60 suites/504 discovered/504 passed**, **0 failures/errors/skips**. Debug assembly passed; lint **0 Error, 0 Fatal, 14 Warning, 1 Hint**. **12 min 26 s**, **59 tasks executed**, **201 tracked non-documentation input hashes unchanged**. All six opt-in ScreenshotCapture cases ran. The two infrastructure failures before test execution and the exact command are recorded in docs2-reconciliation.md; this is one completed debug gate, not a new release or physical gate. | Fresh JUnit XML and Gradle/lint outputs, detailed under Required gate; source remains the pinned base. |
| V02 | **PASS in the same merged integration gate**, from the named records in its `required-suites.json`: `MigrationsTest` **6/6**, `BackupTest` **8/8**, `SnapshotStoreTest` **6/6**, `RepositoryTest` **67/67**, `ImportedOriginalRestoreTest` **10/10**; zero skips/failures/errors in these suites. Coverage includes v1 migration, snapshots/import, merged P5 check-in persistence and original provenance/range/ignored-byte controls. These suites are already included in V01; the earlier Repository count of 65 was pre-integration. | **PASS in the same GPT-5 gate:** current JUnit XML records `MigrationsTest` **6/6**, `BackupTest` **8/8**, `SnapshotStoreTest` **6/6**, `RepositoryTest` **67/67**, `ImportedOriginalRestoreTest` **10/10**; zero skips/failures/errors in these suites. Coverage includes v1 migration, snapshots/import, check-in persistence and original provenance/range/ignored-byte controls. These **97 tests** are already included in V01, not an additional run. See docs2-reconciliation.md. | Fresh JUnit XML: 97/97 across the five named suites; core/store/Migrations.kt:24–68, data/Repository.kt:294–319,408–424,478–483; these tests are included in V01. |
| V03 | Historical baseline retained. Current candidate screenshots were **not rerun locally**. `.github/workflows/build.yml` runs `SCREENSHOT_TAG=after` and publishes after images plus the comparison report; follow [PR #2](https://github.com/Willzy12h/bedjet-android/pull/2) for the containing commit's run after publication. The CI result is pending at this pre-publication checkpoint, which cannot contain future run metadata. Physical phone captures remain **NOT RUN**. | **partial: host captures executed; no before/after comparison**. `SCREENSHOT_TAG=docs2` enabled all **six ScreenshotCapture cases**, producing **38 PNGs** under `app/build/screenshots/docs2/` in the same gate. Source: app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt:35–54,82–109. Dark, Light, Simple and combined 320 dp/200% fixtures ran; existing historical before/after evidence is retained. This is not a new visual approval or a new before/after comparison. Physical phone and TalkBack captures remain **NOT RUN**. | app/src/test/kotlin/io/github/willzy12h/nocturne/ScreenshotCapture.kt:35–54,82–109. Dark, Light, Simple and combined 320 dp/200% fixtures ran; existing historical before/after evidence is retained. This is not a new visual approval or a new before/after comparison. Physical phone and TalkBack captures remain **NOT RUN**. |
| V04 | open | implemented: minified TEST-ONLY signing path and historical signed APK evidence; commits `57bccb4`, `b0e4e1b`, `8f4195d`; source: app/build.gradle.kts:94–100 and .github/workflows/release_signing.py:88–143; see release-candidate-report.md and release-instructions.md. No new minified build at this base; physical install/upgrade NOT RUN. | app/build.gradle.kts:94–100 and .github/workflows/release_signing.py:88–143; see release-candidate-report.md and release-instructions.md. No new minified build at this base; physical install/upgrade NOT RUN. |
| V06 | open | Claude final engineering pass — pending | Owner brief: Claude final pass remains pending. |

## Retained rows: source and evidence checks

The unchanged status cells retain their original, limited host-test scope. This table identifies the source families inspected; it does not broaden their physical or protected-runtime claims.

| Rows | Source checked / conclusion |
|---|---|
| R01–R08 | `ble/GattLink.kt:49–103,129–165,235–275,337–439,451–558`; `ble/AndroidBedJetLink.kt:51–57,129–151`; `night/LinkSessions.kt:51–89,157–190`. Session ownership, callbacks, bounded target-bound retry, start boundary and connection holds are present. Named GATT/adapter/holder regressions exist. R06's obsolete method is explicitly labelled a replaced predecessor, not current coverage. |
| R10/R12 | `core/night/ActiveNight.kt:22–61`; `core/night/NightRunner.kt:56–105`. Snapshot-based re-time and explicit reapply preserve the stated boundary and wake/source rules. Existing core test names resolve. |
| R14–R17, R26–R29 | Runtime-overlap rows retained for GPT-2. This pass does not certify completion of their new follow-ups. R17's local Stop tracking dispatch is present at `night/NightActions.kt:52–69,421–450`. |
| R19 | `night/NightActions.kt:132–144,208–220,288–292,880–915`; `data/Repository.kt:352–389,408–424,478–483,741–745`. Saved originals, empty markers, integrity barrier and imported-original mode guard exist. User-facing consequences are in [recovery procedures](recovery-procedures.md); legacy provenance remains a limitation. |
| R30–R31 | `night/NightActions.kt:394–407,452–459,694–778,941–949`; `night/NightAlarms.kt:21–49`; `night/Receivers.kt:13–50`; `App.kt:43–48`. Existing scheduling/local-delivery regressions remain distinct from GPT-2's queue-budget work. |
| R35 | `core/plan/PlanCompiler.kt:103–144`. Mode limits, fixed Turbo temperature and quantised fan limits exist; named planning/ownership tests remain the evidence. |
| R37 | `ui/ShortcutActivity.kt:26–42`; `platform/PlatformActionActivity.kt:30–58`. External/launcher confirmation paths are present; physical launch/delivery remains NOT RUN. |
| U01 | `ui/MainActivity.kt:139–143,163,187–195`; `UiFinalisationTest.offRemainsAboveTheVisibleSnackbar`. This establishes the tested Robolectric case, not every layout. |
| U08 | Typed-origin/runtime overlap retained for GPT-2. |
| U09/U10 | `ui/HistoryScreen.kt:78–98,176–192`; `ui/TonightScreen.kt:135–146`; `ui/AppViewModel.kt:299–304`. History curve/revision and next-night/requested-wake statements are supported. U09 does not claim that expanded initial-step text became the latest revision; R13 records that gap. |
| U11/F14 | `ui/Accessibility.kt:21–31`; `ui/Format.kt:64–93`; `ui/NightCurve.kt:58–65,136–160`; `ui/AppViewModel.kt:449–453`. Resources and the documented Context-free/core English exceptions remain. Old literal-audit counts are historical, not a new scan result. No translation or TalkBack pass is claimed. |
| F07 | `widget/Widgets.kt:65–67,114–117,157–166`; `widget/WidgetPresentation.kt:14–36`; `widget/WidgetUpdater.kt:47–59`. Mode is last known, with age at refresh and a timestamp; exact device refresh timing remains unmeasured. |
| V05 | [Samsung + BedJet 3 acceptance](samsung-bedjet-acceptance.md) contains the ordered plan and preserves every physical result as NOT RUN. |

## Important source qualifications

1. **History detail versus curve (R13).** `ui/HistoryScreen.kt:90–98` iterates `night.steps`, while `:176–177` selects the last applied revision for the curve. `core/night/NightChanges.kt:25–31` deliberately retains the first steps for old readers. The revision-list/curve tests do not establish that all expanded step text shows the last applied state.
2. **Alarm preview versus START (R33/R36).** `ui/AppViewModel.kt:202–205` passes the creator to the preview. `night/AlarmSource.kt:21` returns only the accepted time; `night/NightActions.kt:174–175` passes that time without the creator. `core/night/NightRunner.kt:39–45` defaults creator to null, and `core/plan/NightPreview.kt:18–21,32–33` rejects it when unknown sources are disabled. This source trace explains a possible preview/execution difference for a known allowed alarm. A dedicated app regression and any correction belong to the implementation owner.
3. **Quick Off presentation (R38).** `ui/TonightScreen.kt:325–329` routes Quick Off through `NightGuard`; `ui/NightGuard.kt:27–39` asks while a night is tracked. The runtime's `night/RuntimeState.kt:59–68` policy exempts Off from replacement confirmation. Claude must settle the intended presentation; this document changes no Off contract or ordering.
4. **Stored reason versus visible reason (KL-13).** `night/NightActions.kt:576–579,607–641` can save a consumed re-time failure before posting. `ui/TonightScreen.kt:124–133` renders its reason only for unconfirmed/manual nights. Some failures produce a History ERROR; early refusal paths do not universally do so. Retaining the saved reason is not a universal guarantee that it remains visible after a lost notice. The exact limit is recorded in [known limitations](known-limitations.md#kl-13-what-is-now-retained-and-what-still-needs-checking).
5. **Test coverage versus implementation.** Existing-profile/navigation restoration, Remote semantics, collector lifetime and scheduled DST transition coverage are qualified explicitly. The tests named in the register are supporting evidence for their actual assertions, not a blanket acceptance result.

## Implementation history

These are existing implementation commits reachable from the pinned base, not commits created by GPT-5.

| Commit | Relevant work |
|---|---|
| `7718d3c` | BLE/session ownership. |
| `6bc0541` | Task 2 storage; replaces the vague storage-commit placeholder. |
| `459278d` | Core snapshot/transaction and wake-policy implementation. |
| `7f5203b`, `19ebb6b` | Runtime ownership and subsequent cleanup/recovery correction. |
| `f71cde5` | Platform feature integration. |
| `57bccb4` | Hardening UI wiring, scheduled theme, haptics, pin/restore previews and release setup. |
| `4d2d888`, `6c02def` | Snackbar clearance and new-profile draft recreation. |
| `b1d4d70`, `4626278`, `3c3a64e` | P5 recovery banners, quiet-status/diagnostics controls and running/queued/outcome presentation. |
| `df5a5e0`, `f64b065`, `3fbbbb5` | P5 wake/edit feedback, manual-run wording and explicit setup/demo routes. |
| `9ba7350`, `094dbb3`, `25e843a`, `26c8509`, `4d9f58a` | P5/P5b semantics, History revisions, accepted fan suggestions, Stop tracking wording and final presentation corrections. |
| `627391a` | Ordinary service entry and current permission regression named under R32. |
| `b0e4e1b`, `8f4195d` | Release signing/provenance gates and post-R8 JVM execution evidence. |
| `27d667a`, `9655f42`, `262c52f` | P6 resource migration retained without code/resource edits. |

## Claude review follow-ups — 25 October 2026

These documentation corrections are a fast-forward follow-up to `ef70dcd924b6178d27a7c405d564ac120eb3906b`. The source pin remains `c15c515a4dabbeae719c12323b6a6c0639c8406e`; GPT-2r behaviour and haptics await Claude's later re-pin assignment.

U06 is restored to the exact base row, and U02–U05 and U07 retain their reviewed contents. V03 now says **partial: host captures executed; no before/after comparison**. The four operational documents carry dated scope notes. Restore instructions state the charger automation reset; the sleep-permission citation, production-environment wording, tag-name rule, fatal manifest audit, F12 ownership, tile-intent evidence and recovery citations are corrected. Acceptance step 14 covers all 42 detailed cases, and the widget-theme case is **W01**. Prose uses “dialogue”; every physical result remains **NOT RUN**.

### Merge compatibility: one known documentation conflict

The default merge preview was checked against these pinned heads. This inspects Git trees without merging a branch.

| Comparison branch | Head | Result |
|---|---|---|
| `gpt/nocturne-u06` | `66825099193256ad64d08f087cb0b4d08250a4b3` | **Exit 1:** one content conflict in `docs/review/register.md`, covering adjacent U02–U07 edits. |
| `gpt/nocturne-runtime2-candidate` | `625498978ca44059d612ff2baf27a0edcbe05413` | **Exit 0:** no conflicts. |

**M1's requested clean default merge is not established.** The common ancestor with U06 is `262c52fc7a2416f6b5d93a7abafb11079ec29d76`. Restoring U06 alone does not leave a shared unchanged line inside the U02–U07 block: GPT-5 changes its neighbours and the U06 branch changes U06. A second read-only review reproduced the conflict and found no minimal formatting adjustment that kept the requested row contents and removed it.

Claude's integration resolution is to retain GPT-5's **U02–U05 and U07**, take **U06 from the approved U06 branch**, and retain all other automatically merged regions. GPT-5 has not performed that merge or changed the owning branch. The final handover records the results of running the requested commands against the published correction tip:

```bash
git merge-tree --write-tree origin/gpt/nocturne-docs2 origin/gpt/nocturne-u06
git merge-tree --write-tree origin/gpt/nocturne-docs2 origin/gpt/nocturne-runtime2-candidate
```

The fresh checks for this correction are documentation consistency, source references, permitted-file scope, whitespace and merge previews. The project gate below is retained as dated historical evidence; this correction claims no new gate, CI, minified or physical verification.

## Required gate

These are the recorded results from 24 October 2026, before this documentation fix round. No gate was rerun on 25 October; this correction does not verify CI or a minified build. V03 remains **partial: host captures executed; no before/after comparison**.

**PASS — one completed gate at the pinned base, with no code or test changes.** The requested tasks were run together with the existing screenshot opt-in and host execution flags:

```bash
SCREENSHOT_TAG=docs2 ./gradlew \
  :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  --offline --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

| Check | Fresh result |
|---|---|
| Core | 20 suites; **156 discovered, 156 passed**; 0 failures, errors or skips. |
| App | 60 suites; **504 discovered, 504 passed**; 0 failures, errors or skips. |
| Combined | **80 suites; 660 tests passed**. |
| Recovery subset, already included above | MigrationsTest 6/6; BackupTest 8/8; SnapshotStoreTest 6/6; RepositoryTest 67/67; ImportedOriginalRestoreTest 10/10 — **97/97**, no skips/failures/errors. |
| Existing opt-in renders, already included in app count | ScreenshotCapture **6/6**; **38 PNGs** in `app/build/screenshots/docs2/`. Host fixtures cover Dark, Light, Simple and combined 320 dp/200% text. No fresh visual approval or before/after comparison is claimed. |
| Debug assembly | **PASS**. |
| Lint | **0 Fatal, 0 Error, 14 Warning, 1 Hint**. |
| Execution | **12 min 26 s**; **59 actionable tasks, all 59 executed**; exit 0. |
| Source integrity | All **201 tracked non-documentation input hashes unchanged** before and after the gate. |

Counts were read from `core/build/test-results/test/TEST-*.xml`, `app/build/test-results/testDebugUnitTest/TEST-*.xml` and `app/build/reports/lint-results-debug.xml`; the task result and duration came from the completed Gradle log. They match the F1 base's recorded full-gate totals. `ScreenshotCapture.kt:35–54,82–109` defines the opt-in and fixtures; it does not turn these host renders into physical acceptance.

**Infrastructure retries before any tests:** the first invocation could not determine a usable wildcard IP inside the sandbox. The next invocation reached Gradle but failed plugin resolution because the shared read-only dependency cache was pointed one directory too deep. Both stopped before test execution. After correcting the host execution context and cache path, the command above completed once; no test suite was rerun after a product failure. No repository configuration, source or test was changed to make the gate run.

No fresh minified release build, release helper suite, installed APK, phone, Samsung, BedJet 3 or TalkBack check was run. Historical release evidence remains separately identified in [release instructions](release-instructions.md).

## Documentation validation — original pass, 24 October 2026

- The final diff is limited to seven permitted Markdown files: three changed and four new. All 233 tracked files outside the documentation allowlist match the base byte for byte, including the 201 non-documentation inputs hashed around the gate and the existing screenshot/plan files.
- R14–R16, R26–R29 and U08 are byte-identical. F02/F12 retain their original runtime assertions and named tests; only their presentation disposition and evidence were appended. The completion record, frozen runtime contract and F1 implementation record are unchanged.
- All 45 original physical-result rows remain present verbatim. Every physical-result cell remains **NOT RUN**; the ordered plan and additional cases introduce no physical pass.
- Each changed register row has an exact old/new Status entry in this document. Local document links and explicit source file/line references were checked for valid paths and ranges. `git diff --check` passed.
- An independent read-only documentation review confirmed the source qualifications, ownership boundary and evidence limits after its five documentation corrections. It reported no remaining findings. This is a scoped documentation review, not Claude's final engineering pass.

## Statements not established by code or this pass

- Actual BedJet timing, Samsung scheduling/delivery, real phone layout, TalkBack, real haptic feel, physical installation and same-signer upgrade: **NOT RUN**.
- Live GitHub production environment, required reviewer, secret scope, protected tag rules and approved production certificate: **UNVERIFIED**; the code defines expected configuration, not its live presence.
- A freshly built or installed minified/production APK at this base: **NOT RUN**. Historical release records retain their original provenance; the required gate is a debug build/test/lint gate.
- The Health Connect 30-day boundary and iOS background advertising restriction need official platform documentation in addition to app source. The [limitations document](known-limitations.md) cites that guidance; provider/interoperability acceptance remains NOT RUN.
- R33/R36's app-path discrepancy and the KL-13 visibility gap are source findings, not newly executed failure reproductions. No new tests are allowed in this workstream.
- F1 approval, candidate/workstream ownership and review order are owner-supplied state. A git commit cannot prove that approval, and this pass does not independently declare Claude's final review complete.

## Claude's decisions and next integration

Decide the treatment of R13's expanded History steps, the R33/R36 alarm-creator discrepancy, R38's Off confirmation and KL-13's saved-reason visibility. Leave GPT-2r's reserved runtime items to its owner. U06 is approved on its own branch and pending integration; review GPT-4 and GPT-6b (after GPT-2r), then integrate through GPT-3 last. Owner release configuration and physical acceptance still need their own evidence. GPT-5 does not merge or open a PR.

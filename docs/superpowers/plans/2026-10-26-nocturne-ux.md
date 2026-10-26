# Nocturne UX implementation plan

> **For agentic workers:** Use superpowers:executing-plans with isolated parallel worktrees. Each behavioural change follows observed RED, minimal implementation, GREEN and source review.

**Goal:** Implement the owner's U1–U8/P1–P10 changes, code cross-check, defect review loop and test APK.

**Architecture:** Retain the existing command owner, durable journal and raw receipt boundary. Keep presentation state in existing settings/Glance state; route widget and test commands through the service owner. Add focused helpers only where they separate a new policy from UI or transport.

**Tech Stack:** Kotlin, Compose, Glance, coroutines, Gradle8.14.3; coreJUnit5, appRobolectricJUnit4 SDK34.

**Spec:** [Design and authority](../specs/2026-10-26-nocturne-ux-design.md), plus the full owner GPT-11 request.

## Global constraints

Base367968bddc390e5b2bb12876fc1662ffa07e47c3; branchgpt/nocturne-ux. Preserve90/100/115second bounds, oneowner, receipt truthfulness, APP haptics and R38. All app strings resources. Never weaken/skip tests;38capture skips expected. No production signing or release/tag. No destructive Git operations. Fullgate uses requestedoffline/maxworkers2/2GiB/in-process flags plusassembleRelease.

## Review focus

- Alarm five minutes ahead, null creator and cached recheck: reason must be precise, unknown explicit and every recheck fresh. TestAlarmDetectionTest owns this.
- A target/night changes while a widget confirmation is open: captured identity must be refused; no false success or MainActivity launch. WidgetInPlaceActionTest owns this.
- UI hidden or screen off while animation/status flows run: no animation loop remains; raw receipt ownership stays live. ActiveIndicatorTest and ForegroundStatusTest own this.
-200%font/320dp and saved malformed section order: text and actions stay reachable; normalise duplicate/missing order entries. TonightLayoutTest and editor/layout tests own this.
- Bluetooth changes between tap, owner admission and reconnect: refusal is fast and truthful; no command replay; background notices are window-deduplicated. BluetoothOffTest owns this.

## Block1 — implementation with focused test cycles

### A. UI and themes (isolated UI worker)
Files: ui/TonightScreen.kt, ProfileEditor.kt, NightCurve.kt, Theme.kt, shared UI progress/Quick components, SettingsScreen.kt; core/store/Settings.kt and validation only for layout preference. Create focused UI helper/resources/tests as needed.
Interfaces: existing AppViewModel settings/actions, NightGuard, plan/profile/Mode APIs. Keep AlarmExplanationLine callbacks. Max labels useMode.temperatureRange; root ownsP10model correction.
- [x] Add and run RED tests for persisted collapse/order, real drag and move actions, fixed wake scroll position, editor compact/phase gestures, stable progress, removed bubbles, Release destination, mode maxima, light confirmation and lifecycle animation.
- [x] Implement U1–U8/P8/P9 with minimal focused UI components. Retain existing look identities and contrast controls; no infinite animation while hidden/off.
- [x] Run GREEN focused tests plus affected U06/contrast/large-text controls; document exact executed counts and source references; commit isolated patch.

### B. Alarm/re-time/test run (isolated runtime worker)
Files: night/AlarmSource.kt, AlarmExplanation.kt, NightActions.kt; core/plan/WakeResolver.kt/NightPreview.kt and explicit test plan helper; core/night/ActiveNight.kt only if durable marker needed; SettingsScreen.kt/AppViewModel methods, health export boundary, dedicated resources/tests.
Interfaces: existing ownedstart/re-time/fresh-report paths. Settings control previews and explicitly confirms a marked short run; never auto-runs on save. Root owns Bluetooth preflight; coordinate shared service/notification hunks.
- [x] Diagnose current source/window/recheck path and record real code cause.
- [x] Add/run RED for five-minute reason,18h upper bound, null/Samsung/Google creator, outside window, fresh recheck and all explanations; marked test preview/run/confirmation/Health exclusion; real/false terminal re-time warning.
- [x] Implement P6/P7 and authorised Settings test through existing owner; precise Check BedJet action reconnects/reads only.
- [x] Run GREEN plus existing runtime/receipt/override controls; record sources/tests; commit isolated patch.

### C. Widgets (isolated widget worker)
Files: widget/Widgets.kt, WidgetPresentation.kt, WidgetUpdater.kt, provider XML/preview layouts, new confirmation activity/theme/manifest and focused tests. Shared service/notifications coordinate with root/runtime.
Interfaces: BedJetService.runOwned and CommandOrigin.WIDGET, existing capture/strict-identity guards. Glance preferences ownpage and result state; page changes never send hardware commands.
- [x] Add/run RED for each supported size/page control budget and actual minimum sizes/text; in-place callback noMainActivity; pending/confirmed/failed/unknown transitions; confirmation onlywhenrequired and captured-identity refusal.
- [x] Implement P4 responsive layout/pages/actions/provider and transient confirmation activity, event-driven updates. Retain Start/Boost/Turbo main actions.
- [x] Run GREEN plus existing widget policy/freshness/startup controls; record evidence; commit isolated patch.

### D. Control semantics, Bluetooth and protocol (root)
Files: ui/RemoteScreen.kt and Boost helper/resources, recovery labels/guards, service/BedJetService.kt, night/NightActions.kt preflight, UI Bluetooth banner/lifecycle/permission entry, notify/Notifications.kt, core/protocol/Model.kt and related limit checks.
- [x] Trace every Boost command; add/run RED for all label/mode/description mappings; implement P1.
- [x] Trace all recovery actions/strings; add/run RED for Resume and Stop effects; preserve R38 and working notification; implement P2.
- [x] Add/run RED for Bluetooth-off fast refusal, banner appears/clears, enable intent through permission, dedup and no replay; implement P3 via existing lifecycle/owner routes.
- [x] Research P10 primary protocol/manufacturer sources and trace device-reported/UI/plan limits; add/run RED for confirmed mismatch and implement consistent range/cap. Report any hardware-only uncertainty.
- [x] Verify P5 and section3 evidence without redoing working behavior. Run focused GREEN.

### E. Integrate and gate
- [x] Review every isolated patch, integrate shared files deliberately and preserve all tests.
- [ ] Run exact fullgate plusassembleRelease. Compare lint againstStepAbase by identity; fix any regression test-first and rerun fullgate.
- [x] Record/push the first integrated UX checkpoint through exactindex-tree publication (`0eddd18`, later `b1c3a81`; full gate still under repair).

## Block2 — phone-test cross-check and whole-diff self-review
- [x] Trace each section4 ID to actual files/tests; inventory every alarm/WorkManager schedule and cadence. Add missing defect regressions before repairs. Mark device-only acceptance honestly. Add the later Temporary feature from fixes2 `655993d`; final source-line refresh remains.
- [ ] Review entirebase diff and everytouchedfile for owner/coroutine/race/lifecycle/state/timeout/origin/haptics/resources/a11y/contrast/lint/battery. Logeachdefect→fixcommit→test; suggestionsremainunimplemented.
- [ ] Runfullgate+release afterrepairs; reviewagain. Repeatuntilnofindings andgreen/no-newlint.
- [ ] Publish finalsource checkpoint and verifyremoteSHA.

## Block3 — authorised merge and APK/handover
- [ ] InspectlivePRbase/head/workfloweffects; createappropriatePRux→main; preservehistorywithmergecommit usingexpectedhead afterallchecks. No release/tag.
- [ ] Buildfinaltest-signedAPKfromfinalSHAwithsurvivingexistingtestcertificate; verifyrevision, APK SHA256andcertificateSHA256. Neveruseproductionkey.
- [ ] SaveAPK, completeowner0–12handover including StepA, allrequirements/test/file:line tables, diagnosis, cadence, selfreview, limitsandprioritisedphonechecks.

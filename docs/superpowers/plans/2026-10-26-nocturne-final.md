# Nocturne GPT-12 implementation plan

**Goal:** Complete the authorised acceptance fixes, R5 and optional R4, then provide a reviewed test build and an open PR for the user to merge.

**Architecture:** Preserve the existing Kotlin/Compose application, command owner and repository. Isolated worktrees separate acceptance, import integrity and platform integration work. All changes have recorded failing behavioural tests before implementation, followed by passing tests. Root integrates normal commits on `gpt/nocturne-final`.

**Spec:** `docs/review/gpt12-request.md` is the full user-approved request. It overrides historical handover merge instructions and any generic skill workflow.

## Global constraints

- Base `e61b8b4ffec1653b9136b0ef184dd2bdf558a08c`, tree `562b8809ea848c47c8fa05b3f9376a211ab38990`.
- Push only `gpt/nocturne-final`; open a PR to `main` and leave it open. No merge, force push, rebase, reset, clean, branch/worktree deletion, release or tag.
- Test signing certificate must equal `42efc6e2284a00ae55345479177e27c45242196c02acb0f51640b886f7c2bde9`, versionCode greater than `20261026`. Do not substitute a key if the original cannot be recovered.
- Preserve `NightActions` via `BedJetService.runOwned`, receipt-only confirmation, APP haptics, 115/100/90-second budgets, tracked-night confirmations, Temporary, 10-minute Turbo cap, Boost labels, 30-second connection grace, Bluetooth banner and 1–18-hour alarm explanations.
- All application-facing strings in resources, UK English; SDK 34 Robolectric; retain all applicable tests and the 38 opt-in capture skips.
- Evidence is host-only. No hardware, Samsung, TalkBack, installation or on-device verification claims.

## Review focus

- A queued but not yet admitted service request must exclude restore just as an active hardware write does; reject an import whose preview has become stale.
- Demo entry/exit must preserve real runtime and originals and perform no real History, Health Connect, backup or BLE writes.
- A light-theme due prompt must remain dark and survive app lifecycle changes; dismissing a choice must not save it.
- Auto-start must not duplicate after reboot/time changes or race a manually started night; permission and charging changes must cancel safely.
- Health export must distinguish a genuinely completed confirmed programme from test, demo, stopped, partial and uncertain records, including permission loss during a retry.

## Execution tasks

### 1. B1–B3 and C1/C2: Tonight, demo and Simple presentation

Files: `ui/TonightSections.kt`, `ui/TonightScreen.kt`, `ui/ProfilesScreen.kt`, `ui/AppViewModel.kt` target/wake blocks, `App.kt`, `data/Repository.kt` demo boundary, `core/store/Settings.kt`, new picker/demo composables and resource file.

- [ ] Write and run RED for section collapse/expand and restart persistence, real-target selection from home, legacy demo migration, isolated demo actions, alarm-first headline and explicit override, new-install Simple and retained saved choice.
- [ ] Keep a visible accessible 48 dp section header, introduce direct scan/pair/remembered-target selection and explicit demo enter/exit.
- [ ] Separate the next-phone-alarm headline from the confirmed current programme wake; a displayed next alarm alone must not imply the programme was re-timed.
- [ ] Match Profiles to the existing Simple night editor and verify the applicable layouts.
- [ ] Run focused GREEN, retain named test evidence, commit.

### 2. B4: light-theme consent

Files: `ui/Theme.kt`, `ui/MainActivity.kt`, `ui/SettingsScreen.kt`, theme preference policy and tests.

- [ ] Add RED for Scheduled-in-day selection, cancellation via Back/outside/Cancel, automatic scheduled/system light transitions and return from hidden state.
- [ ] Gate only light transitions; keep prompts dark, retain the previous saved setting on cancelled selection, persist stay-dark until the next configured dark period.
- [ ] Verify immediate dark changes, theme boundary/lifecycle cleanup and GREEN; commit.

### 3. C3–C7: status and Remote controls

Files: `ui/MainActivity.kt`, `ui/AppViewModel.kt` display flow, `ui/RemoteScreen.kt`, `ui/SettingsScreen.kt`, new status/device controls and duration UI.

- [ ] Add RED for fixed status header, 30-second/0.5-degree display filter with immediate control feedback, slider scales at mode limits and custom duration bounds/owner routing.
- [ ] Implement notification-driven display filtering only; keep raw owner receipts untouched.
- [ ] Add mode-bounded custom hours/minutes selection, preserving Temporary/End tonight review.
- [ ] Move existing accessory switches to Settings with unchanged mappings and receipt feedback.
- [ ] Verify 320 dp/200% labels, touch targets, contrast and focused GREEN; commit.

### 4. C8: Auto-start

Files: `platform/ChargerRun.kt`, `platform/ChargerJobs.kt`, new scheduling policy/receiver/UI, `PlatformRuntime.kt`, boot/clock receivers, `core/store/PlatformSettings.kt`.

- [ ] Add RED for fixed/relative timing, no-alarm fallback, optional charging, existing-setting migration, unreachable/active-night skips, reboot and clock re-arm, duplicate claims.
- [ ] Schedule one night alarm; use existing exact permission or explain an inexact window. Preserve charging grace/cancel when enabled and command-owner start confirmation.
- [ ] Persist truthful outcomes and run GREEN; commit.

### 5. C9–C11: remove obsolete integrations and shorten About

Files: `platform/PhoneHandover.kt`, `platform/PlatformRuntime.kt`, `ui/features/FeatureSections.kt`, updater code/settings/UI/tests/resources, manifest, About resources.

- [ ] Add RED for no Lull request UI/advertising watcher and external Releases link with no in-app fetch.
- [ ] Remove the isolated updater and obsolete settings/token path; remove INTERNET if no remaining use.
- [ ] Disable/remove Lull-specific requesting while preserving any still-used generic connection ownership functions; record the exact decision.
- [ ] Keep the Lull MIT protocol attribution, version, non-affiliation and project link; verify GREEN; commit.

### 6. R5: import preview and restore exclusion

Files: `core/store/BackupValidator.kt`, `data/Repository.kt`, new preview/admission models, `night/NightActions.kt`, `service/BedJetService.kt`, `ui/AppViewModel.kt` import block, Settings backup block/dialogue.

- [ ] Add RED for all preview categories, uncertain and associated originals, malformed/oversized fields, pending service and owner queue exclusion, and stale-preview race.
- [ ] `Repository.previewImport(bundle)` returns an immutable preview; `applyImport(preview)` revalidates frozen input and current-state fingerprint under the restore/admission boundary before the existing atomic journal path.
- [ ] Include snapshots in preview protection; retain rollback/current-original rules.
- [ ] Run GREEN and commit.

### 7. R4: optional Health Connect export

Files: platform Health Connect writer/export policy, History model/marker/UI, export settings section, permission/rationale resources, manifest.

- [ ] Add RED for disabled default, explicit write permission, complete/confirmed eligibility, exclusions, stable-ID update, revocation, failure/retry and Sync now.
- [ ] Use the existing Health Connect SDK with stable client record identifiers; mark export only after successful write and retain retry state without duplicates.
- [ ] Present the record as “BedJet programme ran”, never verified sleep; run GREEN and commit.

### 8. Integrated self-review, security pre-check and delivery

- [ ] Run the exact offline core/app/debug/lint gate plus release assembly.
- [ ] Review every changed file and full diff against the base; retain findings, fix commits and RED/GREEN test names, including minor defects.
- [ ] Audit exported components/permissions, PendingIntent flags, native intent bounds, backup parsing, secrets/logs and FileProvider paths.
- [ ] Repeat until the reviewed source is green with no new lint findings. Keep suggestions separate.
- [ ] Verify APK source/version/hash/certificate if the original key is available; otherwise record that exact signing blocker without substituting an identity.
- [ ] Push the branch, open and leave the PR open; deliver the ordered handover and prioritised phone tests.

## Current preparation evidence

The source checkout is isolated from the previous GPT-11 directory. Origin/main was verified via GitHub at the stated SHA; the exact signed merge object and tree match. Ordinary build caches were absent and are being restored. The original test private key is not in accessible files or the recovered GPT-11 evidence archive; certificate evidence alone does not satisfy signing.

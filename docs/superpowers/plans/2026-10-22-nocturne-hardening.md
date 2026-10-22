# Nocturne hardening and quality-of-life plan

**Source:** implementation brief of 2026-10-22, plus `docs/review/claude-second-review.md`. The attached review action plan was not available; see the note at the top of `docs/review/register.md`.

**Base:** `claude/lucid-mayer-i1h2gy` @ `22766fd`.
**Branch:** `claude/nocturne-hardening`.
**Register:** `docs/review/register.md`.
**Handover:** `docs/HANDOVER.md`.

## Baseline (recorded 2026-10-22)

- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passes: core 69/69, app 24/24, lint 0 errors / 26 warnings.
- Real-app renders are in `docs/screenshots/baseline/`:
  - Dark, Light, Simple-dark and Simple-light, covering Tonight (top and lower), Profiles, Remote, History, Settings and Tonight running.
  - Rendered by Robolectric NATIVE graphics, API 34, 411×891 dp at xxhdpi, against the demo BedJet, using the real wall clock (06:34 UTC), halved for storage.
  - These are not device or Bluetooth evidence.
- Lull source used for the protocol: `rkanjani/lull` @ `7214216` (2026-10-21).

## Constraints that bind every workstream

- **Keep the current UI:** Midnight Ink, Daylight, Simple view, navigation, cards, curve and controls. Extend locally; no redesign and none of the review's mock-ups.
- **BedJet 3 only.** The night runs on the BedJet. Keep: 3 slots, default working slot index 2, 32 steps, 1–8 ramp steps, protocol limits.
- **Pure-Kotlin `core`** for anything testable on the JVM; Android in `app`.
- **Wording:** UK English. "Snooze/extend" acts on the BedJet, not the phone alarm. Pending, failed and unknown are shown truthfully.
- **Test discipline:** test-first for every fix (watch it fail). Injected clocks and fakes. Never weaken a test to make it pass.
- **Repository safety:** no force-push, no merge to `main`, no published release, no signing-credential changes.

## Shared contracts (lead, before parallel work)

1. **Clock:** `Graph.clock: java.time.Clock`. Every "now" in the app goes through it, so tests and screenshots can pin time.
2. **Device identity:** a `DeviceId` string, `"demo"` or `"ble:<MAC>"`.
   - `Settings.targetDevice` holds the chosen real BedJet; `Settings.deviceId()` resolves the effective target.
   - The link never silently picks another BedJet once one is chosen.
3. **Schema ownership:**
   - `Settings` moves to its own file `core/store/Settings.kt`, owned by W3 during phase 1.
   - `Backup.kt` (bundle, slot backup, codec) is owned by W2.
   - `ActiveNight` and the night state (`core/night/`) are owned by W3.
4. **NightActions** is owned by W3 in phase 1, then by the lead during integration.

## Phases

| Phase | Work | Who |
|---|---|---|
| 0 | Branch, baseline, docs, contracts (clock, device id, Settings split) | Lead |
| 1 (parallel) | W1 BLE: R01–R08, R21 link side. W2 Persistence: R19 storage, R20 storage, R22–R25, V02. W3 Runtime: R09–R18, R26–R38 core and app runtime, F01/F02/F15/F16 backends, U02/U09 backends | 3 agents in isolated worktrees |
| 2 | Integrate phase 1, full suite | Lead |
| 3 (parallel) | W4 UI: U01–U11, F05, F08, F09, F13, F14, UI for F01/F02/F15/F16, editor drafts. W5 Features: F03, F04, F06, F07, F10, F11, F12, U03, U08 widget side | 2 agents |
| 4 | Integrate; R39/R40 release signing and shrinking; after-screenshots; acceptance checklist | Lead |
| 5 | W6 independent review, then the fix pass | Reviewer, then lead |
| 6 | Docs, push, draft PR, test APK | Lead |

## Decisions log

- **D1:** the register uses its own IDs (R, U, F, V) because the attached plan is missing.
- **D2:** baseline renders use the real clock. `java.time` doesn't follow Robolectric's clock, so fixed-clock renders come after contract 1.
- **D3:** subagents run on Opus at medium effort, as the owner set earlier in the project.

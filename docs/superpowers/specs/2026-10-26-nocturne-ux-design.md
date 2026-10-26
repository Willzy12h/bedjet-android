# Nocturne UX and phone-test design

Authority: the owner's GPT-11 specification and follow-up allowing the normal short-alarm restriction with an explanation, requesting a Settings sleep/wake test, and authorising merge after completion. The detailed implementation instruction already authorises this work; no new design approval is required.

Base: final pushed fixes2 `367968bddc390e5b2bb12876fc1662ffa07e47c3`. Work branch: `gpt/nocturne-ux`.

## Intent and approach

Keep the existing Kotlin/Compose/Glance application, themes, five tabs, night curve, journal and one command owner. Improve reachability and state explanations without replacing the working runtime. The chosen implementation uses existing settings persistence and owner/service APIs. A separate UI runtime or widget command owner would duplicate the most sensitive state and is unnecessary. A launcher swipe interaction would not meet the specified Samsung fallback; widget chevrons instead change Glance state in place.

Builder decisions: the normal minimum duration stays one hour; the upper normal alarm window becomes 18 hours. Settings offers an explicit short test with a preview/confirmation, through the same owner and receipt checks. Test runs are marked and excluded from real sleep/Health Connect reporting. No automatic command is launched merely by changing a setting. Exact test durations and phase compression will be selected to fit BedJet programme limits and covered by tests.

## Scope

- U1–U8: Quick controls first with saved collapse; persisted drag order plus Move up/down; fixed top-right wake and explanation; Simple-style editor and compact add row; curve phase selection and long-press editing; remove fan-set bubbles; stable progress space; move Release BedJet to Settings; mode maximum secondary labels; subtle active indicators respecting animation/lifecycle settings.
- P1–P3: mode-accurate Boost labels/descriptions; Resume/Stop recovery semantics and R38; Bluetooth state banner/enable permission flow, immediate refusal, de-duplicated background notice, reconnect without replay.
- P4: responsive 2×1, 3×2, 4×3+ widgets with respectively at most one/two/three actions; labels16sp, value20sp, actions56dp; in-place pages; provider XML; only explicit Open app launches MainActivity; receipt-truthful feedback and transient confirmation activity for guarded actions.
- P5: verify existing30-second connection grace,20-second return and night/owner protection; do not reimplement it.
- P6–P7: diagnose and explain accepted/null/disallowed/too-soon/too-late/absent alarms, fresh re-check and source setting; preserve pending/unknown precedence; remove false terminal re-time warnings and make real ones identify requested time/target with Check BedJet.
- P8–P10: light-theme confirmation including outside/back dismissal and current System mode; Daylight heat amber/orange-red with contrast preserved; investigate Dry range from protocol/UI and honour actual accepted limits consistently.
- Section3: retain sensor presentation throttling and idle BLE suppression; lifecycle-bound animation; event-driven widgets; exact cadence inventory.
- Section4: trace every requested phone-test ID, fix demonstrated defects, distinguish code/test evidence from device-only acceptance.

## Constraints and acceptance

UK English; app-visible text in resources. No change to production signing identity or environment-variable mechanism. No release or tags. Push only fixes2/ux; the later authorised merge uses a PR to main with expected head protection, no force/rebase. No reset --hard, clean, deletion of worktrees, skipped/disabled tests or assertion weakening.

All commands keep NightActions mutex through BedJetService.runOwned. Owner90s < service100s < UI115s. Confirmation requires a later SourcedStatus receipt. Haptics remain APP-only, admission CLOCK_TICK and same-request CONFIRM. R38 guards stay. Graphics/control identification>=3:1, text>=4.5:1, touch>=48dp, 200% text support. Animations stop offscreen/background and obey remove animations.

Each behaviour change requires an observed assertion RED and GREEN. Full offline gate plus assembleRelease after integrated implementation, after review remediation, and at final completion. Lint compared by finding identity to the base: 0errors, no new warnings. Self-review repeats over the whole base diff and touched files until no defects; no release-ready or hardware verification claim. Final debug APK uses the surviving existing debug test key and reports both SHA256 hashes.

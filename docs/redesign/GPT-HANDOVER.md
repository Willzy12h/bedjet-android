# Nocturne redesign remediation handover

Date: 29 October 2026. Branch: `gpt/nocturne-redesign-remediation`.

**Final automated verification passed; independent review remains.** This document replaces the conflicting historical counts and ownership restrictions in the previous handover. The unmodified starting reports are retained in `evidence/remediation/historical/`. This is the implementer's handover and self-review, not Claude's independent sign-off.

## Source identity and scope

- Fetched starting branch: `gpt/nocturne-redesign-final-review`.
- Starting SHA: `e4be250fb9f340800fa89aea8e358d5bddbf5054`.
- Verified application/test source SHA: **c9704ee91b4d5642a90f7cf0b7f9d22b9ce619dd**.
- Verification source-tree SHA-256: **dbe37daa47686e6ff20c17d58191d5e7aca3738eb52cbd9e4457ed2834a8c4e8**.
- Final delivery includes documentation/evidence commits after the verified application commit. `git rev-parse origin/gpt/nocturne-redesign-remediation` identifies the delivery SHA; the accompanying delivery manifest records that exact SHA and APK hash. No application, test or build changes are permitted between the final successful gate and delivery.

The owner's latest brief authorises remediation throughout the application and supersedes earlier Claude-only file ownership and numerical warning budgets. Work was performed by one implementer, without sub-agents. All nine requested redesign documents were read before changes. No applicable AGENTS.md or CLAUDE.md was present. No PR, merge, rebase, force-push, release publication or production-signing change is part of this delivery.

## What was repaired

### Tonight, command feedback and navigation

Tonight derives running and active-preset states from confirmed owner/device evidence. An unknown or manual night does not gain a running label merely because a record exists. Quick controls expose disabled semantics when fresh control evidence is unavailable. Sending feedback follows the completion identity of its own operation; the arbitrary 15-second fallback was removed. The ViewModel tracks all admitted requests so one completion cannot clear busy while another remains queued. A settled confirmed Boost journal no longer prevents a later fresh Off or Heat indication, while pending, failed and unknown journals still prevent an unsupported active claim.

Wake summaries retain the alarm/fallback/pending explanation and full accessible value. The ignored-alarm link reaches Night settings, and consumed navigation requests do not replay on retained-ViewModel recreation. Setup interruptions park the current editor draft. Remote remains a Tonight subpage with Back, functioning controls, reconnect information and Bluetooth entry points through the existing owners.

The current four-tab design intentionally replaces the old Tonight section-collapse/reorder UI and global status banner. Their old test entry points were migrated; the obsolete controls were not restored. Legacy backup fields remain for compatibility.

### Profiles, editor and data safety

Whole-card Edit, New, overflow actions, cooldown offers and draft restoration use the approved UI. Phase steppers retain range and unit semantics, with full spoken Long heat and Turbo names. Dragging uses measured row displacement and commits the move once on release. Overcast uses a light drag surface; the dark appearance remains approved. At large text, labels and values can wrap or stack and sheets scroll; the 48dp interaction targets remain intact. Middle temperature units stay on one line at normal size, and narrow plot labels move beneath proportional bars instead of disappearing.

Profiles Import now merges profiles by ID after a pre-import snapshot, preserving unrelated profiles, settings, History and saved BedJet originals. Settings retains whole-backup restore. The existing validation, import admission, preview fingerprint and transactional writer are reused; the merged profile limit is validated. **The backup schema remains 3.**

Share captures the selected profile when the document request is launched, survives Activity recreation and rejects a result from an old real/demo session revision. Later profile edits cannot silently substitute the shared data. Cancellation remains cancellation. `DemoImportPreviewInvalidationTest` is unchanged and enabled; no production behaviour was altered to satisfy that fixture.

### Accessibility, palettes and supporting screens

Rendered tests cover text foregrounds and meaningful control/selection indicators across all seven palettes. They exposed and drove fixes for low-contrast Overcast labels, navigation, History issues, active quick dots, text-field boundaries, sliders, radios, checkboxes and selection borders/fills. The protected palette literals, Ember boundaries, four tint strengths and Start fill remain. Selected mode labels inherit their selected control's readable foreground, including the secondary temperature limit.

History uses complete profile/time/status labels and scrollable check-in explanations. Large-text suggestion actions have explicit spacing, and touch tests distinguish Keep from Apply. Settings Connected uses the same receipt-backed mapper as Tonight. Setup's remembered-device row no longer applies the tint twice. Menus and floating surfaces are opaque; Forest, Graphite and Plum use the agreed `#171A2C` sheets. Old unreachable UI implementations and unused resources were removed without removing test cases or backup fields.

## Evidence and final gate

The reproduced baseline was **313 core passed; 1,708 app total, 1,460 passed, 207 failed, 0 errors, 41 opt-in skips**, actual exit **1**. The historical 238 failures were not assumed to be the current baseline. All 207 reproduced failures have individual classifications, root causes, current test names, commits and final case matches in [baseline-failures.csv](evidence/remediation/baseline-failures.csv).

The exact final command is:

```sh
./gradlew --offline --no-daemon --max-workers=3 -I docs/redesign/verification.init.gradle clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

| Check | Final result |
|---|---|
| Actual Gradle process exit | 0; source unchanged throughout |
| Core tests | 313 passed, 0 failures, 0 errors, 0 skips |
| App tests | 1,805 total; 1,764 passed, 0 failures, 0 errors, 41 opt-in skips |
| Ordinary-gate skips | 41, exclusively the four documented opt-in capture classes |
| Separately opted-in captures | 41 passed, 0 failures, 0 errors, 0 skips; exit 0 |
| Lint | 0 errors, 0 warnings, 0 hints |
| Project-owned compiler/build warnings | 0 |
| External diagnostics in final gate | 0 warning diagnostics |

Raw process receipts record the command, start/end source SHAs, source-tree digests and actual exit status. JUnit cases are reconciled directly. Earlier reports left by incremental runs are identified by their suite timestamps and are not counted as a new run. Nothing relies on a pipe's `tee` status. Screenshot generation passing does not establish visual correctness: the first complete capture review exposed UI-19–22, which were reproduced and fixed before the final gate.

The detailed [remediation register](REMEDIATION-REGISTER.md) includes RED/GREEN evidence, intermediate failures and the deliberately interrupted pre-final gate. [WARNING-AUDIT.md](WARNING-AUDIT.md) lists all 210 observed diagnostics and every remaining narrow exception. Broad `app/lint.xml` suppressions were removed. The final result is not obtained through disabling rules, warning filters, test removal, quarantines, arbitrary waits or increasing timeouts.

## Reproducing the environment

Verification uses JDK 21, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.0.21, Robolectric 4.16.1 and NDK 27.0.12077973. JVM bytecode remains 17; minSdk is 31 and compileSdk/targetSdk are 36. The three relevant CI workflows use JDK 21. The release-manifest policy fixture matches targetSdk 36; its nine Python checks passed. Production signing was not changed.

Before an offline run in a fresh environment, install those Android/JDK tools, warm Gradle's plugin/runtime dependencies, and run:

```sh
python3 docs/redesign/prepare-offline-verification.py
python3 docs/redesign/prepare-offline-verification.py --check-only
```

The script validates the official Robolectric SDK JAR/POM files for API 31/33/34/35/36 against committed SHA-512 pins. It does not pretend to provision the separate Gradle dependency cache. Legacy API 31/33 security cases remain enabled, with API 34/36 navigation and manifest coverage. The test manifest bridge uses the final merged application manifest via AGP's public host-test source API, retaining receiver declarations and variant hardening.

The documented three-worker init script isolates test classes. In this 8 GiB environment Kotlin compilation runs in process with a 2 GiB Gradle heap, avoiding the reproduced competing-daemon OOM. Robolectric's requested-window-size option is enabled in test configuration to resolve the independently reproduced wide native-dialog measurement loop; production dialog sizing and timeouts were not changed.

## Visual evidence and accepted differences

Use [P8-AUDIT.md](P8-AUDIT.md) and [DESIGN-CAPTURE-PAIRING.md](DESIGN-CAPTURE-PAIRING.md). All **59** published references are paired with native evidence or an explicit explanation where the reference is a token/decision page or an unimplemented design-only wizard stage. Captures use `SCREENSHOT_TAG=remediation`, normal 411dp layouts and 320dp/200% text, with all palettes and expanded interactive states.

1,003 PNGs (536 unique), 45 contact sheets, 5 lossless capture ZIPs, 10 reference-pairing sheets and 10 final-change review sheets. The numbered archives preserve unchanged full-resolution PNGs. The manifest gives each file's hash, dimensions, archive and contact-sheet location. Contact sheets and reference/native pairing sheets are for navigation; inspect original PNGs for detail.

Accepted differences remain explicit: deterministic demo names/times and receipt states; real alarm, mode-limit and safety copy; accessible wrapping and scrolling; existing setup/checklist flow; fan sliders; immediate “Start night now”; widget stripe rows, paging and Open app. The mock's unimplemented wizard or four-tile widget is not fabricated. No pixel-perfect claim is made.

## Assumptions retained

1. Explicit final user decisions outrank older mocks and tests. Four tabs and the current editor/sheet hierarchy are authoritative; existing command owners and data safeguards remain authoritative where the design is silent.
2. New generic after-wake choices use Cool 22°C, 30%, 10 minutes. Existing presets and copied profiles keep their own after-wake content. Saved legacy 30-minute Turbo chains remain readable; selecting a new Turbo phase uses 43°C, 100%, 10 minutes. There is no silent migration.
3. Readable foreground/selection ink and responsive spacing are accessibility corrections, not new palette or feature decisions. Decorative card boundaries are not falsely treated as the sole control indicator.
4. History says “issue” when a recorded ERROR may mean an uncertain outcome; it does not claim a write definitely failed. Connection and active-state labels require the existing receipt evidence.
5. Profiles Import has profile-only merge semantics; full restore remains the explicit Settings operation. The file schema and ownership of non-profile data do not change.

## Debug APK and device limits

Package `io.github.willzy12h.nocturne`, version **0.1.0** (code **1**), minSdk **31**, targetSdk **36**, debuggable. The separately delivered `nocturne-remediation-delivery.json` records the exact delivery commit, post-gate committed-source build receipt, byte size, APK SHA-256 and verified Android Debug certificate fingerprint. The APK and complete verification bundle accompany this handover.

The APK is a debug-signed test build from the final committed source. No release was published. Its manifest metadata, debug certificate, byte size and SHA-256 are included in the delivery manifest and verification evidence.

**Untested hardware scenarios:** real BedJet BLE discovery/pairing, firmware/status behaviour, radio loss/reconnect, physical programme writes/restoration and overnight timing; Samsung permission/battery screens and background restrictions; installed launcher widgets and Quick Settings tiles; physical TalkBack traversal/announcements and touch exploration; physical display/font rendering, insets and keyboard behaviour; real Health Connect provider, system alarm delivery and OEM process death. Robolectric/native rendering, fake-radio safety tests and Glance RemoteViews coverage do not substitute for these checks. The requested gate exercises the debug variant; release-specific R8 shrinking, production signing and distribution were not executed.

## Claude's independent review

Fetch the remediation branch normally and record its exact HEAD. Read this handover, FINAL-REVIEW, REMEDIATION-REGISTER, WARNING-AUDIT, P8-AUDIT and DESIGN-CAPTURE-PAIRING, then the approved design/reference documents. Review the full redesign from `56bfbcd^` through delivery, and the remediation diff from `e4be250` separately. Re-run the exact clean gate and all opt-in captures without changing test scope or warning reporting.

Prioritise data-import/share isolation, confirmation target identity, pending/unknown/confirmed state truth, fresh and retained ViewModel navigation restoration, running-night safety, command ownership and cancellation, Overcast selected/drag states, 320dp/200% controls, widget/background paths and the targetSdk 36 compatibility changes. The implementer's self-review and passing automated evidence are inputs to your independent judgement, not a substitute for it.

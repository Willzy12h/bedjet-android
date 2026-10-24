# U06 implementation and Claude revision

> **Robolectric revision complete; Claude review pending.** The two approved layout fixes are retained, the extra screen padding is reverted, and the requested evidence is recorded below.

## 1. Review and implementation identity

| Item | Identity |
|---|---|
| Original U06 base | `262c52fc7a2416f6b5d93a7abafb11079ec29d76` |
| Predecessor reviewed by Claude | `437c902bc5eac5f8f3150eb1212df0d72b7a10e4` |
| Continuing branch | `gpt/nocturne-u06` |
| Full verified implementation commit | `633af410b49cf1b21c69b1ffd6f1a4b711948641` |
| Source and test input verification | Corrected-run input manifest (`final-green/source-inputs.json`), digest `b04bf9a353e7c39a560f022776ec8c927b14f5d9eb2612dcc629566f829c4e21` |
| Publication result | Append-only revision: the implementation has the reviewed predecessor as its parent; the report and register follow in a documentation-only commit. No force-push or merge. |

This revision uses two new commits on the same branch, preserving the reviewed predecessor without a history rewrite or force push. The implementation commit above contains the code, tests and selected PNGs. The following documentation-only commit contains this report and the U06 register update; its full branch-head SHA is identified in the accompanying handover reply.

Logs, XML, source hashes and JSON metadata are available in the [verification evidence archive](https://chatgpt.com/library?file_id=libfile_ae666592f0988191967a7186980ffef0&account_id=personal). Evidence paths below are members under `u06-r2-evidence/` in that archive. The 104 native PNGs are committed under `docs/screenshots/u06/`.

Claude retains review and merge authority. This report supplies evidence for that review; the revision is **awaiting Claude's review**, and no merge has been performed.

## 2. Final production scope

The net production diff against the original base contains two local layout fixes:

| File and component | Base behaviour | Retained change and purpose |
|---|---|---|
| `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt`, `BigButton` | The primary button has an exact `height(56.dp)`. | Use `heightIn(min = 56.dp)` so Start and End night retain their minimum size and can grow to fit enlarged text. |
| `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RecoveryNotices.kt`, `AcceptLostOriginalsDialog` | The long loss explanation is a plain, non-scrollable dialog text slot. | Put that explanation in a vertically scrollable `Column`, retaining the existing text and confirm/cancel actions. |

`MainActivity.kt` is restored byte-for-byte to the original `262c52fc7a2416f6b5d93a7abafb11079ec29d76` content. Removing the predecessor's extra 8dp top/bottom padding is a change relative to `437c902bc5eac5f8f3150eb1212df0d72b7a10e4`, but is not a third net production fix relative to the original base. The original navigation and measured snackbar inset remain in place.

The final production identity record (`final-production-identity.json`) confirms that `MainActivity.kt` and the existing `AppSmokeTest.kt` match the original base exactly, while `TonightScreen.kt` and `RecoveryNotices.kt` match the reviewed predecessor exactly. The base (`base-red/source-inputs.json`) and corrected (`final-green/source-inputs.json`) input manifests differ only in the latter two files. All test inputs are identical, and the temporary clipping mutation is absent from the corrected input.

## 3. Changes made in response to Claude's review

### Geometry contract

The tests retain the comparison between the full, unclipped control rectangle and the visible rectangle. A partially clipped control must fail even when `assertIsDisplayed()` succeeds. The visible control must remain within its window and scroll viewport and above the real navigation bar and opaque snackbar surface.

The minimum click/toggle touch size remains **48dp in both dimensions**, with the existing one-pixel measurement tolerance. The revision removes the separate assumption that all of Material's expanded touch padding must also be contained within the drawn viewport. A chip can have a smaller drawn surface and an automatically expanded touch target; `performScrollTo()` aligns the drawn control with the viewport edge. The additional touch-area overhang at that edge is allowed. The drawn control, its label and the minimum target-size assertion remain checked.

Scalar sliders and range thumbs use `checkLayout` for full visibility, text where present, viewport containment and obstruction checks. Their label, unit, state, numeric value and `SetProgress` assertions remain. The primary-action 48dp requirement is not extended to a `SetProgress` semantics rectangle: the pinned Compose slider's 44dp semantics height is not, by itself, a product layout defect. No production change to `Accessibility.kt` is retained.

### Fixture and test cleanup

- `U06Fixture` passes JUnit `Description` to pre-launch setup. Scenario selection uses runtime-retained annotations and enums instead of substrings in test method names. Primary and recovery tests explicitly declare their scenarios; the platform-row fixture declares its special prerequisites.
- Primary-layout test labels resolve through string resources, including tabs, modes, timer chips, dialog choices and manual snapshot labels.
- The Remote primary-layout scenario reaches Heat by pressing the real Warm UI action. It waits for Heat and an idle command owner, then dismisses the command's snackbar through its semantics action before retaining the existing checks without feedback and with controlled feedback. It no longer sets the controller mode directly.
- The shared `assertLinesFit` implementation checks line spans, character coverage, ellipsis and vertical bounds for both control labels and the loss explanation. It avoids the known false width result from reconstructing `GetTextLayoutResult` in pinned Compose 1.7.6.
- Capture-only cases are separate from assertions that are expected to fail against the base. Normal and large-text captures can therefore record the actual base appearance as well as the corrected appearance.

The underlying fixture still launches the real Activity, ViewModel and Repository with a demo transport. It stops each previous graph before replacing it, pins the clock before Activity composition, and keeps runtime reconciliation separate from seeded layout states. These fixtures establish UI behaviour under Robolectric; they do not establish physical-device behaviour.

## 4. Changed files

| Path | Role in this revision |
|---|---|
| `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/MainActivity.kt` | Revert the predecessor's extra padding; identical to the original base. |
| `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/TonightScreen.kt` | Retain the primary-button minimum-height fix; temporary probes are excluded. |
| `app/src/main/kotlin/io/github/willzy12h/nocturne/ui/RecoveryNotices.kt` | Retain the scrollable loss explanation; no additional revision to its action semantics. |
| `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06Fixture.kt` | Revised geometry contract, shared line-fit checks, explicit pre-launch callback and capture configuration. |
| `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06PrimaryLayoutTest.kt` | Resource-based labels, explicit scenario annotations and UI-driven Heat setup. |
| `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06ControlSemanticsTest.kt` | Slider/switch coverage and explicit platform prerequisites. |
| `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06RecoveryLayoutTest.kt` | Explicit recovery scenarios and shared dialog text validation. |
| `app/src/test/kotlin/io/github/willzy12h/nocturne/ui/U06CaptureTest.kt` | Separate normal-size and large-text native capture cases. |
| 104 selected PNGs under `docs/screenshots/u06` | Full linked capture list in section 10; JSON metadata is external evidence, not a committed capture file. |
| `docs/review/u06-implementation.md` | This report, completed in the documentation-only handover. |
| `docs/review/register.md` | Update only the U06 row's Status, Commit and Tests fields. |

The authoritative final changed-file list, with its comparison base and implementation SHA, is **110 implementation files relative to the reviewed predecessor**: six code/test files and 104 PNGs. The documentation-only handover adds this report and changes the single U06 register row, for **112 revision files relative to `437c902`** and **113 net files relative to `262c52f`**. The complete paths are recorded in `scope-check.json` in the evidence archive; the code/document table above and 104-image list below enumerate them. No prohibited path changed. The table distinguishes a restoration relative to the reviewed predecessor from the two retained production differences relative to the original base.

## 5. Automated coverage and revised runs

The assertion matrix uses Robolectric API 34 with native graphics at **320dp wide, 640dp high and font scale 2.0**. Every case runs in Midnight Ink, Daylight, Simple view with Midnight Ink, and Simple view with Daylight. Simple view is a separate preference, so both palettes are covered.

| Suite | Cases per look | Four-look executions | Main coverage |
|---|---:|---:|---|
| `U06PrimaryLayoutTest` | 10 | 40 | Idle Start, confirmed End night, UNKNOWN manual Keep/Stop/Resume, idle Resume, tracking dialog, Quick actions, Remote actions, setup/demo routes and Settings actions. |
| `U06ControlSemanticsTest` | 8 | 32 | Every populated Settings/Remote/editor slider, both range thumbs, Celsius/Fahrenheit states, switch-row semantics and supported state changes. |
| `U06RecoveryLayoutTest` | 4 | 16 | Ordinary unreadable data, unfinished restore, lost-originals confirmation and unconfirmed-slot acknowledgement in real app routes. |
| Assertion-bearing subtotal | 22 | **88** | Layout and behaviour assertions. |
| `U06NormalCaptureTest` | 3 | 12 | 411dp × 891dp, font scale 1.0; tab tops and views of the retained fixes. |
| `U06LargeTextCaptureTest` | 3 | 12 | 320dp × 640dp, font scale 2.0; matching primary-action and loss-dialog views. |
| Capture-case subtotal | 6 | **24** | Opt-in capture executions, separate from the base-failing assertions. |

The initial revised run (`initial-revision/run.json`), tag `u06-r2-initial`, passed **112 executions with 0 failures, errors or skips**. It ran from 19:05:55 to 19:10:14 UTC on 2026-10-24 and recorded the same source-input digest as the corrected run. Its log (`initial-revision/gradle.log`) and XML (`initial-revision/app-xml/`) are retained.

The subsequent corrected run also passed **112/112: 88 assertion-bearing executions plus 24 capture executions**, with **0 failures, errors or skips**. Of the 88 assertion cases, **72 already passed against original production** and provide newly recorded coverage. The remaining **16 are product RED → GREEN cases** for the two retained fixes. The 24 capture cases passed on both inputs and are accounted for separately.

The count of 24 capture executions is not a claim that there are only 24 PNGs. A case can record several scroll positions or windows. The final capture manifest below supplies the actual files.

## 6. RED → GREEN for the two retained fixes

The base comparison runs the revised tests and capture cases against original production content. The base production check (`base-production-check.json`) confirms that all 18 UI production files matched the original base. The revised tests are identical between RED and GREEN; the original commit did not itself contain these new tests. Both run records (`base-red/run.json`) and corrected-run records (`final-green/run.json`) confirm unchanged inputs throughout their respective executions.

| Retained fix | Observed base RED | Corrected GREEN |
|---|---|---|
| Primary button changes from exact 56dp height to minimum 56dp height | **12 failures:** the idle Start, idle Resume scenario and confirmed End tests each fail in all four appearances. The failed label is Start in both idle scenarios and End in the confirmed scenario: `clips line 0 vertically: 84.0 > 80`. | The same **12 cases pass** with the retained button fix; the complete Primary suite passes **40/40**. |
| Loss explanation becomes scrollable | **4 failures:** `lostOriginalsConfirmationStaysReachableOnTonightAndSettings` fails in each appearance: `Accept-loss explanation clips line 7 vertically: 600.0 > 578`. | The same **4 cases pass** with the scrollable explanation; the complete Recovery suite passes **16/16**. |

The line bounds above are pixels: **84px > 80px** for the buttons and **600px > 578px** for the explanation. The primary failures are in `idleStartHasItsWholeLabelAndTouchTargetAboveFeedback`, `idleResumePreviousStaysReachable` and `runningConfirmedEndAndTrackingActionsStayReachable`. These are label-fit failures, not touch-size failures. The Resume scenario's failure identifies its Start button; it does not establish a defect in the Resume control itself. Exact assertions are retained in the base Primary XML (`base-red/app-xml/TEST-io.github.willzy12h.nocturne.ui.U06PrimaryLayoutTest.xml`) and base Recovery XML (`base-red/app-xml/TEST-io.github.willzy12h.nocturne.ui.U06RecoveryLayoutTest.xml`).

| Suite | Base passed / failed | Corrected passed / failed |
|---|---:|---:|
| Primary | 28 / 12 | 40 / 0 |
| Control semantics | 32 / 0 | 32 / 0 |
| Recovery | 12 / 4 | 16 / 0 |
| Normal capture | 12 / 0 | 12 / 0 |
| Large-text capture | 12 / 0 | 12 / 0 |
| **Total** | **96 / 16** | **112 / 0** |

Both runs have **0 errors and 0 skips**. The base run exited 1; the corrected run exited 0.

The recorded command was:

```bash
SCREENSHOT_TAG=u06-r2-base ./gradlew --no-daemon --offline --no-build-cache \
  --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest --rerun --tests '*U06*Test'
```

The corrected run uses the same invocation with `SCREENSHOT_TAG=u06-r2-after`.

| Run | UTC on 2026-10-24 | Frozen input digest | Evidence |
|---|---|---|---|
| Base RED | 19:15:32–19:18:12 | `ff5275b6a6a15ceef7069408908049a2999bd84018f0b29702ac8d6623183350` | run (`base-red/run.json`), log (`base-red/gradle.log`), XML (`base-red/app-xml/`), inputs (`base-red/source-inputs.json`) |
| Corrected GREEN | 19:19:58–19:22:09 | `b04bf9a353e7c39a560f022776ec8c927b14f5d9eb2612dcc629566f829c4e21` | run (`final-green/run.json`), log (`final-green/gradle.log`), XML (`final-green/app-xml/`), inputs (`final-green/source-inputs.json`) |

The before/after primary-action and dialog captures are linked in section 10. These app-only runs did not request `:core:test`; copied historical core XML in their evidence directories is not a fresh core result. The exact four-task gate remains separate.

## 7. Deliberate clipping validates the guard

### First same-node attempt: diagnostic only

The first mutation put clipping on the same node and did not actually clip the rendered control. Its **4 passes, 0 failures, errors or skips** are diagnostic only and are not RED evidence. The retained run record (`deliberate-clip/run.json`), log (`deliberate-clip/gradle.log`) and patch (`deliberate-clip.patch`) identify that attempt, tagged `u06-r2-clip`.

### Separate-parent clipping: observed RED

The valid probe used a separate parent with a 32dp height and clipping, containing an 80dp button. This intentionally clips the child while retaining its larger full layout rectangle. All four appearances failed: **4 tests, 4 failures, 0 errors, 0 skips**.

Each failed with the visible-versus-unclipped assertion:

```text
Start night now layout is clipped: expected Rect.fromLTRB(32.0, 960.0, 608.0, 1120.0) inside Rect.fromLTRB(32.0, 1008.0, 608.0, 1072.0)
```

At density 2, the full rectangle is **80dp high** and the visible rectangle is **32dp high**. This directly demonstrates that the retained full-visibility check detects real clipping; allowing expanded touch padding at a viewport edge does not waive that check.

The probe used the same recorded Gradle options with selector `*U06PrimaryLayoutTest.idleStartHasItsWholeLabelAndTouchTargetAboveFeedback*` and tag `u06-r2-clip-parent`. Evidence: run (`clip-parent-red/run.json`), log (`clip-parent-red/gradle.log`), XML (`clip-parent-red/app-xml/TEST-io.github.willzy12h.nocturne.ui.U06PrimaryLayoutTest.xml`), source inputs (`clip-parent-red/source-inputs.json`), mutation patch (`clip-parent.patch`) and mutation hashes (`clip-parent.json`). Its four native PNGs are linked in section 10.

The restoration record (`clip-restored.json`) and final production identity (`final-production-identity.json`) confirm the approved Tonight checksum `88799e67a0f62cd939f719850697cbecdc236477c0470d26b9788363a235ae07`. The probe and corrected input manifests differ only in `TonightScreen.kt`. The same four parameterized Start cases then pass inside the complete corrected GREEN run, as recorded in its Primary XML (`final-green/app-xml/TEST-io.github.willzy12h.nocturne.ui.U06PrimaryLayoutTest.xml`): **4 genuine-clip failures → 4 restored passes**, with the test contract unchanged.

## 8. E3 repetition record

Exact selector:

```text
io.github.willzy12h.nocturne.AppSmokeTest.completedNightCheckInRecordsComfortWithoutChangingAProfile
```

**Not reproduced:** E3 passed **10/10 fresh isolated executions and 3/3 full-context executions**.
No failure was retried, no sleep was added, and no assertion or E3 fixture was changed.
`AppSmokeTest.kt` remains byte-identical to the original base.

Every invocation used the frozen source-input digest recorded in section 1.
The isolated command was the common recorded Gradle invocation followed by
`:app:testDebugUnitTest --rerun --tests` and the exact selector above.
The first two full runs used `:app:testDebugUnitTest --rerun`; the third is the four-task gate below.
`SCREENSHOT_TAG` was unset. Generated app XML was removed before each invocation,
and each task ran freshly with build-cache reuse disabled.
Each run directory in the evidence archive contains `run.json`, `gradle.log`, `source-inputs.json`
and `app-xml/TEST-io.github.willzy12h.nocturne.AppSmokeTest.xml`.
`e3-series-summary.json` also indexes the exact E3 result and duration.

All times below are UTC on 2026-10-24; E3 duration is the JUnit case time.

| Isolated run / evidence directory | Invocation time | E3 seconds | Fresh result |
|---|---|---:|---|
| `e3-isolated-01/` | 19:22:27–19:23:03 | 10.150 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-02/` | 19:23:03–19:23:27 | 6.996 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-03/` | 19:23:27–19:23:49 | 6.395 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-04/` | 19:23:49–19:24:08 | 5.946 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-05/` | 19:24:08–19:24:27 | 6.120 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-06/` | 19:24:27–19:24:45 | 6.322 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-07/` | 19:24:45–19:25:05 | 6.651 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-08/` | 19:25:05–19:25:25 | 6.285 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-09/` | 19:25:25–19:25:45 | 6.322 | 1 passed / 1 test; 0 failures, errors or skips |
| `e3-isolated-10/` | 19:25:45–19:26:08 | 7.087 | 1 passed / 1 test; 0 failures, errors or skips |

| Full-context run / evidence directory | Invocation time | E3 seconds | Fresh app-suite result |
|---|---|---:|---|
| `full-app-01/` | 19:26:08–19:32:53 | 0.474 | **E3 passed**; 554 passed / 584 discovered; 30 expected capture skips; 0 failures, 0 errors |
| `full-app-02/` | 19:32:53–19:39:43 | 0.502 | **E3 passed**; 554 passed / 584 discovered; 30 expected capture skips; 0 failures, 0 errors |
| `four-task-gate/` | 19:39:43–19:47:31 | 0.489 | **E3 passed**; 554 passed / 584 discovered; 30 expected capture skips; 0 failures, 0 errors |

The 30 skips in each full run are six existing `ScreenshotCapture` cases plus 12 `U06NormalCaptureTest` and 12 `U06LargeTextCaptureTest` cases. All **88 mandatory U06 assertion cases execute and pass** without the capture tag.

## 9. Exact four-task gate

Exact command, environment and frozen inputs:

```text
./gradlew --no-daemon --offline --no-build-cache --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

| Task | Exact final result |
|---|---|
| `:core:test` | 20 suites; 156 discovered, **156 passed**, 0 skipped, 0 failures, 0 errors |
| `:app:testDebugUnitTest` | 64 suites; 584 discovered, **554 passed**, 30 skipped, 0 failures, 0 errors |
| `:app:assembleDebug` | PASS; task status `EXECUTED`; `app/build/outputs/apk/debug/app-debug.apk`, 67,344,103 bytes; SHA-256 `fee9b42211910d461ae01032f858cab476cc7a45689a2efb6018cd42484ef999` |
| `:app:lintDebug` | PASS; 0 Fatal, 0 Error, 14 Warning, 1 Hint |

Gradle reported **BUILD SUCCESSFUL in 7m 47s; 59 actionable tasks: 13 executed, 46 up-to-date**. No task result came from the build cache. The raw log also includes lifecycle and non-actionable task rows, which are separate from this actionable-task total and from the 30 opt-in test-case skips.

Raw logs, XML, lint report and input-manifest evidence: `four-task-gate/run.json`, `four-task-gate/gradle.log`, `four-task-gate/app-xml/`, `four-task-gate/core-xml/`, `four-task-gate/lint-results-debug.xml`, `four-task-gate/source-inputs.json` and `gate-summary.json` in the evidence archive.

Final gate disposition: PASS before branch publication. Both test tasks executed freshly; source and test inputs were unchanged throughout the gate and match the verified implementation commit. `SCREENSHOT_TAG` was unset; JDK 17.0.21 and Gradle 8.14.3 were used. Earlier historical gates and the predecessor's evidence do not supply these final counts.

## 10. Native capture evidence

The completed capture tags are `u06-r2-base`, `u06-r2-after` and `u06-r2-clip-parent`, corresponding to the frozen inputs recorded above. The external capture manifest (`capture-manifest.json`) contains **104 PNG records: 48 before, 52 after and 4 probe**. These comprise **48 before/after pairs**, **4 corrected dialog scroll-end views** and **4 deliberate-clip views**. JSON metadata remains external; the curated capture directory contains only PNGs.

Both normal-size and large-text evidence cover all four appearances. The manifest records native renderer/API, measured dimensions, density, font scale, look, clock, visible windows, source image and SHA-256 for the comparison captures; probe entries identify their source and SHA-256. The clock is fixed at `2026-10-22T22:30:00Z` with the `Europe/London` zone before Activity composition. At density 2, the normal Activity viewport is **411dp × 891dp / 822px × 1782px, font scale 1.0**; the large-text viewport is **320dp × 640dp / 640px × 1280px, font scale 2.0**. Dialog windows have their own recorded dimensions.

The capture cases record primary actions and Quick actions together where they fit, with a separate companion view when they do not fit in one viewport. The loss-dialog start views are paired; the four large-text end views exist only after the explanation becomes scrollable. Normal-size evidence also records all five tab tops. All **20 normal tab-top pairs (5 tabs × 4 appearances) are pixel-identical and byte-identical**, as recorded in normal-spacing-comparison.json (`normal-spacing-comparison.json`).

### Final capture list

#### Normal: 411dp × 891dp, font scale 1.0 — 32 pairs / 64 PNGs

| Appearance / view | Before | After |
|---|---|---|
| Midnight Ink — Tonight tab top | [docs/screenshots/u06/before/411-100-midnight-tab-tonight.png](../screenshots/u06/before/411-100-midnight-tab-tonight.png) | [docs/screenshots/u06/after/411-100-midnight-tab-tonight.png](../screenshots/u06/after/411-100-midnight-tab-tonight.png) |
| Midnight Ink — Profiles tab top | [docs/screenshots/u06/before/411-100-midnight-tab-profiles.png](../screenshots/u06/before/411-100-midnight-tab-profiles.png) | [docs/screenshots/u06/after/411-100-midnight-tab-profiles.png](../screenshots/u06/after/411-100-midnight-tab-profiles.png) |
| Midnight Ink — Remote tab top | [docs/screenshots/u06/before/411-100-midnight-tab-remote.png](../screenshots/u06/before/411-100-midnight-tab-remote.png) | [docs/screenshots/u06/after/411-100-midnight-tab-remote.png](../screenshots/u06/after/411-100-midnight-tab-remote.png) |
| Midnight Ink — History tab top | [docs/screenshots/u06/before/411-100-midnight-tab-history.png](../screenshots/u06/before/411-100-midnight-tab-history.png) | [docs/screenshots/u06/after/411-100-midnight-tab-history.png](../screenshots/u06/after/411-100-midnight-tab-history.png) |
| Midnight Ink — Settings tab top | [docs/screenshots/u06/before/411-100-midnight-tab-settings.png](../screenshots/u06/before/411-100-midnight-tab-settings.png) | [docs/screenshots/u06/after/411-100-midnight-tab-settings.png](../screenshots/u06/after/411-100-midnight-tab-settings.png) |
| Midnight Ink — Start and Quick | [docs/screenshots/u06/before/411-100-midnight-tonight-start-and-quick.png](../screenshots/u06/before/411-100-midnight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/411-100-midnight-tonight-start-and-quick.png](../screenshots/u06/after/411-100-midnight-tonight-start-and-quick.png) |
| Midnight Ink — End and Quick | [docs/screenshots/u06/before/411-100-midnight-tonight-end-and-quick.png](../screenshots/u06/before/411-100-midnight-tonight-end-and-quick.png) | [docs/screenshots/u06/after/411-100-midnight-tonight-end-and-quick.png](../screenshots/u06/after/411-100-midnight-tonight-end-and-quick.png) |
| Midnight Ink — Accept-loss explanation start | [docs/screenshots/u06/before/411-100-midnight-accept-loss-start.png](../screenshots/u06/before/411-100-midnight-accept-loss-start.png) | [docs/screenshots/u06/after/411-100-midnight-accept-loss-start.png](../screenshots/u06/after/411-100-midnight-accept-loss-start.png) |
| Daylight — Tonight tab top | [docs/screenshots/u06/before/411-100-daylight-tab-tonight.png](../screenshots/u06/before/411-100-daylight-tab-tonight.png) | [docs/screenshots/u06/after/411-100-daylight-tab-tonight.png](../screenshots/u06/after/411-100-daylight-tab-tonight.png) |
| Daylight — Profiles tab top | [docs/screenshots/u06/before/411-100-daylight-tab-profiles.png](../screenshots/u06/before/411-100-daylight-tab-profiles.png) | [docs/screenshots/u06/after/411-100-daylight-tab-profiles.png](../screenshots/u06/after/411-100-daylight-tab-profiles.png) |
| Daylight — Remote tab top | [docs/screenshots/u06/before/411-100-daylight-tab-remote.png](../screenshots/u06/before/411-100-daylight-tab-remote.png) | [docs/screenshots/u06/after/411-100-daylight-tab-remote.png](../screenshots/u06/after/411-100-daylight-tab-remote.png) |
| Daylight — History tab top | [docs/screenshots/u06/before/411-100-daylight-tab-history.png](../screenshots/u06/before/411-100-daylight-tab-history.png) | [docs/screenshots/u06/after/411-100-daylight-tab-history.png](../screenshots/u06/after/411-100-daylight-tab-history.png) |
| Daylight — Settings tab top | [docs/screenshots/u06/before/411-100-daylight-tab-settings.png](../screenshots/u06/before/411-100-daylight-tab-settings.png) | [docs/screenshots/u06/after/411-100-daylight-tab-settings.png](../screenshots/u06/after/411-100-daylight-tab-settings.png) |
| Daylight — Start and Quick | [docs/screenshots/u06/before/411-100-daylight-tonight-start-and-quick.png](../screenshots/u06/before/411-100-daylight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/411-100-daylight-tonight-start-and-quick.png](../screenshots/u06/after/411-100-daylight-tonight-start-and-quick.png) |
| Daylight — End and Quick | [docs/screenshots/u06/before/411-100-daylight-tonight-end-and-quick.png](../screenshots/u06/before/411-100-daylight-tonight-end-and-quick.png) | [docs/screenshots/u06/after/411-100-daylight-tonight-end-and-quick.png](../screenshots/u06/after/411-100-daylight-tonight-end-and-quick.png) |
| Daylight — Accept-loss explanation start | [docs/screenshots/u06/before/411-100-daylight-accept-loss-start.png](../screenshots/u06/before/411-100-daylight-accept-loss-start.png) | [docs/screenshots/u06/after/411-100-daylight-accept-loss-start.png](../screenshots/u06/after/411-100-daylight-accept-loss-start.png) |
| Simple / Midnight Ink — Tonight tab top | [docs/screenshots/u06/before/411-100-simple_midnight-tab-tonight.png](../screenshots/u06/before/411-100-simple_midnight-tab-tonight.png) | [docs/screenshots/u06/after/411-100-simple_midnight-tab-tonight.png](../screenshots/u06/after/411-100-simple_midnight-tab-tonight.png) |
| Simple / Midnight Ink — Profiles tab top | [docs/screenshots/u06/before/411-100-simple_midnight-tab-profiles.png](../screenshots/u06/before/411-100-simple_midnight-tab-profiles.png) | [docs/screenshots/u06/after/411-100-simple_midnight-tab-profiles.png](../screenshots/u06/after/411-100-simple_midnight-tab-profiles.png) |
| Simple / Midnight Ink — Remote tab top | [docs/screenshots/u06/before/411-100-simple_midnight-tab-remote.png](../screenshots/u06/before/411-100-simple_midnight-tab-remote.png) | [docs/screenshots/u06/after/411-100-simple_midnight-tab-remote.png](../screenshots/u06/after/411-100-simple_midnight-tab-remote.png) |
| Simple / Midnight Ink — History tab top | [docs/screenshots/u06/before/411-100-simple_midnight-tab-history.png](../screenshots/u06/before/411-100-simple_midnight-tab-history.png) | [docs/screenshots/u06/after/411-100-simple_midnight-tab-history.png](../screenshots/u06/after/411-100-simple_midnight-tab-history.png) |
| Simple / Midnight Ink — Settings tab top | [docs/screenshots/u06/before/411-100-simple_midnight-tab-settings.png](../screenshots/u06/before/411-100-simple_midnight-tab-settings.png) | [docs/screenshots/u06/after/411-100-simple_midnight-tab-settings.png](../screenshots/u06/after/411-100-simple_midnight-tab-settings.png) |
| Simple / Midnight Ink — Start and Quick | [docs/screenshots/u06/before/411-100-simple_midnight-tonight-start-and-quick.png](../screenshots/u06/before/411-100-simple_midnight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/411-100-simple_midnight-tonight-start-and-quick.png](../screenshots/u06/after/411-100-simple_midnight-tonight-start-and-quick.png) |
| Simple / Midnight Ink — End and Quick | [docs/screenshots/u06/before/411-100-simple_midnight-tonight-end-and-quick.png](../screenshots/u06/before/411-100-simple_midnight-tonight-end-and-quick.png) | [docs/screenshots/u06/after/411-100-simple_midnight-tonight-end-and-quick.png](../screenshots/u06/after/411-100-simple_midnight-tonight-end-and-quick.png) |
| Simple / Midnight Ink — Accept-loss explanation start | [docs/screenshots/u06/before/411-100-simple_midnight-accept-loss-start.png](../screenshots/u06/before/411-100-simple_midnight-accept-loss-start.png) | [docs/screenshots/u06/after/411-100-simple_midnight-accept-loss-start.png](../screenshots/u06/after/411-100-simple_midnight-accept-loss-start.png) |
| Simple / Daylight — Tonight tab top | [docs/screenshots/u06/before/411-100-simple_daylight-tab-tonight.png](../screenshots/u06/before/411-100-simple_daylight-tab-tonight.png) | [docs/screenshots/u06/after/411-100-simple_daylight-tab-tonight.png](../screenshots/u06/after/411-100-simple_daylight-tab-tonight.png) |
| Simple / Daylight — Profiles tab top | [docs/screenshots/u06/before/411-100-simple_daylight-tab-profiles.png](../screenshots/u06/before/411-100-simple_daylight-tab-profiles.png) | [docs/screenshots/u06/after/411-100-simple_daylight-tab-profiles.png](../screenshots/u06/after/411-100-simple_daylight-tab-profiles.png) |
| Simple / Daylight — Remote tab top | [docs/screenshots/u06/before/411-100-simple_daylight-tab-remote.png](../screenshots/u06/before/411-100-simple_daylight-tab-remote.png) | [docs/screenshots/u06/after/411-100-simple_daylight-tab-remote.png](../screenshots/u06/after/411-100-simple_daylight-tab-remote.png) |
| Simple / Daylight — History tab top | [docs/screenshots/u06/before/411-100-simple_daylight-tab-history.png](../screenshots/u06/before/411-100-simple_daylight-tab-history.png) | [docs/screenshots/u06/after/411-100-simple_daylight-tab-history.png](../screenshots/u06/after/411-100-simple_daylight-tab-history.png) |
| Simple / Daylight — Settings tab top | [docs/screenshots/u06/before/411-100-simple_daylight-tab-settings.png](../screenshots/u06/before/411-100-simple_daylight-tab-settings.png) | [docs/screenshots/u06/after/411-100-simple_daylight-tab-settings.png](../screenshots/u06/after/411-100-simple_daylight-tab-settings.png) |
| Simple / Daylight — Start and Quick | [docs/screenshots/u06/before/411-100-simple_daylight-tonight-start-and-quick.png](../screenshots/u06/before/411-100-simple_daylight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/411-100-simple_daylight-tonight-start-and-quick.png](../screenshots/u06/after/411-100-simple_daylight-tonight-start-and-quick.png) |
| Simple / Daylight — End and Quick | [docs/screenshots/u06/before/411-100-simple_daylight-tonight-end-and-quick.png](../screenshots/u06/before/411-100-simple_daylight-tonight-end-and-quick.png) | [docs/screenshots/u06/after/411-100-simple_daylight-tonight-end-and-quick.png](../screenshots/u06/after/411-100-simple_daylight-tonight-end-and-quick.png) |
| Simple / Daylight — Accept-loss explanation start | [docs/screenshots/u06/before/411-100-simple_daylight-accept-loss-start.png](../screenshots/u06/before/411-100-simple_daylight-accept-loss-start.png) | [docs/screenshots/u06/after/411-100-simple_daylight-accept-loss-start.png](../screenshots/u06/after/411-100-simple_daylight-accept-loss-start.png) |

#### Large text: 320dp × 640dp, font scale 2.0 — 16 pairs and 4 end views / 36 PNGs

| Appearance / view | Before | After |
|---|---|---|
| Midnight Ink — Start and Quick | [docs/screenshots/u06/before/320-200-midnight-tonight-start-and-quick.png](../screenshots/u06/before/320-200-midnight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/320-200-midnight-tonight-start-and-quick.png](../screenshots/u06/after/320-200-midnight-tonight-start-and-quick.png) |
| Midnight Ink — End night | [docs/screenshots/u06/before/320-200-midnight-tonight-end.png](../screenshots/u06/before/320-200-midnight-tonight-end.png) | [docs/screenshots/u06/after/320-200-midnight-tonight-end.png](../screenshots/u06/after/320-200-midnight-tonight-end.png) |
| Midnight Ink — Quick companion to End | [docs/screenshots/u06/before/320-200-midnight-tonight-end-quick-companion.png](../screenshots/u06/before/320-200-midnight-tonight-end-quick-companion.png) | [docs/screenshots/u06/after/320-200-midnight-tonight-end-quick-companion.png](../screenshots/u06/after/320-200-midnight-tonight-end-quick-companion.png) |
| Midnight Ink — Accept-loss explanation start | [docs/screenshots/u06/before/320-200-midnight-accept-loss-start.png](../screenshots/u06/before/320-200-midnight-accept-loss-start.png) | [docs/screenshots/u06/after/320-200-midnight-accept-loss-start.png](../screenshots/u06/after/320-200-midnight-accept-loss-start.png) |
| Midnight Ink — Accept-loss explanation end | No scrollable base end view | [docs/screenshots/u06/after/320-200-midnight-accept-loss-end.png](../screenshots/u06/after/320-200-midnight-accept-loss-end.png) |
| Daylight — Start and Quick | [docs/screenshots/u06/before/320-200-daylight-tonight-start-and-quick.png](../screenshots/u06/before/320-200-daylight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/320-200-daylight-tonight-start-and-quick.png](../screenshots/u06/after/320-200-daylight-tonight-start-and-quick.png) |
| Daylight — End night | [docs/screenshots/u06/before/320-200-daylight-tonight-end.png](../screenshots/u06/before/320-200-daylight-tonight-end.png) | [docs/screenshots/u06/after/320-200-daylight-tonight-end.png](../screenshots/u06/after/320-200-daylight-tonight-end.png) |
| Daylight — Quick companion to End | [docs/screenshots/u06/before/320-200-daylight-tonight-end-quick-companion.png](../screenshots/u06/before/320-200-daylight-tonight-end-quick-companion.png) | [docs/screenshots/u06/after/320-200-daylight-tonight-end-quick-companion.png](../screenshots/u06/after/320-200-daylight-tonight-end-quick-companion.png) |
| Daylight — Accept-loss explanation start | [docs/screenshots/u06/before/320-200-daylight-accept-loss-start.png](../screenshots/u06/before/320-200-daylight-accept-loss-start.png) | [docs/screenshots/u06/after/320-200-daylight-accept-loss-start.png](../screenshots/u06/after/320-200-daylight-accept-loss-start.png) |
| Daylight — Accept-loss explanation end | No scrollable base end view | [docs/screenshots/u06/after/320-200-daylight-accept-loss-end.png](../screenshots/u06/after/320-200-daylight-accept-loss-end.png) |
| Simple / Midnight Ink — Start and Quick | [docs/screenshots/u06/before/320-200-simple_midnight-tonight-start-and-quick.png](../screenshots/u06/before/320-200-simple_midnight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/320-200-simple_midnight-tonight-start-and-quick.png](../screenshots/u06/after/320-200-simple_midnight-tonight-start-and-quick.png) |
| Simple / Midnight Ink — End night | [docs/screenshots/u06/before/320-200-simple_midnight-tonight-end.png](../screenshots/u06/before/320-200-simple_midnight-tonight-end.png) | [docs/screenshots/u06/after/320-200-simple_midnight-tonight-end.png](../screenshots/u06/after/320-200-simple_midnight-tonight-end.png) |
| Simple / Midnight Ink — Quick companion to End | [docs/screenshots/u06/before/320-200-simple_midnight-tonight-end-quick-companion.png](../screenshots/u06/before/320-200-simple_midnight-tonight-end-quick-companion.png) | [docs/screenshots/u06/after/320-200-simple_midnight-tonight-end-quick-companion.png](../screenshots/u06/after/320-200-simple_midnight-tonight-end-quick-companion.png) |
| Simple / Midnight Ink — Accept-loss explanation start | [docs/screenshots/u06/before/320-200-simple_midnight-accept-loss-start.png](../screenshots/u06/before/320-200-simple_midnight-accept-loss-start.png) | [docs/screenshots/u06/after/320-200-simple_midnight-accept-loss-start.png](../screenshots/u06/after/320-200-simple_midnight-accept-loss-start.png) |
| Simple / Midnight Ink — Accept-loss explanation end | No scrollable base end view | [docs/screenshots/u06/after/320-200-simple_midnight-accept-loss-end.png](../screenshots/u06/after/320-200-simple_midnight-accept-loss-end.png) |
| Simple / Daylight — Start and Quick | [docs/screenshots/u06/before/320-200-simple_daylight-tonight-start-and-quick.png](../screenshots/u06/before/320-200-simple_daylight-tonight-start-and-quick.png) | [docs/screenshots/u06/after/320-200-simple_daylight-tonight-start-and-quick.png](../screenshots/u06/after/320-200-simple_daylight-tonight-start-and-quick.png) |
| Simple / Daylight — End night | [docs/screenshots/u06/before/320-200-simple_daylight-tonight-end.png](../screenshots/u06/before/320-200-simple_daylight-tonight-end.png) | [docs/screenshots/u06/after/320-200-simple_daylight-tonight-end.png](../screenshots/u06/after/320-200-simple_daylight-tonight-end.png) |
| Simple / Daylight — Quick companion to End | [docs/screenshots/u06/before/320-200-simple_daylight-tonight-end-quick-companion.png](../screenshots/u06/before/320-200-simple_daylight-tonight-end-quick-companion.png) | [docs/screenshots/u06/after/320-200-simple_daylight-tonight-end-quick-companion.png](../screenshots/u06/after/320-200-simple_daylight-tonight-end-quick-companion.png) |
| Simple / Daylight — Accept-loss explanation start | [docs/screenshots/u06/before/320-200-simple_daylight-accept-loss-start.png](../screenshots/u06/before/320-200-simple_daylight-accept-loss-start.png) | [docs/screenshots/u06/after/320-200-simple_daylight-accept-loss-start.png](../screenshots/u06/after/320-200-simple_daylight-accept-loss-start.png) |
| Simple / Daylight — Accept-loss explanation end | No scrollable base end view | [docs/screenshots/u06/after/320-200-simple_daylight-accept-loss-end.png](../screenshots/u06/after/320-200-simple_daylight-accept-loss-end.png) |

#### Deliberate parent clipping — 4 PNGs

| Appearance | Probe |
|---|---|
| Midnight Ink | [docs/screenshots/u06/probe/320-200-midnight-deliberate-clip.png](../screenshots/u06/probe/320-200-midnight-deliberate-clip.png) |
| Daylight | [docs/screenshots/u06/probe/320-200-daylight-deliberate-clip.png](../screenshots/u06/probe/320-200-daylight-deliberate-clip.png) |
| Simple / Midnight Ink | [docs/screenshots/u06/probe/320-200-simple_midnight-deliberate-clip.png](../screenshots/u06/probe/320-200-simple_midnight-deliberate-clip.png) |
| Simple / Daylight | [docs/screenshots/u06/probe/320-200-simple_daylight-deliberate-clip.png](../screenshots/u06/probe/320-200-simple_daylight-deliberate-clip.png) |

### Visual review record

Independent review verified all 104 files against their recorded SHA-256 and original native bytes, with no missing or extra files. The curated set totals **10,005,903 bytes**. All 20 normal tab pairs and all 12 normal retained-fix pairs are byte-identical. The normal tab pairs are also pixel-identical.

Visual review covered the large-text Start and End pairs across all four looks, normal primary-plus-Quick views across all four looks, all four large-text End Quick companions, the dark/light dialog positions and all four clipping probes. The corrected views show complete targeted actions. All four Quick buttons appear together in the Start view and in the separate End companion. Normal-size dialogs show the complete explanation and both actions.

The BigButton change is a small height increase; its precise RED proof is the **84px line box exceeding 80px available height**, rather than a claim that the PNG loses words. The large-text dialog's initial before/after images are identical. Its additional final-scroll captures and the successful scroll assertions prove access to the warning that was unreachable on the base. There can be no baseline end-position capture because the base has no scroll route. Both dialog actions remain visible, and those PNGs capture the separate native dialog window.

The four valid probes visibly cut the button surface to a strip and correspond to the **80dp full / 32dp visible** assertion failure. None of the curated probe images comes from the ineffective same-node diagnostic. The detailed review record is retained as `visual-review.json` in the evidence bundle. These are native Robolectric results; physical phone and TalkBack verification remain **NOT RUN**.

Each curated PNG's SHA-256 is recorded in the external capture manifest (`capture-manifest.json`). Final evidence-bundle identity: [u06-r2-verification-evidence.zip](https://chatgpt.com/library?file_id=libfile_ae666592f0988191967a7186980ffef0&account_id=personal), 488,435 bytes, 316 members; SHA-256 `7479a8c2b3b981c06c1bf28549f5640f08a18755662a4e3224804a303b460769`.

## 11. Physical acceptance and review handover

| Acceptance activity | Result |
|---|---|
| Actual phone/Samsung layout inspection at large text | **NOT RUN** |
| TalkBack focus traversal and spoken output on a phone | **NOT RUN** |
| Physical touch-target interaction on a phone | **NOT RUN** |
| Physical BedJet/BLE behaviour | **NOT RUN** |

Robolectric semantics, demo transport responses and native captures are the evidence supplied here. They do not establish any of the physical results above. Health/provider-specific enablement and other external integrations are limited to the prerequisites and supported actions explicitly exercised by the tests.

This report pins the verified implementation SHA and records the changed files, RED → GREEN and deliberate-clipping evidence, E3 repetitions, exact gate counts and paired captures. The accompanying reply identifies the later documentation-only branch head. Claude's acceptance or requested further changes determine the U06 disposition.

# Fixes2 — restore preview, curve semantics, lights and beeps

**Source base:** `1c67c0a28084238377c7675e6dc25d0444fe2c22`. **F4/F5 implementation:** `4ad8334443cdf5c3752bfd8ad752153843ee60e8`. **Execution date:** 25 October 2026. This record covers F4, F5 and the lights/beeps verification only. The unchanged design remains [the agreed Nocturne design](../superpowers/specs/2026-10-21-nocturne-design.md).

## F4 — a maximum-size restore preview

`RestoreOriginalDialogLayoutTest.everyOriginalStepAndTheCompleteWarningAreReachableWithBothActionsVisible` imports a real, uncertain matching original with 32 steps through the repository, opens the actual Settings preview, and exercises the dialogue at **320 dp wide / 640 dp high / 200% text**. It reuses `U06Fixture`, its `GraphLifecycle`, native Robolectric graphics, full text-layout checks, unclipped bounds and 48 dp action checks. The four cases cover Midnight Ink, Daylight and Simple view with each palette.

Before the production change, all four cases failed with the same inaccessible final step:

```text
The 32nd original step is inaccessible without a vertical scroll route:
visible=Rect.fromLTRB(0.0, 0.0, 0.0, 0.0), size=544 x 0, position=Offset(48.0, 946.0)
```

This is an observed missing scroll route and zero-height final step, rather than a claim based on the former one-step fixture. The fix adds `verticalScroll(rememberScrollState())` to the restore dialogue's text-body `Column`. The title and action buttons stay in their existing Material dialogue slots. The regression test reaches step 32, scrolls back to the complete uncertainty warning, checks all 32 steps, and checks the title and both buttons at the beginning and end of that navigation. The existing six `UncertainOriginalsSettingsTest` assertions remain unchanged.

## F5 — available controls in curve descriptions

The existing `NightCurveAlternativeTest` previously required a fan value for both Off and Pause. Its two non-temperature cases now require this independently specified description, in Celsius and Fahrenheit preferences:

```text
Night plan from 22:30 to 22:43; wake 22:43. 22:30 Start Off, 1 minute; 22:31 Middle Turbo, fan 100%, 10 minutes; 22:41 Wake-up Pause, 2 minutes
```

Both cases failed before the production change because the actual Off and Pause clauses included `fan 5%`. The two existing temperature/unit controls passed in that same RED run. The fix selects the curve-description resource by `usesTemperature`, then `usesFan`, then the mode-and-duration-only resource. The new singular/plural text is in `res/values/strings_gaps_visual.xml`. Turbo retains its fan description. Canvas rendering and the separate Simple-view timeline are unchanged.

## Public BedJet 3 protocol verification

The public implementations were retrieved on 25 October 2026. Their documented mappings agree with Nocturne's existing mappings; **no command constant, status-bit polarity or Remote behaviour correction was needed**.

Primary sources:

- [Lull `Codec.swift`, immutable commit `f98821a6f53cbe80c00fc1ba11396a9433b322f0`, command lines 33–34](https://github.com/rkanjani/lull/blob/f98821a6f53cbe80c00fc1ba11396a9433b322f0/BedJetKit/Sources/BedJetKit/Codec.swift#L33-L34): LED enable/disable and mute/unmute packets.
- [The same Lull decoder, lines 137–142](https://github.com/rkanjani/lull/blob/f98821a6f53cbe80c00fc1ba11396a9433b322f0/BedJetKit/Sources/BedJetKit/Codec.swift#L137-L142): byte 27 masks for enabled lights and muted beeps.
- [ESPHome `bedjet_codec.h`, immutable commit `06bd92c3880c575a366f7b8e6fb77f74fd200937`, lines 103–115](https://github.com/esphome/esphome/blob/06bd92c3880c575a366f7b8e6fb77f74fd200937/esphome/components/bedjet/bedjet_codec.h#L103-L115): independent documentation of the same byte-27 bit meanings. The [published ESPHome API documentation](https://api-docs.esphome.io/bedjet__codec_8h) also identifies these flags. ESPHome is corroboration for status bits here; Lull is the command-byte source.

| Requested behaviour | Literal command | Matching status at byte 27 | Remote checked state |
|---|---|---|---|
| Lights on | `01 46` | `0x10` set | Lights on |
| Lights off | `01 47` | `0x10` clear | Lights off |
| Beeps on / unmuted | `01 49` | `0x01` clear | Beeps on |
| Beeps off / muted | `01 48` | `0x01` set | Beeps off |

`LightsAndBeepsProtocolTest` asserts all four literal commands and eight literal flag-byte cases: `00`, `10`, `01`, `11`, `ee`, `fe`, `ef`, `ff`. Expectations are explicit booleans, independent of Nocturne's bit expressions; unrelated bits and the other accessory cannot change the expected state.

`RemoteAccessorySwitchTest` uses the actual Activity, Remote screen, view model, command owner and codec. Its transport double records outbound bytes but does not infer replies from them. Each of the four directions checks the accessible **Lights** or **Beeps** switch state, the literal outbound packet, continued pending work after a fresh report changes only the other accessory, and a confirmed outcome with the expected label after the matching report. It waits for actual switch semantics before completing. Thus **Beeps on** is verified as `!beepsMuted`, with a request for `setMuted(false)` and a predicate requiring the mute bit to clear.

These are verification tests for behaviour that was already correct. The first complete command/flag/confirmation execution was **GREEN**; the earlier navigation-fixture failure is recorded separately below. There was no manufactured production RED and no protocol/Remote production edit. The [phone-test guide](phone-test-guide.md) adds separate off-to-on and on-to-off rows for both settings, requiring physical observation and switch/outcome agreement. All four rows remain **NOT RUN**.

## Executed evidence

All Gradle executions use the shared serial runner, Java 17, the existing offline caches, `--no-daemon`, `--offline`, `--max-workers=2`, a 2 GiB Gradle heap and in-process Kotlin compilation. No signing settings are changed.

| Run label | Targeted suites | Observed result |
|---|---|---|
| `ui-f4-f5-red` | `NightCurveAlternativeTest`, `RestoreOriginalDialogLayoutTest` | **8 tests, 6 failures, 0 errors, 0 skipped**: two curve expectation failures and four inaccessible-final-step failures. Production unchanged at this point. |
| `ui-f4-f5-green` | Above, plus unchanged `UncertainOriginalsSettingsTest` | **14 passed, 0 failures/errors/skips**: 4 curve cases, 4 maximum-size dialogue cases and the unchanged 6 uncertainty cases. |
| `ui-protocol-initial` | `LightsAndBeepsProtocolTest`, `RemoteAccessorySwitchTest` | **4 test-fixture selector failures** before a switch command ran: the 411 dp Remote tab exposes text, whereas the initial selector expected compact-navigation content description. The core test task did not execute before Gradle stopped. This is not a protocol/Remote production RED. |
| `ui-protocol-verification` | `LightsAndBeepsProtocolTest`, `RemoteAccessorySwitchTest` | **16 passed, 0 failures/errors/skips**: 12 core command/bit cases and 4 Remote direction/confirmation cases. Only the test's navigation selector changed after the preceding fixture failure; protocol and Remote production code are unchanged. |

Recorded targeted commands, run from the checkout. Use a new label for another execution so its evidence does not replace these runs:

```sh
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py --project /workspace/scratch/6cc8f67848d1/nocturne-ui-work --label ui-f4-f5-red -- :app:testDebugUnitTest --tests '*NightCurveAlternativeTest*' --tests '*RestoreOriginalDialogLayoutTest*'
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py --project /workspace/scratch/6cc8f67848d1/nocturne-ui-work --label ui-f4-f5-green -- :app:testDebugUnitTest --tests '*NightCurveAlternativeTest*' --tests '*RestoreOriginalDialogLayoutTest*' --tests '*UncertainOriginalsSettingsTest*'
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py --project /workspace/scratch/6cc8f67848d1/nocturne-ui-work --label ui-protocol-initial -- :core:test --tests '*LightsAndBeepsProtocolTest*' :app:testDebugUnitTest --tests '*RemoteAccessorySwitchTest*'
python /workspace/scratch/6cc8f67848d1/run_nocturne_gradle.py --project /workspace/scratch/6cc8f67848d1/nocturne-ui-work --label ui-protocol-verification -- :core:test --tests '*LightsAndBeepsProtocolTest*' :app:testDebugUnitTest --tests '*RemoteAccessorySwitchTest*'
```

Each run's log, exact invocation/result JSON and copied JUnit XML are retained under `/workspace/scratch/6cc8f67848d1/fixes2-evidence/<run-label>/`. Successful runs still emit the existing Android API/icon and Gradle deprecation warnings and a Kotlin daemon-session notice. The RED command describes the pre-fix checkpoint and is not expected to fail against the fixed source. The full integration gate belongs to the final fixes2 verification; targeted results here do not claim that gate passed.

## Remaining acceptance boundary

Robolectric measurements and semantic assertions are host evidence. They do not establish TalkBack behaviour on a device, Samsung rendering, physical light or sound behaviour, radio timing or battery use. Phone and BedJet checks remain **NOT RUN**.

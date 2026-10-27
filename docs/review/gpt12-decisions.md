# GPT-12 implementation decisions

This working record accompanies the acceptance prompt in `gpt12-request.md`. It is not a device-verification or release-sign-off claim.

## Source and signing boundary

- Base: `e61b8b4ffec1653b9136b0ef184dd2bdf558a08c`, tree `562b8809ea848c47c8fa05b3f9376a211ab38990`.
- Work branch: `gpt/nocturne-final`. The requested PR must remain open; only the user merges it.
- The required GPT-11 certificate is `42efc6e2284a00ae55345479177e27c45242196c02acb0f51640b886f7c2bde9`.
- The earlier signing evidence has been recovered. Its archive explicitly excludes private keys. The matching private test key has not been found; the public certificate cannot recreate it. No replacement test identity may be presented as an update-compatible build.
- Production signing configuration and release verification remain protected. Removing the in-app updater does not remove build-time signing checks.

## Wake display and scheduling

The Tonight headline's next accepted Android alarm is distinct from the programme's confirmed wake time. Showing a new alarm must not imply that an on-device programme has been rewritten. A user-supplied tonight override takes precedence. Existing alarm source acceptance, the 1–18 hour window and its explanation remain in force.

Before-wake Auto-start must capture the wake used to calculate its start time, revalidate it before admission, and pass that same wake into the actual prepared programme. It must not silently start a fixed-profile programme with a different end time. The captured automatic wake must have truthful provenance, separate from a user-entered tonight override.

## Presentation and protocol boundaries

The protocol's actual and ambient temperatures are integer half-degrees Celsius. A one-unit change therefore meets the 0.5 °C threshold. The 30-second cadence applies to those displayed sensors; mode, target temperature, fan, remaining time and receipt identity remain on their immediate path. There is no new status polling.

The running fan protocol has twenty steps, from 5% to 100%. The requested 0% scale reference must not send an unsupported zero-fan command. Slider labels and accessible values must express the actual bounded value.

Manual runtime is bounded by the status packet's `max_hrs` and `max_mins`, with the independent Turbo maximum of ten minutes. The stored-programme step limit is a different constraint and must not be substituted for the manual runtime limit. The existing Temporary programme safeguards still apply when preserving a tracked night's wake phase.

Primary protocol references inspected for this pass:

- [ESPHome BedJet status packet fields](https://api-docs.esphome.io/bedjet__codec_8h): half-degree sensor representation, fan steps, mode temperature bounds and runtime maximum fields.
- [ESPHome BedJet mode and command definitions](https://api-docs.esphome.io/bedjet__const_8h_source): Turbo's ten-minute limit and the distinct mode-dependent runtime limits.

## Test evidence conventions

Only an executed assertion failure counts as RED. Missing JDK/SDK files, TLS trust, Robolectric image downloads, test compilation failures and invalid JUnit fixture signatures are infrastructure or fixture errors, not behavioural evidence.

The restored toolchain uses Java 17, the project's unchanged Gradle 8.14.3 wrapper and the pinned Android dependencies. Network access was used to populate the missing caches; the final required gate must use `--offline` exactly as requested. Independent worktrees may use two serial build lanes, each with two Gradle workers and the requested 2 GiB heap. The same worktree must never build concurrently.

Baseline lint was run on the unmodified product source at the specified base: **0 errors, 23 warnings**, plus one hint. Version-availability warnings reflect the inspection environment; the final comparison must check for new findings, not merely compare a historical warning count from GPT-11.

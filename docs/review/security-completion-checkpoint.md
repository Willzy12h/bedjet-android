# Security implementation checkpoint

Source implementation: `92bc8f68ff57802e821aca724a2ca0bb12c68998` on `gpt/nocturne-final`. PR #4 remains open and unmerged.

## Implemented

- APP-01: final command-boundary mode, temperature, fan and duration policy, including effective clock-ending durations and Turbo at most ten minutes. Unsupported originals remain inspectable but cannot be replayed. Manual requests are preflighted before changing mode.
- APP-02: semantic packet checks before publication and fragment association; malformed ranges cannot mint a new confirmation receipt. Defensive slider rendering and a full Remote composition test cover the UI boundary.
- LIB-01: fail-closed legacy Health Connect containment. This build removes the legacy exported Binder service and refuses pre-Android-14 client creation, reads and exports. Android 14+ uses the native platform client. The dependency itself is not claimed fixed: the tested alpha06 upgrade needs a newer SDK/AGP toolchain. Saved consent remains intact.

## Completion regression round

The earlier full run40483749235 exposed22 app regressions. Source/fixture corrections preserve the safety policy rather than restoring unsafe writes:

- Non-heating Standby fan preferences remain supported, with fan bounds and running-mode duration checks.
- Start-barrier fixtures answer the newly mandatory read-only safe-programme query, without releasing held clock/start writes or weakening receipt assertions.
- Cancellation and foreign-receipt fixtures use valid mode temperatures.
- The invalid-live-setpoint test now arranges a physical-remote change in the fake peripheral. It checks the specific refusal, zero writes and unchanged night/runtime, rather than using Nocturne to emit an unsafe setpoint.

Verification run: https://github.com/Willzy12h/bedjet-android/actions/runs/40491695781 . Both the unsigned read-only verification and exact-head app/core-only fast-forward publication succeeded. The verification runs the complete core suite plus security, GATT, cancellation, runtime ownership, send confirmation, truthful-state and duration-preservation cases.

The preceding failed runs remain failures; compile or fixture problems are not described as behavioural RED. All temporary patch/publisher files are removed at this checkpoint. No production-signing configuration or secret value was accessed.

## Still requiring the final release gate

The normal full app/debug/lint, unsigned release, serialization/R8, merged-manifest, screenshots and permanent TEST APK verification run from this cleanup commit. Passing focused tests alone is not a release approval. The subsequent UAT handover must report its actual gate and signature results, not inherit an earlier APK claim.

No physical BedJet, Samsung Clock creator value, real Health Connect provider I/O, Binder exploit or TalkBack verification has been executed. The actual Samsung creator value remains unknown until captured on the user's phone.

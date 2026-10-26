# Temporary actions during a night

## Request and scope

The owner asked for Quick actions and related controls to offer a temporary Turbo or Heat action followed by the running night, while retaining the existing choice to end the night. This extends the fixes2 work; the earlier fixes/reconnect gate does not verify this feature.

The implementation keeps the current appearance and navigation. The choice is available for Tonight's Boost/Warm/Cool actions, Remote mode actions and Boost, and the corresponding widget and shortcut actions. Off always remains an immediate target-bound ending command. Wake notification Snooze/Extend actions keep their existing manual behaviour: they operate at or after wake-up, when there is no pre-wake programme to return to. Sliders, Run for, Lights and Beeps keep their existing meanings.

## Behaviour and wording

When a confirmed programme is running, the action dialogue offers **Temporarily, then resume** and **End tonight**. It shows the actual captured mode, temperature/fan where applicable, duration, planned return and original wake. Saved Warm/Cool labels can hold another mode, so the actual preset determines this text. Temporary is the initial choice when eligible; the user can select End tonight to retain the current behaviour. Dismissal sends nothing.

Quick actions start with their saved duration. Turbo starts at 10 minutes; Boost keeps its existing Turbo-then-Heat duration. Remote mode actions start at 10 minutes and use the current reported temperature and fan, clamped to the selected mode and profile limits; Turbo uses its fixed 43 °C setting. The dialogue can change this one action's duration without changing saved presets. Turbo alone is limited to 10 minutes; Boost may continue as Heat. Controls and explanatory text use string resources and UK English.

A temporary action replaces the current interval of the night. The original wake remains fixed. At the planned return, the programme continues at the point the original schedule has reached; elapsed phases are skipped. This is not a pause that delays wake-up. The running-night view says that a return is **planned**. A phone clock crossing the return time never creates a newly confirmed resume event.

## Architecture decision

Use one composite BedJet programme: temporary steps followed by the remaining original steps. Upload and start it through the existing NightActions owner, mutex, backup/readback and guarded start path. Do not schedule a second phone command to resume. This lets the stored programme continue without an activity or phone connection, and avoids a delayed phone timer turning the unit back on after physical Off.

Two alternatives were considered: a phone alarm that issues Resume at the end, and a manual temporary command with an explicit later Resume tap. The former cannot safely distinguish physical Off from normal expiry while disconnected; the latter does not meet the requested automatic return.

The composite programme has the same night identity, with a new revision. `ActiveNight` gains optional serialisable temporary metadata containing the immutable base programme and the expanded temporary timeline. The applied snapshot remains the exact composite sent to the unit. A new `ChangeKind.TEMPORARY` and `Block.TEMPORARY` distinguish the action from starting or ending a night. Optional fields retain old journal readability.

The immutable base is retained so another temporary action can replace the first without losing the underlying schedule. Repeated actions never reconstruct a night from subsequently edited live settings. Ending the night keeps the current bounded explicit Resume offer, based on the underlying night rather than restarting a temporary action.

## Timing and eligibility

The owner requires a captured target and exact night/revision, a confirmed non-manual active programme with an immutable snapshot and profile, and enough pre-wake time for the complete temporary action and a safe return. Pending, failed, unknown, ended, manual, missing-snapshot and stale requests are refused before writes. End tonight remains available.

The planner preserves absolute times in the remaining base schedule, omits elapsed and already-started original Turbo steps, inserts Pause gaps where necessary, and rebuilds clock-time bytes against the new clock-sync basis. It validates the complete 1–32-step programme after splitting long Heat/Turbo phases and inserting gaps. It retains the existing clock-time write margin and clock-offset checks. Initial durations that cannot fit are refused rather than silently shortened. Timing is minute-based, as in existing BedJet programmes.

Before backup/write and again after upload/clock sync, recheck the original and intended wake-up boundaries, request lifetime, temporary phase boundaries, target/identity and takeover status. Upload must not send an expired Turbo phase or a clock-time step that would wrap to tomorrow. Receipt-based confirmation and the fresh-read fallback remain authoritative. The timeouts remain UI 115 seconds > service 100 seconds > owner 90 seconds.

An unchanged alarm observation remains quiet and local. A genuinely changed accepted wake replans the immutable base while preserving only the unelapsed temporary timeline; it never starts another full Turbo interval or extends the temporary return because the alarm moved later. If a new earlier wake conflicts, the planner may shorten/remove the remaining temporary interval to honour wake-up and the result must explain that change. Unsafe rewrites use the existing refusal/retry/expiry rules. Pending alarm observations must survive the revision change.

## Durable state and cancellation

The full intended composite and metadata are saved in PENDING before any possible write. A failure/cancellation before a write restores the previous state for that exact request. A possible write settles UNKNOWN durably; confirmed publication stays confirmed even if later bookkeeping is interrupted. Cleanup is bounded and NonCancellable, then cancellation is rethrown. A regression must cover cancellation from the first durable PENDING writer itself, which currently sits outside the core apply guard.

There is no later automatic phone START. Physical Off or another programme can stop/replace the stored continuation. A physical fan/temperature adjustment may leave the unit's programme running, so documentation must not promise that every physical control cancels the return. Stop tracking remains local and does not stop the stored programme.

## UI and dispatch contracts

Use a typed captured request, not an opaque callback plus a second interpretation of `replacementConfirmed`. Add `Command.TEMPORARY` and an immutable `ActionPreset` payload to `CommandRequest`. The service serialises/validates this payload and rejects missing or invalid input. Temporary always requires the captured night identity. Origins remain APP/WIDGET/SHORTCUT as appropriate, preserving in-app-only haptics.

The existing Compose guard may accept an optional temporary-action descriptor. Only eligible quick/mode routes supply it; recovery/restore and ordinary replacement prompts remain unchanged. Native widget/shortcut dialogues use the same preset/eligibility model and an explicit two-choice selection. No additional fourth native action button is required. Last-reported cached Remote data remains visible on reconnect; temporary execution still requires fresh readiness under the owner.

## Verification and device limits

Observe RED before product changes for planner/model behaviour, owner/service integration and actual UI choice/dispatch. Verify repeated actions, programme capacity, Turbo boundaries, DST/clock offsets, unchanged/changed alarms, queued stale identity/target, before/after-write cancellation, confirmation fallback, explicit ending and background/no-phone-return behaviour. UI tests cover the actual dialogue at 320 dp and 200% text, both choices and dismissal, plus existing Remote control regressions.

Run the exact fixes2 gate and release assembly again after integration. Report final XML counts and compare lint findings to the original baseline. Add phone-guide rows for temporary Turbo/Heat, return while the app is away, original wake, physical Off and repeated actions. These are instructions for future physical testing, not claims of BedJet, Samsung or TalkBack verification.

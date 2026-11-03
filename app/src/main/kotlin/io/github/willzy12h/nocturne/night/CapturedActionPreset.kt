package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset

/** Shared input boundary for UI captures and serialised service requests; saved profile limits apply later. */
internal fun validCapturedPreset(command: NightActions.Command, preset: ActionPreset): Boolean {
    val modes = setOf(Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY, Mode.TURBO)
    if (preset.mode !in modes || preset.temperature !in 38..86 || preset.fanPercent !in 5..100) return false
    return when (command) {
        NightActions.Command.TEMPORARY -> preset.minutes in 1..(if (preset.mode == Mode.TURBO) 30 else 120)
        NightActions.Command.TURBO -> preset.mode == Mode.TURBO && preset.minutes in 1..10
        NightActions.Command.BOOST -> preset.mode == Mode.TURBO && preset.minutes in 1..30
        NightActions.Command.WARM, NightActions.Command.COOL -> preset.mode != Mode.TURBO && preset.minutes in 1..120
        else -> false
    }
}

internal fun NightActions.Command.usesCapturedPreset(): Boolean = this in setOf(
    NightActions.Command.TEMPORARY, NightActions.Command.TURBO, NightActions.Command.BOOST,
    NightActions.Command.WARM, NightActions.Command.COOL,
)

/** Snooze/Extend send one manual run; they do not append Boost's Heat continuation. */
internal fun timedPresetRefusal(context: Context, preset: ActionPreset): String? =
    if (preset.mode == Mode.TURBO && preset.minutes > Limits.TURBO_MINUTES)
        context.resources.getQuantityString(R.plurals.runtime_turbo_duration_limit,
            Limits.TURBO_MINUTES, Limits.TURBO_MINUTES)
    else null

/** A saved Warm label may hold another mode, and a one-action duration need not be the legacy default. */
internal fun capturedPresetMessage(context: Context, preset: ActionPreset): String {
    val mode = context.getString(when (preset.mode) {
        Mode.HEAT -> R.string.mode_heat
        Mode.EXTENDED_HEAT -> R.string.mode_long_heat
        Mode.COOL -> R.string.mode_cool
        Mode.DRY -> R.string.mode_dry
        Mode.TURBO -> R.string.mode_turbo
        Mode.STANDBY -> R.string.mode_off
        Mode.WAIT -> R.string.mode_pause
    })
    return context.resources.getQuantityString(R.plurals.runtime_captured_action_duration, preset.minutes, mode, preset.minutes)
}

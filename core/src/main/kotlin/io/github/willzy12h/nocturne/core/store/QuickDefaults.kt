package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.protocol.CommandSafety
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep

/** Additive overrides: legacy Warm/Cool values are read losslessly, never rewritten on upgrade. */
object QuickDefaults {
    val modes: List<Mode> = listOf(Mode.TURBO, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY)

    fun builtIn(mode: Mode): ActionPreset = when (mode) {
        Mode.TURBO -> ActionPreset(mode, 86, 100, 10)
        Mode.HEAT -> ActionPreset(mode, 64, 30, 30)
        Mode.EXTENDED_HEAT -> ActionPreset(mode, 60, 30, 30)
        // Cool runs for as long as the BedJet allows (11 h 59 min, shown as 12 hours) until it is customised.
        Mode.COOL -> ActionPreset(mode, 44, 40, Limits.MAX_STEP_MINUTES)
        Mode.DRY -> ActionPreset(mode, 56, 40, 30)
        else -> error("Off has no running preset")
    }

    fun resolve(settings: Settings, mode: Mode): ActionPreset {
        require(mode in modes)
        return settings.quickDefaults.firstOrNull { it.mode == mode }
            ?: listOf(settings.extendWarm, settings.extendCool.takeIf { it != LEGACY_COOL }).firstOrNull { it?.mode == mode }
            ?: builtIn(mode)
    }

    /** An untouched Extend Cool still holds this; only a customised one stands in for the Cool quick default. */
    private val LEGACY_COOL = Settings().extendCool

    /** The longest run the BedJet allows for these settings: Cool and Dry 11 h 59 min, Turbo 10 min, hot Heat less. */
    fun maximumMinutes(preset: ActionPreset): Int =
        ProgramStep.longestMinutes(preset.mode, preset.temperature, (preset.fanPercent / 5 - 1).coerceIn(0, 19))

    /** A longer time than the settings allow becomes the longest they allow. */
    fun clampMinutes(preset: ActionPreset): ActionPreset =
        preset.copy(minutes = preset.minutes.coerceIn(1, maximumMinutes(preset).coerceAtLeast(1)))

    fun valid(preset: ActionPreset): Boolean = preset.mode in modes && preset.fanPercent in 5..100 &&
        preset.fanPercent % 5 == 0 && preset.minutes in 1..maximumMinutes(preset) &&
        CommandSafety.manual(preset.mode, preset.temperature, preset.fanPercent / 5 - 1, preset.minutes) == null

    fun withPreset(settings: Settings, preset: ActionPreset): Settings {
        require(valid(preset)) { "QUICK_DEFAULT_INVALID" }
        return settings.copy(quickDefaults = settings.quickDefaults.filterNot { it.mode == preset.mode } + preset)
    }

    fun validOverrides(presets: List<ActionPreset>): Boolean = presets.size <= modes.size &&
        presets.map { it.mode }.distinct().size == presets.size && presets.all(::valid)
}

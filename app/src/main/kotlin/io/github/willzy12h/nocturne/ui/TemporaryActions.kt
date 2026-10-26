package io.github.willzy12h.nocturne.ui

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.TemporaryActionPreview
import io.github.willzy12h.nocturne.night.boostDescription

/** One reviewed action. Changing minutes never recaptures its target, night or preset. */
internal data class TemporaryChoice(val request: CommandRequest, val maximumMinutes: Int,
    val endPreset: ActionPreset? = request.actionPreset, val boost: Boolean = false) {
    val preset: ActionPreset get() = requireNotNull(request.actionPreset)
    fun forMinutes(minutes: Int): CommandRequest = request.copy(actionPreset = preset.copy(minutes = minutes))
}

internal data class CapturedQuickAction(val temporary: TemporaryChoice, val end: CommandRequest)

/** Native entries pass their rendered identity; the app passes its current tap capture. */
internal fun captureQuickAction(graph: Graph, request: CommandRequest): CapturedQuickAction? {
    if (request.command !in setOf(Command.TURBO, Command.BOOST, Command.WARM, Command.COOL)) return null
    val settings = graph.repository.settings.value
    val preset = request.actionPreset ?: when (request.command) {
        Command.TURBO -> ActionPreset(Mode.TURBO, 86, 100, 10)
        Command.BOOST -> ActionPreset(Mode.TURBO, 86, 100, settings.boostMinutes)
        Command.WARM -> settings.extendWarm
        Command.COOL -> settings.extendCool
        else -> return null
    }
    val maximum = when {
        request.command == Command.BOOST -> 30
        preset.mode == Mode.TURBO -> 10
        else -> 120
    }
    val captured = request.copy(requireIdentity = true, actionPreset = preset)
    val temporaryPreset = preset.copy(minutes = preset.minutes.coerceIn(1, maximum))
    return CapturedQuickAction(
        TemporaryChoice(captured.copy(command = Command.TEMPORARY, replacementConfirmed = false,
            actionPreset = temporaryPreset), maximum, endPreset = preset, boost = request.command == Command.BOOST),
        // With no dialogue/night, retain the existing quick-action behaviour and feedback.
        captured.copy(replacementConfirmed = true, actionPreset = preset.takeIf { captured.expected != null }),
    )
}

internal fun captureRemoteTemporary(graph: Graph, mode: Mode, status: DeviceStatus): TemporaryChoice {
    // Preparation owns clamping and typed refusals. A mode outside saved limits must never throw on the UI tap.
    val preset = ActionPreset(mode,
        if (mode == Mode.TURBO) 86 else status.targetTemperature, status.fanPercent, 10)
    return TemporaryChoice(graph.actions.captureTemporary(preset), if (mode == Mode.TURBO) 10 else 120, endPreset = null)
}

/** End uses the captured request; its owner still applies the saved profile limits before sending. */
internal fun requestedEndDetail(context: Context, choice: TemporaryChoice, fahrenheit: Boolean): String? = choice.endPreset?.let {
    val detail = if (choice.boost) boostDescription(context, it.minutes) else presetDetail(context, it, fahrenheit)
    context.getString(R.string.temporary_requested, detail)
}

/** Formats the owner's actual split/clamped temporary prefix, never the future programme as an action preset. */
internal fun temporaryDetails(context: Context, request: CommandRequest, preview: TemporaryActionPreview,
    fahrenheit: Boolean): List<String> {
    val steps = preview.plan?.steps?.filter { it.block == Block.TEMPORARY }
    return if (!steps.isNullOrEmpty()) steps.map { step ->
        presetDetail(context, ActionPreset(step.step.mode, step.step.temperature,
            (step.step.fanStep + 1) * 5, step.minutes), fahrenheit)
    } else listOfNotNull(request.actionPreset?.let {
        val detail = if (it.mode == Mode.TURBO && it.minutes > 10) boostDescription(context, it.minutes)
            else presetDetail(context, it, fahrenheit)
        context.getString(R.string.temporary_requested, detail)
    })
}

internal fun presetDetail(context: Context, preset: ActionPreset, fahrenheit: Boolean): String {
    val words = Format.Words { id, args -> context.getString(id, *args) }
    val mode = Format.modeName(preset.mode, words)
    val duration = Format.duration(preset.minutes, words)
    return when {
        preset.mode.usesTemperature -> context.getString(R.string.temporary_preset_temperature, mode,
            Format.temp(preset.temperature, fahrenheit, words), preset.fanPercent, duration)
        preset.mode.usesFan -> context.getString(R.string.temporary_preset_fan, mode, preset.fanPercent, duration)
        else -> context.getString(R.string.temporary_preset_time, mode, duration)
    }
}

internal fun temporaryReturn(context: Context, preview: TemporaryActionPreview): String? {
    val plan = preview.plan ?: return null
    val until = plan.steps.lastOrNull { it.block == Block.TEMPORARY }?.endsAt ?: return null
    return context.getString(R.string.temporary_return, Format.clock(until), Format.clock(plan.wake))
}

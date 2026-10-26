package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.SlotWrite

/** A unique control matching the reported state. A shared mode alone never identifies a Quick request. */
internal fun reportedQuickAction(status: DeviceStatus?, link: LinkState, settings: Settings, night: ActiveNight?,
    slotWrite: SlotWrite?, limits: ProfileLimits): Command? {
    val report = status?.takeIf { link == LinkState.READY } ?: return null
    if (report.mode == Mode.STANDBY) return Command.OFF
    if (night != null && !night.trackingManual) return null
    val progress = report.extra?.programStep ?: return null
    if (progress > 0) {
        // A cleared journal (including a displaced night) gives no continuing proof of Quick Boost identity.
        val write = slotWrite?.takeIf { night == null && it.confidence == Confidence.CONFIRMED && it.startRequested &&
            DeviceId.same(it.deviceId, settings.deviceId()) } ?: return null
        val programme = write.intended?.takeIf { it.name == "Boost" } ?: return null
        val configured = runCatching { PlanCompiler.phaseSteps(
            Phase(Mode.TURBO, Mode.TURBO.temperatureRange.first, 100, settings.boostMinutes), limits, TurboChain.TURBO_THEN_HEAT)
        }.getOrNull() ?: return null
        if (programme.steps != configured) return null
        val step = programme.steps.getOrNull(progress - 1) ?: return null
        return Command.BOOST.takeIf { report.mode == step.mode && report.fanStep == step.fanStep &&
            report.targetTemperature == step.temperature && report.remainingSeconds > 0 }
    }
    fun matches(preset: ActionPreset): Boolean = runCatching {
        report.mode == preset.mode && report.targetTemperature == PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits) &&
            report.fanStep == PlanCompiler.fanStepFor(preset.fanPercent, limits) &&
            report.remainingSeconds in 1..(preset.minutes * 60 + 120)
    }.getOrDefault(false)
    return listOfNotNull(Command.WARM.takeIf { matches(settings.extendWarm) },
        Command.COOL.takeIf { matches(settings.extendCool) }).singleOrNull()
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.NightActions.Command
import java.time.ZonedDateTime

/** Present device evidence and planned changes in the same way on both screens. */
@Composable
internal fun BedJetReadings(model: AppViewModel, settings: Settings, tag: String, modifier: Modifier = Modifier,
    keepTemperaturesWhileDisconnected: Boolean = true, sending: PresetSending? = null, showRunStatus: Boolean = true) {
    val reading by model.status.collectAsStateWithLifecycle()
    val live by model.hasLiveStatus.collectAsStateWithLifecycle()
    val link by model.linkState.collectAsStateWithLifecycle()
    val recent by model.readingFresh.collectAsStateWithLifecycle()
    val night by model.activeNight.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val nightStatus by model.nightStatus.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
    val latest = reading
    val currentConnection = live && link == LinkState.READY
    val current = currentConnection && (settings.demoMode || recent)
    val now = model.now()
    val target = settings.deviceId()
    val limits = settings.resolveProfile(profiles, now)?.limits ?: ProfileLimits()
    val isBoost = current && reportedQuickAction(latest, link, settings, night, runtime.slotWrite, limits) == Command.BOOST
    // Only applied steps carry the device programme's origin. An editable-profile fallback is no evidence.
    val plan = remember(night, target, nightStatus.confidence) {
        night?.takeIf { !it.trackingManual && it.pending == null && DeviceId.same(it.deviceId, target) &&
            it.confidence == Confidence.CONFIRMED && nightStatus.confidence == Confidence.CONFIRMED }
            ?.let { runCatching { it.applied.plan() }.getOrNull() }
    }
    val verifiedStep = if (latest != null && current) plan?.let { reportedStepIndex(it, latest, now) } else null
    val nextStep = if (verifiedStep != null && plan != null && latest != null)
        nextReportedStep(plan, latest, verifiedStep, now) else null
    val reportedEnd = remember(target, latest?.mode, latest?.remainingSeconds) {
        latest?.remainingSeconds?.takeIf { it > 0 }?.let { now.plusSeconds(it.toLong()) }
    }
    val tokens = paletteTokens
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (latest != null && (currentConnection || keepTemperaturesWhileDisconnected))
            MeasuredTemperatures(latest, settings, current && recent, tag, isBoost)
        else if (!keepTemperaturesWhileDisconnected)
            Text(stringResource(R.string.home_reading_unavailable), modifier = Modifier.testTag("$tag-reading-context"),
                color = tokens.ink2, style = MaterialTheme.typography.bodyMedium)

        val detail = when {
            !current -> plan?.end?.takeIf { it.isAfter(now) }?.let { stringResource(R.string.run_status_planned_off, Format.clock(it)) }
            nextStep != null && latest != null -> nextChange(latest, nextStep.step, nextStep.startsAt, settings.fahrenheit)
            verifiedStep != null && plan != null -> stringResource(R.string.run_status_planned_off, Format.clock(plan.end))
            isBoost && latest != null && reportedEnd?.isAfter(now) == true -> {
                val next = runtime.slotWrite?.intended?.steps?.getOrNull(latest.extra?.programStep ?: 0)
                if (next != null) nextChange(latest, next, reportedEnd, settings.fahrenheit)
                else stringResource(R.string.run_status_planned_off, Format.clock(reportedEnd))
            }
            latest?.isRunning == true && latest.extra?.programStep == 0 && reportedEnd?.isAfter(now) == true ->
                stringResource(R.string.run_status_manual_until, Format.clock(reportedEnd))
            else -> null
        }
        val title = when {
            sending != null -> stringResource(R.string.run_status_sending, sending.title)
            latest == null -> null
            else -> observedMode(latest, current, isBoost)
        }
        if (showRunStatus && (title != null || detail != null)) Text(
            listOfNotNull(title, detail.takeIf { sending == null }).joinToString("\n"),
            modifier = Modifier.testTag(if (tag == "remote") "remote-status" else "home-run-status"),
            color = tokens.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** A receipt for another programme may agree with an old plan's clock while disagreeing with its hardware step. */
internal fun reportedStepIndex(plan: Plan, reading: DeviceStatus, now: ZonedDateTime): Int? {
    if (!reading.isRunning) return null
    val received = reading.extra?.programStep?.minus(1) ?: return null
    val timed = plan.stepAt(now)?.first ?: return null
    return received.takeIf { it == timed && plan.steps[timed].step.matches(reading) }
}

internal fun nextReportedStep(plan: Plan, reading: DeviceStatus, verifiedStep: Int, now: ZonedDateTime): PlannedStep? =
    plan.steps.drop(verifiedStep + 1).firstOrNull { it.startsAt.isAfter(now) && !it.step.matches(reading) }

private fun ProgramStep.matches(reading: DeviceStatus): Boolean = mode == reading.mode &&
    (!mode.usesFan || fanStep == reading.fanStep) &&
    (!mode.usesTemperature || temperature == reading.targetTemperature)

@Composable
internal fun nextChange(reading: DeviceStatus, next: ProgramStep, at: ZonedDateTime, fahrenheit: Boolean): String {
    val time = Format.clock(at)
    if (next.mode == reading.mode && next.mode.usesFan && next.fanStep != reading.fanStep &&
        (!next.mode.usesTemperature || next.temperature == reading.targetTemperature)) {
        return stringResource(if (next.fanStep > reading.fanStep) R.string.run_status_fan_rises else R.string.run_status_fan_falls,
            (next.fanStep + 1) * 5, time)
    }
    val mode = modeName(next.mode)
    val target = if (next.mode.usesTemperature) "$mode " + tempText(next.temperature, fahrenheit) else mode
    return stringResource(R.string.run_status_next, target, time)
}

@Composable
internal fun observedMode(reading: DeviceStatus, current: Boolean, boost: Boolean): String {
    val mode = modeName(reading.mode)
    val state = when {
        !current -> when (reading.mode) {
            Mode.COOL -> stringResource(R.string.run_status_last_cool)
            Mode.STANDBY -> stringResource(R.string.run_status_last_idle)
            else -> stringResource(R.string.run_status_last_mode, mode)
        }
        boost -> stringResource(R.string.run_status_boost, mode)
        else -> when (reading.mode) {
            Mode.STANDBY -> stringResource(R.string.home_idle)
            Mode.COOL -> stringResource(R.string.run_status_cool)
            Mode.HEAT -> stringResource(R.string.run_status_heat)
            else -> mode
        }
    }
    return if (reading.mode.usesFan && current) stringResource(R.string.run_status_with_fan, state, reading.fanPercent) else state
}

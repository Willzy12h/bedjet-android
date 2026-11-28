package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.NightStatus
import io.github.willzy12h.nocturne.night.RuntimeState
import java.time.ZonedDateTime

internal data class TonightRunningEvidence(
    val confirmedPlan: Plan? = null,
    val stepIndex: Int? = null,
    val currentReading: DeviceStatus? = null,
    val markerAt: ZonedDateTime? = null,
)

/** Only a matching live report can place the current step on a previously confirmed programme. */
internal fun tonightRunningEvidence(night: ActiveNight?, confidence: Confidence?, target: String?,
    currentReport: DeviceStatus?, now: ZonedDateTime): TonightRunningEvidence {
    val plan = night?.takeIf {
        it.actualEnd == null && !it.trackingManual && it.pending == null && it.confidence == Confidence.CONFIRMED &&
            confidence == Confidence.CONFIRMED && DeviceId.same(it.deviceId, target)
    }?.appliedPlan()
    val stepIndex = if (plan != null && currentReport != null) reportedStepIndex(plan, currentReport, now) else null
    return TonightRunningEvidence(plan, stepIndex, currentReport.takeIf { stepIndex != null }, now.takeIf { stepIndex != null })
}

@Composable
internal fun rememberTonightRunningEvidence(model: AppViewModel, night: ActiveNight?, status: NightStatus,
    settings: Settings, now: ZonedDateTime): TonightRunningEvidence {
    if (night == null) return TonightRunningEvidence()
    val reading by model.status.collectAsStateWithLifecycle()
    val live by model.hasLiveStatus.collectAsStateWithLifecycle()
    val link by model.linkState.collectAsStateWithLifecycle()
    val fresh by model.readingFresh.collectAsStateWithLifecycle()
    val current = reading.takeIf { live && link == LinkState.READY && (settings.demoMode || fresh) }
    // Reports can arrive immediately at a transition, between Tonight's clock ticks.
    return tonightRunningEvidence(night, status.confidence, settings.deviceId(), current, if (current != null) model.now() else now)
}

internal data class TonightPendingWakeChange(
    val requestedWake: ZonedDateTime?,
    val isPhoneAlarm: Boolean,
    val lastConfirmedPlan: Plan?,
)

internal fun pendingTonightWakeChange(night: ActiveNight, status: NightStatus, runtime: RuntimeState, target: String?,
    acceptedAlarm: ZonedDateTime?): TonightPendingWakeChange? {
    if (night.actualEnd != null || night.isNap || night.isTestRun || night.trackingManual ||
        !DeviceId.same(night.deviceId, target) || status.confidence !in setOf(Confidence.CONFIRMED, Confidence.PENDING)) return null
    val write = night.pending?.takeIf { it.kind == ChangeKind.RETIME && it.expected == night.identity }
    val priorProgramme = night.confidence == Confidence.CONFIRMED ||
        (night.confidence == Confidence.PENDING && write != null)
    if (!priorProgramme) return null
    val queued = runtime.retime?.takeIf { it.expected == night.identity && DeviceId.same(it.deviceId, target) }
    val observed = runtime.alarmInbox?.let { it.nightId == night.recordId && DeviceId.same(it.deviceId, target) } == true
    if (write == null && queued == null && !observed) return null
    val requested = (write?.requestedWake ?: queued?.requestedWakeAt ?: queued?.alarmAt).parsedTime()
    val alarm = acceptedAlarm?.takeUnless { night.wakeAt.parsedTime()?.isEqual(it) == true }
    val wake = requested ?: alarm.takeIf { observed && write == null && queued == null }
    return TonightPendingWakeChange(wake, wake != null && alarm?.isEqual(wake) == true, night.appliedPlan())
}

private fun ActiveNight.appliedPlan(): Plan? =
    runCatching { applied.plan() }.getOrNull()?.takeIf { it.steps.isNotEmpty() }

private fun String?.parsedTime(): ZonedDateTime? = this?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }

@Composable
internal fun TonightRunningStatus(night: ActiveNight, status: NightStatus, runtime: RuntimeState, settings: Settings,
    profileName: String, phoneAlarm: ZonedDateTime?, evidence: TonightRunningEvidence, now: ZonedDateTime, showReading: Boolean = true) {
    val sameTarget = DeviceId.same(night.deviceId, settings.deviceId())
    val confidence = (status.confidence ?: night.confidence).takeIf { sameTarget } ?: Confidence.UNKNOWN
    val pendingWake = pendingTonightWakeChange(night, status, runtime, settings.deviceId(), phoneAlarm)
    val pendingStart = confidence == Confidence.PENDING && night.pending?.kind == ChangeKind.START
    val current = evidence.currentReading
    val headline = when {
        pendingStart -> R.string.tonight_running_start_pending
        pendingWake?.isPhoneAlarm == true -> R.string.tonight_running_alarm_pending
        pendingWake != null -> R.string.tonight_running_wake_pending
        current != null -> R.string.tonight_running_confirmed
        confidence == Confidence.PENDING -> R.string.tonight_state_pending
        confidence == Confidence.FAILED -> R.string.tonight_state_failed
        confidence == Confidence.UNKNOWN -> R.string.tonight_state_unknown
        night.trackingManual -> R.string.tonight_state_manual
        evidence.confirmedPlan?.start?.isAfter(now) == true -> R.string.tonight_running_scheduled
        else -> R.string.tonight_running_awaiting_status
    }
    val tokens = paletteTokens
    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(tokens.visible(if (current != null) tokens.accent else tokens.warn, tokens.surface), sizeDp = 8)
        Text(stringResource(headline), Modifier.testTag("tonight-running-state"), color = tokens.ink,
            style = MaterialTheme.typography.labelLarge)
    }
    val name = if (pendingStart) night.pending?.intended?.profile?.name ?: profileName else profileName
    Text(name, Modifier.semantics { heading() }, color = tokens.ink,
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (current != null && showReading) {
        val plan = requireNotNull(evidence.confirmedPlan)
        val step = requireNotNull(evidence.stepIndex)
        val next = nextReportedStep(plan, current, step, requireNotNull(evidence.markerAt))
        val summary = observedMode(current, current = true, boost = false)
        Text(listOfNotNull(summary, next?.let { nextChange(current, it.step, it.startsAt, settings.fahrenheit) }).joinToString("\n"),
            Modifier.testTag("home-run-status"), color = tokens.ink2, style = MaterialTheme.typography.bodyMedium)
    }
    if (pendingStart) Note(stringResource(R.string.tonight_running_start_waiting))
    if (pendingWake != null) {
        pendingWake.requestedWake?.let {
            TonightRunningFact(if (pendingWake.isPhoneAlarm) R.string.tonight_running_phone_alarm else R.string.tonight_running_requested_wake,
                Format.clock(it))
        }
        pendingWake.lastConfirmedPlan?.let {
            TonightRunningFact(R.string.tonight_running_last_wake, Format.clock(it.wake))
            TonightRunningFact(R.string.tonight_running_last_off, Format.clock(it.end))
        }
        Note(stringResource(R.string.tonight_running_wake_waiting))
    }
    status.reason?.takeIf { sameTarget && confidence != Confidence.CONFIRMED }?.let { Note(it) }
}

@Composable
internal fun TonightRunningFacts(night: ActiveNight, evidence: TonightRunningEvidence, settings: Settings,
    now: ZonedDateTime, onWake: (() -> Unit)?) {
    val plan = evidence.confirmedPlan?.takeIf { it.end.isAfter(now) } ?: return
    if (night.isTestRun) return
    val wakeSource = when {
        night.isNap -> null
        night.wakeOrigin == WakeOrigin.ALARM -> R.string.tonight_running_phone_alarm
        night.wakeOrigin == WakeOrigin.OVERRIDE -> R.string.gpt12_wake_override
        night.wakeOrigin == WakeOrigin.AUTO_START -> R.string.wake_origin_auto_start
        else -> R.string.gpt12_wake_profile_fallback
    }
    val wake = Format.clock(plan.wake)
    TonightRunningFact(if (night.isNap) R.string.nap_wake else R.string.tonight_running_wake,
        if (wakeSource == null) wake else stringResource(R.string.tonight_running_wake_source, wake, stringResource(wakeSource)),
        Modifier.testTag("tonight-fixed-wake").then(if (onWake == null) Modifier else
            Modifier.clickable(role = Role.Button, onClick = onWake).heightIn(min = 48.dp)))
    val afterWake = plan.steps.filter { it.block == Block.AFTER_WAKE }
    if (afterWake.isEmpty()) return
    val first = afterWake.first().step
    val mode = first.mode.takeIf { mode -> afterWake.all { it.step.mode == mode } }
    val off = Format.clock(plan.end)
    TonightRunningFact(R.string.tonight_running_after_wake, if (mode == null)
        stringResource(R.string.run_status_planned_off, off)
        else {
            val target = first.temperature.takeIf { mode.usesTemperature && afterWake.all { it.step.temperature == first.temperature } }
            val action = if (target == null) modeName(mode) else "${modeName(mode)} ${tempText(target, settings.fahrenheit)}"
            stringResource(R.string.tonight_running_after_wake_mode, action, off)
        })
}

@Composable
private fun TonightRunningFact(label: Int, value: String, modifier: Modifier = Modifier) {
    val tokens = paletteTokens
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), color = tokens.ink2, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), color = tokens.ink, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
internal fun TonightOffConfirmed(onDismiss: () -> Unit) {
    val tokens = paletteTokens
    Column(Modifier.fillMaxWidth().testTag("tonight-off-confirmed"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(tokens.visible(tokens.accent, tokens.surface), sizeDp = 8)
            Text(stringResource(R.string.tonight_running_off_confirmed), color = tokens.ink,
                style = MaterialTheme.typography.labelLarge)
        }
        Text(stringResource(R.string.tonight_running_night_ended), Modifier.semantics { heading() },
            color = tokens.ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Note(stringResource(R.string.tonight_running_bedjet_off))
        NocturneOutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.tonight_running_back)) }
    }
}

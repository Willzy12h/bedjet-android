package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.data.MiddleSuggestion
import io.github.willzy12h.nocturne.ui.features.HealthSleepOverlay
import io.github.willzy12h.nocturne.ui.features.HistoryExportButton
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One card per night, newest first: what was planned and everything that happened. */
@Composable
fun HistoryScreen(model: AppViewModel, modifier: Modifier) {
    val history by model.history.collectAsState()
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    val activeNight by model.activeNight.collectAsState()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
        item { HistoryExportButton(model.graph) }
        if (history.isEmpty()) item { Text("Nights you run appear here, with every change made during them.") }
        items(history.sortedByDescending { it.startedAt }, key = { it.id }) { night ->
            var open by remember { mutableStateOf(false) }
            var checkingIn by remember(night.id) { mutableStateOf(false) }
            var submitting by remember(night.id) { mutableStateOf(false) }
            var suggestion by remember(night.id) { mutableStateOf<MiddleSuggestion?>(null) }
            var showSuggestion by remember(night.id) { mutableStateOf(false) }
            val completed = night.events.any { it.kind == EventKind.ENDED || it.kind == EventKind.STOPPED }
            val checkedIn = night.events.any { it.kind == EventKind.MORNING_FEEDBACK }
            val availableSuggestion = if (checkedIn) model.morningCheckIn(night.id)?.suggestion else suggestion
            Card(Modifier.fillMaxWidth().clickable { open = !open }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val day = runCatching { ZonedDateTime.parse(night.startedAt).format(DateTimeFormatter.ofPattern("EEE d MMM")) }.getOrDefault("")
                    Text("$day · ${night.profileName}", style = MaterialTheme.typography.titleMedium)
                    Text("${Format.clock(night.startedAt)} → wake ${Format.clock(night.wakeAt)}" + if (night.wakeFromAlarm) " (alarm)" else "")
                    historyPlan(night)?.let { NightCurve(it) }
                    HealthSleepOverlay(model.graph, night)
                    if (completed && !checkedIn && activeNight?.recordId != night.id) {
                        OutlinedButton(onClick = { checkingIn = true }) { Text(stringResource(R.string.checkin_button)) }
                    }
                    night.events.lastOrNull { it.kind == EventKind.MORNING_FEEDBACK }?.let { feedback ->
                        Text(stringResource(R.string.checkin_saved, feedback.detail), style = MaterialTheme.typography.bodySmall)
                    }
                    if (availableSuggestion != null && !showSuggestion) {
                        TextButton(onClick = { showSuggestion = true }) { Text(stringResource(R.string.checkin_review)) }
                    }
                    if (open) {
                        night.events.forEach {
                            Text(stringResource(R.string.history_event, Format.clock(it.at), eventKindName(it.kind), it.detail), style = MaterialTheme.typography.bodySmall)
                        }
                        // Each programme the BedJet confirmed for this night, in order; only confirmed ones are recorded.
                        if (night.revisions.size > 1) {
                            night.revisions.forEachIndexed { index, revision ->
                                Text(stringResource(R.string.history_revision, index + 1, Format.clock(revision.wake),
                                    pluralStringResource(R.plurals.history_steps, revision.steps.size, revision.steps.size)),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(stringResource(R.string.history_curve_applied), style = MaterialTheme.typography.labelSmall)
                        }
                        night.steps.forEach { step ->
                            Text(
                                "${Format.clock(step.startsAt)}  ${blockName(step.block)}: ${Format.modeName(step.mode)}, " +
                                    (if (step.mode.usesTemperature) "${Format.temp(step.temperature, settings.fahrenheit)}, " else "") +
                                    "fan ${step.fanPercent}%, ${Format.duration(step.minutes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else if (night.events.size > 1) {
                        Text("${night.events.size} events · tap for details", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (checkingIn) AlertDialog(
                onDismissRequest = { if (!submitting) checkingIn = false },
                title = { Text(stringResource(R.string.checkin_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.checkin_help))
                        listOf(
                            MorningComfort.TOO_HOT to R.string.checkin_too_hot,
                            MorningComfort.JUST_RIGHT to R.string.checkin_just_right,
                            MorningComfort.TOO_COLD to R.string.checkin_too_cold,
                        ).forEach { (comfort, label) ->
                            OutlinedButton(enabled = !submitting, onClick = {
                                submitting = true
                                model.recordMorningCheckIn(night.id, comfort) { checkIn ->
                                    submitting = false
                                    checkingIn = false
                                    suggestion = checkIn?.suggestion
                                    showSuggestion = checkIn?.suggestion != null
                                }
                            }) { Text(stringResource(label)) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(enabled = !submitting, onClick = { checkingIn = false }) { Text(stringResource(R.string.feature_cancel)) } },
            )
            availableSuggestion?.takeIf { showSuggestion }?.let { proposal ->
                val profile = profiles.firstOrNull { it.id == proposal.profileId }
                AlertDialog(
                    onDismissRequest = { showSuggestion = false },
                    title = { Text(stringResource(R.string.checkin_change_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.checkin_change_intro, profile?.name ?: stringResource(R.string.checkin_this_profile),
                                stringResource(if (proposal.comfort == MorningComfort.TOO_HOT) R.string.checkin_lower else R.string.checkin_raise)))
                            Text(stringResource(R.string.checkin_start_change, Format.temp(proposal.expectedMiddle.tempStart, settings.fahrenheit),
                                Format.temp(proposal.proposedMiddle.tempStart, settings.fahrenheit)))
                            Text(stringResource(R.string.checkin_end_change, Format.temp(proposal.expectedMiddle.tempEnd, settings.fahrenheit),
                                Format.temp(proposal.proposedMiddle.tempEnd, settings.fahrenheit)))
                            Text(stringResource(R.string.checkin_future_only))
                        }
                    },
                    confirmButton = { Button(onClick = {
                        model.acceptMorningSuggestion(proposal) { showSuggestion = false; suggestion = null }
                    }) { Text(stringResource(R.string.checkin_apply)) } },
                    dismissButton = { TextButton(onClick = { showSuggestion = false }) { Text(stringResource(R.string.checkin_keep)) } },
                )
            }
        }
    }
}

private fun planOf(night: NightRecord): Plan? = runCatching {
    val steps = night.steps.map {
        PlannedStep(it.block, ProgramStep(it.mode, it.fanPercent / 5 - 1, it.temperature, it.minutes / 60, it.minutes % 60), ZonedDateTime.parse(it.startsAt), it.minutes)
    }
    if (steps.isEmpty()) null else Plan(ZonedDateTime.parse(night.startedAt), ZonedDateTime.parse(night.wakeAt), steps)
}.getOrNull()

/**
 * The plan the History card draws: the last revision the BedJet confirmed (after a re-time or "Update tonight"), so
 * the curve and the "→ wake" line agree; the recorded steps for a night with no revisions (U09).
 */
internal fun historyPlan(night: NightRecord): Plan? =
    night.revisions.lastOrNull()?.let { runCatching { it.plan() }.getOrNull() } ?: planOf(night)

@Composable
private fun eventKindName(kind: EventKind): String = stringResource(when (kind) {
    EventKind.STARTED -> R.string.event_started
    EventKind.RETIMED -> R.string.event_retimed
    EventKind.SNOOZED -> R.string.event_snoozed
    EventKind.EXTENDED -> R.string.event_extended
    EventKind.STOPPED -> R.string.event_stopped
    EventKind.ENDED -> R.string.event_ended
    EventKind.UNIT_NOTICE -> R.string.event_unit_notice
    EventKind.ERROR -> R.string.event_error
    EventKind.REMOTE_CHANGE -> R.string.event_remote_change
    EventKind.RECOVERED -> R.string.event_recovered
    EventKind.MORNING_FEEDBACK -> R.string.event_feedback
    EventKind.MORNING_SUGGESTION_APPLIED -> R.string.event_suggestion
})

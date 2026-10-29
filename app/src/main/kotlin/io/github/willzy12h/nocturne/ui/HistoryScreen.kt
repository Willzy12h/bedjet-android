package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.core.protocol.Mode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import io.github.willzy12h.nocturne.ui.NocturneButton as Button
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
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
import io.github.willzy12h.nocturne.ui.features.HealthExportMarker
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
    val tokens = paletteTokens
    var earlier by rememberSaveable { mutableStateOf(false) }
    val nights = history.sortedByDescending { it.startedAt }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.history_title), color = tokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() }) }
        if (history.isEmpty()) item { Text(stringResource(R.string.history_empty), color = tokens.ink2, fontSize = 15.sp) }
        items(nights.take(RECENT_NIGHTS), key = { it.id }) { night -> NightCard(model, night, profiles, settings, activeNight) }
        if (nights.size > RECENT_NIGHTS) {
            item(key = "earlier") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
                    .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)
                    .testTag("history-earlier").clickable(role = Role.Button) { earlier = !earlier }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.parity_history_earlier), color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(pluralStringResource(R.plurals.parity_history_earlier_count, nights.size - RECENT_NIGHTS, nights.size - RECENT_NIGHTS),
                            color = tokens.ink2, fontSize = 14.sp)
                    }
                    Text(if (earlier) "⌃" else "⌄", color = tokens.ink3, fontSize = 16.sp)
                }
            }
            if (earlier) items(nights.drop(RECENT_NIGHTS), key = { it.id }) { night -> NightCard(model, night, profiles, settings, activeNight) }
        }
        item(key = "export") { HistoryExportButton(model.graph) }
    }
}

private const val RECENT_NIGHTS = 3

@Composable
private fun NightCard(model: AppViewModel, night: NightRecord, profiles: List<io.github.willzy12h.nocturne.core.plan.Profile>,
    settings: io.github.willzy12h.nocturne.core.store.Settings, activeNight: io.github.willzy12h.nocturne.core.night.ActiveNight?) {
            val tokens = paletteTokens
            var open by remember { mutableStateOf(false) }
            var checkingIn by remember(night.id) { mutableStateOf(false) }
            var submitting by remember(night.id) { mutableStateOf(false) }
            var suggestion by remember(night.id) { mutableStateOf<MiddleSuggestion?>(null) }
            var showSuggestion by remember(night.id) { mutableStateOf(false) }
            val completed = night.events.any { it.kind == EventKind.ENDED || it.kind == EventKind.STOPPED }
            val checkedIn = night.events.any { it.kind == EventKind.MORNING_FEEDBACK }
            val availableSuggestion = if (checkedIn) model.morningCheckIn(night.id)?.suggestion else suggestion
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
                .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)
                .clickable { open = !open }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val issues = night.events.count { it.kind == EventKind.ERROR }
                    val comfort = night.events.lastOrNull { it.kind == EventKind.MORNING_FEEDBACK }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(nightLabel(night, model.now(), activeNight?.recordId == night.id), Modifier.weight(1f),
                            color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        comfort?.let { feedback ->
                            HistoryPill(feedback.comfort?.let { stringResource(comfortName(it)) } ?: feedback.detail, tokens.ink2)
                        }
                        if (issues > 0) HistoryPill(pluralStringResource(R.plurals.history_issues, issues, issues), tokens.warn,
                            Modifier.testTag("history-issues-${night.id}"))
                    }
                    historyPlan(night)?.let { StepBar(it) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(night.profileName, Modifier.weight(1f), color = tokens.ink2, fontSize = 15.sp, maxLines = 1)
                        if (night.wakeFromAlarm) Icon(painterResource(R.drawable.ic_nc_alarm), stringResource(R.string.parity_history_alarm),
                            Modifier.padding(end = 6.dp).size(14.dp), tint = tokens.ink3)
                        Text("${Format.clock(night.startedAt)} – ${Format.clock(night.wakeAt)}", color = tokens.ink3, fontSize = 15.sp)
                    }
                    HealthSleepOverlay(model.graph, night)
                    HealthExportMarker(model.graph, night)
                    if (completed && !checkedIn && activeNight?.recordId != night.id) {
                        AccentLink(stringResource(R.string.checkin_button), { checkingIn = true }, fontSize = 14.sp)
                    }
                    if (availableSuggestion != null && !showSuggestion) {
                        AccentLink(stringResource(R.string.checkin_review), { showSuggestion = true }, fontSize = 14.sp)
                    }
                    if (open) {
                        night.events.forEach {
                            Text(stringResource(R.string.history_event, Format.clock(it.at), eventKindName(it.kind), it.detail), color = tokens.ink3, fontSize = 12.5.sp)
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
                        // Use the same usable applied revision as the curve, including its legacy/error fallback.
                        val appliedSteps = night.revisions.lastOrNull()?.takeIf {
                            runCatching { it.plan() }.getOrNull() != null
                        }?.steps ?: night.steps
                        appliedSteps.forEach { step ->
                            Text(
                                if (step.mode.usesTemperature) stringResource(R.string.history_step_temp, Format.clock(step.startsAt), blockName(step.block),
                                    modeName(step.mode), tempText(step.temperature, settings.fahrenheit), step.fanPercent, durationText(step.minutes))
                                else stringResource(R.string.history_step, Format.clock(step.startsAt), blockName(step.block), modeName(step.mode),
                                    step.fanPercent, durationText(step.minutes)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else if (night.events.size > 1) {
                        Text(pluralStringResource(R.plurals.history_events, night.events.size, night.events.size), color = tokens.ink3, fontSize = 12.5.sp)
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
                            val name = profile?.name ?: stringResource(R.string.checkin_this_profile)
                            if (proposal.changesFan) {
                                // Cool or Dry: more fan when too hot, less when too cold.
                                Text(stringResource(R.string.checkin_change_intro_fan, name,
                                    stringResource(if (proposal.comfort == MorningComfort.TOO_HOT) R.string.checkin_raise else R.string.checkin_lower)))
                                val oldQuiet = proposal.expectedMiddle.quietFan
                                val newQuiet = proposal.proposedMiddle.quietFan
                                if (proposal.expectedMiddle.fanShape == io.github.willzy12h.nocturne.core.plan.FanShape.QUIET_MIDDLE && oldQuiet != null && newQuiet != null) {
                                    val delta = newQuiet.settle - oldQuiet.settle
                                    Text(stringResource(R.string.checkin_all_fan_change, if (delta >= 0) "+" else "−", kotlin.math.abs(delta)))
                                    Text(stringResource(R.string.checkin_quiet_fan_change, oldQuiet.settle, newQuiet.settle,
                                        oldQuiet.asleep, newQuiet.asleep, oldQuiet.rise, newQuiet.rise))
                                } else {
                                    Text(stringResource(R.string.checkin_fan_start_change, proposal.expectedMiddle.fanStart, proposal.proposedMiddle.fanStart))
                                    Text(stringResource(R.string.checkin_fan_end_change, proposal.expectedMiddle.fanEnd, proposal.proposedMiddle.fanEnd))
                                }
                            } else {
                                Text(stringResource(R.string.checkin_change_intro, name,
                                    stringResource(if (proposal.comfort == MorningComfort.TOO_HOT) R.string.checkin_lower else R.string.checkin_raise)))
                                Text(stringResource(R.string.checkin_start_change, tempText(proposal.expectedMiddle.tempStart, settings.fahrenheit),
                                    tempText(proposal.proposedMiddle.tempStart, settings.fahrenheit)))
                                Text(stringResource(R.string.checkin_end_change, tempText(proposal.expectedMiddle.tempEnd, settings.fahrenheit),
                                    tempText(proposal.proposedMiddle.tempEnd, settings.fahrenheit)))
                            }
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

/** "Tonight", "Last night", a weekday within the week, then the date. */
@Composable
private fun nightLabel(night: NightRecord, now: ZonedDateTime, running: Boolean): String {
    if (running) return stringResource(R.string.parity_history_tonight)
    val start = runCatching { ZonedDateTime.parse(night.startedAt).withZoneSameInstant(now.zone) }.getOrNull() ?: return ""
    val days = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate())
    return when {
        days <= 1 -> stringResource(R.string.parity_history_last_night)
        days < 7 -> start.format(DateTimeFormatter.ofPattern("EEEE"))
        else -> start.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

@StringRes
private fun comfortName(comfort: MorningComfort) = when (comfort) {
    MorningComfort.TOO_HOT -> R.string.checkin_too_hot
    MorningComfort.JUST_RIGHT -> R.string.checkin_just_right
    MorningComfort.TOO_COLD -> R.string.checkin_too_cold
}

@Composable
private fun HistoryPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(50)).background(paletteTokens.surface2).padding(horizontal = 12.dp, vertical = 5.dp),
        color = color, fontSize = 14.sp, maxLines = 1)
}

/** The night as one bar: a segment per step, as wide as the step is long, in the step's colour. */
@Composable
private fun StepBar(plan: Plan) {
    val tokens = paletteTokens
    Row(Modifier.fillMaxWidth().height(18.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        plan.steps.filter { it.minutes > 0 }.forEach { step ->
            val color = if (step.step.mode == Mode.WAIT) tokens.surface2 else tokens.stepColor(step.step.mode, step.step.temperature)
            Box(Modifier.weight(step.minutes.toFloat()).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(color))
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
    EventKind.TEMPORARY -> R.string.runtime_command_temporary
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

package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import io.github.willzy12h.nocturne.core.store.History
import io.github.willzy12h.nocturne.core.store.SLEEP_RANGES
import io.github.willzy12h.nocturne.sleep.SleepInsights
import kotlinx.coroutines.delay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.compositeOver
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
import androidx.compose.ui.semantics.contentDescription
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

/**
 * The Sleep page under Settings. The range switch stays at the top; below it the sections (trend, the chosen
 * night, its figures, comparisons, the stage timeline and the saved nights) can be held and dragged into any order.
 * [onBack] returns to the Settings list; [onOpenPage] opens another Settings page, such as Health.
 */
@Composable
fun HistoryScreen(model: AppViewModel, modifier: Modifier, onBack: (() -> Unit)? = null, onOpenPage: (String) -> Unit = {}) {
    var retention by rememberSaveable { mutableStateOf(false) }
    if (retention) {
        SleepHistorySettings(model, modifier, onBack = { retention = false }, onOpenHealth = { onOpenPage("health") })
        return
    }
    val settings by model.settings.collectAsState()
    val tokens = paletteTokens
    val range = settings.normalisedSleepRange()
    // Eight days more than the range, so the oldest night in range can still be compared with the week before it.
    val data = rememberSleepDays(model, range + SleepInsights.RECENT_NIGHTS + 1)
    val now = model.now()
    val zone = now.zone
    val today = now.toLocalDate()
    val inRange = remember(data.days, today, range) { SleepInsights.inRange(data.days, today, range) }
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = inRange.firstOrNull { it.date.toEpochDay() == selectedDay } ?: inRange.firstOrNull()
    val position = selected?.let(inRange::indexOf) ?: -1
    val sleep = selected?.sleep
    val noticed = remember(selected, data.days) { selected?.let { SleepInsights.noticed(it, data.days) } }
    val averageEfficiency = remember(inRange) {
        inRange.mapNotNull { it.sleep }.takeIf { it.isNotEmpty() }?.let { nights -> nights.sumOf { SleepInsights.figures(it).efficiencyPercent } / nights.size }
    }
    val available = settings.normalisedSleepOrder().filter { id ->
        when (id) {
            "nights", "stages" -> selected != null
            "figures", "compare", "timeline" -> sleep != null
            "noticed" -> noticed != null
            else -> true
        }
    }
    val listState = rememberLazyListState()
    val reorder = remember { ProfileReorder() }
    val spacingPx = with(LocalDensity.current) { 12.dp.toPx() }
    val latestAvailable by rememberUpdatedState(available)
    val latestSaved by rememberUpdatedState(settings.normalisedSleepOrder())
    val shown = reorder.order?.let { ids -> ids.filter { it in available } + available.filterNot { it in ids } } ?: available
    fun save(order: List<String>) {
        val merged = mergeSectionOrder(latestSaved, order)
        model.updateSettings { it.copy(sleepSectionOrder = merged) }
    }
    LaunchedEffect(available, reorder.dragging) {
        if (reorder.dragging == null && reorder.order?.let { it == available } != false) reorder.order = null
    }
    val holdThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    // Near the top or bottom edge the list scrolls under the held section, as on the Profiles page.
    LaunchedEffect(reorder.dragging) { scrollWhileHeld(listState, reorder, spacingPx, holdThreshold) }
    val moveUp = stringResource(R.string.sleep_move_up)
    val moveDown = stringResource(R.string.sleep_move_down)
    val rangeNames = mapOf(7 to stringResource(R.string.sleep_range_7), 30 to stringResource(R.string.sleep_range_30),
        90 to stringResource(R.string.sleep_range_90))
    // The range switch stays put above the sections, unless large text or a short screen would leave too little room for them.
    val configuration = LocalConfiguration.current
    val pinned = configuration.fontScale < 1.5f && configuration.screenHeightDp >= 600
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            onBack?.let { AccentLink("‹ " + stringResource(R.string.settings_nav_back), it, Modifier.testTag("settings-back"), fontSize = 15.sp) }
            Text(stringResource(R.string.history_title), color = tokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Segmented(SLEEP_RANGES.map { it to rangeNames.getValue(it) }, range, Modifier.fillMaxWidth(), tag = "sleep-range") { days ->
                selectedDay = null
                model.updateSettings { it.copy(sleepRangeDays = days) }
            }
            Text(stringResource(R.string.sleep_move_hint), color = tokens.ink2, fontSize = 13.sp)
        }
    }
    Column(modifier.fillMaxSize()) {
        if (pinned) Box(Modifier.padding(horizontal = 16.dp)) { header() }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("sleep-list"), state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!pinned) item(key = "header") { header() }
            if (data.healthOff) item(key = "health-off") {
                Column(Modifier.sleepCard(tokens).testTag("sleep-health-off").clickable(role = Role.Button) { onOpenPage("health") }) {
                    Text(stringResource(R.string.sleep_health_off), color = tokens.ink2, fontSize = 14.sp)
                }
            }
            items(shown, key = { it }) { id ->
                val dragged = reorder.dragging == id
                val at = shown.indexOf(id)
                Box(Modifier.fillMaxWidth().testTag("sleep-section-$id")
                    .then(if (dragged) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                    .heldForReorder(dragged) { reorder.offset }
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { reorder.start(id, latestAvailable) },
                            onDrag = { change, amount ->
                                change.consume()
                                reorder.offset += amount.y
                                reorder.travel += amount.y
                                reorder.swapIfPast(listState, spacingPx)
                            },
                            onDragEnd = { reorder.finish()?.let(::save) },
                            onDragCancel = { reorder.finish()?.let(::save) },
                        )
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            if (at > 0) CustomAccessibilityAction(moveUp) {
                                save(shown.toMutableList().apply { add(at - 1, removeAt(at)) }); true } else null,
                            if (at < shown.lastIndex) CustomAccessibilityAction(moveDown) {
                                save(shown.toMutableList().apply { add(at + 1, removeAt(at)) }); true } else null,
                        )
                    }) {
                    when (id) {
                        "trend" -> SleepTrendCard(inRange, today, range, selected, zone) { selectedDay = it.date.toEpochDay() }
                        "nights" -> selected?.let { day ->
                            SleepNightPicker(day, zone, inRange.getOrNull(position + 1), inRange.getOrNull(position - 1)) {
                                selectedDay = it.date.toEpochDay()
                            }
                        }
                        "stages" -> selected?.let { SleepNightChart(it, zone, settings.fahrenheit, data.availability) }
                        "figures" -> sleep?.let { SleepFiguresBlock(it, zone, averageEfficiency) }
                        "compare" -> selected?.let { SleepCompareCard(it, data.days, zone) }
                        "noticed" -> noticed?.let { SleepNoticedCard(it, zone, settings.fahrenheit) }
                        "timeline" -> sleep?.let { SleepTimelineCard(it, selected.plan, zone, settings.fahrenheit) }
                        "history" -> SavedNights(model, settings) { retention = true }
                    }
                }
            }
        }
    }
}

/** The saved BedJet nights: how long they are kept, a card per night (the newest three, then the rest on request) and export. */
@Composable
private fun SavedNights(model: AppViewModel, settings: io.github.willzy12h.nocturne.core.store.Settings, onRetention: () -> Unit) {
    val history by model.history.collectAsState()
    val profiles by model.profiles.collectAsState()
    val activeNight by model.activeNight.collectAsState()
    val tokens = paletteTokens
    var earlier by rememberSaveable { mutableStateOf(false) }
    val nights = history.sortedByDescending { it.startedAt }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.sleep_nights), color = tokens.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Column(Modifier.sleepCard(tokens).padding(horizontal = 2.dp)) {
            SettingsLine(stringResource(R.string.sleep_history_title),
                stringResource(R.string.sleep_history_row_detail, stringResource(keepName(History.keepDays(settings.historyKeepDays)))),
                tokens.ink, "sleep-history-row", onClick = onRetention)
        }
        if (history.isEmpty()) Text(stringResource(R.string.history_empty), color = tokens.ink2, fontSize = 15.sp)
        nights.take(RECENT_NIGHTS).forEach { night -> key(night.id) { NightCard(model, night, profiles, settings, activeNight) } }
        if (nights.size > RECENT_NIGHTS) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
                .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)
                .testTag("history-earlier").clickable(role = Role.Button) { earlier = !earlier }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.parity_history_earlier), color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(pluralStringResource(R.plurals.parity_history_earlier_count, nights.size - RECENT_NIGHTS, nights.size - RECENT_NIGHTS),
                        color = tokens.ink2, fontSize = 14.sp)
                }
                Text(if (earlier) "⌃" else "⌄", color = tokens.ink2, fontSize = 16.sp)
            }
            if (earlier) nights.drop(RECENT_NIGHTS).forEach { night -> key(night.id) { NightCard(model, night, profiles, settings, activeNight) } }
        }
        HistoryExportButton(model.graph)
    }
}

private const val RECENT_NIGHTS = 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NightCard(model: AppViewModel, night: NightRecord, profiles: List<io.github.willzy12h.nocturne.core.plan.Profile>,
    settings: io.github.willzy12h.nocturne.core.store.Settings, activeNight: io.github.willzy12h.nocturne.core.night.ActiveNight?) {
            val tokens = paletteTokens
            val largeText = LocalConfiguration.current.fontScale >= 1.5f
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
                    FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(nightLabel(night, model.now(), activeNight?.recordId == night.id),
                            if (largeText) Modifier.fillMaxWidth() else Modifier.weight(1f),
                            color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        comfort?.let { feedback ->
                            HistoryPill(feedback.comfort?.let { stringResource(comfortName(it)) } ?: feedback.detail, tokens.ink2)
                        }
                        if (issues > 0) HistoryPill(pluralStringResource(R.plurals.history_issues, issues, issues), tokens.warn,
                            Modifier.testTag("history-issues-${night.id}"))
                    }
                    historyPlan(night)?.let { StepBar(it) }
                    FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(night.profileName, if (largeText) Modifier.fillMaxWidth() else Modifier.weight(1f),
                            color = tokens.ink2, fontSize = 15.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (night.wakeFromAlarm) Icon(painterResource(R.drawable.ic_nc_alarm), stringResource(R.string.parity_history_alarm),
                                Modifier.padding(end = 6.dp).size(14.dp), tint = tokens.ink2)
                            Text("${Format.clock(night.startedAt)} – ${Format.clock(night.wakeAt)}", color = tokens.ink2, fontSize = 15.sp)
                        }
                    }
                    HealthSleepOverlay(model.graph, night, historyPlan(night), open)
                    HealthExportMarker(model.graph, night)
                    if (completed && !checkedIn && activeNight?.recordId != night.id) {
                        AccentLink(stringResource(R.string.checkin_button), { checkingIn = true }, fontSize = 14.sp)
                    }
                    if (availableSuggestion != null && !showSuggestion) {
                        AccentLink(stringResource(R.string.checkin_review), { showSuggestion = true }, fontSize = 14.sp)
                    }
                    if (open) {
                        night.events.forEach {
                            Text(stringResource(R.string.history_event, Format.clock(it.at), eventKindName(it.kind), it.detail), color = tokens.ink2, fontSize = 12.5.sp)
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
                        Text(pluralStringResource(R.plurals.history_events, night.events.size, night.events.size), color = tokens.ink2, fontSize = 12.5.sp)
                    }
            }
            if (checkingIn) AlertDialog(
                onDismissRequest = { if (!submitting) checkingIn = false },
                title = { Text(stringResource(R.string.checkin_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    confirmButton = {
                        // Keep full, multi-line actions separate at large font scales; the dialog's
                        // baseline-aligned action flow can overlap these differently sized controls.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                            Button(onClick = {
                                model.acceptMorningSuggestion(proposal) { showSuggestion = false; suggestion = null }
                            }) { Text(stringResource(R.string.checkin_apply)) }
                            TextButton(onClick = { showSuggestion = false }) { Text(stringResource(R.string.checkin_keep)) }
                        }
                    },
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
internal fun comfortName(comfort: MorningComfort) = when (comfort) {
    MorningComfort.TOO_HOT -> R.string.checkin_too_hot
    MorningComfort.JUST_RIGHT -> R.string.checkin_just_right
    MorningComfort.TOO_COLD -> R.string.checkin_too_cold
}

@Composable
private fun HistoryPill(text: String, color: Color, modifier: Modifier = Modifier) {
    val tokens = paletteTokens
    val background = tokens.surface2.compositeOver(tokens.surface.compositeOver(tokens.ground))
    Text(text, modifier.clip(RoundedCornerShape(50)).background(tokens.surface2).padding(horizontal = 12.dp, vertical = 5.dp),
        color = tokens.readableText(color, background), fontSize = 14.sp)
}

/** The night as one bar: a segment per step, as wide as the step is long, in the step's colour. */
@Composable
private fun StepBar(plan: Plan) {
    val tokens = paletteTokens
    val description = stringResource(R.string.history_plan_accessibility, Format.clock(plan.start), Format.clock(plan.end))
    Row(Modifier.fillMaxWidth().height(18.dp).clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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

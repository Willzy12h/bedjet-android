package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.History
import io.github.willzy12h.nocturne.platform.AndroidSleepSource
import io.github.willzy12h.nocturne.platform.SleepAvailability
import io.github.willzy12h.nocturne.platform.SleepNights
import io.github.willzy12h.nocturne.platform.SleepStage
import io.github.willzy12h.nocturne.sleep.DemoSleep
import io.github.willzy12h.nocturne.sleep.SleepDay
import io.github.willzy12h.nocturne.sleep.SleepInsights
import io.github.willzy12h.nocturne.sleep.SleepNight
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import io.github.willzy12h.nocturne.ui.features.SleepLane
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Every night the Sleep page can draw, joined, newest first; [healthOff] when Health Connect sleep is switched off. */
internal data class SleepPageData(val days: List<SleepDay>, val availability: SleepAvailability?, val healthOff: Boolean, val loaded: Boolean)

/**
 * Reads [loadDays] days of Health Connect sleep (Demo mode draws sample nights instead and never touches Health
 * Connect) and joins them with the BedJet's saved nights. Re-reads when the app comes back to the front.
 */
@Composable
internal fun rememberSleepDays(model: AppViewModel, loadDays: Int): SleepPageData {
    val history by model.history.collectAsState()
    val settings by model.settings.collectAsState()
    val now = model.now()
    val zone = now.zone
    val today = now.toLocalDate()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resume by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resume++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val demo = settings.demoMode
    val healthOn = settings.platform.healthSleepOverlay
    var read by remember { mutableStateOf<SleepNights?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(demo, healthOn, loadDays, today, resume) {
        read = when {
            demo -> SleepNights(SleepAvailability.AVAILABLE, DemoSleep.nights(today, loadDays, zone))
            !healthOn -> null
            else -> AndroidSleepSource.nightsReader(model.graph.context)
                .read(today.minusDays(loadDays.toLong()).atStartOfDay(zone).toInstant(), now.toInstant())
        }
        loaded = true
    }
    val records = remember(history, demo) {
        (if (demo) history else History.withoutDemo(history)).filterNot { it.isTestRun }
    }
    val days = remember(read, records, zone) { SleepInsights.join(read?.nights.orEmpty(), records, zone) { historyPlan(it) } }
    return SleepPageData(days, read?.availability, !demo && !healthOn, loaded)
}

/** A section card: the surface colour, 24 dp corners and the palette's edge. */
internal fun Modifier.sleepCard(tokens: PaletteTokens): Modifier = fillMaxWidth().clip(RoundedCornerShape(24.dp))
    .background(tokens.surface).then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(24.dp)) } ?: Modifier)
    .padding(16.dp)

/** The lane a Health Connect stage code is drawn in; awake and out of bed share Awake. */
internal fun laneColour(stage: Int): Color = (SleepLane.of(stage) ?: SleepLane.ASLEEP).colour

/** Dark text on the lighter lane colours, white on Deep. */
internal fun onLane(colour: Color): Color = if (colour.luminance() < .25f) Color.White else Color(0xFF12152A)

/** Bottom to top in a stacked bar: deepest sleep at the base, awake on top. */
private val STACK = listOf(SleepInsights.DEEP, SleepInsights.LIGHT, SleepInsights.SLEEPING, SleepInsights.REM, SleepInsights.AWAKE)

@StringRes
internal fun stageName(stage: Int): Int = when (stage) {
    SleepInsights.AWAKE -> R.string.feature_stage_awake
    SleepInsights.SLEEPING -> R.string.feature_stage_sleeping
    SleepInsights.OUT_OF_BED -> R.string.feature_stage_out
    SleepInsights.LIGHT -> R.string.feature_stage_light
    SleepInsights.DEEP -> R.string.feature_stage_deep
    SleepInsights.REM -> R.string.feature_stage_rem
    else -> R.string.feature_stage_unknown
}

/** "Heat 28 °C", or the mode alone when it has no temperature. */
@Composable
internal fun stepText(step: ProgramStep, fahrenheit: Boolean): String =
    if (step.mode.usesTemperature) stringResource(R.string.sleep_bedjet_step, modeName(step.mode), tempText(step.temperature, fahrenheit))
    else modeName(step.mode)

/** "28 °C" for heating steps, otherwise the mode's name: the short label beside the timeline's BedJet rail. */
@Composable
private fun shortStepText(step: ProgramStep, fahrenheit: Boolean): String =
    if (step.mode.usesTemperature) tempText(step.temperature, fahrenheit) else modeName(step.mode)

/** The weekly or monthly bars: hours asleep per night, stacked by stage; a tap opens that night. */
@Composable
internal fun SleepTrendCard(days: List<SleepDay>, today: LocalDate, range: Int, selected: SleepDay?, zone: ZoneId,
    onSelect: (SleepDay) -> Unit) {
    val tokens = paletteTokens
    val averages = remember(days) { SleepInsights.averages(days, zone) }
    val byDate = remember(days) { days.groupBy { it.date }.mapValues { it.value.first() } }
    val slots = (range - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val figures = remember(days) { days.associateWith { day -> day.sleep?.let(SleepInsights::figures) } }
    val maxMinutes = maxOf(8 * 60, figures.values.maxOfOrNull { it?.asleepMinutes ?: 0 } ?: 0)
    val average = if (averages.nights > 0) durationText(averages.asleepMinutes) else stringResource(R.string.sleep_figure_none)
    val description = stringResource(R.string.sleep_trend_accessibility, averages.nights, average)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = tokens.ink3, fontSize = 11.sp)
    val selectedStyle = TextStyle(color = tokens.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val guide = tokens.ink3.copy(alpha = .45f)
    val dayFormat = remember { DateTimeFormatter.ofPattern(if (range <= 7) "EEEEE" else "d") }
    Column(Modifier.sleepCard(tokens).testTag("sleep-trend"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sleep_average_asleep), color = tokens.ink2, fontSize = 13.sp)
                Text(average, color = tokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            averages.usualBedtimeMinute?.let { minute ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.sleep_usual_bedtime), color = tokens.ink2, fontSize = 13.sp)
                    Text(Format.clockOfDay(minute), color = tokens.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val eight = stringResource(R.string.sleep_trend_hours, 8)
        Canvas(Modifier.fillMaxWidth().height(if (range <= 7) 128.dp else 112.dp).testTag("sleep-trend-bars")
            .clearAndSetSemantics { contentDescription = description }
            .pointerInput(slots, byDate) {
                detectTapGestures { offset ->
                    val index = (offset.x / size.width * slots.size).toInt().coerceIn(0, slots.lastIndex)
                    byDate[slots[index]]?.let(onSelect)
                }
            }) {
            val label = 18.dp.toPx()
            val top = 4.dp.toPx()
            val chart = size.height - label - top
            val slot = size.width / slots.size
            val gap = when { range <= 7 -> 10.dp.toPx(); range <= 30 -> 3.dp.toPx(); else -> 1.dp.toPx() }
            val eightY = top + chart * (1 - 480f / maxMinutes)
            drawLine(guide, Offset(0f, eightY), Offset(size.width, eightY), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val eightText = measurer.measure(eight, labelStyle)
            drawText(eightText, topLeft = Offset(size.width - eightText.size.width, eightY - eightText.size.height - 1.dp.toPx()))
            slots.forEachIndexed { i, date ->
                val day = byDate[date]
                val isSelected = day != null && day == selected
                val left = i * slot + gap / 2
                val width = (slot - gap).coerceAtLeast(1f)
                val figure = day?.let { figures[it] }
                if (figure != null) {
                    var y = top + chart
                    val alpha = if (selected == null || isSelected) 1f else .6f
                    STACK.forEach { stage ->
                        val minutes = figure.stageMinutes[stage] ?: return@forEach
                        val height = chart * minutes / maxMinutes
                        drawRoundRect(laneColour(stage).copy(alpha = alpha), Offset(left, y - height), Size(width, (height - 1).coerceAtLeast(1f)),
                            CornerRadius(minOf(3.dp.toPx(), width / 2)))
                        y -= height
                    }
                    if (isSelected) drawRoundRect(tokens.ink, Offset(left - 3.dp.toPx(), y - 3.dp.toPx()),
                        Size(width + 6.dp.toPx(), top + chart - y + 6.dp.toPx()), CornerRadius(5.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                } else if (day != null) {
                    // A BedJet night with no sleep data: a short grey stub, so the night can still be opened.
                    drawRoundRect(tokens.ink3.copy(alpha = if (isSelected) .9f else .45f), Offset(left, top + chart - 4.dp.toPx()),
                        Size(width, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
                val showLabel = range <= 7 || i == 0 || i == slots.lastIndex || (range <= 30 && i % 7 == slots.lastIndex % 7)
                if (showLabel) {
                    val text = measurer.measure(date.format(dayFormat), if (isSelected) selectedStyle else labelStyle)
                    val x = (i * slot + slot / 2 - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                    drawText(text, topLeft = Offset(x, size.height - text.size.height))
                }
            }
        }
        Text(stringResource(if (averages.nights == 0) R.string.sleep_trend_none else R.string.sleep_trend_hint), color = tokens.ink2, fontSize = 13.sp)
    }
}

/** ‹ the night's date and profile › */
@Composable
internal fun SleepNightPicker(day: SleepDay, zone: ZoneId, older: SleepDay?, newer: SleepDay?, onSelect: (SleepDay) -> Unit) {
    val tokens = paletteTokens
    val comfort = day.record?.events?.lastOrNull { it.kind == EventKind.MORNING_FEEDBACK }?.comfort
    val detail = listOfNotNull(day.record?.profileName ?: stringResource(R.string.sleep_no_bedjet),
        comfort?.let { stringResource(comfortName(it)) }).joinToString(" · ")
    Row(Modifier.fillMaxWidth().testTag("sleep-night-picker"), verticalAlignment = Alignment.CenterVertically) {
        RoundArrow("‹", stringResource(R.string.sleep_previous_night), older != null, "sleep-older") { older?.let(onSelect) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.end.atZone(zone).toLocalDate().minusDays(if (day.sleep != null || day.record != null) 1 else 0)
                .format(DateTimeFormatter.ofPattern("EEEE d MMMM")), color = tokens.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(detail, color = tokens.ink2, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        RoundArrow("›", stringResource(R.string.sleep_next_night), newer != null, "sleep-newer") { newer?.let(onSelect) }
    }
}

@Composable
private fun RoundArrow(text: String, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val tokens = paletteTokens
    Box(Modifier.size(48.dp).clip(CircleShape).background(tokens.surface).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) tokens.ink else tokens.ink3.copy(alpha = .5f), fontSize = 20.sp)
    }
}

/**
 * One night's sleep stages in 32 dp lanes over the BedJet's steps, on one time axis. Dragging across it shows
 * the stage and the BedJet step at that moment. A night with no BedJet run draws a grey strip marked Off; one with
 * no Health Connect sleep draws only the strip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepNightChart(day: SleepDay, zone: ZoneId, fahrenheit: Boolean, missing: SleepAvailability?) {
    val tokens = paletteTokens
    val sleep = day.sleep
    val plan = day.plan
    val start = listOfNotNull(sleep?.start, plan?.start?.toInstant()).minOrNull() ?: day.start
    val end = listOfNotNull(sleep?.end, plan?.end?.toInstant()).maxOrNull()?.takeIf { it > start } ?: day.end
    val span = Duration.between(start, end).toMillis().coerceAtLeast(1).toFloat()
    val lanes = if (sleep == null) emptyList() else SleepLane.entries.filter { it != SleepLane.ASLEEP || sleep.stages.any { s -> SleepLane.of(s.stage) == it } }
    val steps = plan?.steps.orEmpty().filter { it.minutes > 0 && it.step.mode != Mode.WAIT }
    val stepLabels = steps.map { stepText(it.step, fahrenheit) }
    val off = stringResource(R.string.sleep_bedjet_off)
    val stageLabels = (1..6).associateWith { stringResource(stageName(it)) }
    var at by remember(day) { mutableStateOf<Float?>(null) }
    val laneHeight = 32.dp
    val stripHeight = 30.dp
    val stripGap = 10.dp
    val axis = 18.dp
    val measurer = rememberTextMeasurer()
    val stripStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val axisStyle = TextStyle(color = tokens.ink3, fontSize = 12.sp)
    val offStyle = TextStyle(color = tokens.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val totals = sleep?.let { SleepInsights.figures(it) }
    val summary = buildString {
        totals?.stageMinutes?.forEach { (stage, minutes) -> append(stageLabels[stage]).append(' ').append(Format.duration(minutes)).append(", ") }
        append(stringResource(R.string.feature_stage_bedjet)).append(' ')
        append(if (steps.isEmpty()) off else stepLabels.distinct().joinToString(", "))
    }
    fun instantAt(fraction: Float): Instant = start.plusMillis((span * fraction).toLong())
    Column(Modifier.sleepCard(tokens).testTag("sleep-chart"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val shown = at
        if (shown != null) {
            val moment = instantAt(shown)
            val stage = sleep?.stages?.firstOrNull { !moment.isBefore(it.start) && moment.isBefore(it.end) }?.stage
            val step = plan?.stepAt(moment.atZone(plan.start.zone))?.second?.takeIf { it.step.mode != Mode.WAIT }
            val bedJet = step?.let { stepText(it.step, fahrenheit) } ?: off
            val clock = Format.clock(moment.atZone(zone))
            Text(if (stage != null) stringResource(R.string.sleep_scrub_sleep_bedjet, clock, stageLabels.getValue(stage), bedJet)
                else stringResource(R.string.sleep_scrub_bedjet, clock, bedJet),
                Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp)).background(tokens.surface2)
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("sleep-scrub"),
                color = tokens.ink, fontSize = 13.sp)
        }
        if (sleep == null) Text(stringResource(when (missing) {
            SleepAvailability.ABSENT -> R.string.feature_health_absent
            SleepAvailability.PERMISSION -> R.string.feature_health_permission
            SleepAvailability.ERROR -> R.string.feature_health_error
            else -> R.string.sleep_no_stages
        }), color = tokens.ink2, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary }) {
            Column(Modifier.width(56.dp)) {
                lanes.forEach { lane ->
                    Box(Modifier.height(laneHeight), contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(lane.label), color = tokens.ink2, fontSize = 13.sp, maxLines = 1)
                    }
                }
                Box(Modifier.padding(top = if (lanes.isEmpty()) 0.dp else stripGap).height(stripHeight), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.feature_stage_bedjet), color = tokens.ink2, fontSize = 13.sp, maxLines = 1)
                }
            }
            val guide = tokens.ink3.copy(alpha = .3f)
            val chartHeight = laneHeight * lanes.size + (if (lanes.isEmpty()) 0.dp else stripGap) + stripHeight + axis
            Canvas(Modifier.weight(1f).height(chartHeight)
                .pointerInput(day) { detectTapGestures { at = (it.x / size.width).coerceIn(0f, 1f) } }
                .pointerInput(day) {
                    detectHorizontalDragGestures(onDragStart = { at = (it.x / size.width).coerceIn(0f, 1f) }) { change, _ ->
                        change.consume()
                        at = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }) {
                val lane = laneHeight.toPx()
                fun x(instant: Instant) = (Duration.between(start, instant).toMillis() / span * size.width).coerceIn(0f, size.width)
                lanes.indices.forEach { i -> drawLine(guide, Offset(0f, lane * i + lane / 2), Offset(size.width, lane * i + lane / 2), 1f) }
                val block = 22.dp.toPx()
                sleep?.stages?.forEach { stage ->
                    val row = SleepLane.of(stage.stage)?.let(lanes::indexOf)?.takeIf { it >= 0 } ?: return@forEach
                    val left = x(stage.start)
                    val right = maxOf(x(stage.end) - 1f, left + 1.5f)
                    drawRoundRect(lanes[row].colour, Offset(left, lane * row + (lane - block) / 2), Size(right - left, block), CornerRadius(5.dp.toPx()))
                }
                val stripTop = lane * lanes.size + if (lanes.isEmpty()) 0f else stripGap.toPx()
                val strip = stripHeight.toPx()
                if (steps.isEmpty()) {
                    drawRoundRect(tokens.ink3.copy(alpha = .22f), Offset(0f, stripTop), Size(size.width, strip), CornerRadius(6.dp.toPx()))
                    val text = measurer.measure(off, offStyle)
                    drawText(text, topLeft = Offset((size.width - text.size.width) / 2, stripTop + (strip - text.size.height) / 2))
                } else steps.forEachIndexed { i, step ->
                    val from = step.startsAt.toInstant()
                    val left = x(from)
                    val right = x(from.plusSeconds(step.minutes * 60L)) - 2f
                    if (right <= left) return@forEachIndexed
                    val colour = tokens.stepColor(step.step.mode, step.step.temperature)
                    drawRoundRect(colour, Offset(left, stripTop), Size(right - left, strip), CornerRadius(6.dp.toPx()))
                    val text = measurer.measure(stepLabels[i], stripStyle.copy(color = onLane(colour)))
                    if (text.size.width < right - left - 8.dp.toPx())
                        drawText(text, topLeft = Offset(left + (right - left - text.size.width) / 2, stripTop + (strip - text.size.height) / 2))
                }
                // Whole hours along the bottom, every two hours on a long night.
                val hours = Duration.between(start, end).toHours().coerceAtLeast(1)
                val every = if (hours > 6) 2 else 1
                var tick = start.atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
                while (tick.toInstant().isBefore(end)) {
                    if (tick.hour % every == 0) {
                        val text = measurer.measure(Format.clock(tick), axisStyle)
                        val cx = x(tick.toInstant())
                        val left = (cx - text.size.width / 2).coerceIn(0f, size.width - text.size.width)
                        drawText(text, topLeft = Offset(left, size.height - text.size.height))
                    }
                    tick = tick.plusHours(1)
                }
                at?.let { fraction ->
                    val cx = fraction * size.width
                    drawLine(tokens.ink, Offset(cx, 0f), Offset(cx, stripTop + strip), 2.dp.toPx())
                    val moment = instantAt(fraction)
                    val row = sleep?.stages?.firstOrNull { !moment.isBefore(it.start) && moment.isBefore(it.end) }
                        ?.let { SleepLane.of(it.stage) }?.let(lanes::indexOf)?.takeIf { it >= 0 }
                    if (row != null) {
                        drawCircle(tokens.surface, 9.dp.toPx(), Offset(cx, lane * row + lane / 2))
                        drawCircle(tokens.ink, 6.dp.toPx(), Offset(cx, lane * row + lane / 2))
                    }
                }
            }
        }
        if (steps.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var previous: String? = null
            steps.forEachIndexed { i, step ->
                if (stepLabels[i] == previous) return@forEachIndexed
                previous = stepLabels[i]
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(tokens.surface2).padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(tokens.stepColor(step.step.mode, step.step.temperature)))
                    Text(Format.clock(step.startsAt.withZoneSameInstant(zone)), color = tokens.ink2, fontSize = 12.sp)
                    Text(stepLabels[i], color = tokens.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(stringResource(R.string.sleep_scrub_hint), color = tokens.ink2, fontSize = 13.sp)
    }
}

/** Asleep, fell asleep in, woke up and asleep in bed: one block of four. */
@Composable
internal fun SleepFiguresBlock(sleep: SleepNight, zone: ZoneId, averageEfficiency: Int?) {
    val figures = remember(sleep) { SleepInsights.figures(sleep) }
    val woke = if (figures.wakeCount == 0) stringResource(R.string.sleep_figure_woke_none)
        else stringResource(R.string.sleep_figure_woke_note, durationText(figures.longestWakeMinutes),
            figures.longestWakeAt?.let { Format.clock(it.atZone(zone)) } ?: "")
    val cells = listOf(
        Triple(stringResource(R.string.sleep_figure_asleep), durationText(figures.asleepMinutes),
            stringResource(R.string.sleep_figure_asleep_note, durationText(figures.inBedMinutes))),
        Triple(stringResource(R.string.sleep_figure_fell_asleep), figures.fellAsleepMinutes?.let { durationText(it) } ?: stringResource(R.string.sleep_figure_none),
            stringResource(R.string.sleep_figure_fell_asleep_note)),
        Triple(stringResource(R.string.sleep_figure_woke), pluralStringResource(R.plurals.sleep_figure_woke_times, figures.wakeCount, figures.wakeCount), woke),
        Triple(stringResource(R.string.sleep_figure_efficiency), stringResource(R.string.sleep_figure_percent, figures.efficiencyPercent),
            averageEfficiency?.let { stringResource(R.string.sleep_figure_efficiency_note, it) } ?: ""),
    )
    Column(Modifier.fillMaxWidth().testTag("sleep-figures"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, note) -> FigureCell(label, value, note, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun FigureCell(label: String, value: String, note: String, modifier: Modifier) {
    val tokens = paletteTokens
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(tokens.surface)
        .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(20.dp)) } ?: Modifier).padding(14.dp)
        .semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = tokens.ink2, fontSize = 13.sp)
        Text(value, color = tokens.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (note.isNotEmpty()) Text(note, color = tokens.ink3, fontSize = 12.sp)
    }
}

/** Each stage against the average of up to seven earlier nights; a plain observation, never a verdict. */
@Composable
internal fun SleepCompareCard(day: SleepDay, all: List<SleepDay>, zone: ZoneId) {
    val tokens = paletteTokens
    val rows = remember(day, all) { SleepInsights.compare(day, all, zone) }
    val earlier = remember(day, all) { all.count { it.sleep != null && it.start < day.start }.coerceAtMost(SleepInsights.RECENT_NIGHTS) }
    Column(Modifier.sleepCard(tokens).testTag("sleep-compare"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(pluralStringResource(R.plurals.sleep_compare_title, earlier.coerceAtLeast(1), earlier), color = tokens.ink, fontSize = 16.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        if (rows.isEmpty()) Text(stringResource(R.string.sleep_compare_none), color = tokens.ink2, fontSize = 14.sp)
        val most = rows.maxOfOrNull { maxOf(it.minutes, it.averageMinutes) }?.coerceAtLeast(1) ?: 1
        rows.forEach { row ->
            val colour = laneColour(row.stage)
            val delta = when {
                row.delta > 0 -> stringResource(R.string.sleep_compare_more, durationText(row.delta))
                row.delta < 0 -> stringResource(R.string.sleep_compare_less, durationText(-row.delta))
                else -> stringResource(R.string.sleep_compare_same)
            }
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
                    Text(stringResource((SleepLane.of(row.stage) ?: SleepLane.ASLEEP).label), Modifier.weight(1f), color = tokens.ink, fontSize = 14.sp)
                    Text(durationText(row.minutes), color = tokens.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(delta, Modifier.width(78.dp), color = tokens.ink2, fontSize = 13.sp, textAlign = TextAlign.End)
                }
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    val bar = 8.dp.toPx()
                    val top = (size.height - bar) / 2
                    drawRoundRect(tokens.ink3.copy(alpha = .18f), Offset(0f, top), Size(size.width, bar), CornerRadius(bar / 2))
                    drawRoundRect(colour, Offset(0f, top), Size(size.width * row.minutes / most, bar), CornerRadius(bar / 2))
                    val mark = size.width * row.averageMinutes / most
                    drawLine(tokens.ink, Offset(mark, 0f), Offset(mark, size.height), 2.dp.toPx())
                }
            }
        }
        Text(stringResource(R.string.sleep_compare_footer), color = tokens.ink3, fontSize = 12.sp)
    }
}

/** A wake that followed a BedJet step change and repeats on recent nights. */
@Composable
internal fun SleepNoticedCard(noticed: io.github.willzy12h.nocturne.sleep.SleepNoticed, zone: ZoneId, fahrenheit: Boolean) {
    val tokens = paletteTokens
    val change = if (noticed.mode.usesTemperature) stringResource(R.string.sleep_bedjet_step, modeName(noticed.mode), tempText(noticed.temperature, fahrenheit))
        else modeName(noticed.mode)
    Column(Modifier.sleepCard(tokens).testTag("sleep-noticed"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.sleep_noticed))
        Text(stringResource(R.string.sleep_noticed_text, Format.clock(noticed.wokeAt.atZone(zone)), durationText(noticed.minutesAfter), change,
            noticed.nights, noticed.ofNights), color = tokens.ink, fontSize = 16.sp, lineHeight = 22.sp)
        Text(stringResource(R.string.sleep_noticed_caveat), color = tokens.ink2, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Touching stages in the same lane drawn as one row; the timeline lists rows, not every Health Connect record. */
internal fun condensedStages(stages: List<SleepStage>): List<SleepStage> = stages.sortedBy { it.start }.fold(mutableListOf()) { rows, stage ->
    val last = rows.lastOrNull()
    if (last != null && SleepLane.of(last.stage) == SleepLane.of(stage.stage) && Duration.between(last.end, stage.start).toMinutes() < 1)
        rows[rows.lastIndex] = last.copy(end = maxOf(last.end, stage.end))
    else rows += stage
    rows
}

/**
 * The night stage by stage, top to bottom: each row indented by depth (the chart turned on its side), with the
 * BedJet's colour as a rail on the right and its setting where it changed. Short stages keep a readable height.
 */
@Composable
internal fun SleepTimelineCard(sleep: SleepNight, plan: Plan?, zone: ZoneId, fahrenheit: Boolean) {
    val tokens = paletteTokens
    val rows = remember(sleep) { condensedStages(sleep.stages) }
    val off = stringResource(R.string.sleep_bedjet_off)
    Column(Modifier.sleepCard(tokens).testTag("sleep-timeline"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.sleep_timeline), Modifier.weight(1f).semantics { heading() }, color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.feature_stage_bedjet), color = tokens.ink2, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 50.dp, end = 58.dp).clearAndSetSemantics { }) {
            Text(stringResource(R.string.sleep_timeline_awake), Modifier.weight(1f), color = tokens.ink3, fontSize = 12.sp)
            Text(stringResource(R.string.sleep_timeline_deeper), color = tokens.ink3, fontSize = 12.sp)
        }
        var shownStep: Any? = Unit
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            rows.forEach { stage ->
                val lane = SleepLane.of(stage.stage) ?: SleepLane.ASLEEP
                val minutes = Duration.between(stage.start, stage.end).toMinutes().toInt()
                val step = plan?.stepAt(stage.start.atZone(plan.start.zone))?.second?.takeIf { it.step.mode != Mode.WAIT }
                val first = step?.startsAt != shownStep
                shownStep = step?.startsAt
                val rail = step?.let { tokens.stepColor(it.step.mode, it.step.temperature) } ?: tokens.ink3.copy(alpha = .3f)
                val indent = when (lane) { SleepLane.AWAKE -> 0.dp; SleepLane.REM -> 18.dp; SleepLane.LIGHT, SleepLane.ASLEEP -> 36.dp; SleepLane.DEEP -> 54.dp }
                Row(Modifier.fillMaxWidth().height((minutes * .4f).dp.coerceIn(28.dp, 64.dp)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Format.clock(stage.start.atZone(zone)), Modifier.width(42.dp).padding(top = 5.dp), color = tokens.ink3, fontSize = 12.sp)
                    Row(Modifier.weight(1f).fillMaxHeight().padding(start = indent).clip(RoundedCornerShape(9.dp)).background(lane.colour)
                        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(lane.label), Modifier.weight(1f), color = onLane(lane.colour), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(durationText(minutes), color = onLane(lane.colour), fontSize = 13.sp, maxLines = 1)
                    }
                    Box(Modifier.width(8.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(rail))
                    Text(if (first) step?.let { shortStepText(it.step, fahrenheit) } ?: off else "", Modifier.width(42.dp).padding(top = 5.dp),
                        color = if (step != null) tokens.visible(rail, tokens.surface) else tokens.ink3, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Format.clock(sleep.end.atZone(zone)), Modifier.width(42.dp), color = tokens.ink3, fontSize = 12.sp)
            Text(plan?.let { stringResource(R.string.sleep_timeline_up_bedjet, Format.clock(it.end.withZoneSameInstant(zone))) }
                ?: stringResource(R.string.sleep_timeline_up), color = tokens.ink2, fontSize = 13.sp)
        }
    }
}

/** Settings › Sleep › Sleep history: how long nights are kept, where they live, and deleting them. */
@Composable
internal fun SleepHistorySettings(model: AppViewModel, modifier: Modifier, onBack: () -> Unit, onOpenHealth: () -> Unit) {
    val tokens = paletteTokens
    val settings by model.settings.collectAsState()
    val history by model.history.collectAsState()
    val importApplying by model.importApplying.collectAsState()
    val keep = History.keepDays(settings.historyKeepDays)
    var shrinkTo by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    val health = settings.platform.healthSleepOverlay
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            AccentLink("‹ " + stringResource(R.string.history_title), onBack, Modifier.testTag("sleep-history-back"), fontSize = 15.sp)
            Text(stringResource(R.string.sleep_history_title), color = tokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.sleepCard(tokens).selectableGroup().testTag("sleep-keep")) {
            Text(stringResource(R.string.sleep_keep_for), color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
            History.KEEP_OPTIONS.forEach { days ->
                HorizontalDivider(color = tokens.hairline)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("sleep-keep-$days")
                    .selectable(days == keep, enabled = !settings.demoMode, role = Role.RadioButton) {
                        if (days < keep) shrinkTo = days else if (days != keep) model.setHistoryKeepDays(days)
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(days == keep, null, enabled = !settings.demoMode,
                        colors = RadioButtonDefaults.colors(selectedColor = tokens.visible(tokens.accent, tokens.surface), unselectedColor = tokens.ink2))
                    Text(stringResource(keepName(days)), Modifier.weight(1f), color = tokens.ink, fontSize = 16.sp)
                    if (days == History.DEFAULT_KEEP_DAYS) Text(stringResource(R.string.sleep_keep_recommended), color = tokens.ink3, fontSize = 13.sp)
                }
            }
        }
        Text(stringResource(R.string.sleep_keep_help), Modifier.padding(horizontal = 4.dp), color = tokens.ink2, fontSize = 13.sp, lineHeight = 18.sp)
        val warn = tokens.warn
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(warn.copy(alpha = if (tokens.dark) .12f else .14f))
            .border(1.dp, warn.copy(alpha = .45f), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp).testTag("sleep-local-only"),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.padding(top = 1.dp).size(22.dp)) {
                val stroke = 1.8.dp.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2, size.height * .11f); lineTo(size.width * .91f, size.height * .84f)
                    lineTo(size.width * .09f, size.height * .84f); close()
                }
                drawPath(path, warn, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawLine(warn, Offset(size.width / 2, size.height * .39f), Offset(size.width / 2, size.height * .59f), stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(warn, 1.1.dp.toPx(), Offset(size.width / 2, size.height * .71f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sleep_local_title), color = tokens.readableText(warn, tokens.ground), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.sleep_local_body), color = tokens.ink, fontSize = 14.sp, lineHeight = 20.sp)
                Text(stringResource(R.string.sleep_local_health), color = tokens.ink2, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(tokens.surface)
            .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(24.dp)) } ?: Modifier).padding(horizontal = 16.dp, vertical = 4.dp)) {
            SettingsLine(stringResource(R.string.sleep_backup), stringResource(R.string.sleep_backup_detail), tokens.ink, "sleep-backup",
                enabled = !settings.demoMode && !importApplying) { exporter.launch("nocturne-backup-${model.now().toLocalDate()}.json") }
            HorizontalDivider(color = tokens.hairline)
            SettingsLine(stringResource(R.string.sleep_health), stringResource(if (health) R.string.sleep_health_on else R.string.sleep_health_off_short),
                tokens.ink, "sleep-health", onClick = onOpenHealth)
            HorizontalDivider(color = tokens.hairline)
            SettingsLine(stringResource(R.string.sleep_delete), "", tokens.readableText(tokens.bad, tokens.surface), "sleep-delete",
                enabled = history.isNotEmpty() && !settings.demoMode) { deleting = true }
        }
    }
    shrinkTo?.let { days ->
        val cutoff = model.now().minusDays(days.toLong())
        val lost = history.count { night -> runCatching { !ZonedDateTime.parse(night.startedAt).isAfter(cutoff) }.getOrDefault(true) }
        AlertDialog(onDismissRequest = { shrinkTo = null },
            title = { Text(stringResource(R.string.sleep_shrink_title, stringResource(keepName(days)))) },
            text = { Text(pluralStringResource(R.plurals.sleep_shrink_body, lost, lost)) },
            confirmButton = { TextButton(onClick = { shrinkTo = null; model.setHistoryKeepDays(days) }, Modifier.testTag("sleep-shrink-confirm")) {
                Text(stringResource(R.string.sleep_shrink_confirm)) } },
            dismissButton = { TextButton(onClick = { shrinkTo = null }) { Text(stringResource(R.string.sleep_cancel)) } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.sleep_delete_title)) },
        text = { Text(stringResource(R.string.sleep_delete_body)) },
        confirmButton = { TextButton(onClick = { deleting = false; model.deleteHistory() }, Modifier.testTag("sleep-delete-confirm")) {
            Text(stringResource(R.string.sleep_delete_confirm)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.sleep_cancel)) } })
}

@StringRes
internal fun keepName(days: Int): Int = when (days) {
    7 -> R.string.sleep_keep_7
    30 -> R.string.sleep_keep_30
    365 -> R.string.sleep_keep_365
    else -> R.string.sleep_keep_90
}

@Composable
internal fun SettingsLine(label: String, detail: String, colour: Color, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = paletteTokens
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)
        .then(if (enabled) Modifier else Modifier.background(Color.Transparent)), verticalAlignment = Alignment.CenterVertically) {
        // The detail sits under the label, so neither is squeezed on a narrow phone or with large text.
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, color = if (enabled) colour else tokens.ink3, fontSize = 16.sp)
            if (detail.isNotEmpty()) Text(detail, color = tokens.ink2, fontSize = 13.sp)
        }
        Text("›", color = tokens.ink3, fontSize = 18.sp)
    }
}

/** Keeps hidden sections where they were while the shown ones take their new order. */
internal fun mergeSectionOrder(saved: List<String>, shown: List<String>): List<String> {
    val next = shown.iterator()
    val moving = shown.toSet()
    return saved.map { if (it in moving && next.hasNext()) next.next() else it }
}

/** The night that ended this morning (or yesterday morning), or null when Health Connect has no sleep for it. */
internal fun lastNight(days: List<SleepDay>, today: LocalDate): SleepDay? =
    days.firstOrNull { it.sleep != null && !it.date.isBefore(today.minusDays(1)) && !it.date.isAfter(today) }

/**
 * Tonight's look back at last night: time asleep and wakes, the stages in order, and the week's bars. It only shows
 * when Health Connect (or Demo mode) has sleep for last night, and a tap opens the Sleep page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LastNightCard(model: AppViewModel, onOpen: () -> Unit) {
    val data = rememberSleepDays(model, SleepInsights.RECENT_NIGHTS + 1)
    val now = model.now()
    val today = now.toLocalDate()
    val zone = now.zone
    val day = remember(data.days, today) { lastNight(data.days, today) } ?: return
    val sleep = day.sleep ?: return
    val tokens = paletteTokens
    val figures = remember(sleep) { SleepInsights.figures(sleep) }
    val week = remember(data.days, today) { SleepInsights.inRange(data.days, today, SleepInsights.RECENT_NIGHTS) }
    val average = remember(week) { SleepInsights.averages(week, zone) }
    val asleep = durationText(figures.asleepMinutes)
    val description = stringResource(R.string.last_night_accessibility, asleep)
    val stageLabels = (1..6).associateWith { stringResource(stageName(it)) }
    Column(Modifier.sleepCard(tokens).testTag("last-night")
        .clickable(role = Role.Button, onClick = onOpen)
        .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.last_night_title), Modifier.weight(1f), color = tokens.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.last_night_open) + " ›", color = tokens.readableText(tokens.accent, tokens.surface), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(verticalArrangement = Arrangement.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(asleep, color = tokens.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(if (figures.wakeCount == 0) stringResource(R.string.last_night_straight)
                else pluralStringResource(R.plurals.last_night_woke, figures.wakeCount, figures.wakeCount),
                Modifier.align(Alignment.Bottom).padding(bottom = 6.dp), color = tokens.ink2, fontSize = 14.sp)
        }
        // The night's stages in order, end to end.
        val span = Duration.between(sleep.start, sleep.end).toMillis().coerceAtLeast(1).toFloat()
        Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).testTag("last-night-strip")) {
            drawRect(tokens.surface2)
            condensedStages(sleep.stages.ifEmpty { listOf(SleepStage(sleep.start, sleep.end, SleepInsights.SLEEPING)) }).forEach { stage ->
                val left = size.width * Duration.between(sleep.start, stage.start).toMillis() / span
                val right = size.width * Duration.between(sleep.start, stage.end).toMillis() / span
                drawRect(laneColour(stage.stage), Offset(left, 0f), Size((right - left).coerceAtLeast(1f), size.height))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            STACK.reversed().forEach { stage ->
                val minutes = figures.stageMinutes[stage] ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(laneColour(stage)))
                    Text("${stageLabels[stage]} ${durationText(minutes)}", color = tokens.ink2, fontSize = 12.sp)
                }
            }
        }
        HorizontalDivider(color = tokens.hairline)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.last_night_week), color = tokens.ink2, fontSize = 13.sp)
                if (average.nights > 0) Text(stringResource(R.string.last_night_average, durationText(average.asleepMinutes)),
                    color = tokens.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            WeekBars(week, today, day, Modifier.width(140.dp).height(44.dp))
        }
    }
}

/** Seven small stacked bars, one per night, last night drawn at full strength. */
@Composable
private fun WeekBars(week: List<SleepDay>, today: LocalDate, last: SleepDay, modifier: Modifier) {
    val tokens = paletteTokens
    val byDate = remember(week) { week.groupBy { it.date }.mapValues { it.value.first() } }
    val figures = remember(week) { week.associateWith { d -> d.sleep?.let(SleepInsights::figures) } }
    val maxMinutes = maxOf(8 * 60, figures.values.maxOfOrNull { it?.asleepMinutes ?: 0 } ?: 0)
    Canvas(modifier.testTag("last-night-week")) {
        val slots = SleepInsights.RECENT_NIGHTS
        val slot = size.width / slots
        val gap = 4.dp.toPx()
        for (i in 0 until slots) {
            val day = byDate[today.minusDays((slots - 1 - i).toLong())]
            val figure = day?.let { figures[it] }
            val left = i * slot + gap / 2
            val width = slot - gap
            if (figure == null) {
                drawRoundRect(tokens.ink3.copy(alpha = .35f), Offset(left, size.height - 3.dp.toPx()), Size(width, 3.dp.toPx()), CornerRadius(1.5f.dp.toPx()))
                continue
            }
            val alpha = if (day == last) 1f else .55f
            var y = size.height
            STACK.forEach { stage ->
                val minutes = figure.stageMinutes[stage] ?: return@forEach
                val height = size.height * minutes / maxMinutes
                drawRect(laneColour(stage).copy(alpha = alpha), Offset(left, y - height), Size(width, (height - .5f).coerceAtLeast(.5f)))
                y -= height
            }
        }
    }
}

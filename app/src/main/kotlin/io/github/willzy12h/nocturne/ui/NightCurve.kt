package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.Duration
import java.time.ZonedDateTime

/**
 * The night as bars over time: up for heat, down for cooling, height for strength. A dashed line marks the
 * wake time and a solid one marks now. [legend] names the colours underneath (the light layout shows it).
 * Preview cards stay passive; editor and Tonight callers opt into [phaseInteraction].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NightCurve(plan: Plan, modifier: Modifier = Modifier, now: ZonedDateTime? = null, legend: Boolean = false,
    end: PlanEnd? = null, phaseInteraction: Boolean = false, onEditPhase: ((PlannedStep) -> Unit)? = null) {
    val total = Duration.between(plan.start, plan.end).toMinutes().coerceAtLeast(1).toFloat()
    val outline = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val wakeColor = nocturneColors.accent
    // Read once here: the Canvas draw block isn't composable.
    val colors = nocturneColors
    val fahrenheit = LocalTemperatureFahrenheit.current
    var selected by remember(plan, phaseInteraction) { mutableStateOf<Int?>(null) }
    val currentEdit by rememberUpdatedState(onEditPhase)
    val details = plan.steps.map { step ->
        if (step.step.mode.usesTemperature || step.step.mode == Mode.TURBO)
            stringResource(R.string.ux_curve_phase_target, modeName(step.step.mode), Format.clock(step.startsAt),
                Format.clock(step.endsAt), tempText(step.step.temperature, fahrenheit))
        else stringResource(R.string.ux_curve_phase_without_target, modeName(step.step.mode), Format.clock(step.startsAt), Format.clock(step.endsAt))
    }
    val showDetails = stringResource(R.string.ux_curve_details)
    // A kept manual run has no wake programme: it is described, labelled and drawn by its own end instead.
    val description = stringResource(R.string.curve_description, Format.clock(plan.start), Format.clock(plan.end),
        end?.let { "${it.label} ${Format.clock(it.at)}" } ?: stringResource(R.string.curve_wake_lower, Format.clock(plan.wake)),
        plan.steps.map { step ->
            when {
                step.step.mode.usesTemperature -> pluralStringResource(R.plurals.curve_step_temperature, step.minutes, Format.clock(step.startsAt), blockName(step.block),
                    modeName(step.step.mode), (step.step.fanStep + 1) * 5, step.minutes, tempText(step.step.temperature, fahrenheit))
                step.step.mode.usesFan -> pluralStringResource(R.plurals.curve_step_without_temperature, step.minutes, Format.clock(step.startsAt), blockName(step.block),
                    modeName(step.step.mode), (step.step.fanStep + 1) * 5, step.minutes)
                else -> pluralStringResource(R.plurals.curve_step_without_fan, step.minutes, Format.clock(step.startsAt), blockName(step.block),
                    modeName(step.step.mode), step.minutes)
            }
        }.joinToString("; "))
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp).testTag("night-curve")
            .then(if (!phaseInteraction) Modifier else Modifier.pointerInput(plan) {
                fun phaseAt(x: Float): Int? {
                    val minute = (x / size.width.coerceAtLeast(1) * total).coerceIn(0f, total)
                    return plan.steps.indexOfFirst { Duration.between(plan.start, it.endsAt).toMinutes() > minute }
                        .takeIf { it >= 0 } ?: plan.steps.lastIndex.takeIf { it >= 0 }
                }
                detectTapGestures(onTap = { selected = phaseAt(it.x) }, onLongPress = { point ->
                    selected = phaseAt(point.x)
                    selected?.let { currentEdit?.invoke(plan.steps[it]) }
                })
            }).semantics {
                contentDescription = description
                if (phaseInteraction) {
                    onClick(label = showDetails) {
                        if (plan.steps.isEmpty()) false else { selected = ((selected ?: -1) + 1) % plan.steps.size; true }
                    }
                    customActions = details.mapIndexed { index, detail -> CustomAccessibilityAction(detail) { selected = index; true } }
                }
            }) {
            val mid = size.height / 2
            drawLine(outline, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            for (step in plan.steps) {
                val x = Duration.between(plan.start, step.startsAt).toMinutes() / total * size.width
                val w = (step.minutes / total * size.width).coerceAtLeast(2f)
                val level = level(step.step.mode, step.step.temperature, (step.step.fanStep + 1) * 5)
                val h = (kotlin.math.abs(level) * (mid - 4)).coerceAtLeast(3f)
                val top = if (level >= 0) mid - h else mid
                val color = colors.curveMode(step.step.mode, step.block == Block.AFTER_WAKE)
                drawRoundRect(color, Offset(x + 1, top), Size(w - 2, h), CornerRadius(4.dp.toPx()))
            }
            val wakeX = Duration.between(plan.start, plan.wake).toMinutes() / total * size.width
            if (end == null) drawLine(wakeColor, Offset(wakeX, 0f), Offset(wakeX, size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            if (now != null && now.isAfter(plan.start) && now.isBefore(plan.end)) {
                val nowX = Duration.between(plan.start, now).toMinutes() / total * size.width
                drawLine(nowColor, Offset(nowX, 0f), Offset(nowX, size.height), 2.dp.toPx())
            }
        }
        selected?.let { index ->
            Text(details[index], style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("curve-phase-detail").padding(vertical = 8.dp))
        }
        now?.let(plan::stepAt)?.second?.let { step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActiveIndicator(colors.mode(step.step.mode), true, Modifier.testTag("night-active-phase"))
                Text(stringResource(R.string.ux_current_phase, blockName(step.block)), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(Format.clock(plan.start), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
            Text(end?.let { "${it.label} ${Format.clock(it.at)}" } ?: stringResource(R.string.curve_wake, Format.clock(plan.wake)), style = MaterialTheme.typography.labelSmall,
                color = if (end == null) wakeColor else muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(Format.clock(plan.end), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        if (legend) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                plan.steps.map { it.step.mode }.distinct().forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(colors.mode(mode), 10)
                        Text(modeName(mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/** How a plan with no wake programme (a kept manual run) ends: "Manual run until", or "Planned end" while unconfirmed. */
data class PlanEnd(val label: String, val at: ZonedDateTime)

/** -1 is the strongest cooling, +1 the strongest heat. */
private fun level(mode: Mode, temperature: Int, fanPercent: Int): Float = when (mode) {
    Mode.COOL -> -(0.25f + 0.75f * fanPercent / 100f)
    Mode.DRY -> -0.15f
    Mode.STANDBY, Mode.WAIT -> 0.05f
    Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO -> 0.2f + 0.8f * (temperature - 38) / 48f
}

@Composable
fun Dot(color: Color, sizeDp: Int = 12, modifier: Modifier = Modifier) {
    Box(modifier.size(sizeDp.dp).background(color, CircleShape))
}

/** Consecutive plan steps of the same block and mode, which simple view shows as one line. */
data class TimelineEntry(
    val block: Block,
    val mode: Mode,
    val startsAt: ZonedDateTime,
    val minutes: Int,
    val fanStart: Int,
    val fanEnd: Int,
    val tempStart: Int,
    val tempEnd: Int,
) {
    val endsAt: ZonedDateTime get() = startsAt.plusMinutes(minutes.toLong())

    // Cooling is felt as fan speed, so only the heating modes name a temperature.
    private val heats: Boolean get() = mode == Mode.HEAT || mode == Mode.EXTENDED_HEAT || mode == Mode.TURBO

    /** "Cool, easing off" in English, for Context-free callers; screens use [title] with their [Format.Words]. */
    val title: String get() = title(null)

    /** "Cool, easing off": the mode, and which way the step is heading when it changes. */
    fun title(words: Format.Words?): String {
        val trend = when {
            heats && tempEnd > tempStart -> words?.text(R.string.timeline_warming_up) ?: "warming up"
            heats && tempEnd < tempStart -> words?.text(R.string.timeline_easing_off) ?: "easing off"
            fanEnd < fanStart -> words?.text(R.string.timeline_easing_off) ?: "easing off"
            fanEnd > fanStart -> words?.text(R.string.timeline_building) ?: "building"
            else -> null
        }
        val name = Format.modeName(mode, words)
        return trend?.let { words?.text(R.string.timeline_title_trend, name, it) ?: "$name, $it" } ?: name
    }

    /** "7 h · fan 75% → 40%", with the temperature first for modes that heat. English unless [words] is given. */
    fun detail(fahrenheit: Boolean, words: Format.Words? = null): String {
        val parts = mutableListOf(Format.duration(minutes, words))
        if (heats) {
            parts += if (tempStart == tempEnd) Format.temp(tempStart, fahrenheit, words)
                else "${Format.temp(tempStart, fahrenheit, words)} → ${Format.temp(tempEnd, fahrenheit, words)}"
        }
        if (mode != Mode.STANDBY && mode != Mode.WAIT) parts += if (fanStart == fanEnd) words?.text(R.string.timeline_fan, fanStart) ?: "fan $fanStart%"
            else words?.text(R.string.timeline_fan_range, fanStart, fanEnd) ?: "fan $fanStart% → $fanEnd%"
        return parts.joinToString(" · ")
    }
}

/** The plan as simple view lists it. Each block's ramp of small steps becomes one entry. */
fun timelineEntries(plan: Plan): List<TimelineEntry> {
    val entries = mutableListOf<TimelineEntry>()
    for (planned in plan.steps) {
        val fan = (planned.step.fanStep + 1) * 5
        val last = entries.lastOrNull()
        entries += if (last != null && last.block == planned.block && last.mode == planned.step.mode) {
            entries.removeAt(entries.lastIndex)
            last.copy(minutes = last.minutes + planned.minutes, fanEnd = fan, tempEnd = planned.step.temperature)
        } else {
            TimelineEntry(planned.block, planned.step.mode, planned.startsAt, planned.minutes, fan, fan, planned.step.temperature, planned.step.temperature)
        }
    }
    return entries
}

/**
 * Simple view: the night as a list, a time on the left and a coloured dot per step joined by a line. It ends at
 * the wake time; anything that runs after waking is summarised on that last line.
 */
@Composable
fun NightTimeline(plan: Plan, origin: WakeOrigin, fahrenheit: Boolean, modifier: Modifier = Modifier,
    now: ZonedDateTime? = null, end: PlanEnd? = null, onEditPhase: ((TimelineEntry) -> Unit)? = null) {
    val entries = timelineEntries(plan)
    val (before, after) = entries.partition { it.block != Block.AFTER_WAKE }
    val colors = nocturneColors
    val words = rememberWords()
    Column(modifier) {
        before.forEach { entry ->
            val current = now != null && !now.isBefore(entry.startsAt) && now.isBefore(entry.endsAt)
            TimelineRow(
                time = Format.clock(entry.startsAt),
                dot = colors.mode(entry.mode),
                title = if (entry.block == Block.TEMPORARY) stringResource(R.string.temporary_timeline_title, entry.title(words)) else entry.title(words),
                detail = if (current) stringResource(R.string.timeline_now, entry.detail(fahrenheit, words)) else entry.detail(fahrenheit, words),
                titleColor = MaterialTheme.colorScheme.onSurface,
                bold = current,
                last = false,
                active = current,
                onEdit = onEditPhase?.let { edit -> { edit(entry) } },
            )
        }
        if (end != null) {
            TimelineRow(time = Format.clock(end.at), dot = colors.idle, title = end.label, detail = null,
                titleColor = MaterialTheme.colorScheme.onSurface, bold = true, last = true)
            return@Column
        }
        TimelineRow(
            time = Format.clock(plan.wake),
            dot = colors.accent,
            title = wakeOriginLabel(origin, WakeLabelStyle.TIMELINE),
            detail = after.takeIf { it.isNotEmpty() }?.let { rest ->
                stringResource(R.string.timeline_then, rest.map { stringResource(R.string.timeline_after_item, modeName(it.mode).lowercase(), durationText(it.minutes)) }
                    .joinToString(", "))
            },
            titleColor = colors.accent,
            bold = true,
            last = true,
            active = now != null && after.any { !now.isBefore(it.startsAt) && now.isBefore(it.endsAt) },
            onEdit = after.firstOrNull()?.let { entry -> onEditPhase?.let { edit -> { edit(entry) } } },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TimelineRow(time: String, dot: Color, title: String, detail: String?, titleColor: Color, bold: Boolean,
    last: Boolean, active: Boolean = false, onEdit: (() -> Unit)? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val editLabel = stringResource(R.string.ux_edit_phase)
    // One node for TalkBack: "23:10, Cool, easing off, 7 h · fan 75% → 40%".
    Row(Modifier.fillMaxWidth().heightIn(min = if (onEdit == null) 0.dp else 48.dp).height(IntrinsicSize.Min)
        .then(if (onEdit == null) Modifier else Modifier.combinedClickable(role = Role.Button,
            onClickLabel = editLabel, onLongClickLabel = editLabel, onClick = onEdit, onLongClick = onEdit))
        .semantics(mergeDescendants = true) {}) {
        Text(time, style = MaterialTheme.typography.labelLarge, color = muted, softWrap = false, maxLines = 1,
            modifier = Modifier.widthIn(min = 52.dp).width(IntrinsicSize.Max).padding(top = 2.dp))
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            ActiveIndicator(dot, active, Modifier.padding(top = 4.dp).then(if (active) Modifier.testTag("night-active-phase") else Modifier), sizeDp = 12)
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (last) 0.dp else 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, fontWeight = if (bold) FontWeight.SemiBold else null)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        }
    }
}

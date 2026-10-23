package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.Duration
import java.time.ZonedDateTime

/**
 * The night as bars over time: up for heat, down for cooling, height for strength. A dashed line marks the
 * wake time and a solid one marks now. [legend] names the colours underneath (the light layout shows it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NightCurve(plan: Plan, modifier: Modifier = Modifier, now: ZonedDateTime? = null, legend: Boolean = false) {
    val total = Duration.between(plan.start, plan.end).toMinutes().coerceAtLeast(1).toFloat()
    val outline = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val wakeColor = nocturneColors.accent
    // Read once here: the Canvas draw block isn't composable.
    val colors = nocturneColors
    val description = "Night plan from ${Format.clock(plan.start)} to ${Format.clock(plan.end)}; wake ${Format.clock(plan.wake)}. " +
        plan.steps.joinToString("; ") { step ->
            "${Format.clock(step.startsAt)} ${blockName(step.block)} ${Format.modeName(step.step.mode)}, fan ${(step.step.fanStep + 1) * 5}%, ${step.minutes} minutes"
        }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = description }) {
            val mid = size.height / 2
            drawLine(outline, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            for (step in plan.steps) {
                val x = Duration.between(plan.start, step.startsAt).toMinutes() / total * size.width
                val w = (step.minutes / total * size.width).coerceAtLeast(2f)
                val level = level(step.step.mode, step.step.temperature, (step.step.fanStep + 1) * 5)
                val h = (kotlin.math.abs(level) * (mid - 4)).coerceAtLeast(3f)
                val top = if (level >= 0) mid - h else mid
                val color = colors.mode(step.step.mode).copy(alpha = if (step.block == Block.AFTER_WAKE) 0.55f else 1f)
                drawRoundRect(color, Offset(x + 1, top), Size(w - 2, h), CornerRadius(4.dp.toPx()))
            }
            val wakeX = Duration.between(plan.start, plan.wake).toMinutes() / total * size.width
            drawLine(wakeColor, Offset(wakeX, 0f), Offset(wakeX, size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            if (now != null && now.isAfter(plan.start) && now.isBefore(plan.end)) {
                val nowX = Duration.between(plan.start, now).toMinutes() / total * size.width
                drawLine(nowColor, Offset(nowX, 0f), Offset(nowX, size.height), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(Format.clock(plan.start), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
            Text("Wake ${Format.clock(plan.wake)}", style = MaterialTheme.typography.labelSmall, color = wakeColor, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(Format.clock(plan.end), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        if (legend) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                plan.steps.map { it.step.mode }.distinct().forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(colors.mode(mode), 10)
                        Text(Format.modeName(mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

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

    /** "Cool, easing off": the mode, and which way the step is heading when it changes. */
    val title: String get() {
        val trend = when {
            heats && tempEnd > tempStart -> "warming up"
            heats && tempEnd < tempStart -> "easing off"
            fanEnd < fanStart -> "easing off"
            fanEnd > fanStart -> "building"
            else -> null
        }
        return Format.modeName(mode) + (trend?.let { ", $it" } ?: "")
    }

    /** "7 h · fan 75% → 40%", with the temperature first for modes that heat. */
    fun detail(fahrenheit: Boolean): String {
        val parts = mutableListOf(Format.duration(minutes))
        if (heats) {
            parts += if (tempStart == tempEnd) Format.temp(tempStart, fahrenheit) else "${Format.temp(tempStart, fahrenheit)} → ${Format.temp(tempEnd, fahrenheit)}"
        }
        if (mode != Mode.STANDBY && mode != Mode.WAIT) parts += if (fanStart == fanEnd) "fan $fanStart%" else "fan $fanStart% → $fanEnd%"
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
fun NightTimeline(plan: Plan, origin: WakeOrigin, fahrenheit: Boolean, modifier: Modifier = Modifier, now: ZonedDateTime? = null) {
    val entries = timelineEntries(plan)
    val (before, after) = entries.partition { it.block != Block.AFTER_WAKE }
    val colors = nocturneColors
    Column(modifier) {
        before.forEach { entry ->
            val current = now != null && !now.isBefore(entry.startsAt) && now.isBefore(entry.endsAt)
            TimelineRow(
                time = Format.clock(entry.startsAt),
                dot = colors.mode(entry.mode),
                title = entry.title,
                detail = entry.detail(fahrenheit) + if (current) " · now" else "",
                titleColor = MaterialTheme.colorScheme.onSurface,
                bold = current,
                last = false,
            )
        }
        TimelineRow(
            time = Format.clock(plan.wake),
            dot = colors.accent,
            title = when (origin) {
                WakeOrigin.ALARM -> stringResource(R.string.wake_origin_alarm)
                WakeOrigin.OVERRIDE -> stringResource(R.string.wake_origin_override)
                WakeOrigin.PROFILE_DEFAULT -> stringResource(R.string.wake_time)
            },
            detail = after.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Then ") { "${Format.modeName(it.mode).lowercase()} for ${Format.duration(it.minutes)}" },
            titleColor = colors.accent,
            bold = true,
            last = true,
        )
    }
}

@Composable
private fun TimelineRow(time: String, dot: Color, title: String, detail: String?, titleColor: Color, bold: Boolean, last: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // One node for TalkBack: "23:10, Cool, easing off, 7 h · fan 75% → 40%".
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
        Text(time, style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.width(52.dp).padding(top = 2.dp))
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Dot(dot, 12, Modifier.padding(top = 4.dp))
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (last) 0.dp else 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, fontWeight = if (bold) FontWeight.SemiBold else null)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        }
    }
}

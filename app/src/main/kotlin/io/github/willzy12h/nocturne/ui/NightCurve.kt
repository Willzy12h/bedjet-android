package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.Duration
import java.time.ZonedDateTime

/**
 * The night as bars over time: up for heat, down for cooling, height for strength. A dashed line marks the
 * wake time and a solid one marks now.
 */
@Composable
fun NightCurve(plan: Plan, modifier: Modifier = Modifier, now: ZonedDateTime? = null) {
    val total = Duration.between(plan.start, plan.end).toMinutes().coerceAtLeast(1).toFloat()
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val mid = size.height / 2
            drawLine(outline.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            for (step in plan.steps) {
                val x = Duration.between(plan.start, step.startsAt).toMinutes() / total * size.width
                val w = (step.minutes / total * size.width).coerceAtLeast(2f)
                val level = level(step.step.mode, step.step.temperature, (step.step.fanStep + 1) * 5)
                val h = (kotlin.math.abs(level) * (mid - 4)).coerceAtLeast(3f)
                val top = if (level >= 0) mid - h else mid
                val color = modeColor(step.step.mode).copy(alpha = if (step.block == Block.AFTER_WAKE) 0.55f else 1f)
                drawRoundRect(color, Offset(x + 1, top), Size(w - 2, h), CornerRadius(4.dp.toPx()))
            }
            val wakeX = Duration.between(plan.start, plan.wake).toMinutes() / total * size.width
            drawLine(primary, Offset(wakeX, 0f), Offset(wakeX, size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            if (now != null && now.isAfter(plan.start) && now.isBefore(plan.end)) {
                val nowX = Duration.between(plan.start, now).toMinutes() / total * size.width
                drawLine(outline, Offset(nowX, 0f), Offset(nowX, size.height), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Format.clock(plan.start), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("Wake ${Format.clock(plan.wake)}", style = MaterialTheme.typography.labelSmall, color = primary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(Format.clock(plan.end), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
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

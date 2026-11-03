package io.github.willzy12h.nocturne.ui.features

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.platform.SleepStage
import io.github.willzy12h.nocturne.ui.durationText
import io.github.willzy12h.nocturne.ui.paletteTokens
import java.time.Duration
import java.time.Instant

/**
 * The rows of the sleep chart, top to bottom as in a hypnogram. Health Connect's stage codes map onto them:
 * awake and out of bed are Awake; "sleeping" without a stage is Asleep; light, deep and REM are their own.
 */
enum class SleepLane(@StringRes val label: Int, val colour: Color) {
    AWAKE(R.string.feature_stage_awake_short, Color(0xFFFFB25B)),
    REM(R.string.feature_stage_rem_short, Color(0xFFB48CFF)),
    LIGHT(R.string.feature_stage_light_short, Color(0xFF6EC6FF)),
    ASLEEP(R.string.feature_stage_sleeping, Color(0xFF8FA3C8)),
    DEEP(R.string.feature_stage_deep_short, Color(0xFF4A63E0));

    companion object {
        fun of(stage: Int): SleepLane? = when (stage) {
            1, 3 -> AWAKE
            2 -> ASLEEP
            4 -> LIGHT
            5 -> DEEP
            6 -> REM
            else -> null
        }
    }
}

/** Whole minutes in each lane, in lane order; lanes with no time are left out. */
fun sleepLaneMinutes(stages: List<SleepStage>): List<Pair<SleepLane, Int>> {
    val totals = stages.mapNotNull { stage -> SleepLane.of(stage.stage)?.let { it to Duration.between(stage.start, stage.end).toMinutes().coerceAtLeast(0) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, minutes) -> minutes.sum().toInt() }
    return SleepLane.entries.mapNotNull { lane -> totals[lane]?.takeIf { it > 0 }?.let { lane to it } }
}

/**
 * Health Connect's stages as a coloured hypnogram, with the BedJet's programme drawn on the same time axis
 * underneath, so a warm or cool step can be read against the sleep it ran through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepStageChart(stages: List<SleepStage>, start: Instant, end: Instant, plan: Plan?) {
    val tokens = paletteTokens
    val totals = sleepLaneMinutes(stages)
    val lanes = SleepLane.entries.filter { it != SleepLane.ASLEEP || stages.any { s -> SleepLane.of(s.stage) == it } }
    val span = Duration.between(start, end).toMillis().coerceAtLeast(1).toFloat()
    val words = totals.map { (lane, minutes) -> stringResource(R.string.feature_stage_total, stringResource(lane.label), durationText(minutes)) }
    val summary = stringResource(R.string.feature_stage_chart_accessibility, words.joinToString(", "))
    val laneHeight = 20.dp
    val stripHeight = 14.dp
    val stripGap = 6.dp
    val labelWidth = 52.dp
    Column(Modifier.fillMaxWidth().testTag("sleep-stage-chart"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary }) {
            Column(Modifier.width(labelWidth)) {
                lanes.forEach { lane ->
                    Box(Modifier.height(laneHeight), contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(lane.label), color = tokens.ink2, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
                    }
                }
                if (plan != null) Box(Modifier.padding(top = stripGap).height(stripHeight), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.feature_stage_bedjet), color = tokens.ink2, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
                }
            }
            val guide = tokens.ink3.copy(alpha = .35f)
            Canvas(Modifier.weight(1f).height(laneHeight * lanes.size + if (plan != null) stripGap + stripHeight else 0.dp)) {
                val lane = laneHeight.toPx()
                fun x(at: Instant) = (Duration.between(start, at).toMillis() / span * size.width).coerceIn(0f, size.width)
                lanes.indices.forEach { i ->
                    drawLine(guide, Offset(0f, lane * i + lane / 2), Offset(size.width, lane * i + lane / 2), 1f)
                }
                var previous: Pair<Float, Float>? = null
                stages.sortedBy { it.start }.forEach { stage ->
                    val row = SleepLane.of(stage.stage)?.let(lanes::indexOf)?.takeIf { it >= 0 } ?: return@forEach
                    val left = x(stage.start)
                    val right = maxOf(x(stage.end), left + 1.5f)
                    val top = lane * row + 2.dp.toPx()
                    // A thin riser joins each stage to the one before it, as in a hypnogram.
                    previous?.let { (px, py) -> if (left - px < 4.dp.toPx()) drawLine(guide.copy(alpha = .8f), Offset(left, py), Offset(left, top + (lane - 4.dp.toPx()) / 2), 1.5f) }
                    drawRoundRect(lanes[row].colour, Offset(left, top), Size(right - left, lane - 4.dp.toPx()), CornerRadius(3.dp.toPx()))
                    previous = right to top + (lane - 4.dp.toPx()) / 2
                }
                if (plan != null) {
                    val top = lane * lanes.size + stripGap.toPx()
                    plan.steps.filter { it.minutes > 0 && it.step.mode != Mode.WAIT }.forEach { step ->
                        val from = step.startsAt.toInstant()
                        val left = x(from)
                        val right = x(from.plusSeconds(step.minutes * 60L))
                        if (right > left) drawRoundRect(tokens.stepColor(step.step.mode, step.step.temperature), Offset(left, top),
                            Size(right - left, stripHeight.toPx()), CornerRadius(3.dp.toPx()))
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            totals.forEach { (lane, minutes) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("sleep-total-${lane.name}")) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(lane.colour))
                    Text(stringResource(lane.label), color = tokens.ink2, fontSize = 13.sp)
                    Text(durationText(minutes), color = tokens.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** The coloured key for a lane, for use beside a single stage line. */
@Composable
internal fun SleepLaneDot(lane: SleepLane) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(lane.colour))
}

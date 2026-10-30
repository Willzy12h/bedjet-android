package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Endpoint text reserves its own width; all three centres follow the actual notches, including at 200% text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScaledSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier,
    ticks: List<Pair<Float, String>>,
    tag: String,
    onValueChangeFinished: () -> Unit,
    colors: SliderColors = nocturneSliderColors(),
) {
    val style = MaterialTheme.typography.labelMedium
    if (!value.isFinite() || !range.start.isFinite() || !range.endInclusive.isFinite() ||
        range.start > range.endInclusive || ticks.isEmpty() || ticks.any { !it.first.isFinite() } || steps < 0) {
        Text(stringResource(R.string.remote_control_unavailable), style = style,
            modifier = modifier.fillMaxWidth().testTag("$tag-unavailable"))
        return
    }
    if (range.start == range.endInclusive) {
        // A unit may report one permitted value. There is no adjustable range or three distinct notches.
        Text(ticks[ticks.size / 2].second, style = style, textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().testTag("$tag-tick-1"))
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val widths = ticks.map { measurer.measure(AnnotatedString(it.second), style).size.width }
    val thumbRadius = with(density) { 10.dp.toPx() }
    val edge = maxOf(thumbRadius, (maxOf(widths.first(), widths.last()) + 1) / 2f)
    val inset = with(density) { (edge - thumbRadius).toDp() }
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Slider(value, onValueChange, modifier = modifier.fillMaxWidth().padding(horizontal = inset),
            valueRange = range, steps = steps, enabled = enabled, onValueChangeFinished = onValueChangeFinished,
            interactionSource = interaction,
            thumb = { SliderDefaults.Thumb(interaction, enabled = enabled, thumbSize = DpSize(20.dp, 20.dp), colors = colors) }, colors = colors)
        Layout(content = {
            ticks.forEachIndexed { index, (_, text) -> Text(text, style = style, modifier = Modifier.testTag("$tag-tick-$index")) }
        }, modifier = Modifier.fillMaxWidth()) { children, constraints ->
            val labels = children.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, labels.maxOf { it.height }) {
                labels.forEachIndexed { index, label ->
                    val fraction = ((ticks[index].first - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val centre = edge + fraction * (constraints.maxWidth - 2 * edge)
                    label.placeRelative((centre - label.width / 2f).roundToInt(), 0)
                }
            }
        }
    }
}

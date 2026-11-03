package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RangeSliderState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.willzy12h.nocturne.core.protocol.Mode
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
    colors: SliderColors = amountSliderColors(),
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
    val thumbRadius = with(density) { (SLIDER_THUMB / 2).toPx() }
    val edge = maxOf(thumbRadius, (maxOf(widths.first(), widths.last()) + 1) / 2f)
    val inset = with(density) { (edge - thumbRadius).toDp() }
    Column(Modifier.fillMaxWidth()) {
        NocturneSlider(value, onValueChange, modifier = modifier.fillMaxWidth().padding(horizontal = inset),
            valueRange = range, steps = steps, enabled = enabled, onValueChangeFinished = onValueChangeFinished, colors = colors)
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

private val SLIDER_THUMB = 26.dp
private val SLIDER_TRACK = 10.dp

/** The thumb's light ring: the palette's ink on dark grounds, white on Overcast. */
@Composable
private fun thumbRing() = if (paletteTokens.dark) paletteTokens.ink else Color.White

@Composable
private fun SliderThumb(colour: Color) =
    Box(Modifier.size(SLIDER_THUMB).background(thumbRing(), CircleShape).padding(4.dp).background(colour, CircleShape))

/**
 * The app's one slider shape (approved slider design): a 10dp track filled in [colors]' active colour over a
 * tint of it, and a 26dp thumb in the same colour with a light ring. Colour by role with [sliderColors].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NocturneSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = sliderColors(paletteTokens.accent),
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(value, onValueChange, modifier, enabled = enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished, colors = colors, interactionSource = interaction,
        thumb = { SliderThumb(if (enabled) colors.thumbColor else colors.disabledThumbColor) },
        track = { state ->
            SliderDefaults.Track(state, Modifier.height(SLIDER_TRACK), enabled = enabled, colors = colors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = SLIDER_TRACK / 2)
        })
}

/**
 * A two-thumb range in the same shape. The fill blends from [startColour] to [endColour] and each thumb takes
 * its own end's colour, so a temperature range reads coolest to hottest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NocturneRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    startColour: Color,
    endColour: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = paletteTokens
    val start = if (enabled) tokens.visible(startColour) else tokens.ink3
    val end = if (enabled) tokens.visible(endColour) else tokens.ink3
    val rest = tokens.ink3.copy(alpha = if (tokens.dark) .25f else .3f)
    RangeSlider(value, onValueChange, modifier, enabled = enabled, valueRange = valueRange, steps = steps,
        startThumb = { SliderThumb(start) }, endThumb = { SliderThumb(end) },
        track = { state: RangeSliderState ->
            Canvas(Modifier.fillMaxWidth().height(SLIDER_TRACK)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(rest, cornerRadius = radius)
                val span = state.valueRange.endInclusive - state.valueRange.start
                if (span > 0f) {
                    val from = (state.activeRangeStart - state.valueRange.start) / span * size.width
                    val to = (state.activeRangeEnd - state.valueRange.start) / span * size.width
                    drawRoundRect(Brush.horizontalGradient(listOf(start, end), from, to), Offset(from, 0f),
                        Size((to - from).coerceAtLeast(size.height), size.height), radius)
                }
            }
        })
}

/** What a slider sets decides its colour; see the approved slider map. */
@Composable
internal fun sliderColors(role: Color): SliderColors {
    val tokens = paletteTokens
    val colour = tokens.visible(role)
    // Overcast's light track needs a stronger tint to stay visible against its pale surfaces.
    val tint = if (tokens.dark) .22f else .3f
    return SliderDefaults.colors(
        thumbColor = colour, activeTrackColor = colour, inactiveTrackColor = colour.copy(alpha = tint),
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        disabledThumbColor = tokens.ink3, disabledActiveTrackColor = tokens.ink3.copy(alpha = .5f),
        disabledInactiveTrackColor = tokens.ink3.copy(alpha = .2f),
    )
}

/** Temperatures take the value's own colour: Ember when heating, Cool blue or Dry sand. */
@Composable
internal fun temperatureSliderColors(mode: Mode, temperature: Int) = sliderColors(paletteTokens.stepColor(mode, temperature))

/** A fan speed takes the colour of the mode it belongs to. */
@Composable
internal fun fanSliderColors(mode: Mode) = sliderColors(paletteTokens.modeColor(mode))

/** Minutes, counts, steps and offsets use the palette's accent, so they follow the chosen theme. */
@Composable
internal fun amountSliderColors() = sliderColors(paletteTokens.accent)

/** Three labelled marks: both ends and the middle, rounded to whole units. */
@Composable
internal fun temperatureTicks(range: IntRange, fahrenheit: Boolean): List<Pair<Float, String>> =
    listOf(range.first, (range.first + range.last) / 2, range.last).map { it.toFloat() to tempText(it, fahrenheit) }

@Composable
internal fun fanTicks(): List<Pair<Float, String>> =
    listOf(5, 50, 100).map { it.toFloat() to stringResource(R.string.remote_percent_tick, it) }

@Composable
internal fun minuteTicks(minimum: Int, maximum: Int): List<Pair<Float, String>> =
    listOf(minimum, (minimum + maximum) / 2, maximum).map { it.toFloat() to durationText(it) }

package io.github.willzy12h.nocturne.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Settings

private val ReadingIconSize = 22.dp
private val ReadingCoolTravel = 2.dp
private val ReadingIconSpacing = 6.dp

/** Reserve the air reading and icon together, including when the units or number of digits change. */
@Composable
internal fun MeasuredTemperatures(reading: DeviceStatus, settings: Settings, recent: Boolean, tag: String,
    boost: Boolean = false) {
    val tokens = paletteTokens
    val air = tempText(reading.actualTemperature, settings.fahrenheit)
    val room = tempText(reading.ambientTemperature, settings.fahrenheit)
    val roomLabel = stringResource(R.string.home_air_from_bedjet)
    val spokenReadings = stringResource(R.string.remote_air_room, air, room)
    val freshness = stringResource(when {
        settings.demoMode -> R.string.reading_demo
        recent -> R.string.reading_recent
        else -> R.string.remote_last_reported
    })
    val density = LocalDensity.current
    val largeText = density.fontScale >= 1.5f
    val measurer = rememberTextMeasurer()
    val airStyle = MaterialTheme.typography.displayLarge.copy(fontSize = if (largeText) 40.sp else 64.sp,
        lineHeight = if (largeText) 48.sp else 68.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val roomStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum")
    val roomLabelStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
    // Both measurements are unsigned half-Celsius bytes on the wire. Measure the widest display case in either unit.
    val widestCelsius = tempText(255, false)
    val widestFahrenheit = tempText(255, true)
    val roomCelsius = stringResource(R.string.reading_room, widestCelsius)
    val roomFahrenheit = stringResource(R.string.reading_room, widestFahrenheit)
    fun widthOf(text: String, style: TextStyle) =
        with(density) { measurer.measure(text, style = style, maxLines = 1, softWrap = false).size.width.toDp() }
    val airWidth = maxOf(widthOf(widestCelsius, airStyle), widthOf(widestFahrenheit, airStyle))
    val actualAirWidth = widthOf(air, airStyle)
    val roomWidth = maxOf(widthOf(roomLabel, roomLabelStyle), widthOf(roomCelsius, roomStyle), widthOf(roomFahrenheit, roomStyle))
    val columnSpacing = 14.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = !largeText && maxWidth >= 360.dp
        val reservedRoom = if (horizontal) minOf(roomWidth, maxWidth * .4f) + columnSpacing else 0.dp
        val availableAir = (maxWidth - ReadingIconSize - ReadingCoolTravel - ReadingIconSpacing - reservedRoom).coerceAtLeast(1.dp)
        // Fit an exceptionally wide reading inside its slot without moving the room column or icon.
        val fittedStyle = if (actualAirWidth > availableAir)
            airStyle.copy(fontSize = airStyle.fontSize * (availableAir / actualAirWidth)) else airStyle
        val airGroup: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ReadingIconSpacing)) {
                Text(air, modifier = Modifier.width(minOf(availableAir, airWidth)).testTag("$tag-temperature").semantics {
                    contentDescription = spokenReadings
                    stateDescription = freshness
                }, style = fittedStyle, color = tokens.ink, maxLines = 1, softWrap = false)
                ReadingModeIcon(reading.mode, recent && !settings.simpleView && !settings.demoMode, boost)
            }
        }
        val roomGroup: @Composable () -> Unit = {
            Column {
                Text(roomLabel, modifier = Modifier.testTag("$tag-reading-context"), color = tokens.ink2, style = roomLabelStyle)
                Text(stringResource(R.string.reading_room, room), modifier = Modifier.testTag("$tag-room"),
                    color = tokens.ink, style = roomStyle)
            }
        }
        if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(columnSpacing)) {
            airGroup()
            roomGroup()
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            airGroup()
            roomGroup()
        }
    }
}

@Composable
private fun ReadingModeIcon(mode: Mode, recent: Boolean, boost: Boolean) {
    val heating = mode == Mode.HEAT || mode == Mode.EXTENDED_HEAT
    val cooling = mode == Mode.COOL
    val icon = readingModeIcon(mode, boost)
    val animated = !boost && (heating || cooling) && recent && rememberMotionAllowed()
    val motion = if (animated) {
        val strength by rememberInfiniteTransition(label = "Reported operating mode").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Airflow or heat")
        Modifier.drawWithContent {
            if (cooling) translate(left = ReadingCoolTravel.toPx() * strength) { this@drawWithContent.drawContent() }
            else scale(scaleX = 1f, scaleY = 1f - .12f * strength) { this@drawWithContent.drawContent() }
        }
    } else Modifier
    val tokens = paletteTokens
    // Keep airflow motion inside the fixed slot, including where the icon meets the edge of a narrow display.
    Box(Modifier.width(ReadingIconSize + ReadingCoolTravel).testTag("reading-mode-icon-slot-${mode.name}-$animated")) {
        Icon(painterResource(icon), contentDescription = null,
            modifier = Modifier.size(ReadingIconSize).then(motion).testTag("reading-mode-icon"),
            tint = tokens.readableText(if (boost) BoostColor else tokens.modeColor(mode), tokens.ground, 3f))
    }
}

internal fun readingModeIcon(mode: Mode, boost: Boolean): Int = when {
    boost -> R.drawable.ic_nc_ramp
    mode == Mode.COOL -> R.drawable.ic_nc_airflow
    else -> modeIcon(mode)
}

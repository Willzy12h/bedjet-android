package io.github.willzy12h.nocturne.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Settings

/** Both tabs show the measured air and room; any target temperature is identified in the mode details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeasuredTemperatures(reading: DeviceStatus, settings: Settings, recent: Boolean, tag: String,
    details: @Composable () -> Unit) {
    val tokens = paletteTokens
    val largeText = LocalConfiguration.current.fontScale >= 1.5f
    val air = tempText(reading.actualTemperature, settings.fahrenheit)
    val room = tempText(reading.ambientTemperature, settings.fahrenheit)
    val spokenReadings = stringResource(R.string.remote_air_room, air, room)
    val freshness = stringResource(when {
        settings.demoMode -> R.string.reading_demo
        recent -> R.string.reading_recent
        else -> R.string.remote_last_reported
    })
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(air, modifier = Modifier.testTag("$tag-temperature").semantics {
                contentDescription = spokenReadings
                stateDescription = freshness
            }, style = MaterialTheme.typography.displayLarge, fontSize = if (largeText) 40.sp else 64.sp,
                lineHeight = if (largeText) 48.sp else 68.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
            ReadingModeIcon(reading.mode, recent && !settings.simpleView && !settings.demoMode)
        }
        Column(Modifier.align(Alignment.CenterVertically)) {
            Text(stringResource(R.string.home_air_from_bedjet), modifier = Modifier.testTag("$tag-reading-context"),
                color = tokens.ink2, fontSize = 13.sp)
            Text(stringResource(R.string.reading_room, room), modifier = Modifier.testTag("$tag-room"),
                color = tokens.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            details()
        }
    }
}

@Composable
private fun ReadingModeIcon(mode: Mode, recent: Boolean) {
    val heating = mode == Mode.HEAT || mode == Mode.EXTENDED_HEAT || mode == Mode.TURBO
    val cooling = mode == Mode.COOL
    val icon = when {
        heating -> R.drawable.ic_nc_heat
        cooling -> R.drawable.ic_nc_airflow
        else -> modeIcon(mode)
    }
    val motion = if ((heating || cooling) && recent && rememberMotionAllowed()) {
        val strength by rememberInfiniteTransition(label = "Reported operating mode").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Airflow or heat")
        Modifier.graphicsLayer {
            if (cooling) translationX = 2.dp.toPx() * strength
            else scaleY = 1f - .12f * strength
        }
    } else Modifier
    val tokens = paletteTokens
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp).then(motion).testTag("reading-mode-icon"),
        tint = tokens.readableText(tokens.modeColor(mode), tokens.ground, 3f))
}

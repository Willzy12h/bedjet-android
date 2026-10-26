package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object Format {
    /**
     * Reads a string resource with its arguments. Screens pass one backed by resources ([rememberWords]); the
     * default `null` keeps the built-in English, for code and plain JVM tests that have no Context.
     */
    fun interface Words {
        fun text(@StringRes id: Int, vararg args: Any): String
    }

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(time: ZonedDateTime): String = time.format(clockFormat)
    fun clock(iso: String): String = runCatching { clock(ZonedDateTime.parse(iso)) }.getOrDefault("--:--")
    fun clockOfDay(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60 % 24, minuteOfDay % 60)

    fun duration(minutes: Int, words: Words? = null): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> words?.text(R.string.minutes_short, m) ?: "$m min"
            m == 0 -> words?.text(R.string.duration_hours, h) ?: "$h h"
            else -> words?.text(R.string.duration_hours_minutes, h, m) ?: "$h h $m min"
        }
    }

    /** Half-degrees Celsius as the person likes to read them. */
    fun temp(halfCelsius: Int, fahrenheit: Boolean, words: Words? = null): String {
        val celsius = halfCelsius / 2.0
        if (fahrenheit) {
            val degrees = (celsius * 9 / 5 + 32).roundToInt()
            return words?.text(R.string.temp_fahrenheit, degrees) ?: "$degrees°F"
        }
        val number = if (halfCelsius % 2 == 0) celsius.roundToInt().toString() else celsius.toString()
        return words?.text(R.string.temp_celsius, number) ?: "$number°C"
    }

    /** The mode's name as a resource, for surfaces outside Compose such as widgets. */
    @StringRes
    fun modeNameRes(mode: Mode): Int = when (mode) {
        Mode.STANDBY -> R.string.mode_off
        Mode.HEAT -> R.string.mode_heat
        Mode.TURBO -> R.string.mode_turbo
        Mode.EXTENDED_HEAT -> R.string.mode_gentle_heat
        Mode.COOL -> R.string.mode_cool
        Mode.DRY -> R.string.mode_dry
        Mode.WAIT -> R.string.mode_pause
    }

    /** The mode's name: the mode_* resource through [words], else the same words in English for Context-free callers. */
    fun modeName(mode: Mode, words: Words? = null): String = words?.text(modeNameRes(mode)) ?: when (mode) {
        Mode.STANDBY -> "Off"
        Mode.HEAT -> "Heat"
        Mode.TURBO -> "Turbo"
        Mode.EXTENDED_HEAT -> "Gentle heat"
        Mode.COOL -> "Cool"
        Mode.DRY -> "Dry"
        Mode.WAIT -> "Pause"
    }
}

/** The mode's name from resources, for screens. */
@Composable
fun modeName(mode: Mode): String = stringResource(Format.modeNameRes(mode))

/** [Format.Words] backed by this screen's resources; refreshed when the configuration (and so the locale) changes. */
@Composable
fun rememberWords(): Format.Words {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { Format.Words { id, args -> context.getString(id, *args) } }
}

/** [Format.temp] in the screen's resources. */
@Composable
fun tempText(halfCelsius: Int, fahrenheit: Boolean): String = Format.temp(halfCelsius, fahrenheit, rememberWords())

/** [Format.duration] in the screen's resources. */
@Composable
fun durationText(minutes: Int): String = Format.duration(minutes, rememberWords())

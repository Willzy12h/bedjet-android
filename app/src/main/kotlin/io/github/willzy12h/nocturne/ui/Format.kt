package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object Format {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(time: ZonedDateTime): String = time.format(clockFormat)
    fun clock(iso: String): String = runCatching { clock(ZonedDateTime.parse(iso)) }.getOrDefault("--:--")
    fun clockOfDay(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60 % 24, minuteOfDay % 60)

    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** Half-degrees Celsius as the person likes to read them. */
    fun temp(halfCelsius: Int, fahrenheit: Boolean): String {
        val celsius = halfCelsius / 2.0
        return if (fahrenheit) "${(celsius * 9 / 5 + 32).roundToInt()}°F" else "${if (halfCelsius % 2 == 0) celsius.roundToInt().toString() else celsius.toString()}°C"
    }

    /** The mode's name as a resource, for surfaces outside Compose such as widgets. */
    fun modeNameRes(mode: Mode): Int = when (mode) {
        Mode.STANDBY -> io.github.willzy12h.nocturne.R.string.mode_off
        Mode.HEAT -> io.github.willzy12h.nocturne.R.string.mode_heat
        Mode.TURBO -> io.github.willzy12h.nocturne.R.string.mode_turbo
        Mode.EXTENDED_HEAT -> io.github.willzy12h.nocturne.R.string.mode_gentle_heat
        Mode.COOL -> io.github.willzy12h.nocturne.R.string.mode_cool
        Mode.DRY -> io.github.willzy12h.nocturne.R.string.mode_dry
        Mode.WAIT -> io.github.willzy12h.nocturne.R.string.mode_pause
    }

    fun modeName(mode: Mode): String = when (mode) {
        Mode.STANDBY -> "Off"
        Mode.HEAT -> "Heat"
        Mode.TURBO -> "Turbo"
        Mode.EXTENDED_HEAT -> "Gentle heat"
        Mode.COOL -> "Cool"
        Mode.DRY -> "Dry"
        Mode.WAIT -> "Pause"
    }
}

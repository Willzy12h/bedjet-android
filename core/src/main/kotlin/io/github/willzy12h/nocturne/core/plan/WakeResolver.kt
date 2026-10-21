package io.github.willzy12h.nocturne.core.plan

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

data class WakeChoice(val time: ZonedDateTime, val fromAlarm: Boolean)

/** Decides when a night ends: the next alarm when it fits the night, otherwise the profile's own wake time. */
object WakeResolver {
    /** An alarm sooner than this isn't a night's sleep, so the default wake time is used instead. */
    val MIN_NIGHT: Duration = Duration.ofHours(1)
    /** An alarm later than this belongs to another night. */
    val MAX_NIGHT: Duration = Duration.ofHours(16)

    /** Shortly after bedtime the night is starting now; later than this, previews look at tomorrow's bedtime. */
    private val BEDTIME_GRACE: Duration = Duration.ofHours(3)

    fun resolve(profile: Profile, now: ZonedDateTime, alarm: ZonedDateTime?): WakeChoice {
        if (profile.wakeSource.useAlarm && alarm != null) {
            val away = Duration.between(now, alarm)
            if (away >= MIN_NIGHT && away <= MAX_NIGHT) return WakeChoice(alarm, fromAlarm = true)
        }
        return WakeChoice(nextOccurrence(profile.wakeSource.defaultWakeMinute, now.plus(MIN_NIGHT)), fromAlarm = false)
    }

    /** When tonight's night would start, for showing a profile's wake time before it runs. */
    fun nextBedtime(profile: Profile, now: ZonedDateTime): ZonedDateTime {
        val last = nextOccurrence(profile.bedtimeMinute, now.minus(BEDTIME_GRACE))
        return if (last.isAfter(now)) last else now
    }

    /** The first time of day [minuteOfDay] at or after [from]. */
    fun nextOccurrence(minuteOfDay: Int, from: ZonedDateTime): ZonedDateTime {
        val time = LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60)
        val sameDay = from.with(time).withSecond(0).withNano(0)
        return if (sameDay.isBefore(from)) from.toLocalDate().plusDays(1).atTime(time).atZone(from.zone) else sameDay
    }
}

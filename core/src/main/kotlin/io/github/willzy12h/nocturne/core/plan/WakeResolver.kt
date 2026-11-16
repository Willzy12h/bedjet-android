package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.night.WakeOrigin
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

data class WakeChoice(val time: ZonedDateTime, val fromAlarm: Boolean, val origin: WakeOrigin = if (fromAlarm) WakeOrigin.ALARM else WakeOrigin.PROFILE_DEFAULT)

/** Decides when a night ends: the next alarm when it fits the night, otherwise the profile's own wake time. */
object WakeResolver {
    /** An alarm sooner than this isn't a night's sleep, so the default wake time is used instead. */
    val MIN_NIGHT: Duration = Duration.ofHours(1)
    /** An alarm later than this belongs to another night. */
    val MAX_NIGHT: Duration = Duration.ofHours(18)

    /** Shortly after bedtime the night is starting now; later than this, previews look at tomorrow's bedtime. */
    private val BEDTIME_GRACE: Duration = Duration.ofHours(3)

    /**
     * The one place a night's wake time is chosen from its alarm and the profile, for previews and real starts alike.
     * [policy] carries the person's alarm-app choices; [alarmOrigin] is the app that set [alarm], when known.
     * [shortestMinutes] is the shorter window a short auto-start agreed (see [AlarmPolicy.earliest]).
     */
    fun resolve(profile: Profile, now: ZonedDateTime, alarm: ZonedDateTime?, policy: AlarmPolicy = AlarmPolicy(),
        alarmOrigin: String? = null, shortestMinutes: Int? = null): WakeChoice {
        if (profile.wakeSource.useAlarm && policy.accept(alarm, alarmOrigin, now, shortestMinutes))
            return WakeChoice(alarm!!, true)
        return WakeChoice(nextOccurrence(profile.wakeSource.defaultWakeMinute, now.plus(MIN_NIGHT)), fromAlarm = false)
    }

    /**
     * The earliest an alarm can end a night that starts at [nightStart]: [MIN_NIGHT] after the start, whatever the time
     * of day, so a nap can end on its alarm. Bedtime plays no part.
     */
    fun earliestAlarm(nightStart: ZonedDateTime): ZonedDateTime = nightStart.plus(MIN_NIGHT)

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

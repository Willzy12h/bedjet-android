package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.AlarmSource
import java.time.ZonedDateTime

/** A phone alarm can be shown alongside a programme with its own fixed wake time. */
internal fun acceptedPhoneAlarm(settings: Settings, now: ZonedDateTime, alarm: AlarmSource.NextAlarm?,
    night: ActiveNight?): ZonedDateTime? {
    val start = night?.startedAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: now
    return alarm?.time?.takeIf { it.isAfter(now) && AlarmPolicy(settings).accept(it, alarm.creator, start, night?.shortAlarmNightMinutes) }
}

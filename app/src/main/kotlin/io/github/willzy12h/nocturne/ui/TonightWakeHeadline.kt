package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.AlarmSource
import java.time.ZonedDateTime

/** A phone alarm is useful even when a profile keeps its own fixed programme wake. It is never a status receipt. */
internal data class TonightWakeHeadline(val at: ZonedDateTime?, @StringRes val label: Int)

internal fun tonightWakeHeadline(settings: Settings, profile: Profile?, now: ZonedDateTime,
    alarm: AlarmSource.NextAlarm?, night: ActiveNight?): TonightWakeHeadline {
    val override = settings.resolveWakeOverride(now)?.wakeAt?.let(ZonedDateTime::parse)
        ?: night?.takeIf { it.wakeOrigin == WakeOrigin.OVERRIDE }?.wakeAt
            ?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }?.takeIf { it.isAfter(now) }
    if (override != null) return TonightWakeHeadline(override, R.string.gpt12_wake_override)

    val start = night?.startedAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: now
    val policy = AlarmPolicy(settings)
    val bedtime = (night?.profile ?: profile)?.bedtimeMinute
    if (alarm != null && alarm.time.isAfter(now) && policy.accept(alarm.time, alarm.creator, start, bedtime))
        return TonightWakeHeadline(alarm.time, R.string.gpt12_wake_next_alarm)

    val fallback = (night?.profile ?: profile)?.let {
        WakeResolver.nextOccurrence(it.wakeSource.defaultWakeMinute, now.plus(WakeResolver.MIN_NIGHT))
    }
    // Why the alarm is not used is said once, by the explanation line under the wake time.
    return TonightWakeHeadline(fallback, R.string.gpt12_wake_profile_fallback)
}

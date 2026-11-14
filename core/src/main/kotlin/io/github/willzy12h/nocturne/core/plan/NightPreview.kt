package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZonedDateTime

@Serializable data class TonightOverride(val wakeAt: String, val setAt: String) {
    /** When it lapses by itself: at its own wake time (see Settings.resolveWakeOverride), for the UI's expiry note. */
    val expiresAt: String get() = wakeAt
}
@Serializable data class TonightChoice(val profileId: String, val expiresAt: String)

/** Attribution and window shared by start and re-time. An alarm is accepted from [WakeResolver.earliestAlarm]
 * (3 h after the start, or after the profile's bedtime for an early start) up to exactly 18 h after the start.
 * [shortestMinutes] replaces that earliest point for an auto-started night that agreed a shorter one. */
class AlarmPolicy(private val settings: Settings = Settings()) {
    fun acceptsSource(origin: String?): Boolean = when {
        settings.alarmApps == io.github.willzy12h.nocturne.core.store.AlarmApps.NONE -> false
        origin == null -> settings.acceptUnknownAlarmSource
        else -> origin in settings.alarmPackages
    }
    fun accept(alarm: ZonedDateTime?, origin: String?, nightStart: ZonedDateTime, bedtimeMinute: Int? = null,
        shortestMinutes: Int? = null): Boolean =
        alarm != null && acceptsSource(origin) && inWindow(alarm, nightStart, bedtimeMinute, shortestMinutes)
    fun inWindow(alarm: ZonedDateTime, nightStart: ZonedDateTime, bedtimeMinute: Int? = null,
        shortestMinutes: Int? = null): Boolean =
        !alarm.isBefore(earliest(nightStart, bedtimeMinute, shortestMinutes)) &&
            Duration.between(nightStart, alarm) <= WakeResolver.MAX_NIGHT
    fun earliest(nightStart: ZonedDateTime, bedtimeMinute: Int? = null, shortestMinutes: Int? = null): ZonedDateTime =
        shortestMinutes?.let { nightStart.plusMinutes(it.toLong()) } ?: WakeResolver.earliestAlarm(nightStart, bedtimeMinute)
}

data class Preview(val plan: Plan?, val wake: WakeChoice, val refusalReason: String?,
    val durationProblem: NightDurationProblem? = null)
object NightPreview {
    fun forStartNow(profile: Profile, now: ZonedDateTime, alarm: ZonedDateTime?, override: TonightOverride? = null,
        settings: Settings = Settings(), alarmOrigin: String? = null): Preview {
        val overrideTime = override?.let { ZonedDateTime.parse(it.wakeAt) }?.takeIf { it.isAfter(now) }
        val wake = if (overrideTime != null) WakeChoice(overrideTime, false, WakeOrigin.OVERRIDE)
            else WakeResolver.resolve(profile, now, alarm, AlarmPolicy(settings), alarmOrigin)
        val length = Duration.between(now, wake.time)
        if (length < WakeResolver.MIN_NIGHT || length > WakeResolver.MAX_NIGHT) {
            return Preview(null, wake, NightDurationProblem.OUTSIDE_WINDOW.name, NightDurationProblem.OUTSIDE_WINDOW)
        }
        return try { Preview(PlanCompiler.compile(profile, now, wake.time), wake, null) }
        catch (e: PlanException) { Preview(null, wake, e.message, (e as? NightDurationException)?.problem) }
    }
}

/** Check both supported night-length extremes, including Heat splitting and Turbo chaining. */
object ProfileValidation {
    fun validate(profile: Profile): List<String> {
        val start = ZonedDateTime.parse("2000-01-01T20:00:00Z")
        return listOf(1L, 18L).mapNotNull { hours ->
            try { PlanCompiler.compile(profile, start, start.plusHours(hours)); null }
            catch (e: PlanException) { e.message }
        }.distinct()
    }
}

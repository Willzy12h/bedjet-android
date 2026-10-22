package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZonedDateTime

@Serializable data class TonightOverride(val wakeAt: String, val setAt: String)
@Serializable data class TonightChoice(val profileId: String, val expiresAt: String)

/** Attribution and window shared by start and re-time. The original start anchors both boundaries:
 * exactly 1 h and exactly 16 h are accepted; less than 1 h or more than 16 h is refused. */
class AlarmPolicy(private val settings: Settings = Settings()) {
    fun acceptsSource(origin: String?): Boolean = if (origin == null) settings.acceptUnknownAlarmSource else origin in settings.alarmPackages
    fun accept(alarm: ZonedDateTime?, origin: String?, nightStart: ZonedDateTime): Boolean =
        alarm != null && acceptsSource(origin) && Duration.between(nightStart, alarm).let {
            it >= WakeResolver.MIN_NIGHT && it <= WakeResolver.MAX_NIGHT
        }
}

data class Preview(val plan: Plan?, val wake: WakeChoice, val refusalReason: String?)
object NightPreview {
    fun forStartNow(profile: Profile, now: ZonedDateTime, alarm: ZonedDateTime?, override: TonightOverride? = null,
        settings: Settings = Settings(), alarmOrigin: String? = null): Preview {
        val overrideTime = override?.let { ZonedDateTime.parse(it.wakeAt) }?.takeIf { it.isAfter(now) }
        val wake = when {
            overrideTime != null -> WakeChoice(overrideTime, false, WakeOrigin.OVERRIDE)
            profile.wakeSource.useAlarm && AlarmPolicy(settings).accept(alarm, alarmOrigin, now) -> WakeChoice(alarm!!, true)
            else -> WakeChoice(WakeResolver.nextOccurrence(profile.wakeSource.defaultWakeMinute, now.plus(WakeResolver.MIN_NIGHT)), false)
        }
        val length = Duration.between(now, wake.time)
        if (length < WakeResolver.MIN_NIGHT || length > WakeResolver.MAX_NIGHT) {
            return Preview(null, wake, "A night must last 1–16 hours. Set a tonight-only wake time.")
        }
        return try { Preview(PlanCompiler.compile(profile, now, wake.time), wake, null) }
        catch (e: PlanException) { Preview(null, wake, e.message) }
    }
}

/** Check both supported night-length extremes, including Heat splitting and Turbo chaining. */
object ProfileValidation {
    fun validate(profile: Profile): List<String> {
        val start = ZonedDateTime.parse("2000-01-01T20:00:00Z")
        return listOf(1L, 16L).mapNotNull { hours ->
            try { PlanCompiler.compile(profile, start, start.plusHours(hours)); null }
            catch (e: PlanException) { e.message }
        }.distinct()
    }
}

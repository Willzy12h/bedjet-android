package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.night.Preparation
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import java.time.ZonedDateTime

// Split out of NightActions.kt: how a Start night command made by auto-start records where its wake-up came from.

/**
 * A ready [prepared] start made by auto-start, attributed to it: [WakeOrigin.AUTO_START], whether [automaticWake]
 * came from an alarm, and [io.github.willzy12h.nocturne.core.night.ActiveNight.shortAlarmNightMinutes]. Anything else
 * is returned as it is. Only for a start with no explicit tonight override.
 */
internal fun attributeAutoStart(prepared: Preparation, profile: Profile, automaticWake: WakeChoice): Preparation =
    if (prepared is Preparation.Ready)
        Preparation.Ready(prepared.change.copy(intended = prepared.change.intended.let { night ->
            val start = ZonedDateTime.parse(night.startedAt)
            val wake = ZonedDateTime.parse(night.wakeAt)
            // Kept whenever the usual window (from the later of start and bedtime) would refuse this wake-up,
            // so a moved alarm is judged against the night that was agreed.
            val agreed = java.time.Duration.between(start, wake).toMinutes().toInt()
                .takeIf { wake.isBefore(WakeResolver.earliestAlarm(start, profile.bedtimeMinute)) }
            night.copy(wakeOrigin = WakeOrigin.AUTO_START, wakeFromAlarm = automaticWake.fromAlarm,
                shortAlarmNightMinutes = agreed)
        })) else prepared

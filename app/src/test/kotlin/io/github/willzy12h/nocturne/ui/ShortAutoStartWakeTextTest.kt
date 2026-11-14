package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.AlarmReason
import io.github.willzy12h.nocturne.night.AlarmSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

/** Tonight's headline and wake explanation agree with the BedJet for an auto-start set less than 3 hours before the
 * alarm, including after the alarm moves and the night is re-timed to it. */
class ShortAutoStartWakeTextTest {
    private val startedAt = ZonedDateTime.parse("2026-10-27T04:00:00Z")
    private val now = startedAt.plusMinutes(30)
    private val alarm = AlarmSource.NextAlarm(ZonedDateTime.parse("2026-10-27T06:00:00Z"), "com.google.android.deskclock", true)

    private fun night(shortest: Int?) = ActiveNight("r", Presets.springAutumn.id, startedAt.toString(), alarm.time.toString(),
        wakeFromAlarm = true, slot = 3, wakeUpStart = alarm.time.minusMinutes(30).toString(), endsAt = alarm.time.toString(),
        profile = Presets.springAutumn, wakeOrigin = WakeOrigin.AUTO_START, shortAlarmNightMinutes = shortest)

    @Test fun aShortAutoStartShowsTheAlarmItWakesFor() {
        val headline = tonightWakeHeadline(Settings(), Presets.springAutumn, now, alarm, night(shortest = 120))
        assertEquals(alarm.time, headline.at)
        assertEquals(R.string.gpt12_wake_next_alarm, headline.label)
    }

    @Test fun anAlarmBeforeTheAgreedWindowIsStillTooSoon() {
        val early = alarm.copy(time = alarm.time.minusMinutes(30))
        val headline = tonightWakeHeadline(Settings(), Presets.springAutumn, now, early, night(shortest = 120))
        assertEquals(R.string.gpt12_wake_profile_fallback, headline.label)
        assertEquals(R.string.gpt12_wake_too_soon, headline.reason)
    }

    @Test fun anyOtherNightKeepsTheUsualThreeHours() {
        val headline = tonightWakeHeadline(Settings(), Presets.springAutumn, now, alarm, night(shortest = null))
        assertEquals(R.string.gpt12_wake_profile_fallback, headline.label)
    }

    @Test fun aRetimedShortAutoStartIsNotExplainedAsOutsideTheWindow() {
        val moved = alarm.copy(time = alarm.time.plusMinutes(15))
        val retimed = night(shortest = 120).copy(wakeAt = moved.time.toString(), wakeOrigin = WakeOrigin.ALARM)
        val explanation = AlarmExplanation.resolve(Settings(), Presets.springAutumn, now, moved, retimed)
        assertEquals(AlarmReason.ELIGIBLE, explanation.reason)
        assertEquals(startedAt.plusMinutes(120), explanation.earliestAlarm)
    }
}

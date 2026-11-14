package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.night.WakeOrigin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** A stray evening alarm (a 23:00 reminder) must never become a three-hour night just because Tonight was opened early. */
class EarlyAlarmWindowTest {
    private val clock = "com.google.android.deskclock"
    private fun at(time: String) = ZonedDateTime.parse("2026-11-12T${time}:00Z")
    private val bedtime = 22 * 60 + 30

    @Test fun `an evening preview measures from bedtime so a 23_00 alarm is ignored`() {
        val preview = NightPreview.forStartNow(Presets.summerCool, at("19:00"), at("23:00"), alarmOrigin = clock)
        assertEquals(WakeOrigin.PROFILE_DEFAULT, preview.wake.origin)
        assertEquals(at("22:30").plusHours(3), WakeResolver.earliestAlarm(at("19:00"), bedtime))
        assertFalse(AlarmPolicy().accept(at("23:00"), clock, at("19:00"), bedtime))
    }

    @Test fun `a morning alarm after an early start is still used`() {
        val wake = at("06:30").plusDays(1)
        val preview = NightPreview.forStartNow(Presets.summerCool, at("20:00"), wake, alarmOrigin = clock)
        assertEquals(WakeOrigin.ALARM, preview.wake.origin)
        assertEquals(wake, preview.wake.time)
    }

    @Test fun `a night started after bedtime is measured from its start`() {
        val start = at("23:30")
        assertEquals(start.plusHours(3), WakeResolver.earliestAlarm(start, bedtime))
        assertTrue(AlarmPolicy().accept(start.plusHours(3), clock, start, bedtime))
        assertFalse(AlarmPolicy().accept(start.plusHours(3).minusMinutes(1), clock, start, bedtime))
    }

    @Test fun `a daytime sleep long before bedtime is measured from its start`() {
        val start = at("13:00")
        assertEquals(start.plusHours(3), WakeResolver.earliestAlarm(start, bedtime))
        assertTrue(AlarmPolicy().accept(at("19:00"), clock, start, bedtime))
    }

    @Test fun `without a profile bedtime the window starts three hours after the start`() {
        assertEquals(at("22:00"), WakeResolver.earliestAlarm(at("19:00")))
    }
}

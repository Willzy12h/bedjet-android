package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.night.WakeOrigin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** Any alarm at least an hour after the night starts is its wake-up, so a nap can end on an alarm; bedtime plays no part. */
class EarlyAlarmWindowTest {
    private val clock = "com.google.android.deskclock"
    private fun at(time: String) = ZonedDateTime.parse("2026-11-12T${time}:00Z")

    @Test fun `an alarm an hour after the start is used whatever the time of day`() {
        for (start in listOf(at("13:00"), at("19:00"), at("22:00"), at("23:30"))) {
            assertEquals(start.plusHours(1), WakeResolver.earliestAlarm(start), "Start $start")
            assertTrue(AlarmPolicy().accept(start.plusHours(1), clock, start), "Start $start")
            assertFalse(AlarmPolicy().accept(start.plusMinutes(59), clock, start), "Start $start")
        }
    }

    @Test fun `a one-hour afternoon nap ends on its alarm`() {
        val preview = NightPreview.forStartNow(Presets.summerCool, at("14:00"), at("15:00"), alarmOrigin = clock)
        assertEquals(WakeOrigin.ALARM, preview.wake.origin)
        assertEquals(at("15:00"), preview.wake.time)
        assertNotNull(preview.plan)
    }

    @Test fun `an evening start before bedtime is measured from the start, not bedtime`() {
        val preview = NightPreview.forStartNow(Presets.summerCool, at("19:00"), at("20:30"), alarmOrigin = clock)
        assertEquals(WakeOrigin.ALARM, preview.wake.origin)
        assertEquals(at("20:30"), preview.wake.time)
    }

    @Test fun `an alarm under an hour away still uses the profile wake`() {
        val preview = NightPreview.forStartNow(Presets.summerCool, at("22:00"), at("22:45"), alarmOrigin = clock)
        assertEquals(WakeOrigin.PROFILE_DEFAULT, preview.wake.origin)
    }

    @Test fun `a morning alarm after an early start is still used`() {
        val wake = at("06:30").plusDays(1)
        val preview = NightPreview.forStartNow(Presets.summerCool, at("20:00"), wake, alarmOrigin = clock)
        assertEquals(WakeOrigin.ALARM, preview.wake.origin)
        assertEquals(wake, preview.wake.time)
    }
}

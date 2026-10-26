package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WakeResolverTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
    private val now = at(2026, 10, 21, 22, 30)
    private val profile = Profile(
        id = "p", name = "Test",
        start = emptyList(), middle = Middle(Mode.COOL, 40, 40, 44, 44), wake = emptyList(),
        wakeSource = WakeSource(useAlarm = true, defaultWakeMinute = 7 * 60),
    )

    @Test
    fun alarmInWindowUsed() {
        val alarm = at(2026, 10, 22, 6, 15)
        val choice = WakeResolver.resolve(profile, now, alarm)
        assertEquals(alarm, choice.time)
        assertTrue(choice.fromAlarm)
    }

    @Test
    fun wakeTooSoonUsesDefault() {
        val choice = WakeResolver.resolve(profile, now, now.plusMinutes(30))
        assertEquals(at(2026, 10, 22, 7, 0), choice.time)
        assertFalse(choice.fromAlarm)
    }

    @Test
    fun alarmTooFarAwayUsesDefault() {
        assertFalse(WakeResolver.resolve(profile, now, now.plusHours(20)).fromAlarm)
    }

    @Test
    fun alarmIgnoredWhenProfileDoesNotUseIt() {
        val choice = WakeResolver.resolve(profile.copy(wakeSource = WakeSource(useAlarm = false, defaultWakeMinute = 8 * 60)), now, now.plusHours(7))
        assertEquals(at(2026, 10, 22, 8, 0), choice.time)
    }

    @Test
    fun noAlarmUsesDefaultNextOccurrence() {
        // Starting at 06:30 with a 07:00 default is under an hour away, so the next day's 07:00 is used.
        val early = at(2026, 10, 22, 6, 30)
        assertEquals(at(2026, 10, 23, 7, 0), WakeResolver.resolve(profile, early, null).time)
        // A nap starting in the afternoon still wakes at the next 07:00.
        assertEquals(at(2026, 10, 23, 7, 0), WakeResolver.resolve(profile, early.withHour(15), null).time)
    }

    @Test
    fun previewUsesTonightsBedtime() {
        val afternoon = at(2026, 10, 21, 15, 0)
        assertEquals(at(2026, 10, 21, 22, 30), WakeResolver.nextBedtime(profile, afternoon))
        // Shortly after bedtime the night is starting now, not tomorrow.
        assertEquals(now.plusMinutes(40), WakeResolver.nextBedtime(profile, now.plusMinutes(40)))
    }
}

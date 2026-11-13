package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.ShadowPhoneClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** Every time the app shows follows the phone's 12- or 24-hour setting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PhoneClockFormatTest {
    private val app = RuntimeEnvironment.getApplication()
    private val evening = ZonedDateTime.parse("2026-11-11T22:30:00Z")

    @After fun restore() { ShadowPhoneClock.twentyFourHour = true; Format.followPhoneClock(app) }

    @Test fun aTwentyFourHourPhoneReadsTwentyFourHourTimes() {
        Format.followPhoneClock(app)
        assertTrue(Format.twentyFourHour)
        assertEquals("22:30", Format.clock(evening))
        assertEquals("07:05", Format.clockOfDay(7 * 60 + 5))
        assertEquals("Wed 22:30", Format.dayClock(evening))
        assertEquals("22:30 Z", Format.clockWithOffset(evening))
    }

    @Test fun aTwelveHourPhoneReadsTwelveHourTimesEverywhere() {
        ShadowPhoneClock.twentyFourHour = false
        Format.followPhoneClock(app)
        assertFalse(Format.twentyFourHour)
        assertEquals("10:30 PM", Format.clock(evening).uppercase().replace(' ', ' '))
        assertEquals("7:05 AM", Format.clockOfDay(7 * 60 + 5).uppercase().replace(' ', ' '))
        assertEquals("12:00 AM", Format.clockOfDay(0).uppercase().replace(' ', ' '))
        assertEquals("WED 10:30 PM", Format.dayClock(evening).uppercase().replace(' ', ' '))
    }
}

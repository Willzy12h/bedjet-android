package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.store.AlarmApps
import io.github.willzy12h.nocturne.core.store.Settings
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

/** The alarm app chosen in setup, Settings or Tonight decides which alarms can set or move a wake-up. */
class AlarmAppChoiceTest {
    private val google = "com.google.android.deskclock"
    private val samsung = "com.sec.android.app.clockpackage"
    private val calendar = "com.samsung.android.calendar"
    private val installed = listOf(ClockApps.App("other.clock", "Other Clock"))
    private val start = ZonedDateTime.parse("2026-11-01T22:30:00Z")
    private val nine = start.plusHours(10).plusMinutes(30)

    @Test fun anyClockAppTakesGoogleSamsungAndClockAppsFoundButNotACalendar() {
        val settings = ClockApps.choose(Settings(), AlarmApps.CLOCK_APPS, installed)
        assertEquals(AlarmApps.CLOCK_APPS, settings.alarmApps)
        assertTrue(settings.alarmAppsChosen)
        assertTrue(listOf(google, samsung, "other.clock").all { it in settings.alarmPackages })
        assertFalse(calendar in settings.alarmPackages)
        assertFalse(AlarmPolicy(settings).accept(nine, calendar, start))
        assertTrue(AlarmPolicy(settings).accept(nine, samsung, start))
    }

    @Test fun oneAppTakesOnlyThatApp() {
        val settings = ClockApps.choose(Settings(), AlarmApps.ONE_APP, installed, google)
        assertEquals(setOf(google), settings.alarmPackages)
        assertTrue(AlarmPolicy(settings).accept(nine, google, start))
        assertFalse(AlarmPolicy(settings).accept(nine, samsung, start))
        assertEquals(google, ClockApps.chosenApp(settings))
    }

    @Test fun samsungAliasesAreNotAcceptedUnlessFoundAsAClockApp() {
        val alias = "com.samsung.android.clockpackage"
        assertFalse(alias in ClockApps.choose(Settings(), AlarmApps.CLOCK_APPS, installed).alarmPackages)
        assertTrue(alias in ClockApps.choose(Settings(), AlarmApps.CLOCK_APPS, installed + ClockApps.App(alias, "Clock")).alarmPackages)
        assertEquals(setOf(samsung), ClockApps.choose(Settings(), AlarmApps.ONE_APP, installed, samsung).alarmPackages)
    }

    @Test fun noAlarmsIgnoresEveryAlarmButKeepsTheAppListForLater() {
        val before = ClockApps.choose(Settings(), AlarmApps.ONE_APP, installed, google)
        val settings = ClockApps.choose(before, AlarmApps.NONE, installed)
        assertEquals(setOf(google), settings.alarmPackages)
        assertFalse(AlarmPolicy(settings).accept(nine, google, start))
        assertNull(ClockApps.chosenApp(settings))
    }

    @Test fun anAlarmFromAnUnnamedAppIsNeverUsedOnceChosen() {
        AlarmApps.entries.forEach { choice ->
            val settings = ClockApps.choose(Settings(acceptUnknownAlarmSource = true), choice, installed, google)
            assertFalse(settings.acceptUnknownAlarmSource)
            assertFalse(AlarmPolicy(settings).accept(nine, null, start))
        }
    }

    /** Item 17: a running night is retimed through the same policy, so a calendar-type alarm can't move it. */
    @Test fun aCalendarAlarmCannotMoveARunningNight() {
        val settings = Settings().withoutUnidentifiedAlarms()
        listOf(calendar, null, "android").forEach { origin ->
            assertFalse("$origin", AlarmPolicy(settings).accept(nine, origin, start))
        }
    }
}

package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.store.AlarmApps
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticking clock apps and other alarm apps (white noise, sleep) in any combination, stored without a new setting. */
class AlarmAppChoiceTest {
    private val google = ClockApps.App("com.google.android.deskclock", "Clock")
    private val samsung = ClockApps.App("com.sec.android.app.clockpackage", "Samsung Clock")
    private val clocks = listOf(google, samsung)
    private val noise = ClockApps.App("com.example.whitenoise", "White Noise", clock = false, seen = true)
    private val sleep = ClockApps.App("com.example.sleep", "Sleep Cycle", clock = false)

    private fun clockApps(vararg extra: String) = ClockApps.choose(Settings(), AlarmApps.CLOCK_APPS, clocks, extra.toSet())

    @Test fun anyClockAppPlusATickedAppKeepsBoth() {
        val settings = clockApps(noise.packageName)
        assertEquals(AlarmApps.CLOCK_APPS, settings.alarmApps)
        assertEquals(setOf(noise.packageName), ClockApps.extraApps(settings, clocks))
        assertTrue(ClockApps.isTicked(settings, google))
        assertTrue(ClockApps.isTicked(settings, noise))
        assertFalse(ClockApps.isTicked(settings, sleep))
    }

    @Test fun tickingAnotherAppUnderClockAppsAddsIt() {
        val (choice, chosen) = ClockApps.afterToggle(clockApps(noise.packageName), clocks, sleep)
        assertEquals(AlarmApps.CLOCK_APPS, choice)
        assertEquals(setOf(noise.packageName, sleep.packageName), chosen)
    }

    @Test fun untickingAClockAppKeepsEveryOtherTick() {
        val (choice, chosen) = ClockApps.afterToggle(clockApps(noise.packageName), clocks, samsung)
        assertEquals(AlarmApps.ONE_APP, choice)
        assertEquals(setOf(google.packageName, noise.packageName), chosen)
    }

    @Test fun onlyTickedAppsCanBeSeveral() {
        val settings = ClockApps.choose(Settings(), AlarmApps.ONE_APP, clocks, setOf(google.packageName, noise.packageName))
        assertEquals(setOf(google.packageName, noise.packageName), settings.alarmPackages)
        assertTrue(ClockApps.isTicked(settings, noise))
        assertFalse(ClockApps.isTicked(settings, samsung))
        assertEquals(setOf(google.packageName), ClockApps.afterToggle(settings, clocks, noise).second)
    }

    @Test fun tickingAnAppAfterNoAlarmsUsesThatAppAlone() {
        val settings = ClockApps.choose(Settings(), AlarmApps.NONE, clocks)
        assertEquals(AlarmApps.ONE_APP to setOf(noise.packageName), ClockApps.afterToggle(settings, clocks, noise))
        assertFalse(ClockApps.isTicked(settings, google))
    }

    @Test fun onlyTickedStartsFromWhatIsTickedNow() {
        assertEquals(AlarmApps.ONE_APP to setOf(google.packageName, samsung.packageName, noise.packageName),
            ClockApps.onlyTicked(clockApps(noise.packageName), clocks))
    }

    @Test fun otherAppsLeaveOutClockAppsAndNocturneAndPutSeenFirst() {
        val apps = ClockApps.merge(
            clock = setOf(google.packageName),
            seen = listOf(noise.packageName, google.packageName, "android", ""),
            requesting = setOf("io.github.willzy12h.nocturne", sleep.packageName, "com.example.alarmy"),
            nextAlarmCreator = noise.packageName,
            self = "io.github.willzy12h.nocturne",
        ) { mapOf(noise.packageName to "White Noise", sleep.packageName to "Sleep Cycle", "com.example.alarmy" to "Alarmy")[it] ?: it }
        assertEquals(listOf("White Noise", "Alarmy", "Sleep Cycle"), apps.map { it.label })
        assertTrue(apps.first().seen && apps.first().nextAlarm && !apps.first().clock)
        assertFalse(apps[1].seen)
    }

    @Test fun seenAppsAreNewestFirstWithoutRepeatsAndCapped() {
        assertEquals(listOf("b", "a", "c"), SeenAlarmApps.remember(listOf("a", "b", "c"), "b"))
        val many = (1..SeenAlarmApps.LIMIT).map { "app$it" }
        val updated = SeenAlarmApps.remember(many, "new")
        assertEquals(SeenAlarmApps.LIMIT, updated.size)
        assertEquals("new", updated.first())
        assertFalse("app${SeenAlarmApps.LIMIT}" in updated)
    }
}

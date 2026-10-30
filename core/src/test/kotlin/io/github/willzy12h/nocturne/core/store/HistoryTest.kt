package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryTest {
    private val now = ZonedDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneId.of("Europe/London"))

    private fun night(daysAgo: Long) = NightRecord(
        id = "n$daysAgo", profileId = "p", profileName = "P",
        startedAt = now.minusDays(daysAgo).toString(), wakeAt = now.minusDays(daysAgo).plusHours(8).toString(), wakeFromAlarm = true,
    )

    @Test
    fun keepsNinetyDaysByDefault() {
        val kept = History.prune(listOf(night(1), night(59), night(89), night(91), night(200)), now)
        assertEquals(listOf("n1", "n59", "n89"), kept.map { it.id })
    }

    @Test
    fun keepsTheChosenPeriod() {
        val all = listOf(night(1), night(6), night(8), night(29), night(31), night(364), night(366))
        assertEquals(listOf("n1", "n6"), History.prune(all, now, 7).map { it.id })
        assertEquals(listOf("n1", "n6", "n8", "n29"), History.prune(all, now, 30).map { it.id })
        assertEquals(listOf("n1", "n6", "n8", "n29", "n31", "n364"), History.prune(all, now, 365).map { it.id })
    }

    @Test
    fun anUnknownPeriodKeepsTheDefault() {
        assertEquals(listOf("n1", "n89"), History.prune(listOf(night(1), night(89), night(91)), now, 0).map { it.id })
    }

    @Test
    fun addEventAppendsToTheNamedNight() {
        val records = listOf(night(1), night(2))
        val updated = History.addEvent(records, "n2", HistoryEvent(now.toString(), EventKind.SNOOZED, "Snooze warm 10 min"))
        assertEquals(1, updated.single { it.id == "n2" }.events.size)
        assertEquals(0, updated.single { it.id == "n1" }.events.size)
    }

    @Test
    fun recordsWithoutADeviceAreUnknown() {
        assertEquals("unknown", night(1).deviceId)
    }

    @Test
    fun theDemoUnitsNightsCanBeFilteredOut() {
        val demo = night(1).copy(id = "demo", deviceId = "demo")
        val real = night(2).copy(id = "real", deviceId = "ble:AA:BB")
        val old = night(3)
        val all = listOf(demo, real, old)
        assertEquals(listOf("real", "n3"), History.withoutDemo(all).map { it.id })
        assertEquals(listOf("demo"), History.forDevice(all, "demo").map { it.id })
        assertEquals(listOf("real"), History.forDevice(all, "ble:AA:BB").map { it.id })
    }
}

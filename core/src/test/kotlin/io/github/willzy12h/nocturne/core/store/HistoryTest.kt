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
    fun keepsSixtyDays() {
        val kept = History.prune(listOf(night(1), night(59), night(61), night(200)), now)
        assertEquals(listOf("n1", "n59"), kept.map { it.id })
    }

    @Test
    fun addEventAppendsToTheNamedNight() {
        val records = listOf(night(1), night(2))
        val updated = History.addEvent(records, "n2", HistoryEvent(now.toString(), EventKind.SNOOZED, "Snooze warm 10 min"))
        assertEquals(1, updated.single { it.id == "n2" }.events.size)
        assertEquals(0, updated.single { it.id == "n1" }.events.size)
    }
}

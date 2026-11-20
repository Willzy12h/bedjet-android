package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.sleep.SleepNight
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SleepRangeReadTest {
    private val end = Instant.parse("2026-11-20T12:00:00Z")
    private fun daysAgo(days: Long) = end.minus(Duration.ofDays(days))
    private val recent = SleepNight.of(daysAgo(1), end.minusSeconds(3600), emptyList(), "recent")

    private fun reader(granted: () -> Boolean = { true }, fetch: (Instant, Instant) -> List<SleepNight>) =
        SleepNightsReader({ true }) { object : SleepSource {
            override suspend fun permitted() = granted()
            override suspend fun sessions(start: Instant, end: Instant) = emptyList<SleepStage>()
            override suspend fun nights(start: Instant, end: Instant) = fetch(start, end)
        } }

    @Test fun inaccessibleOlderDatesKeepTheReadablePartOfTheBoundaryWeek() = runBlocking {
        val old = SleepNight.of(daysAgo(29), daysAgo(28).minusSeconds(600), emptyList(), "old")
        val result = reader { start, stop ->
            if (start < daysAgo(30)) throw SecurityException()
            listOf(recent, old).filter { it.start >= start && it.start < stop }
        }.read(daysAgo(90), end)
        assertEquals(listOf(recent, old), result.nights)
        assertEquals(SleepAvailability.AVAILABLE, result.availability)
        assertTrue(result.partial)
    }

    @Test fun failureReadingOptionalComparisonsLeavesTheVisibleWeekComplete() = runBlocking {
        val result = reader { start, _ ->
            if (start < daysAgo(7)) error("older history unavailable")
            listOf(recent)
        }.read(daysAgo(7), end, daysAgo(15))
        assertEquals(listOf(recent), result.nights)
        assertFalse(result.partial)
    }

    @Test fun sessionStartingBeforeAWeeklyBoundaryAndEndingAfterItSurvivesOlderPermissionFailure() = runBlocking {
        val crossed = SleepNight.of(daysAgo(7).minusSeconds(7200), daysAgo(7).plusSeconds(18000), emptyList(), "other-app")
        val result = reader { start, stop ->
            if (start < daysAgo(30)) throw SecurityException()
            // Some providers return intervals in both pages. Others select them by their start.
            listOf(crossed, recent).filter { it.start >= start && it.start < stop }
        }.read(daysAgo(90), end)
        assertEquals(listOf(recent, crossed), result.nights)
        assertTrue(result.partial)
    }

    @Test fun readablePrecedingEveningIsKeptWhenTheOptionalComparisonWeekIsDenied() = runBlocking {
        val crossing = SleepNight.of(daysAgo(7).minusSeconds(7200), daysAgo(7).plusSeconds(18000), emptyList(), "other-app")
        val result = reader { start, stop ->
            if (start < daysAgo(8)) throw SecurityException()
            listOf(crossing).filter { it.start >= start && it.start < stop }
        }.read(daysAgo(7), end, comparisonStart = daysAgo(15), overlapStart = daysAgo(8))
        assertEquals(listOf(crossing), result.nights)
        assertFalse(result.partial)
    }

    @Test fun revokedPermissionDiscardsEvenThePageReadBeforeRevocation() = runBlocking {
        var granted = true
        val result = reader({ granted }) { start, _ ->
            if (start < daysAgo(7)) { granted = false; throw SecurityException() }
            listOf(recent)
        }.read(daysAgo(30), end)
        assertEquals(SleepAvailability.PERMISSION, result.availability)
        assertTrue(result.nights.isEmpty())
    }

    @Test fun fullReadFailuresDistinguishPermissionProviderAndEmpty() = runBlocking {
        assertEquals(SleepAvailability.PERMISSION,
            reader({ false }) { _, _ -> error("should not read") }.read(daysAgo(7), end).availability)
        assertEquals(SleepAvailability.ERROR, reader { _, _ -> error("provider failure") }.read(daysAgo(7), end).availability)
        assertEquals(SleepAvailability.ERROR, reader { _, _ -> throw SecurityException() }.read(daysAgo(7), end).availability)
        assertEquals(SleepAvailability.ABSENT,
            SleepNightsReader({ false }) { error("should not connect") }.read(daysAgo(7), end).availability)
    }

    @Test fun sparseRangeWithNoDataReportsEmptyOnlyWhenItWasFullyRead() = runBlocking {
        assertEquals(SleepAvailability.EMPTY, reader { _, _ -> emptyList() }.read(daysAgo(90), end).availability)
        val result = reader { start, _ ->
            if (start < daysAgo(7)) error("provider unavailable")
            emptyList()
        }.read(daysAgo(30), end)
        assertTrue(result.partial)
        assertTrue(result.nights.isEmpty())
    }
}

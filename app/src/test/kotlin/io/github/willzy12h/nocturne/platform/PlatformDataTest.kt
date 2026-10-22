package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlatformDataTest {
    val at = "2026-10-22T22:30:00+01:00[Europe/London]"
    fun record() = NightRecord("n", "p", "Cool, \"quiet\"\r\nnight", at, "2026-10-23T07:00:00+01:00[Europe/London]", false,
        steps = listOf(RecordedStep(Block.MIDDLE, Mode.COOL, 44, 50, at, 60)),
        events = listOf(HistoryEvent(at, EventKind.STARTED, "Device ble:AA:BB:CC:DD:EE:FF")), deviceId="ble:AA:BB:CC:DD:EE:FF")
    @Test fun csvEscapesAndPreservesOffsetsZoneUnitsAndPrivacy() {
        val csv = HistoryCsv.encode(listOf(record()), fahrenheit=true, identifiers=false)
        assertTrue(csv.contains("temperature °C"))
        assertTrue(csv.contains("temperature °F"))
        assertTrue(csv.contains("2026-10-22T22:30:00+01:00"))
        assertTrue(csv.contains("Europe/London"))
        assertTrue(csv.contains("\"Cool, \"\"quiet\"\"\r\nnight\""))
        assertTrue(csv.contains("22.0,71.6"))
        assertFalse(csv.contains("AA:BB"))
        assertTrue(HistoryCsv.encode(listOf(record()), false, true).contains("ble:AA:BB:CC:DD:EE:FF"))
    }
    @Test fun healthAbsentDoesNotConstructClientAndRevocationIsHandled() = runBlocking {
        var made = false
        val absent = SleepOverlayReader({ false }) { made=true; error("Must not create") }
        assertEquals(SleepAvailability.ABSENT, absent.read(record()).availability)
        assertFalse(made)
        val denied = object : SleepSource {
            override suspend fun permitted() = true
            override suspend fun sessions(start: Instant, end: Instant): List<SleepStage> = throw SecurityException("revoked")
        }
        assertEquals(SleepAvailability.PERMISSION, SleepOverlayReader({true}){denied}.read(record()).availability)
    }
    @Test fun missingSleepIsUnknownAndStagesClipToNightInstants() = runBlocking {
        val start = Instant.parse("2026-10-22T21:30:00Z")
        val source = object : SleepSource {
            override suspend fun permitted() = true
            override suspend fun sessions(s: Instant, e: Instant) = listOf(SleepStage(start.minusSeconds(60), start.plusSeconds(60), 4))
        }
        val result = SleepOverlayReader({true}){source}.read(record())
        assertEquals(start, result.stages.single().start)
        assertEquals(start.plusSeconds(60), result.stages.single().end)
    }
}

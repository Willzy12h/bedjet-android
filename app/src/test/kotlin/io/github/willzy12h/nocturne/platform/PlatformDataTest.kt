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
    /** Parse the exported RFC4180 cells, so quoting alone cannot make this test pass. */
    private fun cells(csv: String): List<List<String>> {
        val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val cell=StringBuilder()
        var quoted=false;var i=0
        while(i<csv.length) {
            val ch=csv[i++]
            when {
                ch=='"' && quoted && i<csv.length && csv[i]=='"' -> {cell.append('"');i++}
                ch=='"' -> quoted=!quoted
                ch==',' && !quoted -> {row+=cell.toString();cell.clear()}
                ch=='\r' && !quoted -> {assertEquals('\n',csv[i++]);row+=cell.toString();cell.clear();rows+=row.toList();row.clear()}
                else -> cell.append(ch)
            }
        }
        assertFalse(quoted);assertTrue(row.isEmpty());assertEquals(0,cell.length)
        return rows
    }
    @Test fun csvMakesFormulaAndLeadingControlTextVisibleAndInertWithEitherPrivacyChoice() {
        val dangerous=listOf("=1+1","+1","-1","@SUM(1,2)","＝1+1","＋1","－1","＠SUM(1,2)",
            "\t=1+1","\r=1+1","\n=1+1"," \t\uFEFF=1+1","\uFEFF@SUM(1,2)","\u0000+1","=HYPERLINK(\"url\",\"label\")")
        for(identifiers in listOf(false,true)) for(raw in dangerous) {
            val record=record().copy(id=raw,profileName=raw,startedAt=raw,deviceId=raw+" ble:AA:BB:CC:DD:EE:FF")
            val rows=cells(HistoryCsv.encode(listOf(record),true,identifiers));val head=rows.first();val night=rows[1]
            for(column in listOf("night id","profile","timestamp ISO 8601")) assertEquals("Text: $raw",night[head.indexOf(column)])
            assertEquals(head.size,night.size)
            if(identifiers) assertEquals("Text: $raw ble:AA:BB:CC:DD:EE:FF",night.last())
            else {assertFalse(head.contains("device identifier"));assertFalse(rows.flatten().any {it.contains("AA:BB")})}
        }
    }
    @Test fun csvPreservesOrdinaryTextAndGeneratedNegativeNumbers() {
        val record=record().copy(profileName="Ordinary, \"quoted\"\r\ntext",
            steps=listOf(record().steps.first().copy(temperature=-4)))
        val rows=cells(HistoryCsv.encode(listOf(record),true,true));val head=rows.first()
        assertEquals(record.profileName,rows[1][head.indexOf("profile")])
        assertEquals("-2.0",rows[2][head.indexOf("temperature °C")])
        assertEquals("28.4",rows[2][head.indexOf("temperature °F")])
        assertEquals("50",rows[2][head.indexOf("fan %")])
        assertEquals("0",rows[2][head.indexOf("revision")])
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

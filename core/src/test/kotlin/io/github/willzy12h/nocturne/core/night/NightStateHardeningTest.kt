package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.plan.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class NightStateHardeningTest {
    private var now = ZonedDateTime.parse("2026-10-21T22:30:00Z")
    private val alarm = now.plusHours(8)
    private val link = DemoBedJet { now }
    private val runner = NightRunner(BedJetController(link) { now }) { now }
    private val json = Json { encodeDefaults = true }

    @Test fun `v1 night gains unknown confidence and alarm origin`() {
        val raw = """{"recordId":"n","profileId":"p","startedAt":"$now","wakeAt":"$alarm","wakeFromAlarm":true,"slot":2,"wakeUpStart":"$alarm","endsAt":"$alarm"}"""
        val migrated = json.encodeToJsonElement(json.decodeFromString<ActiveNight>(raw)).jsonObject
        assertEquals(JsonPrimitive("ALARM"), migrated["wakeOrigin"])
        assertEquals(JsonPrimitive("UNKNOWN"), migrated["confidence"])
        assertEquals(JsonNull, migrated["profile"])
        assertEquals(JsonPrimitive(0), migrated["revision"])
    }
    @Test fun `next night edits cannot affect automatic retiming`() = runTest {
        link.connect()
        val p = Presets.springAutumn
        val night = runner.start(p, alarm, 2, emptyList()).night
        now = now.plusHours(2)
        val edited = p.copy(middle = p.middle.copy(fanStart = 100, fanEnd = 100))
        val result = runner.retime(edited, night, alarm.plusMinutes(30))!!
        assertTrue(result.plan.steps.filter { it.block == Block.MIDDLE }.all { it.step.fanStep < 10 })
    }
    @Test fun `edited wake phases cannot move the confirmed boundary`() = runTest {
        link.connect()
        val p = Presets.springAutumn
        val night = runner.start(p, alarm, 2, emptyList()).night
        now = ZonedDateTime.parse(night.wakeUpStart)
        assertNull(runner.retime(p.copy(wake = emptyList()), night, alarm.plusHours(1)))
    }
    @Test fun `start stores its device snapshot and first applied revision`() = runTest {
        link.connect()
        val result = runner.start(Presets.springAutumn, alarm, 2, emptyList(), "demo")
        val saved = json.encodeToJsonElement(result.night).jsonObject
        assertEquals(JsonPrimitive("demo"), saved["deviceId"])
        assertEquals(json.encodeToJsonElement(Presets.springAutumn), saved["profile"])
        assertEquals(JsonPrimitive("CONFIRMED"), saved["confidence"])
        assertNotNull(json.encodeToJsonElement(result.record).jsonObject["revisions"])
    }
}

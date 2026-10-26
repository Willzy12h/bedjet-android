package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.plan.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZonedDateTime

class ShortBedJetTestTest {
    private val now = ZonedDateTime.parse("2026-10-26T22:30:17Z")
    private val demo = DemoBedJet { now }
    private val runner = NightRunner(BedJetController(demo) { now }) { now }
    private fun ready(): PreparedChange {
        val prepared = runner.prepareShortTest("Five-minute BedJet test", 2, DeviceId.DEMO, null)
        assertTrue(prepared is Preparation.Ready, prepared.toString())
        return (prepared as Preparation.Ready).change
    }

    @Test fun `explicit test has five duration minutes with a wake phase after three`() {
        val change = ready()
        assertTrue(change.intended.isTestRun)
        assertEquals(5, change.plan.steps.sumOf { it.minutes })
        assertEquals(Duration.ofMinutes(3), Duration.between(change.plan.start, change.plan.wakeUpStart))
        assertEquals(Duration.ofMinutes(5), Duration.between(change.plan.start, change.plan.end))
        assertEquals(change.plan.end.toString(), change.intended.endsAt)
        assertTrue(change.plan.steps.none { it.step.endsAtClockTime || it.block == Block.AFTER_WAKE })
        assertFalse(change.intended.wakeFromAlarm)
        assertNull(NightPreview.forStartNow(Presets.springAutumn, now, null,
            TonightOverride(now.plusMinutes(5).toString(), now.toString())).plan)
    }

    @Test fun `test start publishes pending intention before writes and confirms through the normal receipts`() = runTest {
        demo.connect()
        val change = ready()
        var current: ActiveNight? = null
        val states = mutableListOf<Confidence>()
        val result = runner.apply(change, { current }, publish = {
            current = it
            states += it.confidence
            assertTrue(it.isTestRun)
            if (it.confidence == Confidence.PENDING) {
                assertNull(demo.slots[2])
                assertTrue(it.pending!!.intended!!.isTestRun)
            }
        })
        assertTrue(result is NightMutation.Applied)
        assertEquals(listOf(Confidence.PENDING, Confidence.CONFIRMED), states)
        assertEquals(change.plan.programSteps, demo.slots[2]!!.steps)
        assertTrue(Json.decodeFromString<ActiveNight>(Json.encodeToString(current!!)).isTestRun)
    }

    @Test fun `test cannot replace a tracked night`() {
        val night = (runner.prepareStart(Presets.springAutumn, now.plusHours(8), 2, DeviceId.DEMO, null, null)
            as Preparation.Ready).change.intended
        assertTrue(runner.prepareShortTest("Test", 2, DeviceId.DEMO, night) is Preparation.Refused)
    }
}

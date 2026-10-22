package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.plan.Presets
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NightTemporalEligibilityTest {
    private class Fixture {
        var now = ZonedDateTime.parse("2026-10-21T22:30:00Z")
        val wake = ZonedDateTime.parse("2026-10-22T07:00:00Z")
        val link = DemoBedJet { now }
        val controller = BedJetController(link) { now }
    val runner = NightRunner(controller) { now }
    private val fixture = NightFixture(runner, controller) { now }
        var writes = 0
        var starts = 0
        val published = mutableListOf<ActiveNight>()

        suspend fun night(): ActiveNight {
            link.connect()
            return fixture.start(Presets.springAutumn, wake, 2, emptyList(), "demo").night
        }
        suspend fun resume(): PreparedChange {
            val night = night()
            now = wake.minusMinutes(5)
            val offer = stopTracking(night, "Off", now, 1).resumeOffer!!
            now = wake.minusMinutes(1)
            return ready(runner.resume(offer, now, 1))
        }
        fun observedRunner(afterUpload: suspend () -> Unit = {}, afterReadback: suspend () -> Unit = {},
            afterClock: suspend () -> Unit = {}): NightRunner {
            val observed = object : BedJetLink by link {
                override suspend fun send(command: ByteArray) {
                    if (command[0].toInt() and 0xff == 0x40) writes++
                    if (command[0].toInt() and 0xff == 1 && command[1].toInt() and 0xff in 0x80..0x82) starts++
                    link.send(command)
                    if (command[0].toInt() and 0xff == 0x40) afterUpload()
                    if (command[0].toInt() and 0xff == 0x08) afterClock()
                }
                override suspend fun query(request: Int): ByteArray {
                    val response = link.query(request)
                    if (request in 7 until 19) afterReadback()
                    return response
                }
            }
            return NightRunner(BedJetController(observed) { now }) { now }
        }
        suspend fun publish(night: ActiveNight) { published += night }
        companion object {
            fun ready(preparation: Preparation): PreparedChange {
                assertTrue(preparation is Preparation.Ready, "Expected ready, got $preparation")
                return (preparation as Preparation.Ready).change
            }
        }
    }

    @Test fun `resume delayed before apply is refused at wake and after wake without any write`() = runTest {
        for (lateMinutes in listOf(0L, 1L)) {
            val f = Fixture()
            val change = f.resume()
            val originalSlot = f.link.slots[2]
            f.now = f.wake.plusMinutes(lateMinutes)
            val result = f.observedRunner().apply(change, { null }, { 1 }, f::publish)
            assertTrue(result is NightMutation.Refused, "Resume must expire at its agreed wake: $result")
            assertEquals(0, f.writes)
            assertEquals(0, f.starts)
            assertEquals(originalSlot, f.link.slots[2])
            assertTrue(f.published.isEmpty())
        }
    }

    @Test fun `resume delayed in backup fails at wake and after wake without any write`() = runTest {
        for (lateMinutes in listOf(0L, 1L)) {
            val f = Fixture()
            val change = f.resume()
            val originalSlot = f.link.slots[2]
            val result = f.observedRunner().apply(change, { null }, { 1 }, f::publish, beforeWrite = {
                delay(1)
                f.now = f.wake.plusMinutes(lateMinutes)
            })
            assertTrue(result is NightMutation.Failed, "Published pending request must terminate truthfully: $result")
            assertEquals(Confidence.FAILED, (result as NightMutation.Failed).night.confidence)
            assertEquals(listOf(Confidence.PENDING, Confidence.FAILED), f.published.map { it.confidence })
            assertEquals(change.request.copy(intended = change.intended), result.night.pending)
            assertEquals(0, f.writes)
            assertEquals(0, f.starts)
            assertEquals(originalSlot, f.link.slots[2])
        }
    }
    private fun assertUnknownWithoutStart(f: Fixture, change: PreparedChange, result: NightMutation) {
        assertTrue(result is NightMutation.Failed, "Expired upload must not be confirmed: $result")
        assertEquals(Confidence.UNKNOWN, (result as NightMutation.Failed).night.confidence)
        assertEquals(listOf(Confidence.PENDING, Confidence.UNKNOWN), f.published.map { it.confidence })
        assertEquals(change.request.copy(intended = change.intended), result.night.pending)
        assertTrue(f.writes > 0, "Exercise the window after a slot write really happened")
        assertEquals(change.plan.programSteps, f.link.slots[2]!!.steps)
        assertEquals(0, f.starts, "No start command may be issued once temporal eligibility is lost")
    }

    @Test fun `upload crossing request deadline at equality or later never starts the slot`() = runTest {
        for (lateSeconds in listOf(0L, 1L)) {
            val f = Fixture()
            val night = f.night()
            f.now = f.now.plusHours(1)
            val deadline = f.now.plusSeconds(1)
            val change = Fixture.ready(f.runner.prepareRetime(night, night.identity, f.wake.plusMinutes(30), deadline))
            val runner = f.observedRunner(afterUpload = { delay(1); f.now = deadline.plusSeconds(lateSeconds) })
            assertUnknownWithoutStart(f, change, runner.apply(change, { night }, publish=f::publish))
        }
    }

    @Test fun `readback crossing confirmed wake boundary at equality or later never starts the slot`() = runTest {
        for (lateSeconds in listOf(0L, 1L)) {
            val f = Fixture()
            val night = f.night()
            val boundary = ZonedDateTime.parse(night.wakeUpStart)
            f.now = boundary.minusSeconds(1)
            val change = Fixture.ready(f.runner.prepareRetime(night, night.identity, f.wake.plusMinutes(30), f.wake))
            val runner = f.observedRunner(afterReadback = { delay(1); f.now = boundary.plusSeconds(lateSeconds) })
            assertUnknownWithoutStart(f, change, runner.apply(change, { night }, publish=f::publish))
        }
    }

    @Test fun `resume readback reaching original wake never starts the slot`() = runTest {
        val f = Fixture()
        val change = f.resume()
        val runner = f.observedRunner(afterReadback = { delay(1); f.now = f.wake })
        assertUnknownWithoutStart(f, change, runner.apply(change, { null }, { 1 }, f::publish))
    }

    @Test fun `clock sync crossing deadline refuses final start and cancels confirmation waiter`() = runTest {
        val f = Fixture()
        val night = f.night()
        f.now = f.now.plusHours(1)
        val deadline = f.now.plusSeconds(1)
        val change = Fixture.ready(f.runner.prepareRetime(night, night.identity, f.wake.plusMinutes(30), deadline))
        val runner = f.observedRunner(afterClock = { delay(1); f.now = deadline })
        val before = testScheduler.currentTime
        assertUnknownWithoutStart(f, change, runner.apply(change, { night }, publish=f::publish))
        assertTrue(testScheduler.currentTime - before < 10_000, "Cancelled confirmation must not wait for its timeout")
    }

}

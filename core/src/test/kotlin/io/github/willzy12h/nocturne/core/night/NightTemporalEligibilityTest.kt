package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.plan.Presets
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneId
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


    // IR-10 (NGT-1): the final check also bounds the new plan, not only the request deadline and the old boundary.

    @Test fun `a late retime is not started once its clock-time step is within ten minutes of its end`() = runTest {
        val f = Fixture()
        val night = f.night()
        f.now = ZonedDateTime.parse("2026-10-22T05:00:00Z")
        // Earlier alarm: the new wake-up starts at 05:35 and the last Middle step ends at clock 05:35.
        val change = Fixture.ready(f.runner.prepareRetime(night, night.identity, ZonedDateTime.parse("2026-10-22T06:00:00Z"),
            ZonedDateTime.parse(night.wakeUpStart)))
        val clockStep = change.plan.steps.single { it.step.endsAtClockTime }
        assertEquals(ZonedDateTime.parse("2026-10-22T05:35:00Z").toInstant(), clockStep.endsAt.toInstant())
        // Still before the new wake-up start, the old boundary and the deadline, but too late for the clock-time step.
        val runner = f.observedRunner(afterReadback = { delay(1); f.now = clockStep.endsAt.minusMinutes(5) })
        assertUnknownWithoutStart(f, change, runner.apply(change, { night }, publish = f::publish))
    }

    @Test fun `a late retime is not started once the new wake-up has begun`() = runTest {
        val f = Fixture()
        val night = f.night()
        f.now = ZonedDateTime.parse("2026-10-22T05:00:00Z")
        // The new wake-up starts at 05:08; the short last Middle step runs by duration.
        val change = Fixture.ready(f.runner.prepareRetime(night, night.identity, ZonedDateTime.parse("2026-10-22T05:33:00Z"),
            ZonedDateTime.parse(night.wakeUpStart)))
        assertTrue(change.plan.steps.none { it.step.endsAtClockTime })
        val newBoundary = change.plan.wakeUpStart
        assertTrue(newBoundary.isBefore(ZonedDateTime.parse(night.wakeUpStart)))
        val runner = f.observedRunner(afterReadback = { delay(1); f.now = newBoundary })
        assertUnknownWithoutStart(f, change, runner.apply(change, { night }, publish = f::publish))
    }

    // IR-21 (NGT-4): a fresh start refused at the final check is FAILED, not UNKNOWN: no start byte went out.

    @Test fun `a fresh start refused after its upload is failed and says the slot holds the night`() = runTest {
        val f = Fixture()
        f.link.connect()
        val change = Fixture.ready(f.runner.prepareStart(io.github.willzy12h.nocturne.core.plan.Presets.springAutumn,
            f.wake, 2, "demo", null, null))
        val runner = f.observedRunner(afterReadback = { delay(1); f.now = ZonedDateTime.parse(change.request.deadline) })
        val result = runner.apply(change, { f.published.lastOrNull() }, publish = f::publish)
        assertTrue(result is NightMutation.Failed, "$result")
        result as NightMutation.Failed
        assertEquals(Confidence.FAILED, result.night.confidence)
        assertFalse(result.nothingWritten)
        assertTrue(result.cause.message!!.startsWith("Nothing started; the BedJet slot holds the new night but it was not started"), result.cause.message)
        assertEquals(listOf(Confidence.PENDING, Confidence.FAILED), f.published.map { it.confidence })
        assertEquals(change.plan.programSteps, f.link.slots[2]!!.steps)
        assertEquals(0, f.starts)
    }

    @Test fun `a replacement start refused after its upload stays unknown`() = runTest {
        val f = Fixture()
        val night = f.night()
        f.now = f.now.plusHours(1)
        val change = Fixture.ready(f.runner.prepareStart(io.github.willzy12h.nocturne.core.plan.Presets.springAutumn,
            f.wake, 2, "demo", night.identity, night))
        val runner = f.observedRunner(afterReadback = { delay(1); f.now = ZonedDateTime.parse(change.request.deadline) })
        val result = runner.apply(change, { night }, publish = f::publish) as NightMutation.Failed
        assertEquals(Confidence.UNKNOWN, result.night.confidence)
        assertEquals(0, f.starts)
    }

    // IR-20 (NGT-3): a start whose clock sync would be in another UTC offset than the plan is refused.

    @Test fun `a start across a clock change is refused before the start byte`() = runTest {
        val f = Fixture()
        val london = ZoneId.of("Europe/London")
        f.now = ZonedDateTime.parse("2026-03-29T00:59:40Z").withZoneSameInstant(london)
        f.link.connect()
        val change = Fixture.ready(f.runner.prepareStart(io.github.willzy12h.nocturne.core.plan.Presets.springAutumn,
            ZonedDateTime.parse("2026-03-29T07:00:00+01:00[Europe/London]"), 2, "demo", null, null))
        assertEquals(java.time.ZoneOffset.UTC, change.plan.start.offset)
        // The clocks go forward at 01:00 GMT while the night is being saved.
        val runner = f.observedRunner(afterUpload = { delay(1); f.now = ZonedDateTime.parse("2026-03-29T01:00:05Z").withZoneSameInstant(london) })
        val result = runner.apply(change, { f.published.lastOrNull() }, publish = f::publish)
        assertTrue(result is NightMutation.Failed, "$result")
        assertEquals(0, f.starts, "the BedJet clock would be an hour off the plan's clock-time end")
    }

    @Test fun `a start within one offset across midnight still runs`() = runTest {
        val f = Fixture()
        val london = ZoneId.of("Europe/London")
        f.now = ZonedDateTime.parse("2026-03-28T23:59:40Z").withZoneSameInstant(london)
        f.link.connect()
        val change = Fixture.ready(f.runner.prepareStart(io.github.willzy12h.nocturne.core.plan.Presets.springAutumn,
            ZonedDateTime.parse("2026-03-29T07:00:00+01:00[Europe/London]"), 2, "demo", null, null))
        val runner = f.observedRunner(afterUpload = { delay(1); f.now = f.now.plusSeconds(25) })
        assertTrue(runner.apply(change, { f.published.lastOrNull() }, publish = f::publish) is NightMutation.Applied)
        assertEquals(1, f.starts)
    }

    // IR-05 (X-3, RT-4): a rewrite that fails before any write leaves the running night as it was.

    @Test fun `a retime whose backup outlives its deadline republishes the night unchanged`() = runTest {
        val f = Fixture()
        val night = f.night()
        val boundary = ZonedDateTime.parse(night.wakeUpStart)
        f.now = boundary.minusMinutes(1)
        val change = Fixture.ready(f.runner.prepareRetime(night, night.identity, f.wake.plusMinutes(30), boundary))
        val originalSlot = f.link.slots[2]
        val result = f.observedRunner().apply(change, { f.published.lastOrNull() ?: night }, publish = f::publish,
            beforeWrite = { delay(1); f.now = boundary })
        assertTrue(result is NightMutation.Failed, "$result")
        result as NightMutation.Failed
        assertTrue(result.nothingWritten)
        assertEquals(night, result.night)
        assertEquals(listOf(Confidence.PENDING, Confidence.CONFIRMED), f.published.map { it.confidence })
        assertEquals(night, f.published.last())
        assertEquals(0, f.writes)
        assertEquals(originalSlot, f.link.slots[2])
    }

}

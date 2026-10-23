package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class NightTransactionsTest {
    private var now = ZonedDateTime.parse("2026-10-21T22:30:00Z")
    private val wake = now.plusHours(8)
    private val link = DemoBedJet { now }
    private val controller = BedJetController(link) { now }
    private val runner = NightRunner(controller) { now }
    private val fixture = NightFixture(runner, controller) { now }
    private suspend fun start(p: Profile = Presets.springAutumn): ActiveNight {
        link.connect()
        return fixture.start(p, wake, 2, emptyList(), "demo").night
    }
    private fun ready(p: Preparation): PreparedChange {
        assertTrue(p is Preparation.Ready, "Expected a prepared change, got $p")
        return (p as Preparation.Ready).change
    }

    @Test fun `deleted live profile does not prevent retime or display`() = runTest {
        val night = start()
        now = now.plusHours(2)
        val change = ready(runner.prepareRetime(night, night.identity, wake.plusMinutes(30)))
        assertNotNull(night.activePlan(null))
        assertEquals(night.profile, change.intended.profile)
    }
    @Test fun `reapply keeps agreed wake and origin and increments revision`() = runTest {
        val night = start(Presets.springAutumn.copy(wakeSource = WakeSource(useAlarm = false)))
        now = now.plusHours(1)
        val edited = night.profile!!.copy(wakeSource = WakeSource(defaultWakeMinute = 10 * 60), middle = night.profile.middle.copy(fanStart = 50))
        val change = ready(runner.reapply(night, edited, night.identity))
        val result = runner.apply(change, { night }) as NightMutation.Applied
        assertEquals(night.wakeAt, result.night.wakeAt)
        assertEquals(night.wakeOrigin, result.night.wakeOrigin)
        assertEquals(1, result.night.revision)
        assertEquals(edited, result.night.profile)
        assertEquals(result.plan.programSteps, link.slots[2]!!.steps)
        val first = AppliedProgram.from(night.activePlan()!!)
        val history = NightRecord("n","p","P",night.startedAt,night.wakeAt,true, steps=first.steps, revisions=listOf(first))
        val revised = result.recordRevision(history)
        assertEquals(first.steps, revised.steps)
        assertEquals(listOf(first, result.night.applied), revised.revisions)
    }
    @Test fun `stale identity and revision refuse without writing`() = runTest {
        val night = start()
        assertTrue(runner.reapply(night, night.profile!!, night.identity.copy(revision=4)) is Preparation.Stale)
        now = now.plusHours(1)
        val change = ready(runner.prepareRetime(night, night.identity, wake.plusMinutes(30)))
        val before = link.slots[2]
        assertTrue(runner.apply(change, { night.copy(revision=1) }) is NightMutation.Stale)
        assertEquals(before, link.slots[2])
    }
    @Test fun `eligibility is rechecked after preparation at boundary and deadline`() = runTest {
        val night = start()
        val change = ready(runner.prepareRetime(night, night.identity, wake.plusMinutes(30), deadline=now.plusMinutes(1)))
        now = now.plusMinutes(1)
        assertTrue(runner.apply(change, { night }) is NightMutation.Refused)
        assertTrue(Eligibility.check(night, ZonedDateTime.parse(night.wakeUpStart), change.request.copy(deadline=wake.toString())) is Eligibility.Refused)
    }
    @Test fun `pending is published before any write and confirmed follows readback`() = runTest {
        val night = start()
        now = now.plusHours(1)
        val change = ready(runner.prepareRetime(night, night.identity, wake.plusMinutes(30)))
        val before = link.slots[2]
        val states = mutableListOf<Confidence>()
        runner.apply(change, { night }, publish = {
            states += it.confidence
            if (it.confidence == Confidence.PENDING) {
                assertEquals(before, link.slots[2])
                assertEquals(night.applied, it.applied)
                assertEquals(change.request.copy(intended = change.intended), it.pending)
            }
        })
        assertEquals(listOf(Confidence.PENDING, Confidence.CONFIRMED), states)
    }
    @Test fun `failure before write preserves a confirmed night and partial write is unknown`() = runTest {
        val night = start()
        now = now.plusHours(1)
        val change = ready(runner.prepareRetime(night, night.identity, wake.plusMinutes(30)))
        val states = mutableListOf<ActiveNight>()
        val failed = runner.apply(change, { night }, publish = { states += it }, beforeWrite = { error("disk full") }) as NightMutation.Failed
        assertEquals(night, failed.night)
        assertEquals(night, states.last())
        val broken = object : BedJetLink by link {
            override suspend fun send(command: ByteArray) { link.send(command); error("lost acknowledgement") }
        }
        val brokenRunner = NightRunner(BedJetController(broken) { now }) { now }
        val unknown = brokenRunner.apply(change, { night }) as NightMutation.Failed
        assertEquals(Confidence.UNKNOWN, unknown.night.confidence)
        assertNotEquals(Confidence.CONFIRMED, unknown.night.confidence)
    }
    @Test fun `fresh start refused after slot upload is failed when no start was sent`() = runTest {
        link.connect()
        val change = ready(runner.prepareStart(Presets.springAutumn, wake, 2, "demo", null, null))
        var starts = 0
        val observed = object : BedJetLink by link {
            override suspend fun send(command: ByteArray) {
                if (command[0].toInt() and 0xff == 1 && command[1].toInt() and 0xff in 0x80..0x82) starts++
                link.send(command)
                if (command[0].toInt() and 0xff == 0x40) now = ZonedDateTime.parse(change.request.deadline)
            }
        }
        val result = NightRunner(BedJetController(observed) { now }) { now }.apply(change, { null })
        assertTrue(result is NightMutation.Failed)
        assertEquals(Confidence.FAILED, (result as NightMutation.Failed).night.confidence)
        assertEquals(0, starts)
    }
    @Test fun `fresh start rejected by transport guard is failed without a start attempt`() = runTest {
        link.connect()
        val change = ready(runner.prepareStart(Presets.springAutumn, wake, 2, "demo", null, null))
        var starts = 0
        val observed = object : BedJetLink by link {
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                now = ZonedDateTime.parse(change.request.deadline)
                beforeWrite()
                starts++
                link.send(command)
            }
        }
        val result = NightRunner(BedJetController(observed) { now }) { now }.apply(change, { null })
        assertTrue(result is NightMutation.Failed)
        assertEquals(Confidence.FAILED, (result as NightMutation.Failed).night.confidence)
        assertEquals(0, starts)
        assertEquals(change.plan.programSteps, link.slots[2]!!.steps)
    }
    @Test fun `fresh start with ambiguous in flight acknowledgement remains unknown`() = runTest {
        link.connect()
        val change = ready(runner.prepareStart(Presets.springAutumn, wake, 2, "demo", null, null))
        val observed = object : BedJetLink by link {
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                link.send(command)
                error("start acknowledgement was lost")
            }
        }
        val result = NightRunner(BedJetController(observed) { now }) { now }.apply(change, { null })
        assertTrue(result is NightMutation.Failed)
        assertEquals(Confidence.UNKNOWN, (result as NightMutation.Failed).night.confidence)
    }
    @Test fun `local stop and expiry need no link and clear override`() = runTest {
        val night = start()
        val at = now.plusHours(1)
        val stopped = stopTracking(night, "Stopped tracking", at, 4)
        assertEquals(at.toString(), stopped.night.actualEnd)
        assertEquals(EventKind.STOPPED, stopped.event.kind)
        assertNotNull(stopped.resumeOffer)
        assertNull(stopped.settingsAfter(Settings().setWakeOverride(wake, now)).tonightOverride)
        assertNull(expire(night, at))
        assertEquals(EventKind.ENDED, expire(night, ZonedDateTime.parse(night.endsAt))!!.event.kind)
    }
    @Test fun `resume drops turbo already started and refuses expiry or newer action`() = runTest {
        val night = start(Presets.winterWarm)
        now = now.plusMinutes(5)
        val offer = stopTracking(night, "Off", now, 7).resumeOffer!!
        assertEquals(now.plusMinutes(15).toString(), offer.expiresAt)
        now = now.plusMinutes(1)
        val change = ready(runner.resume(offer, now, 7))
        assertFalse(change.plan.steps.any { it.block == Block.START && it.step.mode == Mode.TURBO })
        assertEquals(wake, change.plan.wake)
        assertTrue(runner.resume(offer, now, 8) is Preparation.Stale)
        assertTrue(runner.resume(offer, ZonedDateTime.parse(offer.expiresAt), 7) is Preparation.Refused)
        assertTrue(runner.apply(change, { null }, commandVersion = { 8 }) is NightMutation.Stale)
    }
    @Test fun `preview is the actual start plan`() = runTest {
        link.connect()
        val p = Presets.springAutumn
        val override = TonightOverride(now.plusHours(7).toString(), now.toString())
        val preview = NightPreview.forStartNow(p, now, wake, override)
        val result = fixture.start(p, wake, 2, emptyList(), "demo", override = override)
        assertEquals(preview.plan, result.plan)
        assertEquals(WakeOrigin.OVERRIDE, result.night.wakeOrigin)
        assertTrue(result.record.events.single().detail.contains("tonight-only"))
    }
    @Test fun `reapply changes the remaining start phase without replaying elapsed time`() = runTest {
        val night = start()
        now = now.plusMinutes(5)
        val edited = night.profile!!.copy(start = listOf(Phase(Mode.HEAT, 70, 50, 20)))
        val change = ready(runner.reapply(night, edited, night.identity))
        val first = change.plan.steps.first()
        assertEquals(70, first.step.temperature)
        assertEquals(15, first.minutes)
    }
    @Test fun `resume never restarts an already started middle turbo`() = runTest {
        val p = Presets.winterWarm.copy(start=emptyList(), wake=emptyList(), afterWake=emptyList(),
            middle=Middle(Mode.TURBO,100,100,86,86), turboChain=TurboChain.TURBO_THEN_HEAT)
        val night = start(p)
        now = now.plusMinutes(5)
        val offer = stopTracking(night,"Off",now,1).resumeOffer!!
        now = now.plusMinutes(1)
        val change = ready(runner.resume(offer,now,1))
        assertFalse(change.plan.steps.any { it.step.mode == Mode.TURBO })
    }
    @Test fun `changing identity during backup prevents a stale write`() = runTest {
        val night = start()
        var current = night
        now = now.plusHours(1)
        val change = ready(runner.prepareRetime(night, night.identity, wake.plusMinutes(30)))
        val before = link.slots[2]
        val result = runner.apply(change, {current}, beforeWrite={current=night.copy(revision=10)})
        assertTrue(result is NightMutation.Stale)
        assertEquals(before, link.slots[2])
    }

    @Test fun `start preparation is pending and stale replacement is refused`() = runTest {
        link.connect()
        val ready = ready(runner.prepareStart(Presets.springAutumn,wake,2,"demo",null,null))
        assertEquals(Confidence.PENDING,ready.pendingNight.confidence)
        assertTrue(ready.pendingNight.applied.steps.isEmpty())
        assertEquals(NightPreview.forStartNow(Presets.springAutumn,now,wake).plan,ready.plan)
        val result = runner.apply(ready,{null}) as NightMutation.Applied
        assertEquals(Confidence.CONFIRMED,result.night.confidence)
        assertTrue(runner.prepareStart(Presets.springAutumn,wake,2,"demo",null,result.night) is Preparation.Stale)
    }

    @Test fun `legacy night uses flagged live fallback for retime`() = runTest {
        val original = start()
        val legacy = ActiveNight(original.recordId, original.profileId, original.startedAt, original.wakeAt,
            original.wakeFromAlarm, original.slot, original.wakeUpStart, original.endsAt)
        now = now.plusHours(1)
        assertTrue(legacy.usesLiveProfileFallback)
        val change = ready(runner.prepareRetime(legacy,legacy.identity,wake.plusMinutes(30),liveProfile=Presets.springAutumn))
        assertEquals(Presets.springAutumn,change.intended.profile)
    }
    @Test fun `new revision preserves the first revision of legacy history`() = runTest {
        val night = start()
        now = now.plusHours(1)
        val result = runner.apply(ready(runner.prepareRetime(night,night.identity,wake.plusMinutes(30))), {night}) as NightMutation.Applied
        val legacy = NightRecord(night.recordId,night.profileId,"P",night.startedAt,night.wakeAt,true,steps=night.applied.steps)
        val updated = result.recordRevision(legacy)
        assertEquals(2,updated.revisions.size)
        assertEquals(legacy.steps,updated.revisions.first().steps)
    }

    @Test fun `cancelled alarm falls back to saved default and never restarts an ended night`() = runTest {
        val night = start()
        now = now.plusHours(1)
        val edited = Presets.springAutumn.copy(wakeSource=WakeSource(defaultWakeMinute=10*60))
        val change = ready(runner.prepareRetime(night,night.identity,null,liveProfile=edited))
        assertEquals("2026-10-22T07:00Z",change.intended.wakeAt)
        assertEquals(WakeOrigin.PROFILE_DEFAULT,change.intended.wakeOrigin)
        val ended = stopTracking(night,"Other remote took over",now).night
        assertTrue(runner.prepareRetime(ended,ended.identity,null) is Preparation.Refused)
        now = ZonedDateTime.parse(night.wakeUpStart)
        assertTrue(runner.prepareRetime(night,night.identity,null) is Preparation.Refused)
    }
    @Test fun `uncertain rewrite of a legacy night cannot be retried as legacy fallback`() = runTest {
        val original = start()
        val legacy = ActiveNight(original.recordId, original.profileId, original.startedAt, original.wakeAt,
            original.wakeFromAlarm, original.slot, original.wakeUpStart, original.endsAt,
            pending=PendingChange("failed",ChangeKind.RETIME,wake.toString(),now.toString(),wake.toString(),original.identity))
        now = now.plusHours(1)
        assertTrue(runner.prepareRetime(legacy,legacy.identity,wake.plusMinutes(30),liveProfile=Presets.springAutumn) is Preparation.Refused)
    }

    @Test fun `immediate rewrite confirms even when first step is identical`() = runTest {
        val night = start()
        val change = ready(runner.reapply(night,night.profile!!,night.identity))
        val outcome = runner.apply(change,{night})
        assertTrue(outcome is NightMutation.Applied, "Identical first step must still get fresh confirmation: $outcome")
    }

    @Test fun `resume offers time remaining even when only started turbo was left`() = runTest {
        val p = Presets.springAutumn.copy(wake=listOf(Phase(Mode.TURBO,86,100,10)),afterWake=emptyList())
        val night = start(p)
        now = wake.minusMinutes(5)
        val offer = stopTracking(night,"Off",now,1).resumeOffer
        assertNotNull(offer)
        val change = ready(runner.resume(offer!!,now,1))
        assertTrue(change.plan.steps.all { it.step.mode == Mode.WAIT })
        assertEquals(wake,change.plan.end)
    }

}

package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZonedDateTime

class TemporaryNightTest {
    private var now = ZonedDateTime.parse("2026-10-21T22:00:00Z")
    private val link = DemoBedJet { now }
    private val runner = NightRunner(BedJetController(link) { now }) { now }
    private val heat = ActionPreset(Mode.HEAT, 64, 40, 10)
    private val profile = Profile("temporary-base", "Original", Season.SPRING_AUTUMN,
        start = emptyList(), middle = Middle(Mode.COOL, 30, 15, 44, 40, 3),
        wake = listOf(Phase(Mode.HEAT, 64, 30, 20)),
        afterWake = listOf(Phase(Mode.COOL, 44, 20, 10)))

    private fun ready(preparation: Preparation): PreparedChange {
        assertTrue(preparation is Preparation.Ready, "Expected a prepared programme, got $preparation")
        return (preparation as Preparation.Ready).change
    }

    private fun night(p: Profile = profile, wake: ZonedDateTime = now.plusHours(9)): ActiveNight {
        val plan = PlanCompiler.compile(p, now, wake)
        return ActiveNight("night", p.id, now.toString(), wake.toString(), true, 2,
            plan.wakeUpStart.toString(), plan.end.toString(), "demo", p,
            applied = AppliedProgram.from(plan), confidence = Confidence.CONFIRMED)
    }

    private fun temporary(n: ActiveNight, preset: ActionPreset = heat): PreparedChange =
        ready(runner.prepareTemporary(n, n.identity, preset))

    /** A pre-existing persisted composite lets re-time tests exercise old behaviour independently of preparation. */
    private fun runningTemporary(base: ActiveNight, preset: ActionPreset): ActiveNight {
        val start = now.withSecond(0).withNano(0)
        var cursor = start
        val prefix = PlanCompiler.phaseSteps(Phase(preset.mode, preset.temperature, preset.fanPercent, preset.minutes),
            base.profile!!.limits, TurboChain.TURBO_THEN_HEAT).map {
            PlannedStep(Block.TEMPORARY, it, cursor, it.durationMinutes).also { cursor = it.endsAt }
        }
        val tail = base.activePlan()!!.steps.mapNotNull { s ->
            if (!s.endsAt.isAfter(cursor)) null else {
                val from = maxOf(s.startsAt, cursor)
                val minutes = Duration.between(from, s.endsAt).toMinutes().toInt()
                s.copy(startsAt = from, minutes = minutes,
                    step = if (s.step.endsAtClockTime) s.step else s.step.copy(hours = minutes / 60, minutes = minutes % 60))
            }
        }
        val plan = Plan(start, ZonedDateTime.parse(base.wakeAt), prefix + tail)
        val expanded = AppliedProgram.from(Plan(start, plan.wake, prefix)).steps
        return base.copy(revision = base.revision + 1, applied = AppliedProgram.from(plan, base.revision + 1),
            temporary = TemporaryOverride(base.applied, expanded, cursor.toString()))
    }

    private fun assertControls(expected: PlannedStep, actual: PlannedStep) {
        assertEquals(expected.step.mode, actual.step.mode)
        assertEquals(expected.step.temperature, actual.step.temperature)
        assertEquals(expected.step.fanStep, actual.step.fanStep)
    }

    @Test fun temporaryHeatKeepsTheOriginalAbsoluteTailAndWake() {
        val n = night()
        now = now.plusHours(2)
        val change = temporary(n)
        val until = now.plusMinutes(10)
        assertEquals(ChangeKind.TEMPORARY, change.request.kind)
        assertEquals(n.recordId, change.intended.recordId)
        assertEquals(n.identity, change.request.expected)
        assertEquals(n.revision + 1, change.intended.revision)
        assertEquals(n.startedAt, change.intended.startedAt)
        assertEquals(n.wakeAt, change.intended.wakeAt)
        assertEquals(n.wakeUpStart, change.intended.wakeUpStart)
        assertEquals(n.endsAt, change.intended.endsAt)
        assertFalse(change.intended.trackingManual)
        assertEquals(n.applied, change.intended.temporary!!.base)
        assertEquals(until.toString(), change.intended.temporary!!.until)
        assertEquals(10, change.plan.steps.filter { it.block == Block.TEMPORARY }.sumOf { it.minutes })
        assertControls(n.activePlan()!!.stepAt(until)!!.second, change.plan.stepAt(until)!!.second)
        val originalFuture = n.activePlan()!!.steps.filter { !it.startsAt.isBefore(until) }
        assertEquals(originalFuture.map { it.startsAt }, change.plan.steps.filter { it.startsAt in originalFuture.map { s -> s.startsAt } }.map { it.startsAt })
        assertEquals(n.activePlan()!!.steps.filter { it.block == Block.WAKE || it.block == Block.AFTER_WAKE },
            change.plan.steps.filter { it.block == Block.WAKE || it.block == Block.AFTER_WAKE })
    }

    @Test fun aShorterSecondInterruptionUsesTheUnderlyingSchedule() {
        val original = night()
        now = now.plusHours(1)
        val first = temporary(original, heat.copy(minutes = 30)).intended
        now = now.plusMinutes(5)
        val next = temporary(first, ActionPreset(Mode.COOL, 46, 50, 5))
        val until = now.plusMinutes(5)
        assertEquals(original.applied, next.intended.temporary!!.base)
        assertEquals(until.toString(), next.intended.temporary!!.until)
        assertEquals(2, next.intended.revision)
        assertEquals(5, next.plan.steps.filter { it.block == Block.TEMPORARY }.sumOf { it.minutes })
        assertControls(original.activePlan()!!.stepAt(until)!!.second, next.plan.stepAt(until)!!.second)
        assertFalse(next.plan.steps.drop(1).any { it.block == Block.TEMPORARY })
    }

    @Test fun aLongerSecondInterruptionDoesNotAppendTheFirstOne() {
        val original = night()
        now = now.plusHours(1)
        val first = temporary(original).intended
        now = now.plusMinutes(3)
        val next = temporary(first, heat.copy(minutes = 30))
        assertEquals(original.applied, next.intended.temporary!!.base)
        assertEquals(now.plusMinutes(30).toString(), next.intended.temporary!!.until)
        assertEquals(30, next.plan.steps.filter { it.block == Block.TEMPORARY }.sumOf { it.minutes })
        assertEquals(original.wakeAt, next.intended.wakeAt)
    }

    @Test fun boostAlwaysUsesOneTurboThenHeatEvenWhenTheProfileRepeatsTurbo() {
        val n = night(profile.copy(turboChain = TurboChain.REPEAT_TURBO))
        now = now.plusHours(1)
        val change = temporary(n, ActionPreset(Mode.TURBO, 86, 100, 25))
        val prefix = change.plan.steps.filter { it.block == Block.TEMPORARY }
        assertEquals(listOf(Mode.TURBO, Mode.HEAT), prefix.map { it.step.mode })
        assertEquals(listOf(10, 15), prefix.map { it.minutes })
        assertTrue(prefix.none { it.step.endsAtClockTime })
        assertEquals(AppliedProgram.from(Plan(change.plan.start, change.plan.wake, prefix)).steps, change.intended.temporary!!.steps)
    }

    @Test fun temporaryControlsAreClampedToTheSavedProfile() {
        val p = profile.copy(limits = ProfileLimits(20, 40, 50, 65),
            middle = Middle(Mode.HEAT, 30, 20, 60, 55))
        val n = night(p)
        now = now.plusHours(1)
        val first = temporary(n, ActionPreset(Mode.HEAT, 86, 100, 15)).plan.steps.first()
        assertEquals(65, first.step.temperature)
        assertEquals(7, first.step.fanStep)
        assertEquals(Block.TEMPORARY, first.block)
    }

    @Test fun aProfileThatCannotHonourTurboRefusesIt() {
        val n = night(profile.copy(limits = ProfileLimits(maxTemp = 80)))
        now = now.plusHours(1)
        val refused = runner.prepareTemporary(n, n.identity, ActionPreset(Mode.TURBO, 86, 100, 10)) as Preparation.Refused
        assertEquals(TemporaryRefusal.PROFILE_LIMITS, refused.temporaryRefusal)
    }

    @Test fun startedOriginalTurboIsSkippedAndItsGapIsPausedButFutureTurboRemains() {
        val n = night(profile.copy(start = listOf(Phase(Mode.TURBO, 86, 100, 10),
            Phase(Mode.COOL, 44, 30, 20), Phase(Mode.TURBO, 86, 100, 10))))
        now = now.plusMinutes(5)
        val change = temporary(n, heat.copy(minutes = 2))
        val pause = change.plan.steps.first { it.step.mode == Mode.WAIT }
        assertEquals(now.plusMinutes(2), pause.startsAt)
        assertEquals(3, pause.minutes)
        val futureTurbo = change.plan.steps.single { it.block != Block.TEMPORARY && it.step.mode == Mode.TURBO }
        assertEquals(ZonedDateTime.parse(n.startedAt).plusMinutes(30), futureTurbo.startsAt)
        assertEquals(10, futureTurbo.minutes)
    }

    @Test fun futureOriginalTurboStartingExactlyAtReturnIsNotDiscarded() {
        val n = night(profile.copy(start = listOf(Phase(Mode.COOL, 44, 30, 10),
            Phase(Mode.TURBO, 86, 100, 10), Phase(Mode.COOL, 44, 30, 10))))
        now = now.plusMinutes(5)
        val change = temporary(n, heat.copy(minutes = 5))
        val returned = change.plan.stepAt(now.plusMinutes(5))!!.second
        assertEquals(Mode.TURBO, returned.step.mode, "The future original phase starts exactly when the temporary phase ends")
        assertEquals(10, returned.minutes)
    }

    @Test fun invalidPresetValuesAreRejectedBeforeAProgrammeIsPrepared() {
        val n = night()
        for (preset in listOf(heat.copy(minutes = 0), heat.copy(minutes = 121), heat.copy(mode = Mode.STANDBY),
            heat.copy(mode = Mode.WAIT), heat.copy(fanPercent = 101), heat.copy(temperature = 37),
            ActionPreset(Mode.TURBO, 86, 100, 31))) {
            val refused = runner.prepareTemporary(n, n.identity, preset) as Preparation.Refused
            assertEquals(TemporaryRefusal.INVALID_PRESET, refused.temporaryRefusal, "$preset")
        }
    }

    @Test fun temporaryEligibilityRequiresTheExactConfirmedProgrammeSnapshot() {
        val n = night()
        assertTrue(runner.prepareTemporary(n, n.identity.copy(revision = 5), heat) is Preparation.Stale)
        for (confidence in listOf(Confidence.UNKNOWN, Confidence.FAILED, Confidence.PENDING)) {
            val result = runner.prepareTemporary(n.copy(confidence = confidence), n.identity, heat) as Preparation.Refused
            assertEquals(TemporaryRefusal.NOT_CONFIRMED, result.temporaryRefusal)
        }
        assertEquals(TemporaryRefusal.MANUAL, (runner.prepareTemporary(n.copy(trackingManual = true), n.identity, heat) as Preparation.Refused).temporaryRefusal)
        assertEquals(TemporaryRefusal.ENDED, (runner.prepareTemporary(n.copy(actualEnd = now.toString()), n.identity, heat) as Preparation.Refused).temporaryRefusal)
        assertEquals(TemporaryRefusal.MISSING_SNAPSHOT, (runner.prepareTemporary(n.copy(profile = null), n.identity, heat) as Preparation.Refused).temporaryRefusal)
        assertEquals(TemporaryRefusal.MISSING_SNAPSHOT, (runner.prepareTemporary(n.copy(applied = AppliedProgram()), n.identity, heat) as Preparation.Refused).temporaryRefusal)
    }

    @Test fun theSelectedDurationIsNeverSilentlyShortenedNearWakeUp() {
        val n = night()
        now = ZonedDateTime.parse(n.wakeUpStart).minusMinutes(20)
        val result = runner.prepareTemporary(n, n.identity, heat.copy(minutes = 15)) as Preparation.Refused
        assertEquals(TemporaryRefusal.TOO_CLOSE_TO_WAKE, result.temporaryRefusal)
    }

    private fun crowdedNight(tailSteps: Int): ActiveNight {
        val wake = now.plusHours(3)
        val middle = ProgramStep(Mode.COOL, 4, 44, wake.hour, wake.minute, true)
        val steps = listOf(PlannedStep(Block.MIDDLE, middle, now, 180)) + (0 until tailSteps).map {
            PlannedStep(Block.AFTER_WAKE, ProgramStep(Mode.COOL, 4, 44, 0, 1), wake.plusMinutes(it.toLong()), 1)
        }
        val plan = Plan(now, wake, steps)
        return night(wake = wake).copy(applied = AppliedProgram.from(plan), wakeUpStart = wake.toString(), endsAt = plan.end.toString())
    }

    @Test fun exactlyThirtyTwoCompositeStepsAreAccepted() {
        val n = crowdedNight(30)
        now = now.plusMinutes(10)
        assertEquals(32, temporary(n).plan.steps.size)
    }

    @Test fun thirtyThreeCompositeStepsAreRefusedWithoutDiscardingTheTail() {
        val n = crowdedNight(31)
        now = now.plusMinutes(10)
        val result = runner.prepareTemporary(n, n.identity, heat) as Preparation.Refused
        assertEquals(TemporaryRefusal.TOO_MANY_STEPS, result.temporaryRefusal)
        assertEquals(32, n.applied.steps.size)
    }

    @Test fun aReplacementAfterTheAutumnOffsetChangeRebuildsClockBytes() {
        now = ZonedDateTime.parse("2026-10-24T22:00:00+01:00[Europe/London]")
        val n = night(wake = ZonedDateTime.parse("2026-10-25T07:00:00Z[Europe/London]"))
        val oldAnchor = n.applied.programSteps.single { it.endsAtClockTime }
        now = ZonedDateTime.parse("2026-10-25T02:15:00Z[Europe/London]")
        val change = temporary(n)
        val anchor = change.plan.steps.single { it.step.endsAtClockTime }
        val newClockEnd = change.plan.start.toLocalTime().plusMinutes(Duration.between(change.plan.start, anchor.endsAt).toMinutes())
        assertEquals(newClockEnd.hour to newClockEnd.minute, anchor.step.hours to anchor.step.minutes)
        assertNotEquals(oldAnchor.hours to oldAnchor.minutes, anchor.step.hours to anchor.step.minutes)
        assertEquals(n.wakeAt, change.intended.wakeAt)
    }

    @Test fun alarmRetimePreservesOnlyTheUnelapsedTurboThenHeat() {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, ActionPreset(Mode.TURBO, 86, 100, 15))
        val until = n.temporary!!.until
        now = now.plusMinutes(3)
        val change = ready(runner.prepareRetime(n, n.identity, ZonedDateTime.parse(n.wakeAt).plusHours(1)))
        val prefix = change.plan.steps.filter { it.block == Block.TEMPORARY }
        assertEquals(listOf(Mode.TURBO, Mode.HEAT), prefix.map { it.step.mode })
        assertEquals(listOf(7, 5), prefix.map { it.minutes })
        assertEquals(until, change.intended.temporary!!.until)
        assertEquals(change.intended.wakeAt, change.intended.temporary!!.base.wake)
        assertTrue(change.intended.temporary!!.base.steps.none { it.block == Block.TEMPORARY })
    }

    @Test fun alarmRetimeDuringBoostHeatDoesNotRestartTurbo() {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, ActionPreset(Mode.TURBO, 86, 100, 25))
        now = now.plusMinutes(12)
        val change = ready(runner.prepareRetime(n, n.identity, ZonedDateTime.parse(n.wakeAt).plusMinutes(30)))
        val prefix = change.plan.steps.filter { it.block == Block.TEMPORARY }
        assertEquals(listOf(Mode.HEAT), prefix.map { it.step.mode })
        assertEquals(13, prefix.sumOf { it.minutes })
        assertEquals(n.temporary!!.until, change.intended.temporary!!.until)
    }

    @Test fun anEarlierAlarmShortensTheTemporaryIntervalAndPreservesTheWakePhase() {
        val original = night(profile.copy(middle = profile.middle.copy(rampSteps = 1)))
        now = now.plusHours(2)
        val n = runningTemporary(original, heat.copy(minutes = 50))
        now = now.plusMinutes(20)
        val wake = now.plusMinutes(40)
        val change = ready(runner.prepareRetime(n, n.identity, wake))
        assertEquals(wake, change.plan.wake)
        assertEquals(wake.minusMinutes(20), change.plan.wakeUpStart)
        assertEquals(now.plusMinutes(10).toString(), change.intended.temporary!!.until)
        assertEquals(10, change.plan.steps.filter { it.block == Block.TEMPORARY }.sumOf { it.minutes })
        assertEquals(20, change.plan.steps.filter { it.block == Block.WAKE }.sumOf { it.minutes })
    }

    @Test fun anEarlierAlarmRemovesThePrefixWhenItsShortMiddleHasNoSafeReturnAnchor() {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, heat.copy(minutes = 50))
        now = now.plusMinutes(20)
        val wake = now.plusMinutes(40)
        val change = ready(runner.prepareRetime(n, n.identity, wake))
        assertNull(change.intended.temporary)
        assertTrue(change.plan.steps.none { it.block == Block.TEMPORARY })
        assertEquals(wake.minusMinutes(20), change.plan.wakeUpStart)
    }

    @Test fun anElapsedTemporaryIntervalIsNotRestartedByAlarmRetime() {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, heat)
        now = now.plusMinutes(11)
        val change = ready(runner.prepareRetime(n, n.identity, ZonedDateTime.parse(n.wakeAt).plusMinutes(30)))
        assertNull(change.intended.temporary)
        assertTrue(change.plan.steps.none { it.block == Block.TEMPORARY })
    }

    @Test fun unchangedAlarmDoesNotRewriteAnActiveTemporaryInterval() {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, heat)
        val result = runner.prepareRetime(n, n.identity, ZonedDateTime.parse(n.wakeAt))
        assertTrue(result is Preparation.Refused && result.unchanged)
    }

    @Test fun stopTrackingOffersTheUnderlyingNightInsteadOfTheTemporaryAction() {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, heat.copy(minutes = 30))
        now = now.plusMinutes(3)
        val offer = stopTracking(n, "Off", now, 7).resumeOffer!!
        assertEquals(original.applied, offer.night.applied)
        assertNull(offer.night.temporary)
        assertTrue(offer.remaining.none { it.block == Block.TEMPORARY })
        val resumed = ready(runner.resume(offer, now.plusMinutes(1), 7))
        assertControls(original.activePlan()!!.stepAt(now.plusMinutes(1))!!.second, resumed.plan.steps.first())
    }

    @Test fun temporaryMetadataRoundTripsAndOldNightsStillRead() {
        val original = night()
        val old = Json.encodeToString(original)
        assertNull(Json.decodeFromString<ActiveNight>(old).temporary)
        now = now.plusHours(2)
        val n = runningTemporary(original, heat)
        assertEquals(n, Json.decodeFromString<ActiveNight>(Json.encodeToString(n)))
        val event = HistoryEvent(now.toString(), EventKind.TEMPORARY, "Temporary Heat")
        assertEquals(event, Json.decodeFromString<HistoryEvent>(Json.encodeToString(event)))
    }

    private fun assertMalformedSnapshotIsRefused(n: ActiveNight) {
        val result = assertDoesNotThrow<Preparation> { runner.prepareTemporary(n, n.identity, heat) }
        assertTrue(result is Preparation.Refused, "A malformed saved timeline must produce a typed refusal")
        assertEquals(TemporaryRefusal.MISSING_SNAPSHOT, (result as Preparation.Refused).temporaryRefusal)
        assertTrue(link.slots.all { it == null })
    }

    @Test fun malformedSnapshotStartIsARefusalInsteadOfAPreviewException() {
        val n = night()
        assertMalformedSnapshotIsRefused(n.copy(applied = n.applied.copy(start = "not-a-date")))
    }

    @Test fun malformedSnapshotWakeIsARefusalInsteadOfAPreviewException() {
        val n = night()
        assertMalformedSnapshotIsRefused(n.copy(applied = n.applied.copy(wake = "not-a-date")))
    }

    @Test fun malformedSnapshotStepIsARefusalInsteadOfAPreviewException() {
        val n = night()
        assertMalformedSnapshotIsRefused(n.copy(applied = n.applied.copy(steps = listOf(
            n.applied.steps.first().copy(startsAt = "not-a-date")) + n.applied.steps.drop(1))))
    }

    @Test fun malformedTemporaryUntilIsARefusalInsteadOfAPreviewException() {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, heat)
        assertMalformedSnapshotIsRefused(n.copy(temporary = n.temporary!!.copy(until = "not-a-date")))
    }

    @Test fun malformedNightWakeBoundaryIsARefusalInsteadOfAPreviewException() {
        assertMalformedSnapshotIsRefused(night().copy(wakeUpStart = "not-a-date"))
    }

    @Test fun longTemporaryHeatIsSplitWithinTheUnitLimits() {
        val n = night()
        now = now.plusHours(1)
        val prefix = temporary(n, ActionPreset(Mode.HEAT, 80, 100, 120)).plan.steps.filter { it.block == Block.TEMPORARY }
        assertEquals(120, prefix.sumOf { it.minutes })
        assertTrue(prefix.size > 1)
        assertTrue(prefix.all { it.step.fitsUnitLimits })
    }

    @Test fun theStoredProgrammeReturnsWithoutASecondPhoneCommand() = runTest {
        val original = night()
        now = now.plusHours(2)
        val change = temporary(original)
        var current = original
        var starts = 0
        val observed = object : BedJetLink by link {
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                starts++
                link.send(command)
            }
        }
        link.connect()
        val result = NightRunner(BedJetController(observed) { now }) { now }.apply(change, { current }, publish = { current = it })
        assertTrue(result is NightMutation.Applied)
        assertEquals(Mode.HEAT, link.status.value!!.mode)
        link.disconnect()
        now = ZonedDateTime.parse(change.intended.temporary!!.until).plusMinutes(1)
        link.tick()
        link.connect()
        assertEquals(original.activePlan()!!.stepAt(now)!!.second.step.mode, link.status.value!!.mode)
        assertEquals(1, starts)
        assertEquals(change.intended, current)
    }

    @Test fun offDoesNotLeaveASecondPhoneStartAtThePlannedReturn() = runTest {
        val original = night()
        now = now.plusHours(2)
        val change = temporary(original)
        var current = original
        link.connect()
        assertTrue(runner.apply(change, { current }, publish = { current = it }) is NightMutation.Applied)
        BedJetController(link) { now }.stop()
        link.disconnect()
        now = ZonedDateTime.parse(change.intended.temporary!!.until).plusMinutes(1)
        link.tick()
        link.connect()
        assertEquals(Mode.STANDBY, link.status.value!!.mode)
        assertEquals(0, link.status.value!!.extra!!.programStep)
    }

    @Test fun anExpiredTemporaryPhaseCannotBeStartedAfterUpload() = runTest { assertExpiredPhaseNotStarted(0x40) }

    @Test fun anExpiredTemporaryPhaseCannotBeStartedAfterClockSync() = runTest { assertExpiredPhaseNotStarted(0x08) }

    @Test fun alarmRetimeCannotRestartExpiredTemporaryTurboAfterAShortUploadDelay() = runTest {
        assertExpiredTurboNotRestarted(0x40)
    }

    @Test fun alarmRetimeCannotRestartExpiredTemporaryTurboAfterAShortClockSyncDelay() = runTest {
        assertExpiredTurboNotRestarted(0x08)
    }

    private suspend fun assertExpiredTurboNotRestarted(opcode: Int) {
        val original = night()
        now = now.plusHours(1)
        val temporary = temporary(original, ActionPreset(Mode.TURBO, 86, 100, 25))
        var current = original
        link.connect()
        assertTrue(runner.apply(temporary, { current }, publish = { current = it }) is NightMutation.Applied)
        assertEquals(Mode.TURBO, link.status.value!!.mode)
        val turboEnd = temporary.plan.steps.first().endsAt
        now = turboEnd.minusSeconds(1)
        link.tick()
        val change = ready(runner.prepareRetime(current, current.identity,
            ZonedDateTime.parse(current.wakeAt).plusMinutes(30)))
        assertEquals(ChangeKind.RETIME, change.request.kind)
        assertEquals(Mode.TURBO, change.plan.steps.first().step.mode)
        assertEquals(turboEnd, change.plan.steps.first().endsAt)
        var delayed = false
        var starts = 0
        val observed = object : BedJetLink by link {
            override suspend fun send(command: ByteArray) {
                link.send(command)
                if (!delayed && command.first().toInt() and 0xff == opcode) {
                    delayed = true
                    now = now.plusSeconds(2)
                    link.tick()
                }
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                starts++
                link.send(command)
            }
        }

        val result = NightRunner(BedJetController(observed) { now }) { now }
            .apply(change, { current }, publish = { current = it })

        assertTrue(delayed, "Exercise an actual upload or clock-sync boundary")
        assertTrue(now.isBefore(ZonedDateTime.parse(change.request.deadline)), "The request itself has not expired")
        assertTrue(now.isBefore(ZonedDateTime.parse(temporary.intended.temporary!!.until)), "Heat remains in the overall temporary interval")
        assertEquals(0, starts, "A small delay across the Turbo boundary must not restart expired Turbo")
        assertTrue(result is NightMutation.Failed && result.cause is RetryablePlanException)
        assertEquals(Confidence.UNKNOWN, current.confidence)
        assertEquals(Mode.HEAT, link.status.value!!.mode, "The original programme continues its Heat segment without another START")
    }

    private suspend fun assertExpiredPhaseNotStarted(opcode: Int) {
        val original = night()
        now = now.plusHours(2)
        val n = runningTemporary(original, heat.copy(minutes = 1))
        val plan = n.activePlan()!!
        val request = PendingChange("temporary", ChangeKind.TEMPORARY, n.wakeAt, now.toString(), now.plusMinutes(5).toString(), original.identity)
        val change = PreparedChange(plan, n, original, request)
        var current = original
        var starts = 0
        link.connect()
        val observed = object : BedJetLink by link {
            override suspend fun send(command: ByteArray) {
                link.send(command)
                if (command.first().toInt() and 0xff == opcode) now = ZonedDateTime.parse(n.temporary!!.until)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                starts++
                link.send(command)
            }
        }
        val result = NightRunner(BedJetController(observed) { now }) { now }.apply(change, { current }, publish = { current = it })
        assertEquals(0, starts, "An interval that ended during command $opcode must not start again")
        assertTrue(result is NightMutation.Failed)
        assertEquals(Confidence.UNKNOWN, current.confidence)
    }
}

package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.*
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NapProgrammeTest {
    private val start = ZonedDateTime.parse("2026-10-25T01:50:12+01:00[Europe/London]")
    private val preset = NapPreset()

    @Test fun durationStartsAtConnectionAndCrossesTheClockChangeInRealMinutes() {
        val plan = NapProgramme.plan(preset, start)
        assertEquals(listOf(10, 30, 50, 10), plan.steps.map { it.minutes })
        assertEquals(listOf(Mode.TURBO, Mode.HEAT, Mode.COOL, Mode.COOL), plan.programSteps.map { it.mode })
        assertEquals(listOf(86, 70, 44, 44), plan.programSteps.map { it.temperature })
        assertEquals(listOf(5, 7, 15), plan.programSteps.drop(1).map { it.fanStep })
        assertEquals(start.plusMinutes(90), plan.wake)
        assertEquals(plan.wake.plusMinutes(10), plan.end)
        assertTrue(plan.programSteps.all { it.fitsUnitLimits && !it.endsAtClockTime })
    }

    @Test fun fixedWakeDoesNotSlideWhenConnectionTakesLonger() {
        val wake = start.plusMinutes(60).withSecond(0)
        val plan = NapProgramme.plan(preset, start.plusMinutes(6), wake)
        assertEquals(wake, plan.wake)
        assertEquals(14, plan.steps.single { it.block == Block.MIDDLE }.minutes)
        assertThrows<PlanException> { NapProgramme.plan(preset, wake.plusMinutes(1), wake) }
    }

    @Test fun fixedChoicesRequireASafeClockDeadlineAndTheActualRequestedWake() {
        val regular = Presets.springAutumn.copy(start = listOf(Phase(Mode.COOL, 44, 40, 12)))
        val opening = preset.copy(useProfileOpening = true)
        val minute = start.withSecond(0)
        assertThrows<PlanException> { NapProgramme.plan(opening, minute, minute.plusMinutes(90), regular) }
        assertThrows<PlanException> { NapProgramme.plan(preset, minute, minute.plusMinutes(8)) }
        assertThrows<PlanException> { NapProgramme.plan(preset, minute, minute.plusMinutes(45)) }
        assertThrows<PlanException> { NapProgramme.plan(preset, minute, minute.plusMinutes(60).plusSeconds(7)) }
        val ending = NapProgramme.plan(opening, minute, minute.plusMinutes(12), regular)
        assertTrue(ending.programSteps.last().endsAtClockTime)
        assertEquals(minute.plusMinutes(12), ending.wake)
        val clippedHeat = NapProgramme.plan(preset, minute.plusSeconds(25), minute.plusMinutes(35))
        assertEquals(Mode.HEAT, clippedHeat.steps.last { it.block != Block.AFTER_WAKE }.step.mode)
        assertTrue(clippedHeat.steps.last { it.block != Block.AFTER_WAKE }.step.endsAtClockTime)
        assertEquals(minute.plusMinutes(35), clippedHeat.wake)
    }

    @Test fun shortAndLongNapsAreAdmittedOnlyWithRepresentableSteps() {
        assertEquals(listOf(5), NapProgramme.plan(preset.copy(durationMinutes = 5, afterWake = emptyList()), start).steps.map { it.minutes })
        assertEquals(start.plusHours(19), NapProgramme.plan(preset.copy(durationMinutes = 19 * 60), start).wake)
        assertThrows<PlanException> { NapProgramme.plan(preset.copy(durationMinutes = Int.MAX_VALUE), start) }
        val overCapacity = preset.copy(opening = List(32) { Phase(Mode.COOL, 44, 40, 1) })
        assertNotNull(NapProgramme.validate(overCapacity))
    }

    @Test fun selectedOpeningStopsAtItsEndAndIgnoresTheNapTailAndRegularNightWake() {
        val profile = Presets.springAutumn.copy(start = listOf(Phase(Mode.COOL, 44, 20, 11)))
        val plan = NapProgramme.plan(preset.copy(useProfileOpening = true), start, selectedProfile = profile)
        assertEquals(listOf(11), plan.steps.map { it.minutes })
        assertEquals(start.plusMinutes(11), plan.end)
        assertEquals(plan.end, plan.wake)
    }

    @Test fun removingFillerCannotBringThePostWakeStageForward() {
        val noFiller = preset.copy(untilWake = null)
        assertThrows<PlanException> { NapProgramme.plan(noFiller, start) }
        assertEquals(start.plusMinutes(40), NapProgramme.plan(noFiller.copy(afterWake = emptyList()), start).end)
    }

    @Test fun rejectsTurboSettingsThatMisdescribeTheUnitAndContinuousHotHeatBeyondItsLimit() {
        val invalid = listOf(Phase(Mode.TURBO, 86, 80, 10), Phase(Mode.TURBO, 86, 100, 11),
            Phase(Mode.TURBO, 80, 100, 10))
        invalid.forEach { assertNotNull(NapProgramme.validate(preset.copy(opening = listOf(it)))) }
        val hot = Phase(Mode.HEAT, 80, 100, 1)
        assertNotNull(NapProgramme.validate(preset.copy(opening = emptyList(), untilWake = hot)))
        assertNotNull(NapProgramme.validate(preset.copy(durationMinutes = 61, opening = listOf(hot.copy(minutes = 30)), untilWake = hot)))
        assertNull(NapProgramme.validate(preset.copy(durationMinutes = 60, opening = emptyList(), untilWake = hot)))
    }

    @Test fun fixedWakeCannotBeResumedAsAnUnanchoredDurationPlan() {
        val minute = start.withSecond(0)
        val runner = NightRunner(BedJetController(DemoBedJet { minute }) { minute }) { minute }
        val fixed = (runner.prepareNap("Quick nap", preset, minute.plusMinutes(90), false, null, 2, "demo", null, null)
            as Preparation.Ready).change.intended
        assertNull(stopTracking(fixed, "Stopped", minute.plusMinutes(3)).resumeOffer)
        val obsolete = ResumeOffer(fixed, fixed.applied.steps, minute.toString(), minute.plusMinutes(15).toString(), 1)
        assertTrue(runner.resume(obsolete, minute.plusMinutes(4), 1) is Preparation.Refused)
        val relative = (runner.prepareNap("Quick nap", preset, null, false, null, 2, "demo", null, null)
            as Preparation.Ready).change.intended
        assertNotNull(stopTracking(relative, "Stopped", minute.plusMinutes(3)).resumeOffer)
    }

    @Test fun receiptTimesUseTheActualStartOfTheUploadedDurationProgramme() {
        val runner = NightRunner(BedJetController(DemoBedJet { start }) { start }) { start }
        val change = (runner.prepareNap("Quick nap", preset, null, false, null, 2, "demo", null, null) as Preparation.Ready).change
        val actual = start.plusSeconds(38)
        val anchored = change.confirmedAt(actual)
        assertEquals(actual.toString(), anchored.startedAt)
        assertEquals(actual.plusMinutes(90).toString(), anchored.wakeAt)
        assertEquals(actual.plusMinutes(100).toString(), anchored.endsAt)
        assertEquals(actual.toString(), anchored.applied.steps.first().startsAt)
        assertTrue(change.matchesConfirmed(anchored))
        assertFalse(change.matchesConfirmed(anchored.copy(slot = 1)))
    }

    @Test fun backupRefusesInvalidNapAndPreservesEditedSettings() {
        val changed = preset.copy(durationMinutes = 225, afterWake = emptyList())
        val good = BackupBundle(createdAt = start.toString(), profiles = Presets.all, settings = Settings(napPreset = changed))
        assertEquals(changed, BackupCodec.decode(BackupCodec.encode(good)).settings.napPreset)
        assertTrue(BackupValidator.validate(good.copy(settings = Settings(napPreset = preset.copy(untilWake = Phase(Mode.COOL, 86, 40, 1))))).isNotEmpty())
    }

    @Test fun fixedNapIgnoresRetimeAndLeavesNormalWakeOverrideAlone() {
        val runner = NightRunner(BedJetController(DemoBedJet { start }) { start }) { start }
        val change = (runner.prepareNap("Quick nap", preset, null, false, null, 2, "demo", null, null) as Preparation.Ready).change
        assertTrue(change.intended.isNap)
        assertFalse(change.intended.wakeFromAlarm)
        val decided = runner.prepareRetime(change.intended, change.intended.identity, start.plusMinutes(55)) as Preparation.Refused
        assertTrue(decided.unchanged)
        val settings = Settings().setWakeOverride(start.plusHours(2), start)
        assertEquals(settings, stopTracking(change.intended, "Stopped", start.plusMinutes(3)).settingsAfter(settings))
        assertTrue(runner.prepareNap("Quick nap", preset, null, false, null, 2, "demo", null, change.intended) is Preparation.Stale)
    }
}

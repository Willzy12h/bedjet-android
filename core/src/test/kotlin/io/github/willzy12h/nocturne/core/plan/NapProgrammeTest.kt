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
        val wake = start.plusMinutes(60)
        val plan = NapProgramme.plan(preset, start.plusMinutes(6), wake)
        assertEquals(wake, plan.wake)
        assertEquals(14, plan.steps.single { it.block == Block.MIDDLE }.minutes)
        assertThrows<PlanException> { NapProgramme.plan(preset, wake.plusMinutes(1), wake) }
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

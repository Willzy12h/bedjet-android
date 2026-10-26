package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

/** Source phase indices survive the real compiler's splitting and front/end trimming. */
class EditorPhaseSelectionTest {
    private val start = ZonedDateTime.parse("2026-10-26T22:30Z")

    @Test fun turboAndItsHeatContinuationBothOpenTheSameSourcePhase() {
        val profile = Presets.summerCool.copy(start = listOf(Phase(Mode.TURBO, 86, 100, 20), Phase(Mode.COOL, 44, 40, 30)))
        val plan = PlanCompiler.compile(profile, start, start.plusHours(8))
        assertEquals(listOf(Mode.TURBO, Mode.HEAT, Mode.COOL), plan.steps.filter { it.block == Block.START }.map { it.step.mode })
        assertEquals(listOf(0, 0, 1), plan.steps.filter { it.block == Block.START }.map {
            editorPhaseSelection(profile, plan, it.block, it.startsAt).index
        })
    }

    @Test fun frontTrimmedWakeKeepsTheSurvivingOriginalPhaseIndices() {
        val profile = Presets.summerCool.copy(start = emptyList(), wake = listOf(
            Phase(Mode.HEAT, 60, 30, 20), Phase(Mode.COOL, 44, 40, 10), Phase(Mode.HEAT, 62, 30, 20)))
        val plan = PlanCompiler.compile(profile, start, start.plusMinutes(25))
        assertEquals(listOf(5, 20), plan.steps.filter { it.block == Block.WAKE }.map { it.minutes })
        assertEquals(listOf(1, 2), plan.steps.filter { it.block == Block.WAKE }.map {
            editorPhaseSelection(profile, plan, it.block, it.startsAt).index
        })
    }

    @Test fun endTrimmedStartAndAdjacentSameModePhasesRemainDistinct() {
        val profile = Presets.summerCool.copy(start = listOf(Phase(Mode.COOL, 44, 40, 20), Phase(Mode.COOL, 44, 50, 30)), wake = emptyList())
        val plan = PlanCompiler.compile(profile, start, start.plusMinutes(40))
        assertEquals(listOf(20, 20), plan.steps.filter { it.block == Block.START }.map { it.minutes })
        assertEquals(listOf(0, 1), plan.steps.filter { it.block == Block.START }.map {
            editorPhaseSelection(profile, plan, it.block, it.startsAt).index
        })
    }

    @Test fun afterWakeSelectionUsesItsOwnSourceSequence() {
        val profile = Presets.summerCool.copy(afterWake = listOf(Phase(Mode.TURBO, 86, 100, 20), Phase(Mode.COOL, 44, 40, 10)))
        val plan = PlanCompiler.compile(profile, start, start.plusHours(8))
        assertEquals(listOf(0, 0, 1), plan.steps.filter { it.block == Block.AFTER_WAKE }.map {
            editorPhaseSelection(profile, plan, it.block, it.startsAt).index
        })
    }
}

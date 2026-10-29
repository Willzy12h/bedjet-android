package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.QuietFan
import io.github.willzy12h.nocturne.core.plan.WakeSource
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** Changing only the wake follows retime policy; explicit Update tonight still reapplies the profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeOverridePolicyTest {
    @get:Rule val graphs = GraphLifecycle()
    private val profile = Profile("quiet-wake-policy", "Quiet wake change", start = emptyList(),
        middle = Middle(Mode.COOL, 50, 45, 44, 44,
            quietFan = QuietFan(settle = 50, settleMinutes = 30, asleep = 25, rise = 45, riseMinutes = 40)),
        wake = emptyList(), wakeSource = WakeSource(useAlarm = false, defaultWakeMinute = 6 * 60))

    @Test fun movingWakeLaterDoesNotReplayCompletedSettling() = wakeOnlyAfterSettling(30)
    @Test fun movingWakeEarlierDoesNotReplayCompletedSettling() = wakeOnlyAfterSettling(-30)

    private fun wakeOnlyAfterSettling(minutes: Long) = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise(profile)
        assertEquals(OutcomeKind.CONFIRMED, f.start(profile).kind)
        val before = f.graph.repository.activeNight.value!!
        f.at(f.started.plusHours(2))
        assertEquals(25, f.link.latestSourcedStatus()!!.status.fanPercent)
        val wake = ZonedDateTime.parse(before.wakeAt).plusMinutes(minutes)
        val outcome = f.graph.actions.setWakeOverride(wake, before.identity)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        val after = f.diskNight()
        assertEquals(before.recordId, after.recordId)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.profile, after.profile)
        assertEquals(WakeOrigin.OVERRIDE, after.wakeOrigin)
        assertFalse(after.wakeFromAlarm)
        assertEquals(wake, ZonedDateTime.parse(after.wakeAt))
        assertEquals(wake, ZonedDateTime.parse(f.graph.repository.settings.value.resolveWakeOverride(f.graph.now())!!.wakeAt))
        assertEquals("A wake-only edit must keep the completed settle behind the running night", 25,
            (after.applied.programSteps.first().fanStep + 1) * 5)
        assertEquals(2, f.starts())
    }

    @Test fun explicitUpdateStillReappliesSettlingWithASavedWakeOverride() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise(profile)
        assertEquals(OutcomeKind.CONFIRMED, f.start(profile).kind)
        f.at(f.started.plusHours(2))
        val wake = f.started.plusHours(8).plusMinutes(30)
        f.graph.repository.updateSettings { it.setWakeOverride(wake, f.graph.now()) }
        val outcome = f.graph.actions.execute(f.graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true))
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(wake, ZonedDateTime.parse(f.diskNight().wakeAt))
        assertEquals("Explicit profile reapply retains its distinct behaviour", 50,
            (f.diskNight().applied.programSteps.first().fanStep + 1) * 5)
    }

    @Test fun wakeChangeBeforeMiddleKeepsTheRemainingStartAndSettling() = runBlocking {
        val withStart = profile.copy(start = listOf(Phase(Mode.HEAT, 60, 50, 10)))
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise(withStart)
        assertEquals(OutcomeKind.CONFIRMED, f.start(withStart).kind)
        f.at(f.started.plusMinutes(5))
        val before = f.graph.repository.activeNight.value!!
        val outcome = f.graph.actions.setWakeOverride(f.started.plusHours(8).plusMinutes(30), before.identity)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        val plan = f.diskNight().applied.plan()!!
        assertEquals(5, plan.steps.filter { it.block == Block.START }.sumOf { it.minutes })
        assertEquals(50, (plan.steps.first { it.block == Block.MIDDLE }.step.fanStep + 1) * 5)
    }

    @Test fun wakeChangeKeepsAnActiveTemporaryRunWithoutReplayingSettlingAfterIt() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise(profile)
        assertEquals(OutcomeKind.CONFIRMED, f.start(profile).kind)
        f.at(f.started.plusHours(2))
        val temporary = f.graph.actions.execute(f.graph.actions.captureTemporary(ActionPreset(Mode.COOL, 44, 65, 10)))
        assertEquals(temporary.message, OutcomeKind.CONFIRMED, temporary.kind)
        val before = f.graph.repository.activeNight.value!!
        val outcome = f.graph.actions.setWakeOverride(f.started.plusHours(8).plusMinutes(30), before.identity)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        val after = f.diskNight()
        assertEquals(before.temporary!!.until, after.temporary!!.until)
        val plan = after.applied.plan()!!
        assertEquals(Block.TEMPORARY, plan.steps.first().block)
        assertEquals(65, (plan.steps.first().step.fanStep + 1) * 5)
        assertEquals(25, (plan.steps.first { it.block == Block.MIDDLE }.step.fanStep + 1) * 5)
    }
}

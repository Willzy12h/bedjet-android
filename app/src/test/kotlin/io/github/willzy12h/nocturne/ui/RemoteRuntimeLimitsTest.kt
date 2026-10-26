package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** A duration shown by Remote must be one the current mode can actually retain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteRuntimeLimitsTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, description ->
        runBlocking {
            fixture.graph.actions.confirmMs = 20
            fixture.graph.actions.freshStatusMs = 20
            val command = if (description.methodName.startsWith("heat")) Command.WARM else Command.TURBO
            assertEquals(OutcomeKind.CONFIRMED,
                fixture.graph.actions.execute(fixture.graph.actions.capture(command)).kind)
        }
    }
    @get:Rule val rules = ui.rules

    private fun duration(minutes: Int) = Format.duration(minutes, Format.Words { id, args -> ui.s(id, *args) })
    private fun openRemote() {
        ui.compose.onNodeWithText(ui.s(R.string.tab_remote)).performClick()
        ui.waitUntil { ui.model.hasLiveStatus.value }
    }

    @Test fun turboOffersTenMinutesAndNeverAdvertisesALongerManualTimer() {
        openRemote()
        assertEquals(Mode.TURBO, ui.model.status.value?.mode)
        assertEquals(10, ui.model.status.value?.maxRuntimeMinutes)
        val outcomes = CopyOnWriteArrayList<CommandOutcome>()
        ui.graph.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ui.graph.actions.outcomes.collect { outcomes += it }
        }
        ui.control(duration(10)).assertIsEnabled().performClick()
        ui.waitUntil { outcomes.any { it.message == ui.s(R.string.remote_timer_set) } }
        val result = outcomes.single { it.message == ui.s(R.string.remote_timer_set) }
        assertEquals(OutcomeKind.CONFIRMED, result.kind)
        assertEquals(CommandOrigin.APP, result.origin)
        assertEquals(600, ui.model.status.value?.remainingSeconds)
        listOf(15, 30, 60, 120, 240, 480).forEach { minutes ->
            ui.compose.onAllNodes(hasText(duration(minutes)) and hasClickAction()).assertCountEquals(0)
        }
    }

    @Test fun heatDoesNotOfferAnEightHourTimerWhenTheUnitReportsFourHours() {
        openRemote()
        assertEquals(Mode.HEAT, ui.model.status.value?.mode)
        assertEquals(240, ui.model.status.value?.maxRuntimeMinutes)
        ui.control(duration(240)).assertIsEnabled()
        ui.compose.onAllNodes(hasText(duration(480)) and hasClickAction()).assertCountEquals(0)
    }
}

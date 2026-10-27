package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.plan.Phase
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
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList

/** A duration shown by Remote must be one the current mode can actually retain.
 * The 320dp / 200% acceptance size also avoids Robolectric #8460's dialog/text-field width loop.
 * No assertion, input action or command path is bypassed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteRuntimeLimitsTest {
    private var swallowRuntime = false
    private var runtimeWrites = 0
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 320, fontScale = 2f) { fixture, description ->
        runBlocking {
            val demo = fixture.graph.link
            val transport = object : BedJetLink by demo {
                override suspend fun send(command: ByteArray) {
                    if (command.firstOrNull()?.toInt() == 0x02) {
                        runtimeWrites++
                        if (swallowRuntime) return
                    }
                    demo.send(command)
                }
                override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            }
            ReflectionHelpers.setField(fixture.graph, "linkOverride", transport)
            fixture.graph.actions.confirmMs = 20
            fixture.graph.actions.freshStatusMs = 20
            val command = if (description.methodName.startsWith("heat")) Command.WARM else Command.TURBO
            assertEquals(OutcomeKind.CONFIRMED,
                fixture.graph.actions.execute(fixture.graph.actions.capture(command)).kind)
            if (description.methodName.contains("Tracked")) {
                val profile = fixture.graph.repository.profiles.value.first()
                fixture.graph.repository.saveProfile(profile.copy(start = listOf(Phase(Mode.HEAT, 64, 30, 30))))
                assertEquals(OutcomeKind.CONFIRMED,
                    fixture.graph.actions.execute(fixture.graph.actions.capture(Command.START_NIGHT)).kind)
            }
        }
    }
    @get:Rule val rules = ui.rules

    private fun duration(minutes: Int) = Format.duration(minutes, Format.Words { id, args -> ui.s(id, *args) })
    private fun openRemote() {
        ui.tab(ui.s(R.string.tab_remote))
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

    private fun custom(hours: String, minutes: String) {
        ui.control("Custom…").performClick()
        ui.compose.onNodeWithText("Hours").performTextReplacement(hours)
        ui.compose.onNodeWithText("Minutes").performTextReplacement(minutes)
    }

    @Test fun heatCustomRejectsZeroAndBeyondTheReportedMaximum() {
        openRemote()
        custom("0", "0")
        ui.compose.onNodeWithText("Set duration").assertIsNotEnabled()
        ui.compose.onNodeWithText("Hours").performTextReplacement("4")
        ui.compose.onNodeWithText("Minutes").performTextReplacement("1")
        ui.compose.onNodeWithText("Set duration").assertIsNotEnabled()
        ui.compose.onNodeWithText("Minutes").performTextReplacement("0")
        ui.compose.onNodeWithText("Set duration").assertIsEnabled()
        ui.compose.onNodeWithText("Minutes").performTextReplacement("60")
        ui.compose.onNodeWithText("Set duration").assertIsNotEnabled()
    }

    @Test fun turboCustomCannotExceedTenMinutes() {
        openRemote()
        custom("0", "11")
        ui.compose.onNodeWithText("Set duration").assertIsNotEnabled()
        ui.compose.onNodeWithText("Minutes").performTextReplacement("10")
        ui.compose.onNodeWithText("Set duration").assertIsEnabled()
        ui.compose.onNodeWithText("Hours").performTextReplacement("1")
        ui.compose.onNodeWithText("Set duration").assertIsNotEnabled()
    }

    @Test fun heatCustomDurationUsesTheAppCommandOwnerAndConfirmsFromItsReceipt() {
        openRemote()
        val outcomes = CopyOnWriteArrayList<CommandOutcome>()
        ui.graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { ui.graph.actions.outcomes.collect { outcomes += it } }
        custom("3", "15")
        ui.compose.onNodeWithText("Set duration").performClick()
        ui.waitUntil { outcomes.isNotEmpty() }
        assertEquals(1, outcomes.size)
        assertEquals(OutcomeKind.CONFIRMED, outcomes.single().kind)
        assertEquals(CommandOrigin.APP, outcomes.single().origin)
        assertEquals(195 * 60, ui.graph.link.latestSourcedStatus()!!.status.remainingSeconds)
    }

    @Test fun heatCustomDurationWithoutAMatchingReceiptStaysUnknown() {
        openRemote()
        val outcomes = CopyOnWriteArrayList<CommandOutcome>()
        ui.graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { ui.graph.actions.outcomes.collect { outcomes += it } }
        swallowRuntime = true
        custom("3", "15")
        val before = runtimeWrites
        ui.compose.onNodeWithText("Set duration").performClick()
        ui.waitUntil { outcomes.isNotEmpty() }
        assertEquals(before + 1, runtimeWrites)
        assertEquals(OutcomeKind.UNKNOWN, outcomes.single().kind)
        assertNotEquals(195 * 60, ui.graph.link.latestSourcedStatus()!!.status.remainingSeconds)
    }

    @Test fun heatCustomDurationDoesNotFollowATargetChangedWhileThePickerWasOpen() {
        openRemote()
        val outcomes = CopyOnWriteArrayList<CommandOutcome>()
        ui.graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { ui.graph.actions.outcomes.collect { outcomes += it } }
        custom("1", "15")
        val captured = captureDuration(ui.graph, ui.graph.link.latestSourcedStatus()!!.status, 75).request
        val before = runtimeWrites
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        org.robolectric.Shadows.shadowOf(android.bluetooth.BluetoothAdapter.getDefaultAdapter()).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        runBlocking { ui.graph.repository.updateSettings {
            it.copy(demoMode = false, targetDevice = DeviceId.ble("AA:BB:CC:DD:EE:99"))
        } }
        ui.waitUntil { !ui.graph.repository.settings.value.demoMode }
        ui.compose.onNodeWithText("Set duration").assertDoesNotExist()
        val outcome = runBlocking { ui.graph.actions.remoteDuration(ui.s(R.string.remote_timer_set), captured) }
        assertEquals(OutcomeKind.STALE, outcome.kind)
        assertEquals("A reviewed duration cannot be sent to another target", before, runtimeWrites)
    }

    @Test fun heatTrackedCustomOffersTemporaryThenResumesTheSameNight() {
        openRemote()
        val original = ui.graph.repository.activeNight.value!!
        custom("0", "20")
        ui.compose.onNodeWithText("Set duration").performClick()
        ui.compose.onNodeWithText(ui.s(R.string.temporary_choice)).assertIsDisplayed()
        ui.compose.onNodeWithText(ui.s(R.string.temporary_end_choice)).assertIsDisplayed()
        assertEquals(original, ui.graph.repository.activeNight.value)
        ui.compose.onNodeWithText(ui.s(R.string.temporary_confirm)).performClick()
        ui.waitUntil { ui.graph.repository.activeNight.value?.temporary != null }
        val changed = ui.graph.repository.activeNight.value!!
        assertEquals(original.recordId, changed.recordId)
        assertEquals(original.wakeAt, changed.wakeAt)
        assertEquals(20, changed.temporary!!.steps.sumOf { it.minutes })
    }

    @Test fun heatTrackedCustomCanEndTonightOnlyAfterTheExplicitChoice() {
        openRemote()
        val original = ui.graph.repository.activeNight.value!!
        custom("0", "20")
        ui.compose.onNodeWithText("Set duration").performClick()
        assertEquals(original, ui.graph.repository.activeNight.value)
        ui.compose.onNodeWithText(ui.s(R.string.temporary_end_choice)).performClick()
        ui.compose.onNode(hasText(ui.s(R.string.guard_confirm)) and hasClickAction() and
            !SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        ui.waitUntil { ui.graph.repository.activeNight.value == null }
        val receipt = ui.graph.link.latestSourcedStatus()!!.status
        assertEquals(20 * 60, receipt.remainingSeconds)
        assertEquals(0, receipt.extra?.programStep)
    }
}

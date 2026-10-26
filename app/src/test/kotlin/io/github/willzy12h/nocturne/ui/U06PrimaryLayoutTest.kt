package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.ResumeOffer
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private enum class PrimaryScenario { IDLE, CONFIRMED, UNKNOWN_MANUAL, RESUME_PREVIOUS, SETUP, BACKUPS }

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class PrimarySeed(val value: PrimaryScenario)

/** U06: actual screens, simultaneous narrow width and large text, including visible feedback. No device claims. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class U06PrimaryLayoutTest(look: U06Look) {
    private val ui = U06Fixture(look) { fixture, description ->
        val scenario = requireNotNull(description.getAnnotation(PrimarySeed::class.java)) {
            "Every primary layout test must declare its launch scenario"
        }.value
        val graph = fixture.graph
        when (scenario) {
            PrimaryScenario.IDLE -> Unit // The shared fixture launches an idle demo.
            PrimaryScenario.SETUP -> runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) }
            }
            PrimaryScenario.BACKUPS -> runBlocking {
                graph.repository.snapshotNow(fixture.s(R.string.snapshot_label_manual))
            }
            PrimaryScenario.CONFIRMED, PrimaryScenario.UNKNOWN_MANUAL, PrimaryScenario.RESUME_PREVIOUS -> {
                val profile = graph.repository.profiles.value.first()
                val now = graph.now()
                val wake = now.plusHours(8)
                val plan = requireNotNull(PlanCompiler.compile(profile, now, wake))
                val night = ActiveNight(
                    "u06-night", profile.id, now.toString(), wake.toString(), false, 2,
                    wake.minusMinutes(30).toString(), wake.plusMinutes(30).toString(),
                    deviceId = "demo", profile = profile, applied = AppliedProgram.from(plan), confidence = Confidence.CONFIRMED,
                )
                runBlocking {
                    if (scenario == PrimaryScenario.CONFIRMED) graph.repository.setActiveNight(night)
                    else {
                        if (scenario == PrimaryScenario.UNKNOWN_MANUAL) {
                            graph.repository.setActiveNight(night.copy(confidence = Confidence.UNKNOWN, trackingManual = true))
                        }
                        graph.repository.updateRuntime { it.copy(resumeOffer = ResumeOffer(night, night.applied.steps,
                            now.toString(), now.plusMinutes(15).toString(), it.generation)) }
                    }
                }
            }
        }
    }
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf(it) }
    }

    @PrimarySeed(PrimaryScenario.IDLE)
    @Test fun idleStartHasItsWholeLabelAndTouchTargetAboveFeedback() {
        withAndWithoutFeedback {
            ui.control(ui.s(R.string.tonight_start_now))
            quickButtons()
        }
    }

    @PrimarySeed(PrimaryScenario.CONFIRMED)
    @Test fun runningConfirmedEndAndTrackingActionsStayReachable() {
        ui.waitUntil { ui.graph.actions.status.value.confidence == Confidence.CONFIRMED }
        assertEquals(Confidence.CONFIRMED, ui.graph.repository.activeNight.value?.confidence)
        withAndWithoutFeedback {
            ui.control(ui.s(R.string.tonight_end_night))
            ui.control(ui.s(R.string.runtime_command_stop_tracking))
            quickButtons()
        }
    }

    @PrimarySeed(PrimaryScenario.UNKNOWN_MANUAL)
    @Test fun runningUnknownManualKeepStopAndResumeStayReachable() {
        ui.waitUntil { ui.graph.actions.status.value.confidence == Confidence.UNKNOWN }
        assertTrue(ui.graph.repository.activeNight.value!!.trackingManual)
        withAndWithoutFeedback {
            ui.control(ui.s(R.string.runtime_command_keep_tracking))
            ui.control(ui.s(R.string.runtime_command_stop_tracking))
            ui.control(ui.s(R.string.recovery_resume_programme))
            quickButtons()
        }
    }

    @PrimarySeed(PrimaryScenario.RESUME_PREVIOUS)
    @Test fun idleResumePreviousStaysReachable() {
        assertNull(ui.graph.repository.activeNight.value)
        assertNotNull(ui.graph.repository.runtime.value.resumeOffer)
        withAndWithoutFeedback {
            ui.control(ui.s(R.string.recovery_resume_previous))
            ui.control(ui.s(R.string.tonight_start_now))
        }
    }

    @PrimarySeed(PrimaryScenario.CONFIRMED)
    @Test fun runningConfirmedStopTrackingDialogKeepsBothChoicesReachable() {
        ui.waitUntil { ui.graph.actions.status.value.confidence == Confidence.CONFIRMED }
        ui.snackbar()
        ui.control(ui.s(R.string.runtime_command_stop_tracking)).performClick()
        val dialog = hasAnyAncestor(isDialog())
        ui.checkControl(ui.compose.onNode(hasText(ui.s(R.string.runtime_command_stop_tracking)) and hasClickAction() and dialog),
            ui.s(R.string.runtime_command_stop_tracking), scroll = false)
        ui.checkControl(ui.compose.onNode(hasText(ui.s(R.string.runtime_command_keep_tracking)) and hasClickAction() and dialog),
            ui.s(R.string.runtime_command_keep_tracking), scroll = false).performClick()
        assertNotNull("Keep tracking must only cancel the dialog", ui.graph.repository.activeNight.value)
    }

    @PrimarySeed(PrimaryScenario.IDLE)
    @Test fun remoteModeTimerAndBoostControlsAreReachableAboveFeedback() {
        val graph = ui.graph
        val warmPreset = graph.repository.settings.value.extendWarm
        val warmFeedback = ui.compose.activity.resources.getQuantityString(
            R.plurals.runtime_captured_action_duration, warmPreset.minutes, ui.s(R.string.mode_heat), warmPreset.minutes)
        ui.control(ui.s(R.string.feature_warm)).performClick()
        ui.waitUntil { graph.link.status.value?.mode == Mode.HEAT && graph.actions.busy.value.runningLabel == null }
        ui.waitUntil { ui.compose.onAllNodesWithText(warmFeedback).fetchSemanticsNodes().isNotEmpty() }
        // Dismiss the command's real feedback so the first pass has no snackbar and the controlled one can follow.
        ui.compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        ui.waitUntil { ui.compose.onAllNodesWithText(warmFeedback).fetchSemanticsNodes().isEmpty() }
        ui.tab(ui.s(R.string.tab_remote))
        ui.waitUntil { ui.compose.onAllNodesWithText(ui.s(R.string.remote_lights)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("The Heat fixture must report its four-hour runtime ceiling", 240, graph.link.status.value?.maxRuntimeMinutes)
        withAndWithoutFeedback {
            listOf(R.string.mode_cool, R.string.mode_heat, R.string.mode_gentle_heat,
                R.string.mode_turbo, R.string.mode_dry, R.string.mode_off).forEach { ui.control(ui.s(it)) }
            listOf(15, 30).forEach { ui.control(ui.s(R.string.minutes_short, it)) }
            listOf(1, 2, 4).forEach { ui.control(ui.s(R.string.duration_hours, it)) }
            ui.compose.onAllNodes(hasText(ui.s(R.string.duration_hours, 8)) and hasClickAction()).assertCountEquals(0)
            ui.control(expectedBoostActionLabel(graph.repository.settings.value.boostMinutes))
        }
    }

    @PrimarySeed(PrimaryScenario.IDLE)
    @Test fun longOwnerAndQueueDescriptionStaysWholeAtTwoHundredPercent() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val label = ui.s(R.string.runtime_override_label)
        val quick = ui.compose.onNodeWithText(ui.s(R.string.tonight_quick)).performScrollTo()
        val before = quick.getUnclippedBoundsInRoot()
        ui.compose.runOnUiThread {
            ui.graph.scope.launch { ui.graph.actions.remote(label) { gate.await() } }
            ui.graph.scope.launch { ui.graph.actions.remote("Second") { } }
            ui.graph.scope.launch { ui.graph.actions.remote("Third") { } }
        }
        try {
            ui.waitUntil { ui.graph.actions.busy.value.runningLabel == label && ui.graph.actions.busy.value.queuedCount == 2 }
            assertEquals("Owner progress must not move Quick controls at 200%", before, quick.getUnclippedBoundsInRoot())
            val full = ui.s(R.string.busy_running, label) + " · " +
                ui.compose.activity.resources.getQuantityString(R.plurals.busy_queued, 2, 2)
            // Actual owner label and queued count, complete text semantics and no visually ellipsised line.
            ui.checkLayout(ui.compose.onNodeWithText(full), "Long owner and queue description")
        } finally { gate.complete(Unit) }
        ui.waitUntil { ui.graph.actions.busy.value.runningLabel == null }
    }

    @PrimarySeed(PrimaryScenario.SETUP)
    @Test fun tonightSetupDemoChoiceStaysReachable() = checkDemoChoice(R.string.tab_tonight)
    @PrimarySeed(PrimaryScenario.SETUP)
    @Test fun remoteSetupDemoChoiceStaysReachable() = checkDemoChoice(R.string.tab_remote)
    @PrimarySeed(PrimaryScenario.SETUP)
    @Test fun settingsSetupDemoChoiceStaysReachable() = checkDemoChoice(R.string.tab_settings)

    private fun checkDemoChoice(@StringRes tab: Int) {
        ui.tab(ui.s(tab))
        ui.control(ui.s(R.string.setup_use_demo))
        ui.snackbar()
        ui.control(ui.s(R.string.setup_use_demo)).performClick()
        ui.waitUntil { ui.graph.repository.settings.value.demoMode }
        assertNull(ui.graph.repository.settings.value.targetDevice)
    }

    @PrimarySeed(PrimaryScenario.BACKUPS)
    @Test fun settingsSlotAndBackupActionsHaveCompleteLabelsAndTargets() {
        ui.tab(ui.s(R.string.tab_settings))
        withAndWithoutFeedback {
            (1..3).forEach { ui.control(ui.s(R.string.settings_slot, it)) }
            ui.control(ui.s(R.string.settings_snapshot_now))
            ui.control(ui.s(R.string.settings_export_file))
            ui.control(ui.s(R.string.settings_restore_from_file))
            val restores = ui.compose.onAllNodes(hasText(ui.s(R.string.settings_restore)) and hasClickAction())
            assertTrue("The manual snapshot must expose Restore", restores.fetchSemanticsNodes().isNotEmpty())
            restores.fetchSemanticsNodes().indices.forEach { ui.checkControl(restores[it], "${ui.s(R.string.settings_restore)} [$it]") }
            ui.control(ui.s(R.string.settings_delete))
            ui.control(ui.s(R.string.settings_diagnostics_export))
        }
    }

    private fun quickButtons() {
        val labels = listOf(expectedBoostActionLabel(ui.graph.repository.settings.value.boostMinutes),
            ui.s(R.string.feature_warm), ui.s(R.string.feature_cool), ui.s(R.string.feature_off))
        labels.forEach { label ->
            ui.checkControl(ui.compose.onNode(hasText(label) and hasClickAction() and
                hasAnyAncestor(hasTestTag("quick-controls"))), label).assertHasClickAction()
        }
    }

    private fun withAndWithoutFeedback(check: () -> Unit) {
        check()
        ui.snackbar()
        check()
    }
}

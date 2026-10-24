package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.ResumeOffer
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** U06: actual screens, simultaneous narrow width and large text, including visible feedback. No device claims. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class U06PrimaryLayoutTest(look: U06Look) {
    private val ui = U06Fixture(look) { fixture, method ->
        val graph = fixture.graph
        if (method.contains("Confirmed") || method.contains("UnknownManual") || method.contains("Resume")) {
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
                if (method.contains("UnknownManual")) {
                    graph.repository.setActiveNight(night.copy(confidence = Confidence.UNKNOWN, trackingManual = true))
                    graph.repository.updateRuntime { it.copy(resumeOffer = ResumeOffer(night, night.applied.steps,
                        now.toString(), now.plusMinutes(15).toString(), it.generation)) }
                } else if (method.contains("Resume")) {
                    graph.repository.updateRuntime { it.copy(resumeOffer = ResumeOffer(night, night.applied.steps,
                        now.toString(), now.plusMinutes(15).toString(), it.generation)) }
                } else graph.repository.setActiveNight(night)
            }
        }
        if (method.contains("Setup")) runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) }
        }
    }
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf(it) }
    }

    @Test fun idleStartHasItsWholeLabelAndTouchTargetAboveFeedback() {
        withAndWithoutFeedback {
            ui.control(ui.s(R.string.tonight_start_now))
            quickButtons()
        }
    }

    @Test fun runningConfirmedEndAndTrackingActionsStayReachable() {
        ui.waitUntil { ui.graph.actions.status.value.confidence == Confidence.CONFIRMED }
        assertEquals(Confidence.CONFIRMED, ui.graph.repository.activeNight.value?.confidence)
        withAndWithoutFeedback {
            ui.control(ui.s(R.string.tonight_end_night))
            ui.control(ui.s(R.string.runtime_command_stop_tracking))
            quickButtons()
        }
    }

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

    @Test fun idleResumePreviousStaysReachable() {
        assertNull(ui.graph.repository.activeNight.value)
        assertNotNull(ui.graph.repository.runtime.value.resumeOffer)
        withAndWithoutFeedback {
            ui.control(ui.s(R.string.recovery_resume_previous))
            ui.control(ui.s(R.string.tonight_start_now))
        }
    }

    @Test fun runningConfirmedStopTrackingDialogKeepsBothChoicesReachable() {
        ui.waitUntil { ui.graph.actions.status.value.confidence == Confidence.CONFIRMED }
        ui.snackbar()
        ui.control(ui.s(R.string.runtime_command_stop_tracking)).performClick()
        val dialog = hasAnyAncestor(isDialog())
        ui.checkControl(ui.compose.onNode(hasText(ui.s(R.string.runtime_command_stop_tracking)) and hasClickAction() and dialog),
            "Stop tracking dialog confirm", scroll = false)
        ui.checkControl(ui.compose.onNode(hasText(ui.s(R.string.runtime_command_keep_tracking)) and hasClickAction() and dialog),
            "Keep tracking dialog cancel", scroll = false).performClick()
        assertNotNull("Keep tracking must only cancel the dialog", ui.graph.repository.activeNight.value)
    }

    @Test fun remoteModeTimerAndBoostControlsAreReachableAboveFeedback() {
        val graph = ui.graph
        runBlocking { graph.link.connect(); graph.controller().setMode(Mode.HEAT) }
        ui.tab("Remote")
        ui.waitUntil { ui.compose.onAllNodesWithText(ui.s(R.string.remote_lights)).fetchSemanticsNodes().isNotEmpty() }
        withAndWithoutFeedback {
            listOf("Cool", "Heat", "Gentle heat", "Turbo", "Dry", "Off").forEach { ui.control(it) }
            listOf("15 min", "30 min", "1 h", "2 h", "4 h", "8 h").forEach { ui.control(it) }
            ui.control(ui.s(R.string.remote_boost, graph.repository.settings.value.boostMinutes))
        }
    }

    @Test fun tonightSetupDemoChoiceStaysReachable() = checkDemoChoice("Tonight")
    @Test fun remoteSetupDemoChoiceStaysReachable() = checkDemoChoice("Remote")
    @Test fun settingsSetupDemoChoiceStaysReachable() = checkDemoChoice("Settings")

    private fun checkDemoChoice(tab: String) {
        ui.tab(tab)
        ui.control(ui.s(R.string.setup_use_demo))
        ui.snackbar()
        ui.control(ui.s(R.string.setup_use_demo)).performClick()
        ui.waitUntil { ui.graph.repository.settings.value.demoMode }
        assertNull(ui.graph.repository.settings.value.targetDevice)
    }

    @Test fun settingsSlotAndBackupActionsHaveCompleteLabelsAndTargets() {
        val graph = ui.graph
        runBlocking { graph.repository.snapshotNow("U06 manual snapshot") }
        ui.tab("Settings")
        withAndWithoutFeedback {
            (1..3).forEach { ui.control(ui.s(R.string.settings_slot, it)) }
            ui.control(ui.s(R.string.settings_snapshot_now))
            ui.control(ui.s(R.string.settings_export_file))
            ui.control(ui.s(R.string.settings_restore_from_file))
            val restores = ui.compose.onAllNodes(hasText(ui.s(R.string.settings_restore)) and hasClickAction())
            assertTrue("The manual snapshot must expose Restore", restores.fetchSemanticsNodes().isNotEmpty())
            restores.fetchSemanticsNodes().indices.forEach { ui.checkControl(restores[it], "Restore snapshot $it") }
            ui.control(ui.s(R.string.settings_delete))
            ui.control(ui.s(R.string.settings_diagnostics_export))
        }
    }

    private fun quickButtons() {
        listOf(R.string.feature_boost, R.string.feature_warm, R.string.feature_cool, R.string.feature_off)
            .forEach { ui.control(ui.s(it)) }
    }

    private fun withAndWithoutFeedback(check: () -> Unit) {
        check()
        ui.snackbar()
        check()
    }
}

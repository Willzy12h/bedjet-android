package io.github.willzy12h.nocturne.platform
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.DeviceNames
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.ui.features.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class FeatureUiTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @get:Rule val compose=createComposeRule()
    @Test fun settingsExplainsBestEffortAndSupervisedAcceptance() {
        val graph=(RuntimeEnvironment.getApplication() as Context).graph
        compose.setContent { MaterialTheme { PlatformSettingsSection(graph) } }
        compose.onNodeWithText("Charger auto-start").assertIsDisplayed()
        compose.onNodeWithContentDescription("Charger auto-start").assertIsDisplayed()
        compose.onNodeWithText("Supervised acceptance pending",substring=true).assertIsDisplayed()
        compose.onNodeWithText("after restoring",substring=true).assertIsDisplayed()
    }
    @Test fun historyExportRequiresExplicitDeviceIdentifierChoice() {
        val graph=(RuntimeEnvironment.getApplication() as Context).graph
        compose.setContent { MaterialTheme { HistoryExportButton(graph) } }
        compose.onNodeWithText("Export history CSV").performClick()
        compose.onNodeWithText("Include device identifiers").assertIsDisplayed()
    }
    @Test fun manualPhoneReleaseIsVisible() {
        val graph=(RuntimeEnvironment.getApplication() as Context).graph
        compose.setContent { MaterialTheme { Column { PhoneHandoverMenuItems(graph) {} } } }
        compose.onNodeWithText("Release BedJet for another phone").assertIsDisplayed()
    }

    private fun failedHandoverRequest(failure: RuntimeException): CommandOutcome {
        val app = RuntimeEnvironment.getApplication() as App
        val context = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String): Int {
                if (permission == Manifest.permission.BLUETOOTH_ADVERTISE) throw failure
                return super.checkSelfPermission(permission)
            }
        }
        val graph = fresh { Graph(context, DemoBedJet()) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        val target = "ble:AA:BB:CC:DD:EE:FF"
        runBlocking {
            graph.repository.updateSettings {
                it.copy(demoMode = false, targetDevice = target, platform = it.platform.copy(lullHandover = true))
            }
        }
        DeviceNames(context) { target }.verify(target, "BEDJET-ORIGIN")
        val received = AtomicReference<CommandOutcome>()
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                Column { PhoneHandoverMenuItems(graph, onOutcome = received::set) { dismissals++ } }
            }
        }
        compose.onNodeWithText(app.getString(R.string.feature_request)).performClick()
        compose.waitUntil(5_000) { received.get() != null }
        assertEquals("The submitted menu operation closes once", 1, dismissals)
        return received.get()
    }

    @Test fun theHandoverMenusTimeoutOutcomeKeepsAppOrigin() {
        val timeout = runBlocking {
            try { withTimeout(1) { awaitCancellation() } }
            catch (e: TimeoutCancellationException) { e }
        }
        val outcome = failedHandoverRequest(timeout)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(CommandOrigin.APP, outcome.origin)
        assertEquals((RuntimeEnvironment.getApplication() as Context).getString(R.string.feature_operation_timeout), outcome.message)
    }

    @Test fun theHandoverMenusFailureOutcomeKeepsAppOrigin() {
        val outcome = failedHandoverRequest(IllegalStateException("Permission check unavailable"))
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals(CommandOrigin.APP, outcome.origin)
        assertEquals("Permission check unavailable", outcome.message)
    }
}

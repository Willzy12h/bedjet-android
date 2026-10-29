package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** A connected transport is not evidence that a saved night programme is confirmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RemediationStateSafetyTest {
    private val link = ReceiptLink()
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, test ->
        val clock = fixture.graph.clock
        val app = RuntimeEnvironment.getApplication() as App
        val graph = fixture.graphs.fresh {
            Graph(app, link, repositoryFactory = { scope, now -> Repository(app, scope, writer = Dispatchers.Main.immediate, now = now) })
                .also { it.clock = clock }
        }
        ReflectionHelpers.setField(app, "graph", graph)
        runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = TARGET) }
            if (test.methodName.contains("WithoutAFresh")) {
                link.status.value = null
                link.sourcedStatus.value = null
            } else {
                val profile = graph.repository.profiles.value.first()
                val now = graph.now()
                val manual = test.methodName.startsWith("manual")
                graph.repository.setActiveNight(ActiveNight("status-proof", profile.id, now.toString(), now.plusHours(8).toString(),
                    false, 0, now.plusHours(7).toString(), now.plusHours(9).toString(), deviceId = TARGET, profile = profile,
                    confidence = if (manual) Confidence.CONFIRMED else Confidence.UNKNOWN, trackingManual = manual))
            }
        }
    }
    @get:Rule val rules = ui.rules

    @Test fun unconfirmedNightDoesNotClaimToBeRunningInTheStatusLine() = assertConnectedWithoutProgrammeClaim()
    @Test fun manualTrackingDoesNotClaimTheNightProgrammeIsRunning() = assertConnectedWithoutProgrammeClaim()

    private fun assertConnectedWithoutProgrammeClaim() {
        ui.waitUntil { ui.model.hasLiveStatus.value }
        assertEquals(LinkState.READY, ui.model.linkState.value)
        assertNotNull(ui.graph.repository.activeNight.value)
        ui.compose.onNodeWithTag("status-connection").assertTextContains("connected", substring = true)
        ui.compose.onNodeWithTag("status-connection").assertTextContains("AA:BB:CC:DD:EE:01", substring = true)
        ui.compose.onAllNodes(hasTestTag("status-connection") and hasText("running", substring = true)).assertCountEquals(0)
    }

    @Test fun quickPresetsWithoutAFreshReportAreSemanticallyDisabled() {
        assertFalse(ui.model.hasLiveStatus.value)
        listOf("TURBO", "HEAT", "COOL", "DRY", "BOOST", "STANDBY").forEach { mode ->
            ui.compose.onNodeWithTag("quick-mode-$mode").performScrollTo().assertIsNotEnabled()
        }
        assertTrue("A disabled control cannot issue any transport write", link.writes.isEmpty())
    }

    private class ReceiptLink : BedJetLink {
        override val state = MutableStateFlow(LinkState.READY)
        override val status = MutableStateFlow<DeviceStatus?>(DeviceStatus(600, 50, 64, Mode.HEAT, 9, 600, 38, 86, 44, 0,
            DeviceStatus.Extra(false, false, 0, 0)))
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(SourcedStatus(TARGET, requireNotNull(status.value)))
        val writes = mutableListOf<ByteArray>()
        override fun latestSourcedStatus() = sourcedStatus.value
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun send(command: ByteArray) { writes += command.copyOf() }
        override suspend fun query(request: Int) = byteArrayOf()
    }
    companion object { private const val TARGET = "ble:AA:BB:CC:DD:EE:01" }
}

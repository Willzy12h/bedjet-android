package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.night.alarmSourceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Phone acceptance requirements against the pre-change public rendering paths. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UatRound2RegressionTest {
    private val graphs = GraphLifecycle()
    private val compose = createComposeRule()
    private val models = ViewModelStore()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)
    @After fun closeModels() = models.clear()

    private fun model(demo: Boolean = false, target: String? = null): AppViewModel {
        val app = RuntimeEnvironment.getApplication() as App
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        @Suppress("DEPRECATION")
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        val link = object : BedJetLink {
            override val state = MutableStateFlow(LinkState.READY)
            override val status = MutableStateFlow<DeviceStatus?>(null)
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun send(command: ByteArray) = Unit
            override suspend fun query(request: Int) = ByteArray(0)
        }
        val graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-27T22:30:00Z"), ZoneOffset.UTC)
        runBlocking { graph.repository.updateSettings {
            it.copy(demoMode = demo, targetDevice = target, quickControlsCollapsed = false)
        } }
        return AppViewModel(app).also { models.put("uat", it) }
    }

    @Test fun U1_readyWithoutACurrentSelectedReportMustNotSayConnected() {
        val model = model(target = "ble:AA:BB:CC:DD:EE:01")
        compose.setContent { MaterialTheme { StatusLine(model, runningName = null, onRemote = {}) } }
        compose.onNodeWithTag("status-connection").assertTextContains("connecting", substring = true)
    }

    @Test fun U2_anUnidentifiedPhoneAlarmHasAnActionableNeutralLabel() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("Phone alarm (app not identified)", alarmSourceName(app, null))
        assertEquals("Phone alarm (app not identified)", alarmSourceName(app, "android"))
    }

    @Test fun U3_idleWorkIndicatorReservesNoVisibleHeight() {
        val model = model(demo = true)
        compose.setContent { MaterialTheme {
            Column(Modifier.testTag("work-slot")) { BusyIndicator(model) }
        } }
        compose.onNodeWithTag("work-slot").assertHeightIsEqualTo(0.dp)
    }

    @Test fun U4_aMissingRequiredSetupItemShowsTheChecklistAtTheTopOfTonight() {
        val model = model()
        compose.setContent { MaterialTheme { TonightScreen(model, Modifier, onEdit = {}) } }
        compose.onNodeWithTag("setup-checklist").assertIsDisplayed()
        compose.onNodeWithText("Setup checklist").assertIsDisplayed()
        compose.onNodeWithTag("setup-pair").assertIsDisplayed()
    }

    @Test fun U5_quickControlsExposeAllSixApprovedPresetsInsteadOfWarm() {
        val model = model(demo = true)
        compose.setContent { MaterialTheme { TonightScreen(model, Modifier, onEdit = {}) } }
        listOf("TURBO", "HEAT", "COOL", "DRY", "BOOST", "STANDBY").forEach {
            compose.onNodeWithTag("quick-mode-$it").assertExists()
        }
        compose.onAllNodesWithText("Warm").assertCountEquals(0)
    }
}

package io.github.willzy12h.nocturne.ui

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
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

/** Full activity and Remote composition; malformed bytes never replace a current receipt. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RemoteMalformedReportSecurityTest {
    private fun packet() = ByteArray(30).also {
        it[1] = 0x56; it[3] = 1; it[5] = 10; it[7] = 50; it[8] = 60
        it[9] = Mode.HEAT.code.toByte(); it[10] = 5; it[11] = 1
        it[13] = 45; it[14] = 80; it[17] = 45
    }
    private val writes = mutableListOf<ByteArray>()
    private val link = object : BedJetLink {
        override val state = MutableStateFlow(LinkState.READY)
        override val status = MutableStateFlow<DeviceStatus?>(Codec.parseStatus(packet()))
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun send(command: ByteArray) { writes += command.copyOf() }
        override suspend fun query(request: Int) = ByteArray(0)
        fun receive(bytes: ByteArray) { Codec.parseStatus(bytes)?.let { status.value = it } }
    }
    private val graphs = GraphLifecycle()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : org.junit.runners.model.Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            val graph = graphs.fresh { Graph(app, link) }
            ReflectionHelpers.setField(app, "graph", graph)
            graph.clock = Clock.fixed(Instant.parse("2026-10-27T22:30:00Z"), ZoneOffset.UTC)
            runBlocking { graph.repository.updateSettings { it.copy(demoMode = true, simpleView = false) } }
            base.evaluate()
        }
    } }
    @get:Rule val rules = RuleChain.outerRule(graphs).around(fixture).around(compose)

    @Test fun reversedSelectedStatusDoesNotCrashRemoteOrCreateAConfirmation() {
        compose.onAllNodesWithText("Remote")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("temperature-tick-0").assertExists()
        val receipt = link.latestSourcedStatus()
        val sent = writes.size
        compose.runOnIdle { link.receive(packet().also { it[13] = 100; it[14] = 40 }) }
        compose.waitForIdle()
        compose.onNodeWithTag("temperature-tick-0").assertExists()
        assertSame(receipt, link.latestSourcedStatus())
        assertEquals(sent, writes.size)
        assertEquals(45, link.status.value!!.minTemperature)
        assertEquals(80, link.status.value!!.maxTemperature)
    }
}

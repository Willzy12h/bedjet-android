package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** A retained screen is presentation only; controls and confirmation still need this connection's report. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RemoteReconnectPresentationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val graphs = GraphLifecycle()
    private val link = ReconnectingLink()
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            val replacement = graphs.fresh { Graph(app, link) }
            replacement.clock = Clock.fixed(Instant.parse("2026-10-25T22:30:00Z"), ZoneOffset.UTC)
            ReflectionHelpers.setField(app, "graph", replacement)
            runBlocking { replacement.repository.updateSettings {
                it.copy(demoMode = true, fahrenheit = false, themeMode = ThemeMode.DARK)
            } }
            replacement.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                replacement.actions.outcomes.collect { outcomes += it }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private val model get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test fun appSwitchKeepsRemoteReadingsAndControlsVisibleWithTheConnectionIndicator() {
        openRemoteAndReconnect()
        compose.onNodeWithText("Air 25°C · room 22°C").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last reported").assertIsDisplayed()
        compose.onNodeWithTag("status-connection").assertTextEquals("Demo")
        assertEquals(LinkState.CONNECTING, model.linkState.value)
        compose.onNode(hasText("Cool") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Temperature").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        switch("Lights").performScrollTo().assertIsDisplayed().assertIsOn().assertIsNotEnabled()
        assertTrue(link.writes.isEmpty())
    }

    @Test fun readyWithoutANewSessionReportCannotEnableCachedControls() {
        openRemoteAndReconnect()
        compose.runOnUiThread { link.readyWithoutStatus() }
        compose.waitForIdle()
        compose.onNodeWithText("Air 25°C · room 22°C").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Cool") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Fan").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Last reported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("status-connection").assertTextEquals("Demo")
        assertEquals(LinkState.READY, model.linkState.value)
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        switch("Lights").performScrollTo().assertIsNotEnabled()
        compose.runOnUiThread { link.publish(temperature = 60, led = false) }
        waitUntil { model.hasLiveStatus.value && model.status.value?.extra?.ledEnabled == false }
        switch("Lights").performScrollTo().assertIsOff().assertIsEnabled()
        compose.onNode(hasText("Remote") and hasClickAction()).performClick()
        compose.onNodeWithText("Air 25°C · room 22°C").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last reported").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fan").performScrollTo().assertIsEnabled()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        waitUntil { model.status.value?.actualTemperature == 60 }
        compose.onNodeWithText("Air 30°C · room 22°C").performScrollTo().assertIsDisplayed()
    }

    @Test fun aCachedMatchingAccessoryDoesNotConfirmAnOwnerCommand() {
        openRemoteAndReconnect()
        compose.runOnUiThread { link.readyWithoutStatus() }
        compose.runOnIdle {
            assertEquals(true, model.status.value?.extra?.ledEnabled)
            assertNull(link.latestSourcedStatus())
            model.remote("Lights on", confirms = { it.extra?.ledEnabled == true }) { it.setLed(true) }
        }
        waitUntil { link.writes.isNotEmpty() }
        compose.runOnIdle {
            assertTrue(graph.actions.busy.value.runningLabel != null)
            assertTrue("A retained matching value cannot confirm a new command", outcomes.isEmpty())
            assertNull("UI replay cannot fill the transport's raw state", link.latestSourcedStatus())
        }
        compose.runOnUiThread { link.publish(temperature = 50, led = false) }
        compose.waitForIdle()
        assertTrue(outcomes.isEmpty())
        compose.runOnUiThread { link.publish(temperature = 50, led = true) }
        val receipt = link.latestSourcedStatus()
        waitUntil { outcomes.isNotEmpty() }
        assertEquals(OutcomeKind.CONFIRMED, outcomes.single().kind)
        assertSame(receipt, link.latestSourcedStatus())
    }

    private fun openRemoteAndReconnect() {
        compose.onNode(hasText("Remote") and hasClickAction()).performClick()
        compose.onNodeWithText("Air 25°C · room 22°C").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        link.beginReconnect()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun switch(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        condition()
    }

    private class ReconnectingLink : BedJetLink {
        override val state = MutableStateFlow(LinkState.READY)
        override val status = MutableStateFlow<DeviceStatus?>(null)
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(null)
        val writes = CopyOnWriteArrayList<ByteArray>()
        private var gate: CompletableDeferred<Unit>? = null
        init { publish(50, true) }
        fun publish(temperature: Int, led: Boolean) {
            val report = DeviceStatus(600, temperature, 64, Mode.HEAT, 9, 600, 38, 86, 44, 0,
                DeviceStatus.Extra(led, false, 0, 0))
            status.value = report
            sourcedStatus.value = SourcedStatus("demo", report)
        }
        fun beginReconnect() {
            gate = CompletableDeferred()
            state.value = LinkState.CONNECTING
            status.value = null
            sourcedStatus.value = null
        }
        fun readyWithoutStatus() { state.value = LinkState.READY; gate?.complete(Unit) }
        override fun latestSourcedStatus() = sourcedStatus.value
        override suspend fun connect() { gate?.await(); state.value = LinkState.READY }
        override suspend fun disconnect() { state.value = LinkState.SEARCHING; status.value = null; sourcedStatus.value = null }
        override suspend fun send(command: ByteArray) { writes += command.copyOf() }
        override suspend fun query(request: Int) = byteArrayOf()
    }
}

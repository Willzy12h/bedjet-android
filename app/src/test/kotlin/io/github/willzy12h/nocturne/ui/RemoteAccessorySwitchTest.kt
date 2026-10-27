package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/** Settings' device switches keep the independently verified wire directions and receipt-only confirmation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RemoteAccessorySwitchTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val graphs = GraphLifecycle()
    private val link = AccessoryLink()
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            val replacement = graphs.fresh { Graph(app, link) }
            replacement.clock = Clock.fixed(Instant.parse("2026-10-25T22:30:00Z"), ZoneOffset.UTC)
            ReflectionHelpers.setField(app, "graph", replacement)
            runBlocking {
                replacement.repository.updateSettings {
                    it.copy(demoMode = true, themeMode = ThemeMode.DARK)
                }
            }
            replacement.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                replacement.actions.outcomes.collect { outcomes += it }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(fixture).around(compose)
    private val graph get() = compose.activity.graph

    @Test fun lightsOnSendsEnableAndWaitsForTheLedBit() {
        toggle("Lights", initialFlags = "00", initiallyOn = false, command = 0x46,
            unrelatedFlags = "01", matchingFlags = "11", confirmedMessage = "Lights on")
    }

    @Test fun lightsOffSendsDisableAndWaitsForTheLedBitToClear() {
        toggle("Lights", initialFlags = "11", initiallyOn = true, command = 0x47,
            unrelatedFlags = "10", matchingFlags = "00", confirmedMessage = "Lights off")
    }

    @Test fun beepsOnSendsUnmuteAndWaitsForTheMutedBitToClear() {
        toggle("Beeps", initialFlags = "11", initiallyOn = false, command = 0x49,
            unrelatedFlags = "01", matchingFlags = "00", confirmedMessage = "Beeps on")
    }

    @Test fun beepsOffSendsMuteAndWaitsForTheMutedBit() {
        toggle("Beeps", initialFlags = "00", initiallyOn = true, command = 0x48,
            unrelatedFlags = "10", matchingFlags = "11", confirmedMessage = "Beeps off")
    }

    private fun toggle(label: String, initialFlags: String, initiallyOn: Boolean, command: Int,
        unrelatedFlags: String, matchingFlags: String, confirmedMessage: String) {
        compose.runOnUiThread { link.publish(initialFlags) }
        compose.onNode(hasText("Remote") and hasClickAction()).performClick()
        switch(label).assertDoesNotExist()
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        waitUntil { switchHasState(label, initiallyOn) }
        switch(label).performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        waitUntil { link.writes.isNotEmpty() }
        assertArrayEquals("$label must send the independently verified command", byteArrayOf(0x01, command.toByte()), link.writes.single())

        // A fresh report changing only the OTHER accessory is not confirmation of this request.
        compose.runOnUiThread { link.publish(unrelatedFlags) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("$label must still await its own matching status bit", graph.actions.busy.value.runningLabel != null)
            assertTrue("An unrelated accessory change must not confirm $label", outcomes.isEmpty())
        }
        assertTrue("$label keeps the reported state while the requested state is unconfirmed", switchHasState(label, initiallyOn))

        compose.runOnUiThread { link.publish(matchingFlags) }
        waitUntil { outcomes.isNotEmpty() && switchHasState(label, !initiallyOn) }
        val outcome = outcomes.single()
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(confirmedMessage, outcome.message)
        switch(label).assertIsDisplayed()
    }

    private fun switch(label: String) = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
    )

    private fun switchHasState(label: String, on: Boolean): Boolean = compose.onAllNodes(
        hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, if (on) ToggleableState.On else ToggleableState.Off),
    ).fetchSemanticsNodes().size == 1

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        condition()
    }

    /** Transport boundary only: replies are supplied independently, never inferred from the command under test. */
    private class AccessoryLink : BedJetLink {
        override val state = MutableStateFlow(LinkState.READY)
        override val status = MutableStateFlow<DeviceStatus?>(null)
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(null)
        val writes = CopyOnWriteArrayList<ByteArray>()

        init { publish("00") }

        fun publish(flags: String) {
            val packet = "01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 $flags 00 00 53"
                .split(" ").map { it.toInt(16).toByte() }.toByteArray()
            val decoded = requireNotNull(Codec.parseStatus(packet))
            status.value = decoded
            sourcedStatus.value = SourcedStatus(DeviceId.DEMO, decoded)
        }

        override fun latestSourcedStatus() = sourcedStatus.value
        override suspend fun connect() { state.value = LinkState.READY }
        override suspend fun disconnect() { state.value = LinkState.SEARCHING }
        override suspend fun send(command: ByteArray) { writes += command.copyOf() }
        override suspend fun query(request: Int): ByteArray = throw BedJetError.BadResponse()
    }
}

package io.github.willzy12h.nocturne.ui

import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
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

/**
 * In Dry a real BedJet reports its lowest temperature moving with the room, 24.5 °C one second and 25 °C the next.
 * The Remote's bar, its labels and its thumb must stay exactly where they are while that happens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RemoteSteadyTemperatureTest {
    /** Dry at 29 °C, 10 minutes left of a run the BedJet allows up to 11 h 59 min, lowest [min] and highest 31 °C. */
    private fun dry(min: Int) = ByteArray(30).also {
        it[1] = 0x56; it[3] = 1; it[5] = 10; it[7] = 50; it[8] = 58
        it[9] = Mode.DRY.code.toByte(); it[10] = 7; it[11] = 11; it[12] = 59
        it[13] = min.toByte(); it[14] = 62; it[17] = 45
    }
    private val link = object : BedJetLink {
        override val state = MutableStateFlow(LinkState.READY)
        override val status = MutableStateFlow<DeviceStatus?>(Codec.parseStatus(dry(49)))
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun send(command: ByteArray) = Unit
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
    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun temp(halves: Int) = Format.temp(halves, false, Format.Words { id, args -> s(id, *args) })

    private fun layout(): List<Pair<String, Rect>> =
        (listOf("temperature-tick-0", "temperature-tick-1", "temperature-tick-2").map { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() to node.boundsInRoot
        } + compose.onNodeWithContentDescription(s(R.string.slider_temp)).fetchSemanticsNode().let { "slider" to it.boundsInRoot })

    @Test fun theBarLabelsAndThumbStayPutWhileTheLowestTemperatureFlips() {
        compose.openRemote()
        compose.onNodeWithContentDescription(s(R.string.slider_temp)).performScrollTo()
        compose.waitForIdle()
        val first = layout()
        // Dry's own scale, 20 to 31 °C, not the BedJet's moving 24.5 °C.
        assertEquals(listOf(temp(40), temp(51), temp(62)), first.take(3).map { it.first })
        repeat(6) { flip ->
            compose.runOnIdle { link.receive(dry(if (flip % 2 == 0) 50 else 49)) }
            compose.waitForIdle()
            assertEquals("Report ${flip + 1} moved the temperature bar", first, layout())
        }
    }

    @Test fun aTemperatureBelowWhatTheBedJetAllowsNowIsSetToItsLowestAndSaysSo() {
        compose.openRemote()
        compose.runOnIdle { link.receive(dry(50)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(s(R.string.slider_temp)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(44f) }
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.remote_temperature_limited, temp(50), temp(62), s(R.string.mode_dry), temp(50)))
            .assertExists()
    }

    @Test fun runForOffersTheMaximumAndTheStatusSaysWhenTheRunEnds() {
        compose.openRemote()
        compose.onNodeWithTag("remote-run-max").performScrollTo()
            .assertTextEquals(s(R.string.remote_run_max, Format.duration(720, Format.Words { id, args -> s(id, *args) })))
        compose.onNodeWithTag("remote-status").assertTextContains("until 22:40", substring = true)
    }
}

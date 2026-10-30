package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** The real idle Quick tap captures its action before owner admission, including unsupported imports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdleQuickCaptureTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val graphs = GraphLifecycle()
    private val imported = ActionPreset(Mode.TURBO, 86, 100, 15)
    private val writes = CopyOnWriteArrayList<ByteArray>()
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            demo = DemoBedJet(now = { graph.now() })
            val link = object : BedJetLink by demo {
                override suspend fun send(command: ByteArray) {
                    writes += command.copyOf()
                    demo.send(command)
                }
                override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                    beforeWrite()
                    send(command)
                }
            }
            graph = graphs.fresh { Graph(app, link) }
            ReflectionHelpers.setField(app, "graph", graph)
            graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
            graph.actions.confirmMs = 20
            graph.actions.freshStatusMs = 100
            runBlocking {
                // Restore deliberately retains the current hardware selection, including Demo mode.
                // Establish it independently before importing the unsupported saved action.
                graph.repository.updateSettings { it.copy(
                    demoMode = true, themeMode = ThemeMode.DARK, fahrenheit = false,
                    boostMinutes = 15,
                ) }
                val saved = graph.repository.bundle().let { it.copy(settings = it.settings.copy(extendWarm = imported)) }
                val decoded = BackupCodec.decode(BackupCodec.encode(saved))
                assertEquals(emptyList<String>(), BackupValidator.validate(decoded))
                graph.repository.restore(decoded)
                assertTrue(graph.repository.settings.value.demoMode)
                assertEquals("demo", graph.repository.settings.value.deviceId())
                assertNull(graph.repository.activeNight.value)
            }
            graph.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                graph.actions.outcomes.collect { if (it.origin == CommandOrigin.APP) outcomes += it }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(fixture).around(compose)

    private fun waitUntil(check: () -> Boolean) {
        var lastTick = System.nanoTime()
        var remainder = 0L
        compose.waitUntil(10_000) {
            // The programme-start refusal grace uses the Android main clock. One virtual millisecond
            // per real polling interval cannot finish that 1.5-second grace within this deadline.
            val tick = System.nanoTime()
            val elapsed = tick - lastTick + remainder
            lastTick = tick
            remainder = elapsed % 1_000_000
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsed / 1_000_000))
            check()
        }
    }

    private fun idleState() {
        waitUntil { demo.status.value != null && graph.actions.busy.value.runningLabel == null }
        assertNull(graph.repository.activeNight.value)
        outcomes.clear()
        writes.clear()
    }

    @Test fun anIdleBoostQueuedAfterItsTapKeepsTheDisplayedFifteenMinuteProgramme() {
        idleState()
        val history = graph.repository.history.value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        try {
            waitUntil { entered.isCompleted }
            compose.onNode(hasTestTag("quick-mode-BOOST") and hasText("Turbo + Heat 5m", substring = true) and hasClickAction())
                .performScrollTo().performClick()
            waitUntil { graph.actions.busy.value.queuedCount > 0 }
            assertTrue("The queued tap must not write before owner admission", writes.isEmpty())
            assertTrue(outcomes.isEmpty())
            runBlocking { graph.repository.updateSettings { it.copy(boostMinutes = 30) } }
            release.complete(Unit)
            waitUntil { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }

            assertEquals(OutcomeKind.CONFIRMED, outcomes.single().kind)
            assertEquals(CommandOrigin.APP, outcomes.single().origin)
            val slot = graph.repository.settings.value.workingSlot
            val programme = requireNotNull(demo.slots[slot])
            assertEquals("The actual stored programme must retain the duration visible at the tap",
                listOf(10, 5), programme.steps.map { it.durationMinutes })
            assertEquals(listOf(Mode.TURBO, Mode.HEAT), programme.steps.map { it.mode })
            assertEquals(1, writes.count { it.contentEquals(Codec.startProgram(slot)) })
            assertEquals("The later setting remains available for the next tap", 30,
                graph.repository.settings.value.boostMinutes)
            assertNull(graph.repository.activeNight.value)
            assertEquals(history, graph.repository.history.value)
        } finally {
            release.complete(Unit)
            runBlocking { holder.cancelAndJoin() }
        }
    }

    @Test fun anUnsupportedImportedIdleWarmPresetIsRefusedBeforeAnyBedJetWrite() {
        idleState()
        val physical = demo.status.value
        val history = graph.repository.history.value
        val generation = graph.repository.runtime.value.generation
        compose.onNodeWithTag("quick-mode-TURBO").performScrollTo().performClick()
        waitUntil { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
        assertTrue("An unsupported imported saved Warm action must not reach the transport", writes.isEmpty())
        assertEquals(OutcomeKind.REFUSED, outcomes.single().kind)
        assertEquals(compose.activity.getString(R.string.runtime_temporary_invalid), outcomes.single().message)
        assertEquals(generation, graph.repository.runtime.value.generation)
        assertEquals(imported, graph.repository.settings.value.extendWarm)
        assertEquals(physical, demo.status.value)
        assertNull(graph.repository.activeNight.value)
        assertEquals(history, graph.repository.history.value)
    }
}

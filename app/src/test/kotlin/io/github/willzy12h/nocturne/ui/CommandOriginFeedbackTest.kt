package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandOriginFeedbackTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app: App = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private lateinit var model: AppViewModel

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { app.graph.now() })
        val isolated = graphs.fresh { Graph(app, demo) }
        ReflectionHelpers.setField(app, "graph", isolated)
        model = AppViewModel(app)
        models.put("origin-feedback", model)
    }

    @After fun finish() {
        models.clear()
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var lastTick = System.nanoTime()
        var remainder = 0L
        while (!check() && System.nanoTime() < deadline) {
            val tick = System.nanoTime()
            val elapsed = tick - lastTick + remainder
            lastTick = tick
            remainder = elapsed % 1_000_000
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsed / 1_000_000))
            Thread.yield()
        }
        assertTrue("Timed out driving the app command", check())
    }

    @Test fun settingsSaveStaysQuietWhenItsWordingChanges() = runBlocking {
        val saved = app.graph.actions.updateSettings { it.copy(fahrenheit = !it.fahrenheit) }
        val seen = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.messages.collect { seen += it }
        }
        try {
            model.report(saved.copy(message = "Settings updated."))
            yield()
            assertTrue("A settings save remains quiet independently of its wording", seen.isEmpty())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun appOutcomeWithSettingsSaveWordingIsStillShown() = runBlocking {
        val message = app.getString(R.string.runtime_settings_saved)
        val seen = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.messages.collect { seen += it }
        }
        try {
            model.report(CommandOutcome(OutcomeKind.LOCAL, message))
            yield()
            assertEquals("Wording alone does not identify a settings save", listOf(message), seen)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun aSettingsFailureIsShownEvenWithSettingsSaveWording() = runBlocking {
        val message = app.getString(R.string.runtime_settings_saved)
        val failure = app.graph.actions.updateSettings { throw IllegalStateException(message) }
        assertEquals(OutcomeKind.FAILED, failure.kind)
        assertEquals(CommandOrigin.SETTINGS, failure.origin)
        val seen = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.messages.collect { seen += it }
        }
        try {
            model.report(failure)
            yield()
            assertEquals(listOf(message), seen)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun targetAndDemoChangesKeepTheirVisibleAppFeedback() = runBlocking {
        val outcomes = listOf(
            app.graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:FF"),
            app.graph.actions.setDemoMode(true),
        )
        outcomes.forEach {
            assertEquals(OutcomeKind.LOCAL, it.kind)
            assertEquals(CommandOrigin.APP, it.origin)
        }
        val seen = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.messages.collect { seen += it }
        }
        try {
            outcomes.forEach(model::report)
            yield()
            assertEquals(outcomes.map { it.message }, seen)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun inAppAlarmRetryKeepsAppOrigin() = runBlocking {
        app.graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        app.graph.repository.updateSettings { it.copy(demoMode = true) }
        app.graph.actions.confirmMs = 1
        assertEquals(OutcomeKind.CONFIRMED,
            app.graph.actions.execute(app.graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val emitted = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            app.graph.actions.outcomes.first()
        }
        try {
            model.run(NightActions.Command.RETIME)
            until { emitted.isCompleted && app.graph.actions.busy.value.runningLabel == null }
            assertEquals(CommandOrigin.APP, emitted.await().origin)
        } finally {
            emitted.cancelAndJoin()
        }
    }
}

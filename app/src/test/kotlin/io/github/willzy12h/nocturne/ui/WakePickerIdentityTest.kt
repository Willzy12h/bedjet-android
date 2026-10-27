package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
import android.content.DialogInterface
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeSource
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** A native wake picker belongs to the night shown when it opened, including an idle screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WakePickerIdentityTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val graphs = GraphLifecycle()
    private val original = Presets.summerCool.copy(id = "picker-original", name = "Original night",
        wakeSource = WakeSource(useAlarm = false, defaultWakeMinute = 7 * 60))
    private val replacement = original.copy(id = "picker-replacement", name = "Replacement night",
        wakeSource = WakeSource(useAlarm = false, defaultWakeMinute = 8 * 60))
    private val writes = CopyOnWriteArrayList<ByteArray>()
    private val messages = CopyOnWriteArrayList<String>()
    private lateinit var graph: Graph
    private val model get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            val demo = DemoBedJet(now = { graph.now() })
            val observed = object : BedJetLink by demo {
                override suspend fun send(command: ByteArray) { writes += command.copyOf(); demo.send(command) }
                override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            }
            graph = graphs.fresh { Graph(app, observed) }
            graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
            ReflectionHelpers.setField(app, "graph", graph)
            runBlocking {
                graph.repository.saveProfile(original)
                graph.repository.saveProfile(replacement)
                // Only the BLE boundary is simulated; the screen, owner, repository and programmes are real.
                graph.repository.updateSettings {
                    it.copy(demoMode = true, themeMode = ThemeMode.DARK, tonightOverride = null)
                        .chooseTonight(original.id, graph.now())
                }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(fixture).around(compose)

    @After fun closePicker() {
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { dialog ->
            compose.runOnUiThread { dialog.dismiss() }
        }
    }

    private fun collectFeedback() = compose.runOnUiThread {
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { model.messages.collect { messages += it } }
    }

    private fun start(profile: Profile): ActiveNight {
        compose.runOnUiThread { runBlocking {
            val request = graph.actions.capture(Command.START_NIGHT, profile.id, requireIdentity = true)
                .copy(replacementConfirmed = graph.repository.activeNight.value != null)
            val result = graph.actions.execute(request)
            assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        } }
        compose.waitForIdle()
        return requireNotNull(graph.repository.activeNight.value).also { assertEquals(profile.id, it.profileId) }
    }

    private fun openPicker(): TimePickerDialog {
        compose.onNodeWithText(compose.activity.getString(R.string.tonight_set_wake)).performScrollTo().performClick()
        return (ShadowDialog.getLatestDialog() as TimePickerDialog).also {
            assertTrue("The actual Tonight wake picker must be showing", it.isShowing)
            compose.runOnUiThread { it.updateTime(7, 30) }
        }
    }

    private fun approve(dialog: TimePickerDialog) {
        compose.runOnUiThread { dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            messages.isNotEmpty() && !model.busy.value && graph.actions.busy.value.runningLabel == null
        }
        compose.waitForIdle()
    }

    private fun rejectAfterNightChange(startedBeforeOpening: Boolean) {
        collectFeedback()
        if (startedBeforeOpening) start(original)
        else assertNull(graph.repository.activeNight.value)
        val dialog = openPicker()
        val changed = start(replacement)
        assertEquals("2026-10-27T08:00Z", changed.wakeAt)
        val settings = graph.repository.settings.value
        val history = graph.repository.history.value
        val runtime = graph.repository.runtime.value
        writes.clear()
        messages.clear()

        approve(dialog)

        assertEquals("A picker opened for another night must not send a rewrite to the BedJet", 0, writes.size)
        assertEquals("The replacement night must retain its confirmed programme and wake", changed, graph.repository.activeNight.value)
        assertEquals(settings, graph.repository.settings.value)
        assertEquals(history, graph.repository.history.value)
        assertEquals(runtime, graph.repository.runtime.value)
        assertEquals(listOf(compose.activity.getString(R.string.runtime_wake_night_changed)), messages.toList())
    }

    @Test fun aPickerOpenedForAnEarlierNightCannotRetimeItsReplacement() = rejectAfterNightChange(true)

    @Test fun aPickerOpenedWhileIdleCannotAdoptANightThatStartsBeforeApproval() = rejectAfterNightChange(false)

    @Test fun anUnchangedNightStillAppliesTheExplicitPickerChoice() {
        collectFeedback()
        val running = start(original)
        val dialog = openPicker()
        writes.clear()
        messages.clear()

        approve(dialog)

        assertTrue("An unchanged-night approval still reaches the BedJet", writes.isNotEmpty())
        val applied = requireNotNull(graph.repository.activeNight.value)
        assertEquals(running.recordId, applied.recordId)
        assertEquals(running.revision + 1, applied.revision)
        assertEquals("2026-10-27T07:30Z", applied.wakeAt)
        assertEquals("2026-10-27T07:30Z", graph.repository.settings.value.tonightOverride?.wakeAt)
    }
}

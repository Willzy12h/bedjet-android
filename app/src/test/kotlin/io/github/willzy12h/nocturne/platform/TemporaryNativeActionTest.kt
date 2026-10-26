package io.github.willzy12h.nocturne.platform

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.widget.SeekBar
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemporaryNativeActionTest : PlatformGraphFixture() {
    private lateinit var app: App
    private lateinit var graph: Graph
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()
    private val originalPreset = ActionPreset(Mode.DRY, 60, 25, 15)

    @Before fun startNight() = runBlocking {
        app = RuntimeEnvironment.getApplication() as App
        graph = fresh { Graph(app) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true, extendWarm = originalPreset) }
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.collect { outcomes += it } }
        Unit
    }

    @Test fun widgetOffersTemporaryAndEndWithoutLosingCancelOrOpenTonight() {
        val request = graph.actions.capture(NightActions.Command.WARM, requireIdentity = true)
        val activity = Robolectric.buildActivity(PlatformActionActivity::class.java, PlatformActionActivity.intent(app, request)).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertChoices(dialog)
            assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
            assertEquals("Open Tonight", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
            val before = graph.repository.activeNight.value
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(before, graph.repository.activeNight.value)
            assertTrue(outcomes.isEmpty())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun shortcutOffersTemporaryAndEndWithoutLosingCancel() {
        val intent = Intent(app, ShortcutActivity::class.java).putExtra("command", NightActions.Command.TURBO.name)
        val activity = Robolectric.buildActivity(ShortcutActivity::class.java, intent).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertChoices(dialog)
            assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
            assertEquals(10, dialog.window!!.decorView.findViewWithTag<SeekBar>("temporary-duration").max)
            dialog.cancel()
            assertTrue(outcomes.isEmpty())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun widgetTemporaryKeepsThePresetCapturedBeforeSettingsChange() {
        val before = graph.repository.activeNight.value!!
        val activity = Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(app, graph.actions.capture(NightActions.Command.WARM, requireIdentity = true))).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertChoices(dialog)
            runBlocking { graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.COOL, 44, 40, 30)) } }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
            assertEquals(OutcomeKind.CONFIRMED, outcomes.single().kind)
            assertEquals(CommandOrigin.WIDGET, outcomes.single().origin)
            val current = graph.repository.activeNight.value!!
            assertEquals(before.recordId, current.recordId)
            assertEquals(before.wakeAt, current.wakeAt)
            assertEquals(15, current.temporary!!.steps.sumOf { it.minutes })
            assertTrue(current.temporary!!.steps.all { it.mode == Mode.DRY && it.temperature == 60 && it.fanPercent == 25 })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun widgetEndKeepsTheReviewedPresetAndItsOriginalNightIdentity() {
        val activity = Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(app, graph.actions.capture(NightActions.Command.WARM, requireIdentity = true))).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            runBlocking { graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.COOL, 44, 40, 30)) } }
            dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
            assertEquals(OutcomeKind.CONFIRMED, outcomes.single().kind)
            assertEquals(CommandOrigin.WIDGET, outcomes.single().origin)
            assertNull(graph.repository.activeNight.value)
            assertEquals(Mode.DRY, graph.link.status.value!!.mode)
            assertEquals(15 * 60, graph.link.status.value!!.remainingSeconds)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun widgetEndCannotReplaceANewerNightRevision() {
        val activity = Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(app, graph.actions.capture(NightActions.Command.WARM, requireIdentity = true))).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val newer = graph.repository.activeNight.value!!.let { it.copy(revision = it.revision + 1) }
            runBlocking { graph.repository.setActiveNight(newer) }
            dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until { outcomes.isNotEmpty() }
            assertEquals(OutcomeKind.STALE, outcomes.single().kind)
            assertEquals(newer, graph.repository.activeNight.value)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun shortcutCarriesTheCapturedTemporaryPresetAcrossTheServiceIntent() {
        val before = graph.repository.activeNight.value!!
        val activity = Robolectric.buildActivity(ShortcutActivity::class.java,
            Intent(app, ShortcutActivity::class.java).putExtra("command", NightActions.Command.WARM.name)).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            runBlocking { graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.COOL, 44, 40, 30)) } }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            var delivered: Intent? = null
            until { delivered = delivered ?: shadowOf(app).nextStartedService; delivered != null }
            val service = Robolectric.buildService(BedJetService::class.java).create()
            try {
                service.get().onStartCommand(delivered, 0, 1)
                until { outcomes.isNotEmpty() && shadowOf(service.get()).isStoppedBySelf }
                assertEquals(OutcomeKind.CONFIRMED, outcomes.single().kind)
                assertEquals(CommandOrigin.SHORTCUT, outcomes.single().origin)
                val current = graph.repository.activeNight.value!!
                assertEquals(before.wakeAt, current.wakeAt)
                assertEquals(15, current.temporary!!.steps.sumOf { it.minutes })
                assertTrue(current.temporary!!.steps.all { it.mode == Mode.DRY })
            } finally { service.destroy() }
        } finally { activity.pause().stop().destroy() }
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
        assertTrue("Timed out driving the actual native action", check())
    }

    private fun assertChoices(dialog: AlertDialog) {
        val choices = dialog.listView
        assertNotNull("Running quick actions need an explicit temporary/end choice before sending", choices)
        assertEquals(listOf("Temporarily, then resume", "End tonight"),
            (0 until choices.adapter.count).map { choices.adapter.getItem(it).toString() })
        assertEquals("Eligible actions initially choose temporary", 0, choices.checkedItemPosition)
    }
}

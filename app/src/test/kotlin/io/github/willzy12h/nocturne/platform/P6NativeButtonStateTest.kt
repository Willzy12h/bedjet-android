package io.github.willzy12h.nocturne.platform

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P6NativeButtonStateTest : PlatformGraphFixture() {
    private lateinit var app: App
    private lateinit var graph: Graph

    @Before fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication() as App
        val link = DemoBedJet(now = { graph.now() })
        graph = fresh { Graph(app, link) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 20
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
        graph.repository.setActiveNight(graph.repository.activeNight.value!!.copy(confidence = Confidence.UNKNOWN))
    }

    @Test fun temporaryChoiceUpdatesDisabledColourAndRestoresEnabledColour() {
        val activity = Robolectric.buildActivity(ShortcutActivity::class.java,
            Intent(app, ShortcutActivity::class.java).putExtra("command", Command.WARM.name)).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            assertTrue(button.isEnabled)
            val enabled = button.currentTextColor
            dialog.listView.performItemClick(dialog.listView.getChildAt(0), 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(button.isEnabled)
            assertNotEquals("Disabled action must be visibly distinct", enabled, button.currentTextColor)
            dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(button.isEnabled)
            assertEquals(enabled, button.currentTextColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertNull(shadowOf(app).nextStartedService)
        } finally { activity.pause().stop().destroy() }
    }
}

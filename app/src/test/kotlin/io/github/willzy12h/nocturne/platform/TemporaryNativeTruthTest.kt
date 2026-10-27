package io.github.willzy12h.nocturne.platform

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PendingChange
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

/** Real native entries must not state that an uncertain/manual programme is running. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemporaryNativeTruthTest : PlatformGraphFixture() {
    private lateinit var app: App
    private lateinit var graph: Graph

    @Before fun seed() = runBlocking {
        app = RuntimeEnvironment.getApplication() as App
        // An injected link is not driven by Graph's one-second demo sensor simulation.
        val link = DemoBedJet(now = { graph.now() })
        graph = fresh { Graph(app, link) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 20
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
        Unit
    }

    private fun uncertainty(confidence: Confidence) = runBlocking {
        val night = graph.repository.activeNight.value!!
        val pending = if (confidence == Confidence.PENDING) PendingChange("native-truth-pending", ChangeKind.RETIME,
            graph.now().plusHours(7).toString(), graph.now().toString(), graph.now().plusMinutes(1).toString(),
            night.identity) else null
        graph.repository.setActiveNight(night.copy(confidence = confidence, pending = pending))
    }

    private fun text(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) repeat(view.childCount) { addAll(text(view.getChildAt(it))) }
    }

    private fun checkDialog(shortcut: Boolean, becomeUnknownWhileOpen: Boolean = false) {
        val identity = graph.repository.activeNight.value!!.identity
        val physical = graph.link.status.value
        val activity = if (shortcut) Robolectric.buildActivity(ShortcutActivity::class.java,
            Intent(app, ShortcutActivity::class.java).putExtra("command", Command.WARM.name)).setup()
        else Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(app, graph.actions.capture(Command.WARM, requireIdentity = true))).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog.listView)
            assertEquals(2, dialog.listView.adapter.count)
            if (becomeUnknownWhileOpen) {
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                uncertainty(Confidence.UNKNOWN)
                shadowOf(Looper.getMainLooper()).idle()
            } else {
                assertEquals(1, dialog.listView.checkedItemPosition)
                assertTrue("Ending remains available for a tracked uncertain/manual night",
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                dialog.listView.performItemClick(dialog.listView.getChildAt(0), 0, 0)
                assertFalse("The unconfirmed Temporary programme must remain unavailable",
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            }
            val words = text(dialog.window!!.decorView).joinToString("\n")
            assertFalse(words, words.contains("is running", ignoreCase = true))
            assertFalse(words, words.contains("waking you at", ignoreCase = true))
            val beforeCancel = graph.repository.activeNight.value
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(identity, graph.repository.activeNight.value?.identity)
            assertEquals(beforeCancel, graph.repository.activeNight.value)
            assertEquals(physical, graph.link.status.value)
            assertNull("Cancellation submits no service request", shadowOf(app).nextStartedService)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun unknownShortcutChoiceDoesNotClaimARunningWake() {
        uncertainty(Confidence.UNKNOWN)
        checkDialog(shortcut = true)
    }
    @Test fun pendingWidgetChoiceDoesNotClaimARunningWake() {
        uncertainty(Confidence.PENDING)
        checkDialog(shortcut = false)
    }
    @Test fun manualShortcutChoiceDoesNotClaimARunningSavedProgramme() {
        runBlocking {
            graph.actions.execute(graph.actions.capture(Command.SNOOZE_WARM))
            graph.actions.execute(graph.actions.capture(Command.KEEP_TRACKING))
        }
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        checkDialog(shortcut = true)
    }
    @Test fun anOpenWidgetChoiceDoesNotRetainAConfirmedClaimAfterUncertainty() =
        checkDialog(shortcut = false, becomeUnknownWhileOpen = true)
}

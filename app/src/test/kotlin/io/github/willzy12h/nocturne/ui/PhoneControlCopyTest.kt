package io.github.willzy12h.nocturne.ui

import android.content.Intent
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.boostDescription
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneControlCopyTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun everyConfiguredBoostDescriptionNamesItsActualTurboAndHeatSegments() {
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            val steps = PlanCompiler.phaseSteps(Phase(Mode.TURBO, 86, 100, minutes), ProfileLimits(), TurboChain.TURBO_THEN_HEAT)
            assertEquals(Mode.TURBO, steps.first().mode)
            assertEquals(10, steps.first().durationMinutes)
            assertEquals(minutes, steps.sumOf { it.durationMinutes })
            assertTrue(steps.drop(1).all { it.mode == Mode.HEAT })
            assertEquals(if (minutes == 10) "Turbo heat for 10 minutes"
                else "Turbo heat for 10 minutes, then Heat for ${minutes - 10} minutes", boostDescription(context, minutes))
        }
    }

    @Test fun recoveryAndNotificationLabelsSayResumeNight() {
        for (id in listOf(R.string.recovery_resume_programme, R.string.recovery_resume_previous,
            R.string.runtime_command_resume, R.string.runtime_resume_title)) {
            assertEquals("Resume night", context.getString(id))
        }
    }

    @Test fun endingAndItsConfirmationSayStopNight() {
        for (id in listOf(R.string.tonight_end_night, R.string.runtime_command_end_night,
            R.string.runtime_end, R.string.guard_confirm)) {
            assertEquals("Stop night", context.getString(id))
        }
        assertTrue(context.getString(R.string.settings_quiet_help).contains("Stop night"))
    }

    // The clarified P1 requirement supersedes the earlier proposed "Turbo boost" static label.
    // A static entry cannot claim Turbo for the whole configurable run; its actual preview supplies the segments.
    @Test fun nonDurationBoostEntriesUseATruthfulGenericName() {
        val android = "http://schemas.android.com/apk/res/android"
        var boostShortcutFound = false
        context.resources.getXml(R.xml.shortcuts).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "shortcut" &&
                    parser.getAttributeValue(android, "shortcutId") == "boost") {
                    boostShortcutFound = true
                    val label = parser.getAttributeResourceValue(android, "shortcutShortLabel", 0)
                    assertEquals("Boost", context.getString(label))
                }
                parser.next()
            }
        }
        assertTrue("The declared Boost shortcut must be checked", boostShortcutFound)
        assertEquals("Boost", context.getString(R.string.runtime_command_boost))
        assertEquals("Boost", context.getString(R.string.feature_boost))
    }

    @Test fun actualShortcutConfirmationPreviewsEveryConfiguredSequenceBeforeSending() = runBlocking {
        val app = context as App
        val graph = graphs.fresh { Graph(app) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            graph.repository.updateSettings { it.copy(boostMinutes = minutes) }
            val controller = Robolectric.buildActivity(ShortcutActivity::class.java,
                Intent(app, ShortcutActivity::class.java).putExtra("command", "BOOST")).setup()
            try {
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                val expected = if (minutes == 10) "Turbo heat for 10 minutes"
                    else "Turbo heat for 10 minutes, then Heat for ${minutes - 10} minutes"
                assertTrue("The actual shortcut preview must state $expected; was ${shadowOf(dialog).message}",
                    shadowOf(dialog).message.toString().contains(expected))
                assertNull("Preview must not submit a hardware command", shadowOf(app).nextStartedService)
                dialog.cancel()
                assertNull("Cancelling the preview must not submit a command", shadowOf(app).nextStartedService)
                assertNull(graph.repository.activeNight.value)
            } finally { controller.pause().stop().destroy() }
        }
    }
}

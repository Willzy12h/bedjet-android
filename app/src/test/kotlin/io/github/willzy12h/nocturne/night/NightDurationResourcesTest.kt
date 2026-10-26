package io.github.willzy12h.nocturne.night

import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.*
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.Preparation
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.ui.AppViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightDurationResourcesTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private val now = ZonedDateTime.parse("2026-10-26T22:30:00Z")
    private lateinit var graph: Graph
    private var writes = 0

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet { now }
        graph = graphs.fresh { Graph(app, object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(now.toInstant(), now.zone)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private fun withModel(check: (AppViewModel) -> Unit) {
        val store = ViewModelStore()
        try {
            val model = AppViewModel(app)
            store.put("duration", model)
            check(model)
        } finally { store.clear() }
    }

    @Test fun startPreviewAndItsFailureUseTheWindowResource() = runBlocking {
        graph.repository.updateSettings { it.setWakeOverride(now.plusMinutes(30), now) }
        withModel { model ->
            assertEquals(app.getString(R.string.night_duration_outside_window), model.startPreview(Presets.springAutumn).refusalReason)
            assertEquals(app.getString(R.string.night_duration_outside_window),
                model.previewPlan(Presets.springAutumn, startNow = true).exceptionOrNull()?.message)
        }
    }

    @Test fun scheduledCompilerFailureUsesTheMaximumResource() {
        val profile = Presets.springAutumn.copy(bedtimeMinute = 22 * 60 + 30,
            wakeSource = WakeSource(useAlarm = false, defaultWakeMinute = 18 * 60 + 30))
        withModel { model ->
            assertEquals(app.getString(R.string.night_duration_too_long), model.previewPlan(profile).exceptionOrNull()?.message)
        }
    }

    @Test fun startRefusalUsesTheWindowResourceBeforeAnyWrite() = runBlocking {
        graph.repository.updateSettings { it.setWakeOverride(now.plusMinutes(30), now) }
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(app.getString(R.string.night_duration_outside_window), result.message)
        assertEquals(0, writes)
    }

    @Test fun runningWakeOverrideRefusalUsesTheWindowResourceBeforeAnyWrite() = runBlocking {
        val night = (graph.runner().prepareStart(Presets.springAutumn, now.plusHours(8), 2, DeviceId.DEMO,
            null, null) as Preparation.Ready).change.intended
        graph.repository.setActiveNight(night)
        val result = graph.actions.setWakeOverride(now.plusMinutes(30), night.identity)
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(app.getString(R.string.night_duration_outside_window), result.message)
        assertEquals(0, writes)
    }

    @Test fun ownerExceptionMapsTheTypedCauseInsteadOfItsDiagnosticCode() = runBlocking {
        val result = graph.actions.updateSettings { throw NightDurationException(NightDurationProblem.TOO_LONG) }
        assertEquals(OutcomeKind.FAILED, result.kind)
        assertEquals(app.getString(R.string.night_duration_too_long), result.message)
        assertEquals(0, writes)
    }

    @Test fun pinPreviewUsesTheTypedResourceDespiteDifferentDiagnosticText() {
        val preview = Preview(null, WakeChoice(now.plusMinutes(30), false), "diagnostic-only",
            durationProblem = NightDurationProblem.OUTSIDE_WINDOW)
        assertEquals(app.getString(R.string.night_duration_outside_window), preview.refusalText(app))
    }

    @Test fun reminderPreviewExplainsTheDurationInsteadOfShowingItsDiagnosticCode() = runBlocking {
        graph.repository.updateSettings { it.setWakeOverride(now.plusMinutes(30), now) }
        val intent = io.github.willzy12h.nocturne.platform.PlatformActionActivity.intent(app,
            graph.actions.capture(NightActions.Command.START_NIGHT, requireIdentity = true))
        val controller = org.robolectric.Robolectric.buildActivity(
            io.github.willzy12h.nocturne.platform.PlatformActionActivity::class.java, intent).setup()
        try {
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(app.getString(R.string.night_duration_outside_window),
                org.robolectric.Shadows.shadowOf(dialog).message.toString())
            val start = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            assertTrue("An invalid preview cannot start a programme", start == null || start.visibility != android.view.View.VISIBLE)
            assertEquals(0, writes)
        } finally { controller.pause().stop().destroy() }
    }
}

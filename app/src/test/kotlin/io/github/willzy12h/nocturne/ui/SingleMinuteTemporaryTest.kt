package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SingleMinuteTemporaryTest {
    private val graphs = GraphLifecycle()
    private val models = androidx.lifecycle.ViewModelStore()
    @org.junit.After fun closeModels() { models.clear() }
    private val compose = createComposeRule()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)

    @Test fun aOneMinuteReportedMaximumKeepsTheTrackedChoiceUsable() {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = graphs.fresh { io.github.willzy12h.nocturne.Graph(app) }
        org.robolectric.util.ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = true) }
            assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
        }
        val original = graph.repository.activeNight.value
        val model = AppViewModel(app).also { models.put("duration", it) }
        val reviewed = captureDuration(graph, graph.link.latestSourcedStatus()!!.status.copy(maxRuntimeMinutes = 1), 1)
        compose.setContent { MaterialTheme {
            val guard = rememberNightGuard(model)
            Button(onClick = { guard.confirm("Run for one minute", reviewed.temporary()) {} }) { Text("Review duration") }
        } }
        compose.onNodeWithText("Review duration").performClick()
        compose.onNodeWithText(app.getString(R.string.temporary_confirm)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(app.getString(R.string.guard_keep)).performClick()
        assertEquals(original, graph.repository.activeNight.value)
    }
}

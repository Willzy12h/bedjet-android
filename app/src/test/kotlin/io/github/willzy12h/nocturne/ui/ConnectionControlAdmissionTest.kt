package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.ui.features.PhoneHandoverMenuItems
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ConnectionControlAdmissionTest {
    private val graphs = GraphLifecycle()
    private val compose = createComposeRule()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)
    private val app get() = RuntimeEnvironment.getApplication() as App

    private fun releaseClick(outcome: AtomicReference<CommandOutcome> = AtomicReference()): () -> Boolean {
        runBlocking { app.graph.repository.updateSettings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:01") } }
        compose.setContent { MaterialTheme {
            PhoneHandoverMenuItems(app.graph, onOutcome = outcome::set, asSettings = true) {}
        } }
        return requireNotNull(compose.onNodeWithText(app.getString(R.string.feature_release))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }

    @Test fun aTappedConnectionControlExcludesImportBeforeItsUiCoroutineRuns() {
        val click = releaseClick()
        var allowed = false
        compose.runOnUiThread {
            click()
            val lease = app.graph.repository.operationAdmission.tryImport()
            allowed = lease != null
            lease?.close()
        }
        assertFalse("A queued connection control must be visible to import admission", allowed)
    }

    @Test fun aTapDuringImportCannotRunAfterThatImportFinishes() {
        val outcome = AtomicReference<CommandOutcome>()
        val click = releaseClick(outcome)
        val importing = requireNotNull(app.graph.repository.operationAdmission.tryImport())
        compose.runOnUiThread {
            click()
            importing.close()
        }
        compose.waitUntil(5_000) { outcome.get() != null }
        assertEquals(OutcomeKind.REFUSED, outcome.get().kind)
        assertFalse(app.graph.sessions.released.value)
    }
}

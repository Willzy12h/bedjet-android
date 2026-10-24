package io.github.willzy12h.nocturne.ui

import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** D6: the delivery source, rather than the translated message, decides which local saves stay quiet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutcomeOriginTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app: App = RuntimeEnvironment.getApplication() as App

    private fun collectMessages(check: suspend (AppViewModel, List<String>) -> Unit) = runBlocking {
        val model = AppViewModel(app)
        val store = ViewModelStore().apply { put("model", model) }
        val received = mutableListOf<String>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) { model.messages.collect { received += it } }
        try { check(model, received) }
        finally { collecting.cancelAndJoin(); store.clear() }
    }

    @Test fun aRoutineSettingsSaveStaysQuietWhenItsWordingChanges() = collectMessages { model, received ->
        val saved = app.graph.actions.updateSettings { it.copy(simpleView = !it.simpleView) }
        assertEquals(OutcomeKind.LOCAL, saved.kind)
        model.report(saved.copy(message = "Preferences updated in different words."))
        yield()
        assertTrue("A routine settings save must stay quiet independently of its message", received.isEmpty())
    }

    @Test fun anAppOutcomeUsingTheSettingsSentenceIsStillShown() = collectMessages { model, received ->
        val acknowledgement = app.graph.actions.acknowledgeSlotWrite()
        assertEquals(CommandOrigin.APP, acknowledgement.origin)
        val message = app.getString(R.string.runtime_settings_saved)
        model.report(acknowledgement.copy(message = message))
        yield()
        assertEquals(listOf(message), received)
    }

    @Test fun aSettingsFailureWithTheSuccessSentenceIsStillShown() = collectMessages { model, received ->
        val message = app.getString(R.string.runtime_settings_saved)
        val failed = app.graph.actions.updateSettings { throw IOException(message) }
        assertEquals(OutcomeKind.FAILED, failed.kind)
        model.report(failed)
        yield()
        assertEquals(listOf(message), received)
    }

    @Test fun everyNonLocalSettingsOutcomeRemainsVisible() = collectMessages { model, received ->
        val saved = app.graph.actions.updateSettings { it.copy(simpleView = !it.simpleView) }
        assertEquals(CommandOrigin.SETTINGS, saved.origin)
        val visible = listOf(OutcomeKind.CONFIRMED, OutcomeKind.ACCEPTED, OutcomeKind.REFUSED, OutcomeKind.STALE,
            OutcomeKind.FAILED, OutcomeKind.UNKNOWN, OutcomeKind.CONFIRMATION_REQUIRED)
        for (kind in visible) {
            model.report(saved.copy(kind = kind))
            yield()
        }
        assertEquals(List(visible.size) { saved.message }, received)
    }

    @Test fun targetAndDemoChangesKeepTheirVisibleConfirmations() = collectMessages { model, received ->
        val target = app.graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:FF")
        val demo = app.graph.actions.setDemoMode(true)
        for (outcome in listOf(target, demo)) {
            assertEquals(OutcomeKind.LOCAL, outcome.kind)
            assertEquals(CommandOrigin.APP, outcome.origin)
            model.report(outcome)
            yield()
        }
        assertEquals(listOf(target.message, demo.message), received)
    }

    @Test fun aSettingsHardwareCheckKeepsItsVisibleConfirmation() = collectMessages { model, received ->
        app.graph.repository.updateSettings { it.copy(demoMode = true) }
        val outcome = app.graph.actions.setupFanCheck()
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(CommandOrigin.APP, outcome.origin)
        model.report(outcome)
        yield()
        assertEquals(listOf(outcome.message), received)
    }

    @Test fun theSameOutcomeIsOnlyShownOnce() = collectMessages { model, received ->
        val outcome = app.graph.actions.acknowledgeSlotWrite()
        model.report(outcome)
        model.report(outcome)
        yield()
        assertEquals(listOf(outcome.message), received)
    }
}

package io.github.willzy12h.nocturne.ui

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Reset to default in the editor, Restore built-in profiles, Reset controls, and the fresh-install restore offer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class KeepSettingsUiTest {
    private val graphs = GraphLifecycle()
    private val compose = createComposeRule()
    private val models = ViewModelStore()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)
    @After fun clear() = models.clear()
    private val app get() = RuntimeEnvironment.getApplication() as App
    private fun text(id: Int) = (app as Application).getString(id)
    /** Saves finish on the writer and resume on the main looper, which the wait must keep running. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        condition()
    }

    private fun model(): AppViewModel {
        val graph = graph()
        runBlocking { graph.repository.updateSettings { it.copy(themeMode = ThemeMode.DARK, cooldownOfferAnswered = true) } }
        return AppViewModel(app).also { models.put("keep", it) }
    }

    /** A new graph over the same files, as after the app restarts. */
    private fun graph(): Graph {
        val link = object : BedJetLink {
            override val state = MutableStateFlow(LinkState.READY)
            override val status = MutableStateFlow<DeviceStatus?>(null)
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun send(command: ByteArray) = Unit
            override suspend fun query(request: Int) = ByteArray(0)
        }
        val graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-11-17T22:30:00Z"), ZoneOffset.UTC)
        return graph
    }

    @Test fun profilesOffersRestoreOnlyOnceABuiltInProfileHasChanged() {
        val model = model()
        compose.setContent {
            val settings by model.settings.collectAsState()
            NocturneTheme(settings, model.now()) { ProfilesScreen(model, Modifier, onEdit = {}) }
        }
        compose.onNodeWithText(text(R.string.profiles_restore_builtin)).assertDoesNotExist()
        val summer = model.profiles.value.first()
        model.saveProfile(summer.copy(name = "Mine", days = setOf(6)))
        waitFor { model.profiles.value.first().name == "Mine" }
        compose.onNodeWithText(text(R.string.profiles_restore_builtin)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.profiles_restore_builtin_confirm)).performClick()
        waitFor { model.profiles.value.first().name == summer.name }
        assertEquals(setOf(6), model.profiles.value.first().days)
        compose.onNodeWithText(text(R.string.profiles_restore_builtin)).assertDoesNotExist()
    }

    @Test fun editorResetsOnlyTheDraftOfAChangedBuiltInProfile() {
        val model = model()
        val summer = model.profiles.value.first()
        val changed = summer.copy(name = "Mine", days = setOf(6))
        var draft = changed
        compose.setContent {
            val settings by model.settings.collectAsState()
            NocturneTheme(settings, model.now()) { ProfileEditor(model, changed, onDone = {}, onDraftChange = { draft = it }) }
        }
        compose.onNodeWithText(text(R.string.editor_builtin_reset)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.editor_builtin_reset_confirm)).performClick()
        compose.runOnIdle { assertEquals(Presets.byId(summer.id)!!.copy(days = setOf(6)), draft) }
        compose.onNodeWithText(text(R.string.editor_builtin_reset)).assertDoesNotExist()
        assertEquals("Nothing is saved until Save", summer, model.profiles.value.first())
    }

    @Test fun settingsResetsControlsAfterConfirming() {
        val model = model()
        model.updateSettings { it.copy(boostMinutes = 5, fahrenheit = true) }
        waitFor { model.settings.value.boostMinutes == 5 }
        compose.setContent {
            val settings by model.settings.collectAsState()
            NocturneTheme(settings, model.now()) { SettingsScreen(model, Modifier) }
        }
        compose.openSettingsPage("notifications")
        compose.onNodeWithTag("settings-reset-controls").performScrollTo().performClick()
        compose.onNodeWithTag("settings-reset-controls-confirm").performClick()
        waitFor { model.settings.value.controlsAreDefault }
        assertTrue(model.settings.value.fahrenheit)
        waitFor { model.snapshots.value.any { it.manual } }
    }

    @Test fun aFreshInstallOffersARestoreUntilDismissed() {
        val model = model()
        assertTrue(model.restoreOffer.value)
        compose.setContent {
            val settings by model.settings.collectAsState()
            NocturneTheme(settings, model.now()) { RestoreOfferCard(model) }
        }
        compose.onNodeWithTag("restore-offer-restore").assertExists()
        compose.onNodeWithTag("restore-offer-dismiss").performClick()
        compose.onNodeWithTag("restore-offer").assertDoesNotExist()
        assertFalse(AppViewModel(app).also { models.put("again", it) }.restoreOffer.value)
    }

    @Test fun theRestoreOfferSurvivesARestartAfterTheFirstLaunchSavedSettings() {
        assertTrue(model().restoreOffer.value)
        val restarted = graph()
        assertFalse("Settings were saved on the first launch", restarted.repository.startedEmpty)
        assertTrue(AppViewModel(app).also { models.put("restarted", it) }.restoreOffer.value)
    }

    @Test fun anExistingInstallIsNotOfferedARestore() {
        runBlocking { graph().repository.updateSettings { it.copy(fahrenheit = true) } }
        app.getSharedPreferences("first-run", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        graph()
        assertFalse(AppViewModel(app).also { models.put("upgraded", it) }.restoreOffer.value)
    }
}

package io.github.willzy12h.nocturne.ui

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A fresh Activity and ViewModel receive only the marshalled saved state, never retained editor state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class NavigationRestoreTest {
    private val graphs = GraphLifecycle()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(compose)
    private val app get() = RuntimeEnvironment.getApplication() as App
    private val graph get() = app.graph
    private var controller: ActivityController<MainActivity>? = null

    @Before fun setUp() {
        graph.clock = Clock.fixed(Instant.parse("2026-10-23T22:30:00Z"), ZoneOffset.UTC)
        runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK) }
            graph.repository.setActiveNight(null)
        }
    }

    @After fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    private fun launch(intent: Intent = Intent(app, MainActivity::class.java), state: Bundle? = null) {
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller = (if (state == null) activity.setup() else activity.setup(state)).windowFocusChanged(true)
        compose.waitForIdle()
    }

    private fun restore(launchIntent: Intent = Intent(app, MainActivity::class.java)) {
        compose.waitForIdle()
        val saved = Bundle()
        controller!!.pause().saveInstanceState(saved).stop().destroy()
        controller = null
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writeBundle(saved); parcel.marshall() } finally { parcel.recycle() }
        val restoredParcel = Parcel.obtain()
        val restored = try {
            restoredParcel.unmarshall(bytes, 0, bytes.size)
            restoredParcel.setDataPosition(0)
            restoredParcel.readBundle(MainActivity::class.java.classLoader)!!
        } finally { restoredParcel.recycle() }
        launch(launchIntent, restored)
    }

    private fun openTonight() {
        compose.runOnUiThread {
            // Android delivers a new intent before the activity's next onResume.
            controller!!.pause().newIntent(Intent(app, MainActivity::class.java).putExtra("openTonight", true))
                .resume().topActivityResumed(true)
        }
        // Native intent -> Compose replacement -> Android measurement are separate frame queues.
        // Drain both after the editor is replaced; a theme polling loop must not be needed to pump them.
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        compose.onNodeWithText("Start night now").assertExists()
    }

    private fun newDraft() {
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithText("New profile").performClick()
        compose.onNodeWithText("Summer Cool (summer)").performClick()
        compose.onNodeWithText("Edit profile").assertExists()
    }

    private fun renameDraft(name: String) {
        compose.onNode(hasSetTextAction() and hasText("Name", substring = true)).performTextReplacement(name)
        compose.waitForIdle()
    }

    @Test fun consumedLaunchIntentDoesNotOverrideNavigationAfterSavedStateRestore() {
        val originalLaunch = Intent(app, MainActivity::class.java).putExtra("openTonight", true)
        launch(Intent(originalLaunch))
        assertFalse(controller!!.get().intent.hasExtra("openTonight"))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Export history CSV").assertExists()

        // Android may redeliver the original launch intent after process death.
        restore(originalLaunch)

        compose.onNodeWithText("Export history CSV").assertExists()
        compose.onNodeWithText("Start night now").assertDoesNotExist()
    }

    @Test fun newIntentOverRestoredNavigationStillOpensTonight() {
        launch()
        compose.onNodeWithText("History").performClick()
        restore()
        compose.onNodeWithText("Export history CSV").assertExists()

        openTonight()

        compose.onNodeWithText("Export history CSV").assertDoesNotExist()
        assertFalse(controller!!.get().intent.hasExtra("openTonight"))
    }

    @Test fun dirtyDraftIsParkedAndResumeRestoresItsUnsavedValues() {
        launch()
        val savedProfiles = graph.repository.profiles.value
        compose.onNodeWithText("Profiles").performClick()
        compose.onAllNodesWithText("Edit")[0].performClick()
        renameDraft("Unsaved existing draft")

        openTonight()
        compose.onNodeWithText("Resume editing draft").performClick()

        compose.onNodeWithText("Edit profile").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Unsaved existing draft")).assertExists()
        assertEquals(savedProfiles, graph.repository.profiles.value)
    }

    @Test fun uneditedNewDraftIsParkedAndCanBeResumed() {
        launch()
        val originalIds = graph.repository.profiles.value.map { it.id }.toSet()
        newDraft()

        openTonight()
        compose.onNodeWithText("Resume editing draft").performClick()

        compose.onNode(hasSetTextAction() and hasText("Summer Cool")).assertExists()
        assertEquals(originalIds, graph.repository.profiles.value.map { it.id }.toSet())
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            graph.repository.profiles.value.size == originalIds.size + 1
        }
    }

    @Test fun selectedTabSurvivesParcelledSavedState() {
        launch()
        compose.onNodeWithText("History").performClick()
        restore()
        compose.onNodeWithText("Export history CSV").assertExists()
        compose.onNodeWithText("Start night now").assertDoesNotExist()
    }

    @Test fun anotherTonightIntentPreservesAnAlreadyParkedNewDraft() {
        launch()
        newDraft()
        renameDraft("Parked across two intents")
        openTonight()
        openTonight()

        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("Parked across two intents")).assertExists()
    }

    @Test fun existingProfileDraftSurvivesParcelledSavedStateWithoutSavingIt() {
        launch()
        val originals = graph.repository.profiles.value
        compose.onNodeWithText("Profiles").performClick()
        compose.onAllNodesWithText("Edit")[0].performClick()
        renameDraft("Parcel existing draft")

        restore()

        compose.onNodeWithText("Edit profile").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Parcel existing draft")).assertExists()
        assertEquals(originals, graph.repository.profiles.value)
    }

    @Test fun newProfileDraftSurvivesParcelledSavedStateAndSaveCreatesOneProfile() {
        launch()
        val originalIds = graph.repository.profiles.value.map { it.id }.toSet()
        newDraft()
        renameDraft("Parcel new draft")

        restore()

        compose.onNodeWithText("Edit profile").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Parcel new draft")).assertExists()
        assertEquals(originalIds, graph.repository.profiles.value.map { it.id }.toSet())
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            graph.repository.profiles.value.size == originalIds.size + 1
        }
        assertEquals("Parcel new draft", graph.repository.profiles.value.single { it.id !in originalIds }.name)
    }
}

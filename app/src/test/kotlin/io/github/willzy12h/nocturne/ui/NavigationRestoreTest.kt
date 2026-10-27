package io.github.willzy12h.nocturne.ui

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import android.os.Parcel
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
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
        settleIntentNavigation()
        compose.onNodeWithText("Start night now").assertExists()
    }

    private fun settleIntentNavigation() {
        // Native intent -> Compose replacement -> Android measurement are separate frame queues.
        // Drain both after the editor is replaced; a theme polling loop must not be needed to pump them.
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
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

    private fun editFirstProfile() {
        compose.onNodeWithText("Profiles").performClick()
        compose.onAllNodesWithText("Edit")[0].performClick()
        compose.onNodeWithText("Edit profile").assertExists()
    }

    private fun discardDraft() {
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.onNodeWithText("Discard changes?").assertExists()
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
    }

    private fun waitForProfileCount(count: Int) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            graph.repository.profiles.value.size == count
        }
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

    @Test fun parkedExistingDraftSurvivesSavingAnotherProfileUntilExplicitDiscard() {
        launch()
        val originals = graph.repository.profiles.value
        editFirstProfile()
        renameDraft("Parked existing edits")
        openTonight()

        newDraft()
        renameDraft("Saved separate profile")
        compose.onNodeWithText("Save").performClick()
        waitForProfileCount(originals.size + 1)

        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("Parked existing edits")).assertExists()
        assertEquals(originals.first(), graph.repository.profiles.value.first { it.id == originals.first().id })
        discardDraft()
        compose.onNodeWithText("Resume editing draft").assertDoesNotExist()
        assertEquals(originals.first(), graph.repository.profiles.value.first { it.id == originals.first().id })
    }

    @Test fun leavingDemoDiscardsItsParkedDraftBeforeReturningToRealProfiles() {
        runBlocking {
            graph.actions.setDemoMode(false)
            graph.actions.setDemoMode(true)
        }
        val originals = graph.repository.profiles.value
        launch()
        editFirstProfile()
        renameDraft("Disposable demo draft")
        openTonight()
        compose.onNodeWithText("Resume editing draft").assertExists()
        compose.onNodeWithText("Exit demo").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            !graph.repository.settings.value.demoMode
        }
        compose.onNodeWithText("Resume editing draft").assertDoesNotExist()
        assertEquals(originals, graph.repository.profiles.value)
        assertFalse(graph.repository.profiles.value.any { it.name == "Disposable demo draft" })
    }

    @Test fun aDemoSessionChangeClosesAnActiveEditorBeforeItCanSaveIntoRealProfiles() {
        runBlocking {
            graph.actions.setDemoMode(false)
            graph.actions.setDemoMode(true)
        }
        launch()
        editFirstProfile()
        renameDraft("Discard on demo exit")
        compose.runOnUiThread { runBlocking { graph.actions.setDemoMode(false) } }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Discard on demo exit")).assertDoesNotExist()
        assertFalse(graph.repository.profiles.value.any { it.name == "Discard on demo exit" })
    }

    @Test fun parkedNewDraftSurvivesDiscardingAnotherEditorAcrossSavedState() {
        launch()
        val originals = graph.repository.profiles.value
        val originalIds = originals.map { it.id }.toSet()
        newDraft()
        renameDraft("Parked new profile")
        openTonight()

        editFirstProfile()
        renameDraft("Discard separate edits")
        restore()
        compose.onNode(hasSetTextAction() and hasText("Discard separate edits")).assertExists()
        discardDraft()

        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("Parked new profile")).assertExists()
        assertEquals(originals, graph.repository.profiles.value)
        compose.onNodeWithText("Save").performClick()
        waitForProfileCount(originals.size + 1)
        assertEquals("Parked new profile", graph.repository.profiles.value.single { it.id !in originalIds }.name)
        compose.onNodeWithText("Resume editing draft").assertDoesNotExist()
    }

    @Test fun parkedNewDraftKeepsItsIdentityWhenAnotherNewProfileIsSaved() {
        launch()
        val originalIds = graph.repository.profiles.value.map { it.id }.toSet()
        newDraft()
        renameDraft("First new profile")
        openTonight()

        newDraft()
        renameDraft("Second new profile")
        compose.onNodeWithText("Save").performClick()
        waitForProfileCount(originalIds.size + 1)
        val secondProfile = graph.repository.profiles.value.single { it.id !in originalIds }
        assertEquals("Second new profile", secondProfile.name)

        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("First new profile")).assertExists()
        compose.onNodeWithText("Save").performClick()
        waitForProfileCount(originalIds.size + 2)
        assertEquals(secondProfile, graph.repository.profiles.value.single { it.id == secondProfile.id })
        assertEquals("First new profile", graph.repository.profiles.value.single {
            it.id !in originalIds && it.id != secondProfile.id
        }.name)
        compose.onNodeWithText("Resume editing draft").assertDoesNotExist()
    }

    @Test fun reopeningParkedProfileResumesItsUnsavedValuesAndSaveUpdatesOnlyThatProfile() {
        launch()
        val originals = graph.repository.profiles.value
        editFirstProfile()
        renameDraft("Resume this profile")
        openTonight()

        editFirstProfile()

        compose.onNode(hasSetTextAction() and hasText("Resume this profile")).assertExists()
        assertEquals(originals, graph.repository.profiles.value)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            graph.repository.profiles.value.first { it.id == originals.first().id }.name == "Resume this profile"
        }
        assertEquals(originals.size, graph.repository.profiles.value.size)
        assertEquals(originals.drop(1), graph.repository.profiles.value.drop(1))
        compose.onNodeWithText("Resume editing draft").assertDoesNotExist()
    }

    @Test fun twoInterruptedEditorsRemainResumableInOrderAfterParcelledSavedState() {
        launch()
        val originals = graph.repository.profiles.value
        val originalIds = originals.map { it.id }.toSet()
        editFirstProfile()
        renameDraft("First interrupted editor")
        openTonight()
        newDraft()
        renameDraft("Second interrupted editor")
        openTonight()
        openTonight()

        restore()

        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("First interrupted editor")).assertExists()
        discardDraft()
        assertEquals(originals, graph.repository.profiles.value)
        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("Second interrupted editor")).assertExists()
        compose.onNodeWithText("Save").performClick()
        waitForProfileCount(originals.size + 1)
        assertEquals("Second interrupted editor", graph.repository.profiles.value.single { it.id !in originalIds }.name)
        compose.onNodeWithText("Resume editing draft").assertDoesNotExist()
    }

    @Test fun parkedDraftSurvivesClosingAnUnchangedOtherEditor() {
        launch()
        val originals = graph.repository.profiles.value
        newDraft()
        renameDraft("Keep parked edits")
        openTonight()

        editFirstProfile()
        compose.onNodeWithContentDescription("Cancel").performClick()

        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("Keep parked edits")).assertExists()
        assertEquals(originals, graph.repository.profiles.value)
    }

    @Test fun editAndTonightIntentInSameUiTurnParksLatestValueWithoutReopeningEditor() {
        launch()
        val originals = graph.repository.profiles.value
        editFirstProfile()
        val setName = compose.onNode(hasSetTextAction() and hasText("Name", substring = true))
            .fetchSemanticsNode().config[SemanticsActions.SetText].action!!

        compose.runOnUiThread {
            assertTrue(setName(AnnotatedString("Last edit before Tonight")))
            controller!!.pause().newIntent(Intent(app, MainActivity::class.java).putExtra("openTonight", true))
                .resume().topActivityResumed(true)
        }
        settleIntentNavigation()

        compose.onNodeWithText("Start night now").assertExists()
        compose.onNodeWithText("Edit profile").assertDoesNotExist()
        compose.onNodeWithText("Resume editing draft").performClick()
        compose.onNode(hasSetTextAction() and hasText("Last edit before Tonight")).assertExists()
        discardDraft()
        compose.onNodeWithText("Resume editing draft").assertDoesNotExist()
        assertEquals(originals, graph.repository.profiles.value)
    }
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.plan.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B2: the disposable editor is still part of Demo, so its exit remains visible. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-notnight-xhdpi")
class EditorDemoBannerTest {
    private var realProfiles: List<Profile> = emptyList()
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT) { f, _ ->
        runBlocking {
            // U06 seeds a legacy demo flag. Enter a genuine disposable session for this case.
            assertTrue(f.graph.actions.setDemoMode(false).succeeded)
            realProfiles = f.graph.repository.profiles.value
            assertTrue(f.graph.actions.setDemoMode(true).succeeded)
            assertTrue(f.graph.repository.isDemoSession)
        }
    }
    @get:Rule val rules = ui.rules

    @Test fun demoEditorKeepsItsBannerAndOneTapExitAtTwoHundredPercent() {
        ui.control("Edit").performClick()
        ui.compose.onNodeWithText("Edit profile").assertExists()
        ui.compose.onNode(hasSetTextAction() and hasText("Name", substring = true))
            .performScrollTo().performTextReplacement("Disposable editor draft")

        ui.compose.onNodeWithText("Demo — no BedJet connected").assertIsDisplayed()
        ui.checkControl(ui.compose.onNodeWithText("Exit demo"), "Exit demo", scroll = false)
            .performClick()

        ui.waitUntil { !ui.graph.repository.settings.value.demoMode }
        ui.compose.onNodeWithText("Edit profile").assertDoesNotExist()
        ui.compose.onNode(hasSetTextAction() and hasText("Disposable editor draft")).assertDoesNotExist()
        assertEquals(realProfiles, ui.graph.repository.profiles.value)
    }
}

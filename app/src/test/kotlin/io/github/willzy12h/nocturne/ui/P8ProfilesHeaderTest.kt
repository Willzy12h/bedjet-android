package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P8ProfilesHeaderTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT)
    @get:Rule val rules = ui.rules

    @Test fun newProfileIsInHeaderAndDoesNotCoverListControls() {
        ui.tab(ui.s(R.string.tab_profiles))
        val newProfile = ui.compose.onNode(hasText(ui.s(R.string.profiles_new)) and hasClickAction())
        ui.checkControl(newProfile, "New profile", scroll = false)
        val headerBounds = newProfile.fetchSemanticsNode().touchBoundsInRoot
        val offer = ui.compose.onNodeWithText("Add a 10-minute Cooldown after waking?")
        val offerBounds = offer.fetchSemanticsNode().boundsInRoot
        assertTrue("New profile belongs above the offer", headerBounds.bottom <= offerBounds.top)
        val id = ui.graph.repository.profiles.value.first().id
        ui.compose.onNode(hasScrollToKeyAction()).performScrollToKey(id)
        val edit = ui.compose.onNode(hasText(ui.s(R.string.action_edit)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("profile-card-$id"))).performScrollTo()
        ui.checkControl(edit, "Profile Edit", scroll = false)
        val editBounds = edit.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("New profile must not cover profile controls", !headerBounds.overlaps(editBounds))
        newProfile.performTouchInput { click(center) }
        ui.compose.onNodeWithText(ui.s(R.string.profiles_start_from)).assertIsDisplayed()
    }
}

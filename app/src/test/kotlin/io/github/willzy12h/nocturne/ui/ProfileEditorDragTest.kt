package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual pointer drops use neighbour geometry; cancellation and accessibility keep their contracts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-xhdpi")
class ProfileEditorDragTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 320, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val cool = Phase(Mode.COOL, 44, 30, 30)
    private val firstHeat = Phase(Mode.EXTENDED_HEAT, 67, 40, 40)
    private val secondHeat = Phase(Mode.EXTENDED_HEAT, 60, 40, 50)
    private val dry = Phase(Mode.DRY, 56, 50, 10)
    private val pause = Phase(Mode.WAIT, 44, 5, 5)
    private lateinit var stored: List<Profile>
    private lateinit var original: Profile
    private lateinit var draft: Profile
    private var publications = 0

    private fun open(phases: List<Phase>) {
        stored = ui.graph.repository.profiles.value.toList()
        original = stored.first().copy(start = phases)
        draft = original
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                ProfileEditor(ui.model, original, onDone = {}, onDraftChange = { draft = it; publications++ })
            }
        } }
        ui.compose.waitForIdle()
    }

    private fun row(index: Int) = ui.compose.onNodeWithTag("editor-phase-START-$index")

    private fun holdOver(sourceIndex: Int, targetIndex: Int): SemanticsNodeInteraction {
        val source = row(sourceIndex).performScrollTo().assertIsDisplayed()
        val from = source.fetchSemanticsNode().boundsInRoot
        val target = row(targetIndex).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val before = publications
        val beforeDraft = draft
        ui.capture("editor-drag-before")
        source.performTouchInput {
            down(Offset(23f * ui.compose.density.density, centerY))
            advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveBy(Offset(0f, target.center.y - from.center.y))
        }
        ui.compose.waitForIdle()
        assertEquals("Holding the row does not mutate the draft", beforeDraft, draft)
        assertEquals("The draft is published only after dropping", before, publications)
        ui.capture("editor-drag-held")
        return source
    }

    private fun assertUnsaved() = assertEquals("Reordering is an unsaved draft", stored, ui.graph.repository.profiles.value)

    @Test fun droppingDownAcrossTallerRowsDoesNotPassTheUnreachedFollowingRow() {
        open(listOf(cool, firstHeat, secondHeat, dry, pause))
        val source = row(0).performScrollTo().fetchSemanticsNode().boundsInRoot
        val taller = row(1).fetchSemanticsNode().boundsInRoot
        assertTrue("The real Long heat row must wrap: $source / $taller", taller.height > source.height + 1f)
        val before = publications
        holdOver(0, 3).performTouchInput { up() }
        ui.compose.waitForIdle()
        ui.capture("editor-drag-dropped-down")
        assertUnsaved()
        assertEquals("Unselected phases retain their relative order", listOf(firstHeat, secondHeat, dry, pause), draft.start.filter { it != cool })
        assertEquals("One publication on release", before + 1, publications)
        assertTrue("A drop inside Dry can go directly before or after Dry, but not after unreached Pause: ${draft.start}", draft.start.indexOf(cool) in 2..3)
    }

    @Test fun droppingUpAcrossTallerRowsDoesNotPassTheUnreachedPrecedingRow() {
        open(listOf(pause, dry, firstHeat, secondHeat, cool))
        val source = row(4).performScrollTo().fetchSemanticsNode().boundsInRoot
        val taller = row(3).fetchSemanticsNode().boundsInRoot
        assertTrue("The real Long heat row must wrap: $source / $taller", taller.height > source.height + 1f)
        val before = publications
        holdOver(4, 1).performTouchInput { up() }
        ui.compose.waitForIdle()
        ui.capture("editor-drag-dropped-up")
        assertUnsaved()
        assertEquals("Unselected phases retain their relative order", listOf(pause, dry, firstHeat, secondHeat), draft.start.filter { it != cool })
        assertEquals("One publication on release", before + 1, publications)
        assertTrue("A drop inside Dry can go directly before or after Dry, but not before unreached Pause: ${draft.start}", draft.start.indexOf(cool) in 1..2)
    }

    /** Reach the far edge of a tall row without entering the next row's bounds. */
    private fun holdInsideTallTargetEdge(sourceIndex: Int, targetIndex: Int, unreachedIndex: Int): SemanticsNodeInteraction {
        val source = row(sourceIndex).performScrollTo().assertIsDisplayed()
        val from = source.fetchSemanticsNode().boundsInRoot
        val target = row(targetIndex).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val unreached = row(unreachedIndex).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val movingDown = targetIndex > sourceIndex
        val midpoint = (target.center.y + unreached.center.y) / 2f
        val edge = if (movingDown) target.bottom else target.top
        assertTrue("The real wrapped target must extend beyond the centre midpoint: $target / $unreached",
            if (movingDown) midpoint < edge - 1f else midpoint > edge + 1f)
        val releaseY = (midpoint + edge) / 2f
        assertTrue("Release stays inside the reached tall row: $releaseY / $target",
            releaseY > target.top && releaseY < target.bottom)
        assertTrue("Release has not entered the next row: $releaseY / $unreached",
            if (movingDown) releaseY < unreached.top else releaseY > unreached.bottom)
        val before = publications
        val beforeDraft = draft
        ui.capture("editor-edge-drag-before")
        source.performTouchInput {
            down(Offset(23f * ui.compose.density.density, centerY))
            advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveBy(Offset(0f, releaseY - from.center.y))
        }
        ui.compose.waitForIdle()
        assertEquals("Holding the row does not mutate the draft", beforeDraft, draft)
        assertEquals("The draft is published only after dropping", before, publications)
        ui.capture("editor-edge-drag-held")
        return source
    }

    @Test fun droppingInsideTheBottomOfATallRowDoesNotPassTheUnreachedFollowingRow() {
        open(listOf(cool, firstHeat, secondHeat, dry, pause))
        val before = publications
        holdInsideTallTargetEdge(0, 2, 3).performTouchInput { up() }
        ui.compose.waitForIdle()
        ui.capture("editor-edge-drag-dropped-down")
        assertUnsaved()
        assertEquals("Unselected phases retain their relative order", listOf(firstHeat, secondHeat, dry, pause), draft.start.filter { it != cool })
        assertEquals("One publication on release", before + 1, publications)
        assertTrue("A drop inside the second Long heat can go directly before or after it, but not after unreached Dry: ${draft.start}",
            draft.start.indexOf(cool) in 1..2)
    }

    @Test fun droppingInsideTheTopOfATallRowDoesNotPassTheUnreachedPrecedingRow() {
        open(listOf(pause, dry, firstHeat, secondHeat, cool))
        val before = publications
        holdInsideTallTargetEdge(4, 2, 1).performTouchInput { up() }
        ui.compose.waitForIdle()
        ui.capture("editor-edge-drag-dropped-up")
        assertUnsaved()
        assertEquals("Unselected phases retain their relative order", listOf(pause, dry, firstHeat, secondHeat), draft.start.filter { it != cool })
        assertEquals("One publication on release", before + 1, publications)
        assertTrue("A drop inside the first Long heat can go directly before or after it, but not before unreached Dry: ${draft.start}",
            draft.start.indexOf(cool) in 2..3)
    }

    @Test fun cancellingAMultirowDragLeavesTheDraftAndStoredProfileUntouched() {
        open(listOf(cool, firstHeat, secondHeat, dry, pause))
        val first = row(0).performScrollTo().fetchSemanticsNode().boundsInRoot
        val before = publications
        holdOver(0, 3).performTouchInput { cancel() }
        ui.compose.waitForIdle()
        assertEquals("Cancel retains the original draft", original, draft)
        assertEquals("Cancel does not publish a changed draft", before, publications)
        assertEquals("Cancel removes the lifted translation", first, row(0).fetchSemanticsNode().boundsInRoot)
        assertUnsaved()
    }

    @Test fun consecutiveDropsUseTheCurrentOrderEvenWhenRowSizesDoNotChange() {
        open(listOf(cool, dry, pause))
        val before = publications
        holdOver(0, 1).performTouchInput { up() }
        ui.compose.waitForIdle()
        assertEquals(listOf(dry, cool, pause), draft.start)
        holdOver(1, 2).performTouchInput { up() }
        ui.compose.waitForIdle()
        assertEquals("The second drop moves Cool in the current list", listOf(dry, pause, cool), draft.start)
        assertEquals("Each drop publishes once", before + 2, publications)
        assertUnsaved()
    }

    @Test fun accessibilityMovesOneRowInEitherDirectionWithoutSaving() {
        open(listOf(cool, firstHeat, dry))
        fun move(index: Int, label: String) {
            val actions = row(index).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            val action = actions.single { it.label == label }
            ui.compose.runOnIdle { assertTrue(action.action()) }
            ui.compose.waitForIdle()
        }
        val firstActions = row(0).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(ui.s(R.string.parity_move_down)), firstActions.map { it.label })
        move(0, ui.s(R.string.parity_move_down))
        assertEquals(listOf(firstHeat, cool, dry), draft.start)
        assertUnsaved()
        move(1, ui.s(R.string.parity_move_up))
        assertEquals(original, draft)
        assertUnsaved()
    }
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.SlotWrite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import kotlin.math.abs

private enum class RecoveryScenario { UNREADABLE, UNFINISHED_RESTORE, LOST_ORIGINALS, UNCONFIRMED_SLOT }

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class RecoverySeed(val value: RecoveryScenario)

/** U06: recovery actions in the actual Activity remain usable at 320 dp / 200% text, with visible feedback. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class U06RecoveryLayoutTest(look: U06Look) {
    private val ui = U06Fixture(look) { fixture, description ->
        val scenario = requireNotNull(description.getAnnotation(RecoverySeed::class.java)) {
            "Each recovery test must explicitly choose its seed"
        }.value
        when (scenario) {
            RecoveryScenario.UNREADABLE -> unreadableFile(fixture, "profiles.json")
            RecoveryScenario.UNFINISHED_RESTORE -> unfinishedRestoreWithUnreadableFile(fixture)
            RecoveryScenario.LOST_ORIGINALS -> unreadableFile(fixture, "slot-backups.json")
            RecoveryScenario.UNCONFIRMED_SLOT -> runBlocking {
                fixture.graph.repository.updateRuntime {
                    it.copy(slotWrite = SlotWrite("demo", 1, null, Confidence.UNKNOWN, "Keep on slot 2"))
                }
            }
        }
    }
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf(it) }
    }

    @Test @RecoverySeed(RecoveryScenario.UNREADABLE)
    fun ordinaryUnreadableActionsStayReachableOnTonightAndSettings() {
        assertEquals(listOf("profiles.json"), ui.graph.repository.unreadable.value)
        assertFalse(ui.graph.repository.restoreUnfinished.value)
        ui.snackbar()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_banner)).assertExists()
        viewAndHideDetails("profiles.json")
        ui.control(ui.s(R.string.unreadable_dismiss))
        // Tonight's Restore opens the real Backups section; Settings has no second banner Restore button.
        ui.control(ui.s(R.string.unreadable_restore)).performClick()
        waitForText(ui.s(R.string.settings_backups).uppercase())
        ui.compose.onNodeWithText(ui.s(R.string.settings_backups).uppercase()).assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_banner)).assertExists()
        viewAndHideDetails("profiles.json")
        ui.control(ui.s(R.string.unreadable_dismiss)).performClick()
        ui.waitUntil {
            ui.graph.repository.unreadable.value.isEmpty() &&
                ui.compose.onAllNodesWithText(ui.s(R.string.unreadable_banner)).fetchSemanticsNodes().isEmpty()
        }
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_banner)).assertDoesNotExist()
        ui.tab("Tonight")
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_banner)).assertDoesNotExist()
    }

    @Test @RecoverySeed(RecoveryScenario.UNFINISHED_RESTORE)
    fun unfinishedRestoreAndUnreadableActionsStayReachableOnTonightAndSettings() {
        val repository = ui.graph.repository
        assertTrue("The fixture must contain a real unfinished restore", repository.restoreUnfinished.value)
        assertEquals(setOf(Repository.RESTORE_JOURNAL, "notices.json"), repository.unreadable.value.toSet())
        ui.snackbar()
        mixedRestoreMessages()
        viewAndHideDetails("notices.json", unfinished = true)
        ui.control(ui.s(R.string.unreadable_dismiss))
        ui.control(ui.s(R.string.unreadable_restore)).performClick()
        waitForText(ui.s(R.string.settings_backups).uppercase())
        ui.compose.onNodeWithText(ui.s(R.string.settings_backups).uppercase()).assertExists()
        mixedRestoreMessages()
        viewAndHideDetails("notices.json", unfinished = true)
        ui.control(ui.s(R.string.unreadable_dismiss)).performClick()
        ui.waitUntil {
            repository.unreadable.value == listOf(Repository.RESTORE_JOURNAL) &&
                ui.compose.onAllNodesWithText(ui.s(R.string.unreadable_banner)).fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Dismissing the other unreadable file must retain the stuck restore", repository.restoreUnfinished.value)
        ui.compose.onNodeWithText(ui.s(R.string.restore_unfinished_title)).assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_banner)).assertDoesNotExist()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_dismiss)).assertDoesNotExist()
        viewAndHideUnfinishedRestoreDetails()
        ui.tab("Tonight")
        ui.compose.onNodeWithText(ui.s(R.string.restore_unfinished_title)).assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_banner)).assertDoesNotExist()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_dismiss)).assertDoesNotExist()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_restore)).assertDoesNotExist()
        viewAndHideUnfinishedRestoreDetails()
    }

    @Test @RecoverySeed(RecoveryScenario.LOST_ORIGINALS)
    fun lostOriginalsConfirmationStaysReachableOnTonightAndSettings() {
        val repository = ui.graph.repository
        assertTrue("Unreadable saved originals must engage the real barrier", repository.originalsBlocked.value)
        assertEquals(listOf("slot-backups.json"), repository.unreadable.value)
        ui.snackbar()
        ui.compose.onNodeWithText(ui.s(R.string.originals_blocked_text)).assertExists()
        viewAndHideDetails("slot-backups.json")
        ui.control(ui.s(R.string.unreadable_restore))
        ui.control(ui.s(R.string.unreadable_dismiss))
        ui.control(ui.s(R.string.originals_accept_button)).performClick()
        checkLossDialog()
        ui.control(ui.s(R.string.originals_accept_cancel), scroll = false).performClick()
        ui.waitUntil {
            ui.compose.onAllNodesWithText(ui.s(R.string.originals_accept_title)).fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Cancel must keep the originals barrier", repository.originalsBlocked.value)

        ui.tab("Settings")
        ui.compose.openSettingsPage("backups")
        ui.compose.onNodeWithText(ui.s(R.string.originals_blocked_text)).assertExists()
        viewAndHideDetails("slot-backups.json")
        ui.control(ui.s(R.string.unreadable_dismiss))
        ui.control(ui.s(R.string.originals_accept_button)).performClick()
        checkLossDialog()
        ui.control(ui.s(R.string.originals_accept_confirm), scroll = false).performClick()
        ui.waitUntil {
            !repository.originalsBlocked.value && repository.unreadable.value.isEmpty() &&
                ui.compose.onAllNodesWithText(ui.s(R.string.originals_blocked_text)).fetchSemanticsNodes().isEmpty()
        }
        ui.compose.onNodeWithText(ui.s(R.string.originals_blocked_text)).assertDoesNotExist()
        ui.tab("Tonight")
        ui.compose.onNodeWithText(ui.s(R.string.originals_blocked_text)).assertDoesNotExist()
    }

    @Test @RecoverySeed(RecoveryScenario.UNCONFIRMED_SLOT)
    fun unconfirmedSlotWriteCanBeCheckedAboveFeedbackOnTonight() {
        val repository = ui.graph.repository
        assertEquals(Confidence.UNKNOWN, repository.runtime.value.unresolvedSlotWrite?.confidence)
        ui.snackbar()
        ui.compose.onNodeWithText(ui.s(R.string.slot_write_notice, "Keep on slot 2", 2)).assertExists()
        ui.control(ui.s(R.string.slot_write_checked)).performClick()
        ui.waitUntil {
            repository.runtime.value.slotWrite == null &&
                ui.compose.onAllNodesWithText(ui.s(R.string.slot_write_checked)).fetchSemanticsNodes().isEmpty()
        }
        ui.compose.onNodeWithText(ui.s(R.string.slot_write_checked)).assertDoesNotExist()
        assertNull("The real local acknowledgement must clear the saved intention", repository.runtime.value.unresolvedSlotWrite)
    }

    private fun viewAndHideDetails(file: String, unfinished: Boolean = false) {
        ui.control(ui.s(R.string.unreadable_view)).performClick()
        waitForText(ui.s(R.string.unreadable_details, file))
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_details, file)).assertExists()
        if (unfinished) ui.compose.onNodeWithText(ui.s(R.string.restore_unfinished_details)).assertExists()
        ui.control(ui.s(R.string.unreadable_hide)).performClick()
        waitForText(ui.s(R.string.unreadable_view))
    }

    private fun mixedRestoreMessages() {
        ui.compose.onNodeWithText(ui.s(R.string.restore_unfinished_title)).assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.restore_unfinished_text)).assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.unreadable_banner)).assertExists()
    }

    private fun viewAndHideUnfinishedRestoreDetails() {
        ui.control(ui.s(R.string.unreadable_view)).performClick()
        waitForText(ui.s(R.string.restore_unfinished_details))
        ui.compose.onNodeWithText(ui.s(R.string.restore_unfinished_details)).assertExists()
        ui.control(ui.s(R.string.unreadable_hide)).performClick()
        waitForText(ui.s(R.string.unreadable_view))
    }

    private fun checkLossDialog() {
        waitForText(ui.s(R.string.originals_accept_title))
        ui.compose.onNodeWithText(ui.s(R.string.originals_accept_title)).assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.originals_accept_text)).assertExists()
        ui.control(ui.s(R.string.originals_accept_confirm), scroll = false)
        ui.control(ui.s(R.string.originals_accept_cancel), scroll = false)
        assertLossExplanationReadable()
    }

    /** A visible button is not enough: the warning about future originals must also be readable before acceptance. */
    private fun assertLossExplanationReadable() {
        val explanation = ui.s(R.string.originals_accept_text)
        val body = ui.compose.onNodeWithText(explanation, useUnmergedTree = true)
        ui.capture("accept-loss-explanation-start")
        val initial = body.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        ui.compose.runOnIdle { initial.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
        assertEquals("The explanation must expose its real text layout", 1, layouts.size)
        val result = layouts.single()
        ui.assertLinesFit(result, "Accept-loss explanation")
        val lastLine = result.lineCount - 1
        assertEquals("The explanation must retain its complete warning", explanation, result.layoutInput.text.text)
        (0..lastLine).forEach { line ->
            assertTrue("The explanation's line $line leaves the visible width",
                initial.positionInRoot.x + result.getLineLeft(line) >= initial.boundsInRoot.left - 1f &&
                    initial.positionInRoot.x + result.getLineRight(line) <= initial.boundsInRoot.right + 1f)
        }

        fun lineIsVisible(line: Int): Boolean {
            val node = body.fetchSemanticsNode()
            val visible = node.boundsInRoot
            val lineBounds = Rect(node.positionInRoot.x + result.getLineLeft(line),
                node.positionInRoot.y + result.getLineTop(line),
                node.positionInRoot.x + result.getLineRight(line),
                node.positionInRoot.y + result.getLineBottom(line))
            return lineBounds.left >= visible.left - 1f && lineBounds.top >= visible.top - 1f &&
                lineBounds.right <= visible.right + 1f && lineBounds.bottom <= visible.bottom + 1f
        }
        if (lineIsVisible(0) && lineIsVisible(lastLine)) return

        // A long text node may intentionally exceed its viewport. Require and exercise its actual scroll
        // action, then check each end in the clipped visible rectangle rather than demanding the whole node fit.
        val scroller = generateSequence(initial) { it.parent }.firstOrNull {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
        }
        assertNotNull("The explanation is clipped and has no vertical scroll route to its final warning", scroller)
        val scrollId = scroller!!.id
        val scroll = ui.compose.onNode(SemanticsMatcher("dialog explanation scroll area") { it.id == scrollId }, useUnmergedTree = true)
        fun reachEnd(end: Boolean) {
            val range = scroll.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!
            assertTrue("The explanation needs scrollable vertical space", range.maxValue() > 0f)
            val target = if (end) range.maxValue() else 0f
            val delta = (target - range.value()) * if (range.reverseScrolling) -1f else 1f
            if (abs(delta) > 1f) scroll.performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                assertTrue("The explanation must accept the scroll action", action(0f, delta))
            }
            ui.waitUntil { abs(range.value() - target) <= 1f }
            ui.capture(if (end) "accept-loss-explanation-end" else "accept-loss-explanation-start-reached")
            assertTrue("The ${if (end) "final" else "first"} line of the explanation must be fully reachable",
                lineIsVisible(if (end) lastLine else 0))
        }
        reachEnd(false)
        reachEnd(true)
    }

    private fun waitForText(text: String) = ui.waitUntil {
        ui.compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun unreadableFile(fixture: U06Fixture, name: String) {
        val app = RuntimeEnvironment.getApplication() as App
        fixture.graphs.afterFinishing { File(app.filesDir, name).writeText("{unreadable") }
        replaceGraph(fixture)
    }

    /**
     * Use the same interrupted multi-file restore as RepositoryTest. A damaged journal alone is an unreadable
     * file, not restoreUnfinished. The extra corrupt file is outside the journal, so loading committed journal
     * contents cannot hide it. No runtime reconciler is started: the real Repository owns these layout states.
     */
    private fun unfinishedRestoreWithUnreadableFile(fixture: U06Fixture) {
        var stopped = false
        val first = replaceGraph(fixture) { file, bytes ->
            if (stopped) throw AssertionError("process stopped")
            if (file.name == "settings.json") {
                stopped = true
                throw AssertionError("process stopped between restore files")
            }
            Repository.writeDurably(file, bytes)
        }
        val incoming = first.repository.bundle().let { bundle ->
            bundle.copy(profiles = bundle.profiles.mapIndexed { index, profile ->
                if (index == 0) profile.copy(name = "Restored profile") else profile
            })
        }
        assertThrows(AssertionError::class.java) { runBlocking { first.repository.restore(incoming) } }
        val app = RuntimeEnvironment.getApplication() as App
        fixture.graphs.afterFinishing {
            assertTrue("The interrupted restore must leave its real journal", File(app.filesDir, Repository.RESTORE_JOURNAL).isFile)
            File(app.filesDir, "notices.json").writeText("{unreadable")
        }
        replaceGraph(fixture) { file, bytes ->
            if (file.name == "profiles.json") throw IOException("Disk full")
            Repository.writeDurably(file, bytes)
        }
    }

    private fun replaceGraph(
        fixture: U06Fixture,
        writeFile: (File, ByteArray) -> Unit = { file, bytes -> Repository.writeDurably(file, bytes) },
    ): Graph {
        val app = RuntimeEnvironment.getApplication() as App
        val clock = fixture.graph.clock
        val replacement = fixture.graphs.fresh {
            Graph(app, repositoryFactory = { scope, _ ->
                Repository(app, scope, writeFile = writeFile, now = { ZonedDateTime.now(clock) })
            }).also { it.clock = clock }
        }
        ReflectionHelpers.setField(app, "graph", replacement)
        return replacement
    }
}

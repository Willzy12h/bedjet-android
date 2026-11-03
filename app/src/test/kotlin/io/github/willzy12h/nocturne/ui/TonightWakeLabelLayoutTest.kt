package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** U-B4: inspect real text layout, including the unaffected sides of the compact-label boundary. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TonightWakeLabelLayoutTest(
    private val look: U06Look,
    private val widthDp: Int,
    private val fontScale: Float,
) {
    private val ui = U06Fixture(look, widthDp = widthDp, fontScale = fontScale) { fixture, _ ->
        val graph = fixture.graph
        val now = graph.now()
        runBlocking { graph.repository.updateSettings { it.setWakeOverride(now.plusHours(7), now) } }
    }
    private val width = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            RuntimeEnvironment.setQualifiers("w${widthDp}dp-h640dp-xhdpi")
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(width).around(ui.rules)

    companion object {
        private const val COMPACT_LABEL = "Wake tonight only"

        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}dp-font{2}")
        fun layouts(): List<Array<Any>> = buildList {
            U06Look.entries.forEach { add(arrayOf(it, 320, 2f)) }
            listOf(U06Look.SIMPLE_MIDNIGHT, U06Look.SIMPLE_DAYLIGHT).forEach {
                add(arrayOf(it, 320, 1f))
                add(arrayOf(it, 411, 2f))
            }
        }
    }

    @Test fun overrideTitleKeepsWholeWordsAndOnlyCompactsAtNarrowLargeSimpleView() {
        ui.compose.openTonightDetail()
        val fullLabel = ui.s(R.string.wake_origin_override)
        val title = hasText(fullLabel) or hasText(COMPACT_LABEL)
        ui.waitUntil { ui.compose.onAllNodes(title, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = ui.compose.onNode(title, useUnmergedTree = true)
        ui.checkLayout(node, "Tonight-only wake title")

        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("The wake title must expose its actual rendered text layout", 1, layouts.size)
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        if (widthDp > 320 || fontScale < 2f) {
            assertEquals("The existing label must remain unchanged outside narrow, large-text layouts", fullLabel, text)
            ui.compose.onAllNodesWithText(COMPACT_LABEL, useUnmergedTree = true).assertCountEquals(0)
        }

        run {
            // UX-04: every look now shows the timeline, so the whole-word check applies to all of them.
            val lines = (0 until layout.lineCount).map {
                text.substring(layout.getLineStart(it), layout.getLineEnd(it, visibleEnd = true))
            }
            Regex("\\S+").findAll(text).forEach { word ->
                assertEquals("Simple wake title splits '${word.value}' within a word: $lines",
                    layout.getLineForOffset(word.range.first), layout.getLineForOffset(word.range.last))
            }
        }
    }
}

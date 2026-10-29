package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-notnight-xhdpi")
class P8StatusHeaderLargeTextTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 320, fontScale = 2f) { fixture, _ ->
        runBlocking { fixture.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) } }
    }
    @get:Rule val rules = ui.rules

    @Test fun unavailableTemperatureWrapsAtWordBoundaries() {
        val node = ui.compose.onNodeWithTag("home-reading-context").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        assertNull(ui.model.status.value)
        assertEquals("Temperature unavailable", text)
        assertFalse(layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = true)
            assertTrue("Line ends inside a word: $text at $end", end == text.length ||
                !text[end - 1].isLetterOrDigit() || !text[end].isLetterOrDigit())
        }
    }
}

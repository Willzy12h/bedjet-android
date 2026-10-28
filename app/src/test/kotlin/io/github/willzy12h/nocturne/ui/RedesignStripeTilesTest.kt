package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class RedesignStripeTilesTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val palette = mutableStateOf(Palette.MIDNIGHT_INK)

    private fun showPresets(width: Int) {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            val settings by ui.graph.repository.settings.collectAsState()
            NocturneTheme(settings, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        QuickDefaultsSection(ui.model, settings)
                    }
                }
            }
        } }
        ui.compose.waitForIdle()
    }

    @Test fun presetHeatTileRendersTemperatureStripeInBothPalettes() {
        showPresets(280)
        for (choice in listOf(Palette.MIDNIGHT_INK, Palette.OVERCAST)) {
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.waitForIdle()
            val tile = ui.compose.onNodeWithTag("quick-default-HEAT").performScrollTo()
            val bounds = tile.fetchSemanticsNode().boundsInRoot
            val expected = choice.tokens.stepColor(Mode.HEAT, QuickDefaults.resolve(ui.graph.repository.settings.value, Mode.HEAT).temperature).toArgb()
            val view = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            // Sample a central vertical span on the left edge; corners and anti-aliasing are excluded.
            val density = ui.compose.density.density
            val left = bounds.left.toInt().coerceAtLeast(0)
            val right = (bounds.left + 5 * density).toInt().coerceAtMost(bitmap.width - 1)
            val top = (bounds.top + bounds.height * .25f).toInt().coerceAtLeast(0)
            val bottom = (bounds.top + bounds.height * .75f).toInt().coerceAtMost(bitmap.height - 1)
            var matches = 0
            for (y in top..bottom) for (x in left..right) if (bitmap.getPixel(x, y) == expected) matches++
            bitmap.recycle()
            assertTrue("$choice heat tile should draw a temperature-coloured left stripe", matches > 20)
        }
    }

    @Test fun presetSubtitleIsCompleteAndWrapsOnNarrowTile() {
        showPresets(120)
        val preset = QuickDefaults.resolve(ui.graph.repository.settings.value, Mode.HEAT)
        val subtitle = ui.s(R.string.uat_quick_summary, Format.temp(preset.temperature, false, Format.Words { id, args -> ui.s(id, *args) }), preset.fanPercent, preset.minutes)
        val node = ui.compose.onNodeWithText(subtitle, useUnmergedTree = true).performScrollTo().assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertTrue("Subtitle must wrap at narrow widths", result.lineCount > 1)
        assertFalse("Subtitle must retain all lines", result.multiParagraph.didExceedMaxLines)
        assertEquals(subtitle.length, result.getLineEnd(result.lineCount - 1))
        repeat(result.lineCount) { assertFalse("Subtitle must not ellipsise", result.isLineEllipsized(it)) }
    }
}

package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette
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
class RedesignHomeTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val palette = mutableStateOf(Palette.MIDNIGHT_INK)

    private fun showHome() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                TonightScreen(ui.model, androidx.compose.ui.Modifier, onEdit = {})
            }
        } }
        ui.compose.waitForIdle()
    }

    @Test fun startUsesRenderedPrimaryFillAndOnPrimaryText() {
        showHome()
        for (choice in listOf(Palette.MIDNIGHT_INK, Palette.OVERCAST)) {
            ui.compose.runOnIdle { palette.value = choice }
            val label = ui.s(R.string.tonight_start_now)
            val button = ui.compose.onNode(hasText(label) and hasClickAction()).performScrollTo()
            ui.checkControl(button, label)
            val results = mutableListOf<TextLayoutResult>()
            ui.compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(choice.tokens.onPrimary, results.single().layoutInput.style.color)
            val view = WindowInspector.getGlobalWindowViews().first { it.isShown && it.width > 0 }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val bounds = button.fetchSemanticsNode().boundsInRoot
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val expected = choice.tokens.primary.toArgb()
            var matches = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height))
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width))
                    if (pixels[y * bitmap.width + x] == expected) matches++
            assertTrue("$choice Start button must render its primary fill", matches > 100)
            bitmap.recycle()
        }
    }

    @Test fun freshReadingUsesTheReportedAirTemperature() {
        ui.waitUntil { ui.model.hasLiveStatus.value && ui.model.status.value != null }
        showHome()
        // The ViewModel deliberately throttles sensor updates; the UI must use its displayed report.
        val expected = ui.model.status.value!!.actualTemperature
        val results = mutableListOf<TextLayoutResult>()
        ui.compose.onNodeWithTag("home-temperature", useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(Format.temp(expected, false), results.single().layoutInput.text.text)
        assertTrue("Home reading should be prominent", results.single().layoutInput.style.fontSize.value >= 48f)
        ui.compose.onNodeWithTag("home-reading-context", useUnmergedTree = true)
            .assertTextEquals("Air from the BedJet")
    }
    @Test fun staleReadingIsNotPresentedAsCurrent() {
        ui.waitUntil { ui.model.hasLiveStatus.value && ui.model.status.value != null }
        showHome()
        ui.compose.runOnUiThread { ui.model.onScreenHidden() }
        ui.waitUntil { !ui.model.hasLiveStatus.value }
        ui.compose.onNodeWithTag("home-temperature", useUnmergedTree = true).assertDoesNotExist()
        ui.compose.onNodeWithTag("home-reading-context", useUnmergedTree = true)
            .assertTextEquals("No reading while the BedJet is out of reach")
    }

}

package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class P6SheetPaletteTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val palette = mutableStateOf(Palette.MIDNIGHT_INK)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun verify(sheet: Boolean) {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            val settings by ui.graph.repository.settings.collectAsState()
            NocturneTheme(settings, ui.graph.now(), paletteOverride = palette.value) {
                if (sheet) ModalBottomSheet(onDismissRequest = {}) { Text("Palette surface") }
                else AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text("Palette surface") })
            }
        } }
        for (choice in listOf(Palette.MIDNIGHT_INK, Palette.OVERCAST)) {
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            ui.compose.onNodeWithText("Palette surface").assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(if (sheet) choice.tokens.ink else choice.tokens.ink2, layouts.single().layoutInput.style.color)
            val views = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 && it.height > 0 }
            val view = views.last()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            var pixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                if (bitmap.getPixel(x, y) == choice.tokens.sheet.toArgb()) pixels++
            bitmap.recycle()
            assertTrue("$choice ${if (sheet) "sheet" else "dialog"} must render its opaque sheet colour", pixels > 100)
        }
    }

    @Test fun alertDialogUsesSheetAndSecondaryInk() = verify(sheet = false)
    @Test fun modalBottomSheetUsesSheetAndInk() = verify(sheet = true)
}

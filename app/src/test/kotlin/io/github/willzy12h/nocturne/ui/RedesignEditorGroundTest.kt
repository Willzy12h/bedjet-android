package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class RedesignEditorGroundTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun fullScreenEditorHasAnOpaquePaletteGround() {
        ui.compose.openTonightProfileEditor()
        ui.compose.onNodeWithTag("editor-save").assertIsDisplayed()
        val view = ui.compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        // The full-screen editor has no Scaffold behind it. Its inset outside the controls must
        // still be the chosen ground, never the Android window shining through a glass surface.
        assertEquals(Palette.MIDNIGHT_INK.tokens.ground.toArgb(), bitmap.getPixel(1, bitmap.height / 2))
        bitmap.recycle()
    }
}

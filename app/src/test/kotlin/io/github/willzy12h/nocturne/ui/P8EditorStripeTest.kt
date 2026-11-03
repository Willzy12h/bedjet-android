package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P8EditorStripeTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun phaseRowRendersModeStripeAndStillExpandsInBothPalettes() {
        val profile = ui.graph.repository.profiles.value.first().copy(start = listOf(Phase(Mode.HEAT, 64, 40, 30)))
        for (palette in listOf(Palette.MIDNIGHT_INK, Palette.OVERCAST)) {
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette) {
                    key(palette) { ProfileEditor(ui.model, profile, onDone = {}) }
                }
            } }
            val row = ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo()
            val bounds = row.fetchSemanticsNode().boundsInRoot
            val root = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { root.draw(Canvas(bitmap)) }
            val density = ui.compose.density.density
            var count = 0
            for (y in (bounds.top + bounds.height * .25f).toInt() until (bounds.top + bounds.height * .75f).toInt())
                for (x in bounds.left.toInt() until (bounds.left + 4 * density).toInt())
                    if (x in 0 until bitmap.width && y in 0 until bitmap.height && bitmap.getPixel(x, y) == palette.tokens.stepColor(Mode.HEAT, 64).toArgb()) count++
            bitmap.recycle()
            assertTrue("$palette phase needs a mode-coloured left stripe", count > 20)
            row.performClick()
            ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertExists()
        }
    }
}

package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P6SetupStyleTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val palette = mutableStateOf(Palette.MIDNIGHT_INK)

    private fun show() {
        ui.compose.runOnUiThread {
            ui.model.openSetupChecklist()
            ui.compose.activity.setContent {
                NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { SetupChecklistCard(ui.model) }
                }
            }
        }
        ui.compose.waitForIdle()
    }

    @Test fun pendingPairRowRendersPaletteSideStripeInBothThemes() {
        show()
        for (choice in listOf(Palette.MIDNIGHT_INK, Palette.OVERCAST)) {
            ui.compose.runOnIdle { palette.value = choice }
            val row = ui.compose.onNodeWithTag("setup-pair").performScrollTo()
            val bounds = row.fetchSemanticsNode().boundsInRoot
            val view = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val tokens = choice.tokens
            val colours = setOf(tokens.cool.toArgb(), tokens.warn.toArgb(), tokens.accent.toArgb(), tokens.bad.toArgb())
            var matches = 0
            for (y in (bounds.top + bounds.height * .25f).toInt()..(bounds.top + bounds.height * .75f).toInt())
                for (x in bounds.left.toInt()..(bounds.left + 4 * ui.compose.density.density).toInt())
                    if (bitmap.getPixel(x, y) in colours) matches++
            bitmap.recycle()
            assertTrue("$choice pending Pair must have a visible palette stripe", matches > 20)
        }
    }

    @Test fun setupRowsKeepTheirOrderAndDemoPairAction() {
        show()
        val pair = ui.compose.onNodeWithTag("setup-pair").performScrollTo().assertExists()
        val health = ui.compose.onNodeWithTag("setup-health").assertExists()
        assertTrue(pair.fetchSemanticsNode().positionInRoot.y < health.fetchSemanticsNode().positionInRoot.y)
        ui.compose.onNode(hasText(ui.s(R.string.uat_setup_fix)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("setup-pair"))).performScrollTo().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.uat_setup_demo)).assertExists()
    }
}

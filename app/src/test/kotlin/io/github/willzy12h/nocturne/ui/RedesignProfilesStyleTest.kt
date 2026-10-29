package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignProfilesStyleTest {
    private val ui = U06Fixture(U06Look.DAYLIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun profileCardRendersTheMiddleStepColourAsItsStripe() {
        val profile = ui.graph.repository.profiles.value.first()
        ui.compose.onNode(hasText(ui.s(R.string.tab_profiles)) and hasClickAction()).performClick()
        val card = ui.compose.onNodeWithTag("profile-card-${profile.id}").performScrollTo()
        val bounds = card.fetchSemanticsNode().boundsInRoot
        val expected = Palette.OVERCAST.tokens.stepColor(profile.middle.mode, profile.middle.tempStart).toArgb()
        val bitmap = draw(ui.compose.activity.window.decorView)
        val density = ui.compose.density.density
        var matches = 0
        for (y in (bounds.top + bounds.height * .25f).toInt()..(bounds.top + bounds.height * .75f).toInt()) {
            for (x in bounds.left.toInt()..(bounds.left + 5 * density).toInt()) {
                if (bitmap.getPixel(x, y) == expected) matches++
            }
        }
        bitmap.recycle()
        assertTrue("Profile stripe should use the middle step colour", matches > 20)
    }

    @Test fun overcastOverflowMenuRendersPaletteBackgroundInkAndBorder() {
        ui.compose.onNode(hasText(ui.s(R.string.tab_profiles)) and hasClickAction()).performClick()
        ui.compose.onNodeWithTag("profile-more-${ui.graph.repository.profiles.value.first().id}")
            .performScrollTo().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.pin_menu)).assertIsDisplayed()
        ui.compose.waitForIdle()
        val popup = WindowInspector.getGlobalWindowViews().single {
            it.isShown && it.width > 0 && it.height > 0 && it !== ui.compose.activity.window.decorView
        }
        val pixels = draw(popup)
        val tokens = Palette.OVERCAST.tokens
        var background = 0
        var ink = 0
        var border = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = Color(pixels.getPixel(x, y))
            if (close(pixel, tokens.menu)) background++
            if (close(pixel, tokens.ink)) ink++
            if (close(pixel, tokens.outline)) border++
        }
        pixels.recycle()
        assertTrue("Menu must render its palette background", background > 100)
        assertTrue("Menu labels must render in palette ink", ink > 5)
        assertTrue("Menu must render the palette edge border", border > 5)
    }

    @Test fun tappingProfileHeaderOpensExistingEditor() {
        val profile = ui.graph.repository.profiles.value.first()
        ui.compose.onNode(hasText(ui.s(R.string.tab_profiles)) and hasClickAction()).performClick()
        ui.compose.onNodeWithTag("profile-open-${profile.id}").performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-save").assertIsDisplayed()
    }

    private fun draw(view: android.view.View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun close(actual: Color, expected: Color): Boolean {
        val a = actual.toArgb()
        val b = expected.toArgb()
        return listOf(16, 8, 0).all { shift -> abs(((a shr shift) and 255) - ((b shr shift) and 255)) <= 3 }
    }
}

/** Every preset must remain reachable in the chooser at the smallest supported viewport. */
@RunWith(org.robolectric.ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignProfileChooserLargeTextTest(private val preset: io.github.willzy12h.nocturne.core.plan.Profile) {
    private val ui = U06Fixture(U06Look.MIDNIGHT)
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic
        @org.robolectric.ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun presets(): List<Array<Any>> = io.github.willzy12h.nocturne.core.plan.Presets.all.map { arrayOf<Any>(it) }
    }

    @Test fun presetIsFullyReachableAndOpensItsDraft() {
        ui.tab(ui.s(R.string.tab_profiles))
        ui.compose.onNodeWithTag("profiles-new").performClick()
        ui.compose.onNodeWithText(ui.s(R.string.profiles_start_from)).assertIsDisplayed()
        val choice = ui.compose.onNode(hasText(preset.name) and hasClickAction() and hasAnyAncestor(isDialog()))
        ui.checkControl(choice, "Preset ${preset.name}")
        choice.performClick()
        ui.compose.onNodeWithTag("editor-save").assertIsDisplayed()
        ui.compose.onNode(hasText(preset.name) and hasSetTextAction()).assertExists()
    }
}

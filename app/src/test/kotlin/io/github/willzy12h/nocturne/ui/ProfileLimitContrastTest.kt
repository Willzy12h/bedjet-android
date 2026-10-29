package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Both real profile-limit handles must remain visible; an ordinary Slider is not this control. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class ProfileLimitContrastTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val palette = mutableStateOf(Palette.OVERCAST)

    @Test fun bothFanLimitHandlesRemainVisibleInEveryPalette() = inspect(R.string.slider_fan_limits)
    @Test fun bothTemperatureLimitHandlesRemainVisibleInEveryPalette() = inspect(R.string.slider_temp_limits)

    private fun inspect(labelResource: Int) {
        val profile = ui.graph.repository.profiles.value.first().copy(
            limits = ProfileLimits(minFan = 20, maxFan = 80, minTemp = 44, maxTemp = 72),
        )
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) { ProfileEditor(ui.model, profile, onDone = {}) }
            }
        } }
        val label = ui.s(labelResource)
        val failures = mutableListOf<String>()
        for (choice in Palette.entries) {
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
            ui.compose.waitForIdle()
            ui.capture("profile-limits-$label-${choice.name}")
            val thumbs = ui.compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
                    hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("$choice: inspect both actual range handles", 2, thumbs.size)
            val view = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                thumbs.forEachIndexed { index, thumb ->
                    val bounds = thumb.boundsInRoot
                    val actual = Color(bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
                    // Two dp above the vertical handle is the real adjacent ground, clear of the track.
                    val background = Color(bitmap.getPixel(bounds.center.x.toInt(),
                        (bounds.top - 2f * ui.compose.density.density).toInt()))
                    val a = actual.luminance()
                    val b = background.luminance()
                    val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                    if (contrast < 3f) failures += "$choice $label handle $index: $actual / $background at $bounds = $contrast:1"
                }
            } finally { bitmap.recycle() }
        }
        assertTrue("Every handle must reach 3:1; all failing handles were inspected:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}

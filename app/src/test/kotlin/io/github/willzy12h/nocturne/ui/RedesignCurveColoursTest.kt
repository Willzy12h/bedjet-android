package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toArgb
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignCurveColoursTest : ComposeActivityFixture() {
    @Test fun curveRendersDifferentEmbersForDifferentTemperaturesIncludingAfterWake() {
        val start = ZonedDateTime.parse("2026-10-27T22:00Z")
        val plan = Plan(start, start.plusMinutes(10), listOf(
            PlannedStep(Block.START, ProgramStep(Mode.HEAT, 9, 56, 0, 10), start, 10),
            PlannedStep(Block.AFTER_WAKE, ProgramStep(Mode.HEAT, 9, 82, 0, 10), start.plusMinutes(10), 10),
        ))
        for (palette in listOf(Palette.MIDNIGHT_INK, Palette.OVERCAST)) {
            setContent {
                NocturneTheme(Settings(themeMode = ThemeMode.DARK, nightPalette = palette), start) {
                    Surface(color = paletteTokens.ground) { Column { NightCurve(plan) } }
                }
            }
            val view = WindowInspector.getGlobalWindowViews().first { it.isShown && it.width > 0 }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            listOf(56, 82).forEach { temperature ->
                val expected = palette.tokens.stepColor(Mode.HEAT, temperature).toArgb()
                assertTrue("$palette must render the $temperature half-degree Ember bar", pixels.count { it == expected } > 100)
            }
            bitmap.recycle()
        }
    }
}

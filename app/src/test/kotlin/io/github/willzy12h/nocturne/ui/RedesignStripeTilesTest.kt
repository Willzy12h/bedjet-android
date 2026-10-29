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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
        val subtitle = ui.compose.activity.resources.getQuantityString(R.plurals.uat_quick_summary, preset.minutes, Format.temp(preset.temperature, false, Format.Words { id, args -> ui.s(id, *args) }), preset.fanPercent, preset.minutes)
        val node = ui.compose.onNodeWithText(subtitle, useUnmergedTree = true).performScrollTo().assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertTrue("Subtitle must wrap at narrow widths", result.lineCount > 1)
        assertFalse("Subtitle must retain all lines", result.multiParagraph.didExceedMaxLines)
        assertEquals(subtitle.length, result.getLineEnd(result.lineCount - 1))
        repeat(result.lineCount) { assertFalse("Subtitle must not ellipsise", result.isLineEllipsized(it)) }
    }
    private fun assertBodyPixel(tag: SemanticsNodeInteraction, tint: Color, label: String,
        base: SemanticsNodeInteraction? = null) {
        val bounds = tag.performScrollTo().fetchSemanticsNode().boundsInRoot
        val view = ui.compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        try {
            val density = ui.compose.density.density
            val actual = bitmap.getPixel((bounds.left + 8 * density).toInt(), bounds.center.y.toInt())
            // Sample the rendered underlying surface: native View layers quantise each alpha blend.
            // Comparing one new tint layer retains a one-channel tolerance without accumulating base-layer error.
            val behind = base?.fetchSemanticsNode()?.boundsInRoot?.center
                ?: Offset(bounds.left - 8 * density, bounds.center.y)
            val background = Color(bitmap.getPixel(behind.x.toInt(), behind.y.toInt()))
            val want = tint.compositeOver(background).toArgb()
            for (shift in listOf(0, 8, 16, 24)) assertTrue(
                "$label: expected ${want.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}",
                kotlin.math.abs(((want ushr shift) and 255) - ((actual ushr shift) and 255)) <= 1)
        } finally { bitmap.recycle() }
    }

    @Test fun rememberedBedJetRowAppliesTheApprovedTintExactlyOnce() {
        kotlinx.coroutines.runBlocking {
            ui.graph.repository.exitDemoSession()
            ui.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        }
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { BedJetPicker(ui.model, compact = true) }
                }
            }
        } }
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            val t = choice.tokens
            val row = ui.compose.onNode(hasText("AA:BB:CC:DD:EE:FF") and hasClickAction())
            assertBodyPixel(row, t.cool.copy(alpha = .10f), "$choice remembered BedJet tint")
        }
    }

    @Test fun everyTintStrengthRendersItsSpecifiedCompositeInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Box(Modifier.size(180.dp, 64.dp).background(palette.value.tokens.surface).testTag("tint-base"))
                        TintLevel.entries.forEach { level ->
                            Box(Modifier.size(180.dp, 64.dp).background(palette.value.tokens.surface)
                                .sideStripe(palette.value.tokens.cool, palette.value.tokens, level = level).testTag("tint-$level"))
                        }
                    }
                }
            }
        } }
        val alphas = listOf(.10f, .24f, .09f, .08f)
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            TintLevel.entries.forEachIndexed { index, level ->
                val t = choice.tokens
                assertBodyPixel(ui.compose.onNodeWithTag("tint-$level"),
                    t.cool.copy(alpha = alphas[index]), "$choice $level", ui.compose.onNodeWithTag("tint-base"))
            }
        }
    }

}

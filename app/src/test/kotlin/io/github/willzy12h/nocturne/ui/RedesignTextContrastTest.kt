package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.testTag
import io.github.willzy12h.nocturne.core.plan.Phase
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Checks the foreground actually laid out by production composables, not merely palette roles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class RedesignTextContrastTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val palette = mutableStateOf(Palette.MIDNIGHT_INK)

    private fun layout(text: String, scroll: Boolean = true): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = ui.compose.onAllNodesWithText(text, useUnmergedTree = true)[0]
        if (scroll) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun luminance(c: Color): Double {
        fun linear(x: Float): Double = if (x <= .04045f) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
        return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
    }

    private fun checkText(label: String, result: TextLayoutResult, background: Color) {
        val style = result.layoutInput.style
        val foreground = style.color.compositeOver(background)
        val a = luminance(foreground)
        val b = luminance(background)
        val ratio = (max(a, b) + .05) / (min(a, b) + .05)
        val size = style.fontSize.value
        val weight = style.fontWeight ?: FontWeight.Normal
        val large = size >= 24f || (size >= 19f && weight >= FontWeight.Bold)
        val minimum = if (large) 3.0 else 4.5
        assertTrue("$label: ${size}sp weight $weight contrast $ratio must reach $minimum", ratio >= minimum)
    }

    @Test fun profileActionTextIsReadableOnTheActualCardInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                ProfilesScreen(ui.model, Modifier, onEdit = {})
            }
        } }
        ui.compose.waitForIdle()
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.waitForIdle()
            val t = choice.tokens
            checkText("$choice profile action", layout(ui.s(R.string.parity_start)), t.surface.compositeOver(t.ground))
        }
    }

    @Test fun invalidQuickPresetErrorIsReadableOnTheActualCardInEveryPalette() {
        val invalid = ui.graph.repository.settings.value.copy(
            quickDefaults = listOf(ActionPreset(Mode.HEAT, 64, 30, 0)),
        )
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(invalid, ui.graph.now(), paletteOverride = palette.value) {
                Column(Modifier.verticalScroll(rememberScrollState())) { QuickDefaultsSection(ui.model, invalid) }
            }
        } }
        ui.compose.waitForIdle()
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.waitForIdle()
            val t = choice.tokens
            checkText("$choice invalid preset", layout(ui.s(R.string.uat_quick_invalid)), t.surface2.compositeOver(t.ground))
        }
    }

    private fun wakeLabelContrast(timeline: Boolean) {
        val start = ZonedDateTime.parse("2026-10-27T22:00Z")
        val plan = Plan(start, start.plusMinutes(10), listOf(
            PlannedStep(Block.START, ProgramStep(Mode.HEAT, 9, 56, 0, 10), start, 10),
        ))
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, start, paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.surface2) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (timeline) NightTimeline(plan, WakeOrigin.PROFILE_DEFAULT, false) else NightCurve(plan)
                    }
                }
            }
        } }
        ui.compose.waitForIdle()
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.waitForIdle()
            val text = if (timeline) ui.s(R.string.wake_time) else ui.s(R.string.curve_wake, Format.clock(plan.wake))
            val t = choice.tokens
            checkText("$choice ${if (timeline) "timeline" else "curve"} wake", layout(text), t.surface2.compositeOver(t.ground))
        }
    }

    @Test fun curveWakeLabelIsReadableInEveryPalette() = wakeLabelContrast(false)
    @Test fun timelineWakeLabelIsReadableInEveryPalette() = wakeLabelContrast(true)

    @Test fun startButtonUsesReadableRenderedTypographyInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                TonightScreen(ui.model, Modifier, onEdit = {})
            }
        } }
        ui.compose.waitForIdle()
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.waitForIdle()
            checkText("$choice Start", layout(ui.s(R.string.tonight_start_now)), choice.tokens.primary)
        }
    }
    @Test fun compactCaptionsAndLinksRemainReadableInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SectionLabel("Caption probe")
                        AccentLink("Action probe", onClick = {})
                    }
                }
            }
        } }
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            checkText("$choice section caption", layout("CAPTION PROBE"), choice.tokens.ground)
            checkText("$choice action link", layout("Action probe"), choice.tokens.ground)
        }
    }

    @Test fun liftedEditorStepKeepsItsTextReadableInOvercast() {
        val choice = Palette.OVERCAST
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = choice) {
                Surface(color = choice.tokens.ground) {
                    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                        StepCard(Phase(Mode.HEAT, 72, 40, 30), "Heat 36°C", "Fan 40%", "30 min", Modifier.testTag("drag-step"),
                            canMoveUp = false, canMoveDown = true, onMove = {}, onClick = {})
                    }
                }
            }
        } }
        val card = ui.compose.onNodeWithTag("drag-step")
        val density = ui.compose.activity.resources.displayMetrics.density
        fun background(): Color {
            val view = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val bounds = card.fetchSemanticsNode().boundsInRoot
            return try { Color(bitmap.getPixel((bounds.left + 8f * density).toInt(), bounds.center.y.toInt())) }
                finally { bitmap.recycle() }
        }
        val restingBackground = background()
        card.performTouchInput {
            down(Offset(23f * density, centerY))
            advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveBy(Offset(0f, 2f))
        }
        ui.compose.waitForIdle()
        // This pixel is inside the side stripe's tinted body, away from the grip and text.
        val background = background()
        try {
            org.junit.Assert.assertNotEquals("The held grip must enter the rendered drag state", restingBackground, background)
            checkText("Overcast lifted step title", layout("Heat 36°C", scroll = false), background)
            checkText("Overcast lifted step subtitle", layout("Fan 40%", scroll = false), background)
        } finally {
            card.performTouchInput { up() }
        }
    }

}

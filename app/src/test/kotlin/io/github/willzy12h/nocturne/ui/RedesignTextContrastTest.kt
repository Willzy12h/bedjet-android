package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import kotlinx.coroutines.launch
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
import androidx.compose.ui.semantics.getOrNull
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

    private fun checkIndicator(label: String, foreground: Color, background: Color) {
        val a = luminance(foreground.compositeOver(background))
        val b = luminance(background)
        val ratio = (max(a, b) + .05) / (min(a, b) + .05)
        assertTrue("$label indicator contrast $ratio must reach 3:1 ($foreground / $background)", ratio >= 3.0)
    }

    private fun pixels(read: (Bitmap) -> Unit) {
        ui.compose.waitForIdle()
        val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.isShown && it.width > 0 }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            read(bitmap)
        } finally { bitmap.recycle() }
    }

    @Test fun focusedTimeFieldLabelsRemainReadableInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                TypeTimeDialog(30, onDismiss = {}, onSet = {})
            }
        } }
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.onNodeWithTag("type-time-hours").performClick()
            checkText("$choice focused hours", layout(ui.s(R.string.parity_hours), scroll = false), choice.tokens.sheet)
        }
    }

    @Test fun timeFieldBoundariesAndFocusRemainVisibleInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                TypeTimeDialog(30, onDismiss = {}, onSet = {})
            }
        } }
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            ui.compose.onNodeWithTag("type-time-hours").performClick()
            for (tag in listOf("type-time-hours", "type-time-minutes")) {
                val bounds = ui.compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                pixels { bitmap ->
                    // Midway down the left edge avoids the floating label and rounded corners.
                    val border = Color(bitmap.getPixel((bounds.left + .5f * ui.compose.density.density).toInt(), bounds.center.y.toInt()))
                    checkIndicator("$choice $tag boundary", border, choice.tokens.sheet)
                }
            }
        }
    }

    @Test fun remoteSliderThumbRemainsVisibleInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    Column(Modifier.padding(20.dp)) {
                        ScaledSlider(50f, {}, 0f..100f, 0, true, Modifier.testTag("contrast-slider"),
                            listOf(0f to "0", 50f to "50", 100f to "100"), "contrast-slider", {})
                    }
                }
            }
        } }
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            val bounds = ui.compose.onNodeWithTag("contrast-slider").fetchSemanticsNode().boundsInRoot
            pixels { bitmap ->
                val thumb = Color(bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
                checkIndicator("$choice Remote slider thumb", thumb, choice.tokens.ground)
            }
        }
    }

    private fun leadingIndicator(tag: String, label: String) {
        val row = ui.compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode().boundsInRoot
        pixels { bitmap ->
            // The leading 24 dp contains only the native checkbox/radio drawing and its solid background.
            val counts = mutableMapOf<Int, Int>()
            for (x in row.left.toInt() until (row.left + 24f * ui.compose.density.density).toInt()) {
                for (y in row.top.toInt() until row.bottom.toInt()) {
                    val color = bitmap.getPixel(x, y)
                    counts[color] = (counts[color] ?: 0) + 1
                }
            }
            val solid = counts.entries.sortedByDescending { it.value }
            assertTrue("$label must render both an indicator and its background", solid.size >= 2 && solid[1].value >= 10)
            checkIndicator(label, Color(solid[1].key), Color(solid[0].key))
        }
    }

    @Test fun selectedProfileRadioRemainsVisibleInEveryPalette() {
        val profile = ui.graph.repository.profiles.value.first()
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                TonightScreen(ui.model, Modifier, onEdit = {})
            }
        } }
        ui.compose.onNodeWithTag("tonight-change").performScrollTo().performClick()
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            leadingIndicator("tonight-choose-${profile.id}", "$choice profile radio")
        }
    }

    @Test fun checkedCooldownChoiceRemainsVisibleInEveryPalette() {
        val profile = ui.graph.repository.profiles.value.first().copy(afterWake = emptyList())
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                        CooldownOfferCard(ui.model, listOf(profile), fahrenheit = false)
                    }
                }
            }
        } }
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            leadingIndicator("cooldown-choice-${profile.id}", "$choice cooldown checkbox")
        }
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

    @Test fun bottomNavigationLabelsMeetSmallTextContrastInEveryPalette() {
        Palette.entries.forEach { choice ->
            kotlinx.coroutines.runBlocking { ui.graph.repository.updateSettings {
                it.copy(themeMode = io.github.willzy12h.nocturne.core.store.ThemeMode.DARK, nightPalette = choice)
            } }
            // Light appearance requires the real consent action, even when the preference is written
            // by this fixture. Without it the Activity deliberately retains the previous dark palette.
            if (!choice.dark) {
                ui.waitUntil { ui.compose.onAllNodesWithText(ui.s(R.string.theme_switch_now)).fetchSemanticsNodes().isNotEmpty() }
                ui.compose.onNodeWithText(ui.s(R.string.theme_switch_now)).performClick()
            }
            ui.waitUntil { layout(ui.s(R.string.tab_tonight), scroll = false).layoutInput.style.color == choice.tokens.ink }
            ui.compose.waitForIdle()
            checkText("$choice navigation", layout(ui.s(R.string.tab_profiles), scroll = false), choice.tokens.ground)
        }
    }

    @Test fun middleBarTemperatureLabelsContrastWithTheirActualFill() {
        val profile = ui.graph.repository.profiles.value.first().copy(start = emptyList(), wake = emptyList(), afterWake = emptyList(),
            middle = io.github.willzy12h.nocturne.core.plan.Middle(Mode.HEAT, fanStart = 30, fanEnd = 30, tempStart = 64, tempEnd = 58, rampSteps = 3))
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                ProfileEditor(ui.model, profile, onDone = {})
            }
        } }
        ui.compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            val temperatures = listOf(64, 61, 58)
            temperatures.forEach { temperature ->
                val label = Format.temp(temperature, false)
                val nodes = ui.compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
                val labels = nodes.mapNotNull { node ->
                    val results = mutableListOf<TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                    results.singleOrNull()?.takeIf { it.layoutInput.style.fontSize.value == 13f }
                }
                assertTrue("The actual middle bar label $label must be inspected", labels.isNotEmpty())
                labels.forEach { checkText("$choice middle bar $label", it, choice.tokens.stepColor(Mode.HEAT, temperature)) }
            }
        }
    }

    @Test fun reportedModeCopyUsesReadableInkInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                TonightScreen(ui.model, Modifier, onEdit = {})
            }
        } }
        for (mode in listOf(Mode.HEAT, Mode.TURBO)) {
            ui.model.remote("Mode", confirms = { it.mode == mode }) {
                it.setMode(mode)
                if (mode == Mode.HEAT) it.setTemperature(64)
            }
            ui.waitUntil { ui.graph.link.status.value?.mode == mode && !ui.model.busy.value }
            val reading = requireNotNull(ui.graph.link.status.value)
            val label = Format.modeName(mode) + if (mode.usesTemperature) " " + Format.temp(reading.targetTemperature, false) else ""
            Palette.entries.forEach { choice ->
                ui.compose.runOnIdle { palette.value = choice }
                checkText("$choice reported $mode", layout(label), choice.tokens.ground)
            }
        }
    }

    @Test fun pendingModeCopyRemainsReadableInOvercast() {
        ui.compose.runOnUiThread { palette.value = Palette.OVERCAST; ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                TonightScreen(ui.model, Modifier, onEdit = {})
            }
        } }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holder = ui.graph.scope.launch { ui.graph.actions.exclusive { entered.complete(Unit); release.await() } }
        try {
            ui.waitUntil { entered.isCompleted }
            ui.compose.onNodeWithTag("quick-mode-HEAT").performScrollTo().performClick()
            ui.waitUntil { ui.graph.actions.busy.value.queuedCount > 0 }
            checkText("Overcast pending Heat", layout("Heat…"), Palette.OVERCAST.tokens.ground)
        } finally { release.complete(Unit); holder.cancel() }
    }

    @Test fun stepTemperatureValuesMeetLargeTextContrastInEveryPalette() {
        val phase = mutableStateOf(Phase(Mode.HEAT, 64, 30, 30))
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.sheet) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        StepEditorContent("Step", phase.value, false, null, "contrast-step", fanSlider = { _, _ -> },
                            onDone = {}, onDelete = {}, onChange = { phase.value = it })
                    }
                }
            }
        } }
        for (mode in listOf(Mode.HEAT, Mode.TURBO, Mode.COOL, Mode.DRY, Mode.EXTENDED_HEAT)) {
            val temperature = when (mode) { Mode.TURBO -> 86; Mode.HEAT -> 64; Mode.EXTENDED_HEAT -> 60; else -> 56 }
            ui.compose.runOnIdle { phase.value = Phase(mode, temperature, 30, 30) }
            Palette.entries.forEach { choice ->
                ui.compose.runOnIdle { palette.value = choice }
                checkText("$choice step $mode", layout(Format.temp(temperature, false)), choice.tokens.sheet)
            }
        }
    }

    @Test fun historyIssueCopyIsReadableAgainstItsActualPillInEveryPalette() {
        val now = ui.graph.now()
        kotlinx.coroutines.runBlocking { ui.graph.repository.addRecord(io.github.willzy12h.nocturne.core.store.NightRecord(
            "contrast-history", "p", "Contrast night", now.minusHours(8).toString(), now.toString(), false,
            events = listOf(io.github.willzy12h.nocturne.core.store.HistoryEvent(now.toString(),
                io.github.willzy12h.nocturne.core.store.EventKind.ERROR, "Outcome unknown")))) }
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, now, paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) { HistoryScreen(ui.model, Modifier) }
            }
        } }
        Palette.entries.forEach { choice ->
            ui.compose.runOnIdle { palette.value = choice }
            val t = choice.tokens
            val background = t.surface2.compositeOver(t.surface.compositeOver(t.ground))
            checkText("$choice History issue", layout("1 issue"), background)
        }
    }

    @Test fun quickControlsKeepReadableTextAndActiveMarkersInEveryPalette() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        QuickPresets(ui.model, rememberNightGuard(ui.model), rememberPresetSending(), onEdit = {})
                    }
                }
            }
        } }
        val presets = listOf(Mode.TURBO, Mode.HEAT, Mode.COOL, Mode.DRY, null, Mode.STANDBY)
        for (mode in presets) {
            val request = when (mode) {
                null -> ui.model.quickAction(io.github.willzy12h.nocturne.night.NightActions.Command.BOOST).end
                Mode.STANDBY -> ui.graph.actions.capture(io.github.willzy12h.nocturne.night.NightActions.Command.OFF)
                else -> captureModeQuick(ui.model, io.github.willzy12h.nocturne.core.store.QuickDefaults.resolve(ui.graph.repository.settings.value, mode)).end
            }
            kotlinx.coroutines.runBlocking { org.junit.Assert.assertEquals(io.github.willzy12h.nocturne.night.OutcomeKind.CONFIRMED,
                ui.graph.actions.execute(request).kind) }
            val key = mode?.name ?: "BOOST"
            try {
                ui.waitUntil { ui.compose.onAllNodesWithTag("quick-active-$key", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("$key marker missing: report=${ui.model.status.value}; live=${ui.model.hasLiveStatus.value}; " +
                    "owner=${ui.model.commandBusy.value}; runtime=${ui.model.runtime.value}; night=${ui.model.activeNight.value}", e)
            }
            for (choice in Palette.entries) {
                ui.compose.runOnIdle { palette.value = choice }
                val tile = ui.compose.onNodeWithTag("quick-mode-$key").performScrollTo()
                val marker = ui.compose.onNodeWithTag("quick-active-$key", useUnmergedTree = true).fetchSemanticsNode()
                val bounds = tile.fetchSemanticsNode().boundsInRoot
                val view = ui.compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                val (foreground, background) = try {
                    Color(bitmap.getPixel(marker.boundsInRoot.center.x.toInt(), marker.boundsInRoot.center.y.toInt())) to
                        Color(bitmap.getPixel((bounds.left + 8 * ui.compose.density.density).toInt(), bounds.center.y.toInt()))
                } finally { bitmap.recycle() }
                val a = luminance(foreground); val b = luminance(background)
                val ratio = (max(a, b) + .05) / (min(a, b) + .05)
                assertTrue("$choice active $key marker contrast $ratio must reach 3:1", ratio >= 3.0)
                val title = if (mode == null) ui.s(R.string.shortcut_boost) else ui.s(Format.modeNameRes(mode))
                checkText("$choice active $key label", layout(title), background)
            }
        }
    }

}

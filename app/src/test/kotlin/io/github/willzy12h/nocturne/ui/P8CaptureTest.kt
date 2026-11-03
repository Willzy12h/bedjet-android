package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.NativePngCapture
import io.github.willzy12h.nocturne.widget.QuickControlsWidget
import io.github.willzy12h.nocturne.widget.SingleActionWidget
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.RecordedStep
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Opt-in host captures of real Compose screens; not physical-device evidence. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class P8CaptureTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    private val tag = System.getenv("SCREENSHOT_TAG")
    private val optIn = TestRule { base, _ -> object : Statement() { override fun evaluate() {
        assumeTrue("set SCREENSHOT_TAG to capture", !tag.isNullOrBlank())
        require(tag!!.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")))
        base.evaluate()
    } } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(optIn).around(ui.rules)

    private fun capture(name: String) {
        ui.compose.waitForIdle()
        val views = android.view.inspector.WindowInspector.getGlobalWindowViews()
            .filter { it.isShown && it.width > 0 && it.height > 0 }
        assertTrue("Capture must include the Activity", views.any { it === ui.compose.activity.window.decorView })
        val dir = File("build/screenshots/$tag").apply { mkdirs() }
        views.forEachIndexed { index, view ->
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                NativePngCapture.write(bitmap, File(dir, "$name-window$index.png"))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun populatedHistoryAndRenderedWidgetsInBothPalettes() = runBlocking {
        val now = ui.graph.now()
        val start = now.minusHours(8)
        val wake = now.minusHours(1)
        val record = NightRecord("capture-history", "capture-profile", "Captured night", start.toString(), wake.toString(), true,
            steps = listOf(RecordedStep(Block.MIDDLE, Mode.COOL, 44, 40, start.toString(), 420)),
            events = listOf(HistoryEvent(start.toString(), EventKind.STARTED, "Started"),
                HistoryEvent(wake.toString(), EventKind.ERROR, "Outcome unknown"),
                HistoryEvent(now.toString(), EventKind.ENDED, "Ended")))
        ui.graph.repository.addRecord(record)
        for (palette in Palette.entries) {
            ui.graph.repository.updateSettings { it.copy(themeMode = if (palette.dark) ThemeMode.DARK else ThemeMode.LIGHT,
                nightPalette = if (palette.dark) palette else Palette.MIDNIGHT_INK, dayPalette = Palette.OVERCAST) }
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette) {
                    Surface(Modifier.fillMaxSize(), color = palette.tokens.ground) { HistoryScreen(ui.model, Modifier) }
                }
            } }
            ui.waitUntil { ui.compose.onAllNodesWithText("Captured night", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.compose.onNodeWithText("Captured night", substring = true).assertExists()
            ui.compose.onNodeWithText("1 issue").assertExists()
            capture("p8-${palette.name.lowercase()}-history-populated")
            val size = DpSize(280.dp, 180.dp)
            for (single in listOf(false, true)) {
                val widget = if (single) SingleActionWidget() else QuickControlsWidget()
                val state = if (single) preferencesOf(SingleActionWidget.KEY_COMMAND to "WARM", SingleActionWidget.KEY_LABEL to "Warm")
                    else preferencesOf(QuickControlsWidget.KEY_PRESETS to "HEAT")
                val root = widget.compose(ui.compose.activity, size = size, state = state).apply(ui.compose.activity, FrameLayout(ui.compose.activity))
                val density = ui.compose.density.density
                root.measure(View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                fun descendants(view: View): List<View> = listOf(view) +
                    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                assertTrue(io.github.willzy12h.nocturne.widget.widgetControl(root, if (single) "Warm" else "Heat") != null)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    ui.compose.runOnUiThread { root.draw(Canvas(bitmap)) }
                    val dir = File("build/screenshots/$tag").apply { mkdirs() }
                    NativePngCapture.write(bitmap, File(dir, "p8-${palette.name.lowercase()}-widget-${if (single) "single" else "quick"}.png"))
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun settingsIndexAndEverySubpageInBothPalettes() {
        // These first captures use the complete Activity, including navigation and real light-theme consent.
        for (palette in Palette.entries) {
            ui.compose.runOnUiThread { runBlocking {
                ui.graph.repository.updateSettings { it.copy(themeMode = if (palette.dark) ThemeMode.DARK else ThemeMode.LIGHT,
                    nightPalette = if (palette.dark) palette else Palette.MIDNIGHT_INK, dayPalette = Palette.OVERCAST) }
            } }
            ui.compose.waitForIdle()
            if (!palette.dark) {
                val switch = ui.s(io.github.willzy12h.nocturne.R.string.theme_switch_now)
                if (ui.compose.onAllNodesWithText(switch).fetchSemanticsNodes().isNotEmpty()) {
                    capture("p8-${palette.name.lowercase()}-light-consent")
                    ui.compose.onNodeWithText(switch).performClick()
                }
                ui.compose.onNodeWithText(switch).assertDoesNotExist()
            }
            ui.tab("Tonight")
            ui.compose.onNodeWithTag("status-header").performScrollTo()
            capture("p8-${palette.name.lowercase()}-tonight")
            ui.compose.onNodeWithTag("quick-controls").performScrollTo()
            capture("p8-${palette.name.lowercase()}-quick-controls")
            captureTonightSurfaces(ui) { capture("p8-${palette.name.lowercase()}-$it") }
            ui.compose.onNodeWithTag("open-remote").performScrollTo().performClick()
            capture("p8-${palette.name.lowercase()}-remote")
            ui.tab("Profiles")
            capture("p8-${palette.name.lowercase()}-profiles")
            captureProfileSurfaces(ui) { capture("p8-${palette.name.lowercase()}-$it") }
        }
        for (palette in Palette.entries) {
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette) {
                    Surface(Modifier.fillMaxSize(), color = palette.tokens.ground) { key(palette) { SettingsScreen(ui.model, Modifier) } }
                }
            } }
            ui.compose.onNodeWithTag("settings-page-bedjet").assertExists()
            capture("p8-${palette.name.lowercase()}-settings-index")
            for (page in listOf("bedjet", "night", "appearance", "permissions", "backups", "notifications", "health", "about")) {
                ui.compose.openSettingsPage(page)
                ui.compose.onNodeWithTag("settings-back").performScrollTo().assertIsDisplayed()
                capture("p8-${palette.name.lowercase()}-settings-$page")
                ui.compose.backSettingsPage()
            }
        }
    }

    @Test fun editorAndSetupInBothPalettes() {
        val profile = ui.graph.repository.profiles.value.first()
        for (palette in Palette.entries) {
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette) {
                    Surface(Modifier.fillMaxSize(), color = palette.tokens.ground) { key(palette) { ProfileEditor(ui.model, profile, onDone = {}) } }
                }
            } }
            ui.compose.onNodeWithTag("editor-back").assertExists()
            captureEditorSurfaces(ui) { capture("p8-${palette.name.lowercase()}-$it") }
            runBlocking { ui.graph.repository.updateSettings {
                it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF")
            } }
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette) {
                    Surface(Modifier.fillMaxSize(), color = palette.tokens.ground) { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { SetupFlow(ui.model) } }
                }
            } }
            ui.compose.onNodeWithText("Choose your BedJet", substring = true).assertExists()
            capture("p8-${palette.name.lowercase()}-setup")
            ui.compose.onNode(hasText("AA:BB:CC:DD:EE:FF") and hasClickAction()).performScrollTo().assertIsDisplayed()
            capture("p8-${palette.name.lowercase()}-setup-remembered")
        }
    }
}

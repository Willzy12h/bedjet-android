package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.NativePngCapture
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.widget.QuickControlsWidget
import io.github.willzy12h.nocturne.widget.SingleActionWidget
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in host renders of the home-screen widgets at common launcher sizes; not physical-device evidence. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetCaptureTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    private val tag = System.getenv("SCREENSHOT_TAG")
    private val optIn = TestRule { base, _ -> object : Statement() { override fun evaluate() {
        assumeTrue("set SCREENSHOT_TAG to capture", !tag.isNullOrBlank())
        base.evaluate()
    } } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(optIn).around(ui.rules)

    /** Launcher cells are roughly 73 × 92 dp each on a 411 dp phone. */
    private val sizes = listOf("1x1" to DpSize(73.dp, 92.dp), "2x1" to DpSize(146.dp, 92.dp), "2x2" to DpSize(146.dp, 184.dp),
        "3x2" to DpSize(219.dp, 184.dp), "4x2" to DpSize(292.dp, 184.dp), "4x3" to DpSize(292.dp, 276.dp), "4x4" to DpSize(292.dp, 368.dp))

    private fun render(name: String, widget: androidx.glance.appwidget.GlanceAppWidget, size: DpSize, state: androidx.datastore.preferences.core.Preferences) = runBlocking {
        val root = widget.compose(ui.compose.activity, size = size, state = state).apply(ui.compose.activity, FrameLayout(ui.compose.activity))
        val density = ui.compose.density.density
        root.measure(View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            ui.compose.runOnUiThread { Canvas(bitmap).apply { drawColor(0xFF4A5A7A.toInt()); root.draw(this) } }
            NativePngCapture.write(bitmap, File(File("build/screenshots/$tag").apply { mkdirs() }, "$name.png"))
        } finally { bitmap.recycle() }
    }

    @Test fun widgetsAtEverySize() {
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val look = theme.name.lowercase()
            ui.compose.runOnUiThread { runBlocking { ui.graph.repository.updateSettings { it.copy(themeMode = theme) } } }
            for ((cells, size) in sizes) if (cells != "1x1") render("widget-$look-quick-$cells", QuickControlsWidget(), size, preferencesOf())
            for (preset in listOf("TURBO", "HEAT", "COOL", "DRY", "BOOST", "START", "OFF"))
                render("widget-$look-tile-$preset", SingleActionWidget(), sizes.first().second, preferencesOf(SingleActionWidget.KEY_PRESET to preset))
            render("widget-$look-tile-legacy", SingleActionWidget(), sizes.first().second,
                preferencesOf(SingleActionWidget.KEY_COMMAND to "WARM", SingleActionWidget.KEY_LABEL to "Warm"))
        }
        // While a night runs, 4 × 3 adds tonight's plan.
        val graph = ui.graph
        val start = graph.now()
        val profile = graph.repository.profiles.value.first()
        val plan = io.github.willzy12h.nocturne.core.plan.PlanCompiler.compile(profile, start, start.plusHours(8))
        ui.compose.runOnUiThread { runBlocking { graph.repository.setActiveNight(io.github.willzy12h.nocturne.core.night.ActiveNight("capture",
            profile.id, start.toString(), plan.wake.toString(), false, 2, plan.wakeUpStart.toString(), plan.end.toString(), deviceId = "demo",
            profile = profile, applied = io.github.willzy12h.nocturne.core.night.AppliedProgram.from(plan),
            confidence = io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED)) } }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.compose.runOnUiThread { runBlocking { ui.graph.repository.updateSettings { it.copy(themeMode = theme) } } }
            for ((cells, size) in sizes.drop(4)) render("widget-${theme.name.lowercase()}-night-$cells", QuickControlsWidget(), size, preferencesOf())
        }
    }
}

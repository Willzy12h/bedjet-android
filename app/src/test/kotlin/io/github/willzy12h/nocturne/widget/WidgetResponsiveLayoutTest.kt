package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Inflate the production Glance RemoteViews at launcher sizes: every control stays readable, reachable and inside its widget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class WidgetResponsiveLayoutTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** Launcher cells are roughly 73 × 92 dp on a 411 dp phone. */
    private val controlsSizes = listOf("2x1" to DpSize(146.dp, 92.dp), "2x2" to DpSize(146.dp, 184.dp), "3x2" to DpSize(219.dp, 184.dp),
        "4x2" to DpSize(292.dp, 184.dp), "4x3" to DpSize(292.dp, 276.dp), "4x4" to DpSize(292.dp, 368.dp))
    private val tileSizes = listOf("1x1" to DpSize(73.dp, 92.dp), "small 1x1" to DpSize(64.dp, 64.dp))

    private fun rendered(size: DpSize, widget: GlanceAppWidget, state: Preferences): ViewGroup = runBlocking {
        context.graph.repository.updateSettings { it.copy(demoMode = true) }
        val remote = widget.compose(context, size = size, state = state)
        (remote.apply(context, FrameLayout(context)) as ViewGroup).also { view ->
            val density = context.resources.displayMetrics.density
            view.measure(View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
    }

    private fun <T> atFontScale(scale: Float, block: () -> T): T {
        val saved = Configuration(context.resources.configuration)
        return try {
            context.resources.updateConfiguration(Configuration(saved).apply { fontScale = scale }, context.resources.displayMetrics)
            block()
        } finally { context.resources.updateConfiguration(saved, context.resources.displayMetrics) }
    }

    private fun bounds(root: View, view: View) = Rect().also { view.getDrawingRect(it); (root as ViewGroup).offsetDescendantRectToMyCoords(view, it) }
    private fun dp(px: Int) = px / context.resources.displayMetrics.density
    private fun sp16() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics)
    /** Glance puts the click on a wrapper and the spoken description on the view inside it. */
    private fun described(view: View) = widgetDescription(view)
    private fun isControl(view: View) = view.hasOnClickListeners() && !described(view).endsWith("Opens Nocturne.")

    /** Whether [label] is laid out whole on one line: no ellipsis and no characters pushed to a hidden line. */
    private fun whole(label: TextView): Boolean {
        val layout = label.layout ?: return true
        return layout.getEllipsisCount(0) == 0 && layout.getLineEnd(0) >= label.text.length &&
            layout.getLineWidth(0) <= label.width - label.totalPaddingLeft - label.totalPaddingRight + 1f
    }

    private fun checkControls(name: String, size: DpSize, scale: Float, presets: String? = null) {
        val root = rendered(size, QuickControlsWidget(), presets?.let { preferencesOf(QuickControlsWidget.KEY_PRESETS to it) } ?: preferencesOf())
        val whole = Rect(0, 0, root.width, root.height)
        val views = widgetDescendants(root)
        val controls = views.filter(::isControl)
        assertTrue("$name at $scale×: Off is always present", widgetControl(root, "Off") != null)
        controls.forEach { control ->
            val rect = bounds(root, control)
            assertTrue("$name at $scale×: ${described(control)} is inside the widget: $rect in $whole", whole.contains(rect))
            assertTrue("$name at $scale×: ${described(control)} is 56dp tall: ${dp(control.height)}", dp(control.height) >= 56f - .5f)
            assertTrue("$name at $scale×: ${described(control)} is 56dp wide: ${dp(control.width)}", dp(control.width) >= 56f - .5f)
            assertFalse("$name: ${described(control)} needs a spoken description", described(control).isBlank())
        }
        val rects = controls.map { bounds(root, it) }
        rects.forEachIndexed { index, rect -> rects.drop(index + 1).forEach {
            assertFalse("$name at $scale×: controls must not overlap: $rect and $it", Rect.intersects(rect, it))
        } }
        val labels = views.filterIsInstance<TextView>().filter { it.text.isNotBlank() }
        labels.forEach { label ->
            assertTrue("$name at $scale×: ${label.text} is at least 16sp", label.textSize + .01f >= sp16())
            assertTrue("$name at $scale×: ${label.text} is inside the widget", whole.contains(bounds(root, label)))
        }
        val pillLabels = labels.filter { label -> generateSequence<View>(label) { it.parent as? View }.any(::isControl) }
        pillLabels.forEach { label -> val pill = generateSequence<View>(label) { it.parent as? View }.first(::isControl)
            assertTrue("$name: ${label.text} keeps its name in what is spoken", described(pill).isNotBlank()) }
        pillLabels.forEach { assertTrue("$name at $scale×: the control label ${it.text} must not be cut short", whole(it)) }
        assertTrue("$name at $scale×: a 20sp value leads the widget", labels.any {
            it.textSize + .01f >= TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, context.resources.displayMetrics) })
    }

    @Test fun controlsFitAtEverySizeAtNormalAndTwoHundredPercentText() {
        for (scale in listOf(1f, 2f)) atFontScale(scale) {
            for ((name, size) in controlsSizes) checkControls(name, size, scale)
            // Every preset the person might choose, including the long ones, still fits.
            for ((name, size) in controlsSizes) checkControls("$name all presets", size, scale, "START,TURBO,HEAT,COOL,DRY,BOOST")
        }
    }

    @Test fun controlsFitWhileANightRunsAtNormalAndTwoHundredPercentText() = runBlocking {
        val graph = context.graph
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val start = graph.now()
        val profile = graph.repository.profiles.value.first()
        val plan = io.github.willzy12h.nocturne.core.plan.PlanCompiler.compile(profile, start, start.plusHours(8))
        graph.repository.setActiveNight(io.github.willzy12h.nocturne.core.night.ActiveNight("layout", profile.id, start.toString(),
            plan.wake.toString(), false, 2, plan.wakeUpStart.toString(), plan.end.toString(), deviceId = "demo", profile = profile,
            applied = io.github.willzy12h.nocturne.core.night.AppliedProgram.from(plan),
            confidence = io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED))
        try {
            for (scale in listOf(1f, 2f)) atFontScale(scale) { for ((name, size) in controlsSizes) checkControls("$name night", size, scale) }
            val roomy = rendered(DpSize(292.dp, 276.dp), QuickControlsWidget(), preferencesOf())
            assertTrue("4 × 3 has room for tonight's plan", widgetDescendants(roomy).filterIsInstance<TextView>()
                .any { it.text.toString() == context.getString(io.github.willzy12h.nocturne.R.string.widget_running, profile.name) })
        } finally { graph.repository.setActiveNight(null) }
    }

    @Test fun theLayoutGrowsToShowMorePresetsAndKeepsOffInReach() {
        assertEquals("2 × 1 is a strip: the header and Off", 0, ControlsLayout.of(146f, 92f, 1f, false).rows)
        val narrow = ControlsLayout.of(146f, 184f, 1f, false)
        assertEquals("2 × 2 keeps pills wide enough for their names: one column", 1, narrow.columns)
        assertEquals(PRESET_OFF, narrow.arrange(DEFAULT_CONTROLS).last().first())
        val wide = ControlsLayout.of(292f, 184f, 1f, false)
        assertEquals(4, wide.columns)
        assertEquals(2, wide.rows)
        assertEquals("With two rows Off starts the last row", listOf(listOf("TURBO", "HEAT", "COOL", "BOOST"), listOf(PRESET_OFF, "DRY", null, null)),
            wide.arrange(DEFAULT_CONTROLS))
        assertEquals("A short list shares one row with Off at its end", listOf(listOf("COOL", PRESET_OFF)), wide.arrange(listOf("COOL")))
        assertEquals("Off alone when nothing is chosen", listOf(listOf(PRESET_OFF)), wide.arrange(emptyList()))
        val strip = ControlsLayout.of(146f, 60f, 1f, false)
        assertEquals("Too short for a row: the header and Off share the strip", 0, strip.rows)
        ControlsLayout.of(292f, 184f, 1f, true).let { assertTrue("4 × 2 shows the plan above one row of controls", it.plan && it.rows == 1) }
        assertTrue("4 × 3 adds the plan while a night runs", ControlsLayout.of(292f, 276f, 1f, true).plan)
        val large = ControlsLayout.of(292f, 368f, 2f, false)
        assertTrue("Large text gets wider pills", large.columns <= 2)
        assertTrue("Large text stacks the header", large.stackedHeader)
    }

    @Test fun savedPresetsAreReadInOrderWithoutOffOrUnknownKeys() {
        assertEquals(DEFAULT_CONTROLS, controlKeys(context, null))
        assertEquals(listOf("COOL", "TURBO"), controlKeys(context, "COOL,OFF,NOPE,TURBO"))
        assertEquals(emptyList<String>(), controlKeys(context, ""))
    }

    @Test fun everyTileFitsAndIsOneWholeButton() {
        val keys = QUICK_PRESETS + PRESET_START + PRESET_OFF
        for (scale in listOf(1f, 2f)) atFontScale(scale) {
            for ((name, size) in tileSizes) for (key in keys) {
                val root = rendered(size, SingleActionWidget(), preferencesOf(SingleActionWidget.KEY_PRESET to key))
                val whole = Rect(0, 0, root.width, root.height)
                val controls = widgetControls(root)
                assertEquals("$key $name at $scale×: the whole tile is the one button", 1, controls.size)
                assertTrue("$key $name: the tile is 56dp or more", dp(controls.single().width) >= 56f && dp(controls.single().height) >= 56f)
                val label = widgetPreset(context, key)!!.label
                val shown = widgetDescendants(root).filterIsInstance<TextView>().first { it.text.isNotBlank() }.text.toString()
                assertTrue("$key $name at $scale×: shows its name or its short form, not $shown",
                    shown == label || (key == PRESET_START && shown == context.getString(io.github.willzy12h.nocturne.R.string.widget_start_short)))
                assertEquals(controls.single(), widgetControl(root, shown))
                widgetDescendants(root).filterIsInstance<TextView>().filter { it.text.isNotBlank() }.forEach {
                    assertTrue("$key $name at $scale×: ${it.text} is at least 16sp", it.textSize + .01f >= sp16())
                    assertTrue("$key $name at $scale×: ${it.text} is inside the tile", whole.contains(bounds(root, it)))
                }
                val title = widgetDescendants(root).filterIsInstance<TextView>().first { it.text.toString() == shown }
                // At 200% a one-cell tile cannot hold the longest names; the spoken description always carries them in full.
                if (scale == 1f && size == tileSizes.first().second) assertTrue("$key $name: ${title.text} must not be cut short", whole(title))
                assertTrue("$key: the description names the preset", described(controls.single()).contains(if (key == "BOOST") "Turbo" else label))
            }
        }
    }

    @Test fun legacySingleActionPlacementsStillRender() {
        val root = rendered(tileSizes.first().second, SingleActionWidget(),
            preferencesOf(SingleActionWidget.KEY_COMMAND to "COOL", SingleActionWidget.KEY_LABEL to "Cool"))
        assertNotNull(widgetControl(root, "Cool"))
        assertEquals(1, widgetControls(root).size)
    }

    @Test fun widgetRequestCarriesWidgetOriginBeforeTheServiceHandoff() {
        assertEquals(CommandOrigin.WIDGET, widgetRequest(context, widgetActions(context).first()).origin)
    }
}

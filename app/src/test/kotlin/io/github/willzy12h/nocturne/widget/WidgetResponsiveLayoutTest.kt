package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** Inflate the production Glance RemoteViews: labels and touch dimensions must survive translation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class WidgetResponsiveLayoutTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val compact = DpSize(200.dp, 128.dp)
    private val medium = DpSize(280.dp, 180.dp)
    private val large = DpSize(340.dp, 240.dp)

    private fun rendered(size: DpSize, page: String, widget: GlanceAppWidget = QuickControlsWidget(),
        state: Preferences = preferencesOf(stringPreferencesKey("widget_page") to page)): View = runBlocking {
        context.graph.repository.updateSettings { it.copy(demoMode = true) }
        val remote = widget.compose(context, size = size, state = state)
        remote.apply(context, FrameLayout(context)).also { view ->
            val density = context.resources.displayMetrics.density
            view.measure(View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun checkPage(size: DpSize, page: String, expected: List<String>, maximum: Int) {
        val root = rendered(size, page) as ViewGroup
        val views = descendants(root)
        val buttons = views.filterIsInstance<Button>()
        val actions = buttons.filterNot { it.text.toString() in listOf("‹", "›") }
        assertEquals("Real controls on $page at $size", expected, actions.map { it.text.toString() })
        assertTrue("Action budget at $size", actions.size <= maximum)
        assertEquals("Both page chevrons are real buttons", listOf("‹", "›"), buttons.filter { it.text.toString() in listOf("‹", "›") }.map { it.text.toString() })
        buttons.forEach { button ->
            assertTrue("${button.text} touch height ${button.height}", button.height / context.resources.displayMetrics.density >= 56f)
            assertTrue("${button.text} touch width ${button.width}", button.width / context.resources.displayMetrics.density >= 56f)
            assertTrue("${button.text} label size", button.textSize / context.resources.displayMetrics.scaledDensity >= 16f)
        }
        val touchBounds = buttons.map { button ->
            android.graphics.Rect().also { rect ->
                button.getDrawingRect(rect)
                root.offsetDescendantRectToMyCoords(button, rect)
                assertTrue("${button.text} must fit inside $size: $rect", android.graphics.Rect(0, 0, root.width, root.height).contains(rect))
            }
        }
        touchBounds.forEachIndexed { index, rect -> touchBounds.drop(index + 1).forEach {
            assertFalse("Controls must not overlap: $rect and $it", android.graphics.Rect.intersects(rect, it))
        } }
        val labels = views.filterIsInstance<TextView>().filter { it.text.isNotBlank() }
        assertTrue("A main status value is present at 20sp", labels.any { it !is Button && it.textSize / context.resources.displayMetrics.scaledDensity >= 20f })
        labels.forEach { assertTrue("${it.text} label is at least 16sp", it.textSize / context.resources.displayMetrics.scaledDensity >= 16f) }
    }

    @Test fun compactNightHasOneLargePrimaryAction() = checkPage(compact, "night:0", listOf("Start tonight"), 1)
    @Test fun compactQuickHasOneLargeAction() = checkPage(compact, "quick:0", listOf("Cool"), 1)
    @Test fun compactDeviceHasOneLargeAction() = checkPage(compact, "device:0", listOf("Off"), 1)
    @Test fun mediumNightHasTwoLargePrimaryActions() = checkPage(medium, "night:0", listOf("Start tonight", "Turbo→Heat"), 2)
    @Test fun mediumQuickHasTwoLargeActions() = checkPage(medium, "quick:0", listOf("Cool", "Warm"), 2)
    @Test fun mediumDeviceHasTwoLargeActions() = checkPage(medium, "device:0", listOf("Off", "Stop tracking"), 2)
    @Test fun largeNightHasThreeLargePrimaryActions() = checkPage(large, "night:0", listOf("Start tonight", "Turbo→Heat", "Turbo 10m"), 3)
    @Test fun largeQuickHasTwoLargeActions() = checkPage(large, "quick:0", listOf("Cool", "Warm"), 3)
    @Test fun largeDeviceHasTwoLargeActions() = checkPage(large, "device:0", listOf("Off", "Stop tracking"), 3)
    @Test fun compactCanReachBoost() = checkPage(compact, "night:1", listOf("Turbo→Heat"), 1)
    @Test fun compactCanReachTurbo() = checkPage(compact, "night:2", listOf("Turbo 10m"), 1)
    @Test fun mediumCanReachTurbo() = checkPage(medium, "night:2", listOf("Turbo 10m"), 2)

    @Test fun widgetRequestCarriesWidgetOriginBeforeTheServiceHandoff() {
        assertEquals(CommandOrigin.WIDGET, widgetRequest(context, widgetActions(context).first()).origin)
    }

    @Test fun compactProfilesRemainReachable() = checkPage(compact, "profiles:0", context.graph.repository.profiles.value.take(1).map {
        context.getString(io.github.willzy12h.nocturne.R.string.feature_start_profile, it.name)
    }, 1)
    @Test fun mediumProfilesRemainReachable() = checkPage(medium, "profiles:0", context.graph.repository.profiles.value.take(2).map {
        context.getString(io.github.willzy12h.nocturne.R.string.feature_start_profile, it.name)
    }, 2)
    @Test fun largeProfilesRemainReachable() = checkPage(large, "profiles:0", context.graph.repository.profiles.value.take(3).map {
        context.getString(io.github.willzy12h.nocturne.R.string.feature_start_profile, it.name)
    }, 3)
    @Test fun compactOpenAppIsExplicit() = checkPage(compact, "app:0", listOf("Open app"), 1)
    @Test fun mediumOpenAppIsExplicit() = checkPage(medium, "app:0", listOf("Open app"), 2)
    @Test fun largeOpenAppIsExplicit() = checkPage(large, "app:0", listOf("Open app"), 3)
    @Test fun compactCanReachWarm() = checkPage(compact, "quick:1", listOf("Warm"), 1)
    @Test fun compactCanReachStopTracking() = checkPage(compact, "device:1", listOf("Stop tracking"), 1)
    @Test fun boostDescriptionNamesBothPartsOfItsRun() {
        val boost = descendants(rendered(medium, "night:0")).filterIsInstance<Button>().first { it.text.toString() == "Turbo→Heat" }
        assertEquals("Turbo heat for 10 minutes, then Heat for 5 minutes", boost.contentDescription?.toString())
    }

    @Test fun everyCompactBoostDurationNamesItsModesAndFitsAtTwoHundredPercent() = runBlocking {
        val saved = android.content.res.Configuration(context.resources.configuration)
        try {
            context.resources.updateConfiguration(android.content.res.Configuration(saved).apply { fontScale = 2f }, context.resources.displayMetrics)
            assertEquals(2f, context.resources.configuration.fontScale, 0f)
            for (minutes in listOf(10, 15, 20, 25, 30)) {
                context.graph.repository.updateSettings { it.copy(boostMinutes = minutes) }
                for (widget in listOf(QuickControlsWidget(), SingleActionWidget())) {
                    val single = widget is SingleActionWidget
                    val preferences = if (single) preferencesOf(SingleActionWidget.KEY_COMMAND to "BOOST",
                        SingleActionWidget.KEY_LABEL to "Stale saved label")
                        else preferencesOf(QuickControlsWidget.KEY_PAGE to "night:1")
                    val root = rendered(compact, "night:1", widget, preferences) as ViewGroup
                    val buttons = descendants(root).filterIsInstance<Button>()
                    val action = buttons.single { it.text.toString() !in listOf("‹", "›", "Open app") }
                    val expected = if (minutes == 10) "Turbo 10m" else "Turbo→Heat"
                    assertEquals("${widget.javaClass.simpleName} must visibly name every actual mode", expected, action.text.toString())
                    assertEquals(if (minutes == 10) "Turbo heat for 10 minutes"
                        else "Turbo heat for 10 minutes, then Heat for ${minutes - 10} minutes", action.contentDescription?.toString())
                    assertEquals("Keep the fixed compact control budget", if (single) 2 else 3, buttons.size)
                    val bounds = android.graphics.Rect(0, 0, root.width, root.height)
                    val touchBounds = buttons.map { button ->
                        val density = context.resources.displayMetrics.density
                        assertTrue("${button.text} needs a 56dp-wide touch target", button.width / density >= 56f)
                        assertTrue("${button.text} needs a 56dp-high touch target", button.height / density >= 56f)
                        // API 34 applies a nonlinear font curve; dividing pixels by scaledDensity is not an SP conversion.
                        val minimumPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                            16f, button.resources.displayMetrics)
                        if (minutes == 10 && !single) println("Widget 200% type: ${button.text}, actualPx=${button.textSize}, " +
                            "required16spPx=$minimumPx, scaledDensity=${button.resources.displayMetrics.scaledDensity}")
                        assertTrue("${button.text} must retain 16sp type: actualPx=${button.textSize}, requiredPx=$minimumPx",
                            button.textSize + .01f >= minimumPx)
                        val rect = android.graphics.Rect()
                        button.getDrawingRect(rect)
                        root.offsetDescendantRectToMyCoords(button, rect)
                        assertTrue("${button.text} must be wholly inside the widget: $rect", bounds.contains(rect))
                        val layout = requireNotNull(button.layout) { "A native button must have actual laid-out text" }
                        assertEquals("Sentence-case labels must survive native rendering", button.text.toString(), layout.text.toString())
                        assertTrue("${button.text} must fit vertically", layout.height <= button.height - button.totalPaddingTop - button.totalPaddingBottom)
                        assertEquals("Every label character must be laid out", layout.text.length, layout.getLineEnd(layout.lineCount - 1))
                        for (line in 0 until layout.lineCount) {
                            assertEquals("${button.text} must not ellipsise at 200%", 0, layout.getEllipsisCount(line))
                            assertTrue("${button.text} must fit horizontally", layout.getLineWidth(line) <=
                                button.width - button.totalPaddingLeft - button.totalPaddingRight + 1f)
                        }
                        rect
                    }
                    touchBounds.forEachIndexed { index, rect -> touchBounds.drop(index + 1).forEach {
                        assertFalse("Compact controls must not overlap", android.graphics.Rect.intersects(rect, it))
                    } }
                }
            }
        } finally { context.resources.updateConfiguration(saved, context.resources.displayMetrics) }
    }

    private fun checkSingle(size: DpSize) {
        val root = rendered(size, "", SingleActionWidget(), preferencesOf(
            SingleActionWidget.KEY_COMMAND to "COOL", SingleActionWidget.KEY_LABEL to "Cool")) as ViewGroup
        val buttons = descendants(root).filterIsInstance<Button>()
        assertEquals(listOf("Open app", "Cool"), buttons.map { it.text.toString() })
        buttons.forEach { button ->
            assertTrue(button.height / context.resources.displayMetrics.density >= 56f)
            assertTrue(button.width / context.resources.displayMetrics.density >= 56f)
            assertTrue(button.textSize / context.resources.displayMetrics.scaledDensity >= 16f)
            val rect = android.graphics.Rect()
            button.getDrawingRect(rect); root.offsetDescendantRectToMyCoords(button, rect)
            assertTrue("${button.text} is not clipped: $rect", android.graphics.Rect(0, 0, root.width, root.height).contains(rect))
        }
    }
    @Test fun compactSingleActionRetainsReadableControlAndExplicitOpenApp() = checkSingle(compact)
    @Test fun mediumSingleActionRetainsReadableControlAndExplicitOpenApp() = checkSingle(medium)
    @Test fun largeSingleActionRetainsReadableControlAndExplicitOpenApp() = checkSingle(large)

    @Test fun everySizeAndPageKeepsControlsAndTextInsideItsBoundsAtTwoHundredPercentFont() {
        val saved = android.content.res.Configuration(context.resources.configuration)
        try {
            context.resources.updateConfiguration(android.content.res.Configuration(saved).apply { fontScale = 2f }, context.resources.displayMetrics)
            assertEquals(2f, context.resources.configuration.fontScale, 0f)
            for ((size, maximum) in listOf(compact to 1, medium to 2, large to 3)) {
                for (page in widgetPages(context, 1).map { it.key }) {
                    val root = rendered(size, page) as ViewGroup
                    val views = descendants(root)
                    val buttons = views.filterIsInstance<Button>()
                    val controls = buttons.filterNot { it.text.toString() in listOf("‹", "›") }
                    assertTrue("At least one action remains on $page", controls.isNotEmpty())
                    assertTrue("The $size action budget still applies", controls.size <= maximum)
                    val bounds = android.graphics.Rect(0, 0, root.width, root.height)
                    val clickable = mutableListOf<android.graphics.Rect>()
                    views.filterIsInstance<TextView>().filter { it.text.isNotBlank() }.forEach { label ->
                        val rect = android.graphics.Rect()
                        label.getDrawingRect(rect); root.offsetDescendantRectToMyCoords(label, rect)
                        assertTrue("At 200%, ${label.text} must fit in $size on $page: $rect", bounds.contains(rect))
                        val textLayout = label.layout
                        if (textLayout != null) {
                            assertTrue("At 200%, ${label.text} must fit vertically: lines=${textLayout.height}, height=${label.height}, padding=${label.totalPaddingTop + label.totalPaddingBottom}, width=${label.width}, textSize=${label.textSize}, maxLines=${label.maxLines}, lineCount=${textLayout.lineCount}, transform=${label.transformationMethod}",
                                textLayout.height <= label.height - label.totalPaddingTop - label.totalPaddingBottom)
                            if (!page.startsWith("profiles:")) for (line in 0 until textLayout.lineCount)
                                assertEquals("The fixed label ${label.text} must not be ellipsised at 200%", 0, textLayout.getEllipsisCount(line))
                        }
                        if (label is Button) {
                            assertTrue(label.height / context.resources.displayMetrics.density >= 56f)
                            assertTrue(label.width / context.resources.displayMetrics.density >= 56f)
                            clickable += rect
                        }
                    }
                    clickable.forEachIndexed { index, rect -> clickable.drop(index + 1).forEach {
                        assertFalse("200% controls must not overlap", android.graphics.Rect.intersects(rect, it))
                    } }
                }
            }
        } finally { context.resources.updateConfiguration(saved, context.resources.displayMetrics) }
    }

    @Test fun aConfirmedPlanAndSingleActionFitAtNormalAndTwoHundredPercentText() = runBlocking {
        val saved = android.content.res.Configuration(context.resources.configuration)
        val graph = context.graph
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.link.connect()
        val start = graph.now()
        val profile = graph.repository.profiles.value.first()
        val plan = io.github.willzy12h.nocturne.core.plan.PlanCompiler.compile(profile, start, start.plusHours(8))
        val night = io.github.willzy12h.nocturne.core.night.ActiveNight("layout", profile.id, start.toString(), plan.wake.toString(),
            false, 2, plan.wakeUpStart.toString(), plan.end.toString(), deviceId = "demo", profile = profile,
            applied = io.github.willzy12h.nocturne.core.night.AppliedProgram.from(plan),
            confidence = io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED)
        graph.repository.setActiveNight(night)
        try {
            for (scale in listOf(1f, 2f)) {
                context.resources.updateConfiguration(android.content.res.Configuration(saved).apply { fontScale = scale }, context.resources.displayMetrics)
                for (widget in listOf(QuickControlsWidget(), SingleActionWidget())) for (size in listOf(compact, medium, large)) {
                    val prefs = if (widget is SingleActionWidget) preferencesOf(SingleActionWidget.KEY_COMMAND to "COOL", SingleActionWidget.KEY_LABEL to "Cool")
                        else preferencesOf(QuickControlsWidget.KEY_PAGE to "night:0")
                    val root = rendered(size, "night:0", widget, prefs) as ViewGroup
                    val bounds = android.graphics.Rect(0, 0, root.width, root.height)
                    descendants(root).filterIsInstance<TextView>().filter { it.text.isNotBlank() }.forEach { label ->
                        val rect = android.graphics.Rect()
                        label.getDrawingRect(rect); root.offsetDescendantRectToMyCoords(label, rect)
                        assertTrue("${widget.javaClass.simpleName} $scale× $size ${label.text} must fit: $rect", bounds.contains(rect))
                        label.layout?.let { layout ->
                            assertTrue("${label.text} must not be vertically clipped at $scale×: ${layout.height} in ${label.height}",
                                layout.height <= label.height - label.totalPaddingTop - label.totalPaddingBottom)
                            for (line in 0 until layout.lineCount)
                                assertEquals("${label.text} must not be ellipsised at $scale× in $size", 0, layout.getEllipsisCount(line))
                        }
                    }
                }
            }
        } finally {
            context.resources.updateConfiguration(saved, context.resources.displayMetrics)
            graph.repository.setActiveNight(null)
        }
    }
}

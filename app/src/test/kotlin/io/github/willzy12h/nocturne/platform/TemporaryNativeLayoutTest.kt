package io.github.willzy12h.nocturne.platform

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import io.github.willzy12h.nocturne.widget.QuickControlsReceiver
import io.github.willzy12h.nocturne.widget.QuickControlsWidget
import io.github.willzy12h.nocturne.widget.SingleActionReceiver
import io.github.willzy12h.nocturne.widget.SingleActionWidget
import io.github.willzy12h.nocturne.widget.WidgetConfirmationActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Actual shortcut and rendered widget windows must retain usable controls at 320dp and 200% text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemporaryNativeLayoutTest : PlatformGraphFixture() {
    private lateinit var app: App
    private lateinit var graph: Graph
    private var previousFontScale = 1f
    private var writes = 0

    @Before fun setup() = runBlocking {
        previousFontScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(2f)
        app = RuntimeEnvironment.getApplication() as App
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = fresh { Graph(app, link) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true, autoUpdateCheck = false, themeMode = ThemeMode.DARK) }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 20
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
        writes = 0
        Unit
    }

    @After fun restoreConfiguration() { RuntimeEnvironment.setFontScale(previousFontScale) }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun assertScreen(activity: Activity, dialog: AlertDialog) {
        assertEquals(320, activity.resources.configuration.screenWidthDp)
        assertEquals(2f, activity.resources.configuration.fontScale, .001f)
        val root = dialog.window!!.decorView
        settle()
        assertTrue("The actual shown dialog window must be laid out", root.isShown && root.width > 0 && root.height > 0)
        assertTrue("Do not give a dialog more width than the actual 320dp phone", root.width <= activity.resources.displayMetrics.widthPixels)
        assertTrue("Do not replace window layout with an artificially tall test surface", root.height <= activity.resources.displayMetrics.heightPixels)
    }

    private fun assertTarget(view: View, minimumDp: Int, name: String) {
        val before = geometry(view)
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        settle()
        val density = view.resources.displayMetrics.density
        assertTrue("$name width ${view.width / density}dp < $minimumDp", view.width + 1f >= minimumDp * density)
        assertTrue("$name height ${view.height / density}dp < $minimumDp", view.height + 1f >= minimumDp * density)
        val visible = Rect()
        assertTrue("$name must be visible; before=$before; after=${geometry(view)}", view.getGlobalVisibleRect(visible))
        val at = IntArray(2).also(view::getLocationInWindow)
        val full = Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
        assertContains("$name touch target is clipped", visible, full)
    }

    /** Check intrinsic layout, then reveal every line using the production scroll containers. */
    private fun assertCompleteText(label: TextView) {
        val name = label.text.toString()
        assertEquals("$name must inherit 200% text", 2f, label.resources.configuration.fontScale, .001f)
        val layout = requireNotNull(label.layout) { "$name has no native text layout" }
        assertTrue("$name has no rendered lines", layout.lineCount > 0)
        assertEquals("$name must retain every character", layout.text.length, layout.getLineEnd(layout.lineCount - 1))
        val contentWidth = label.width - label.totalPaddingLeft - label.totalPaddingRight
        assertTrue("$name loses lines vertically", layout.height <= label.height - label.totalPaddingTop - label.totalPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals("$name ellipsises line $line", 0, layout.getEllipsisCount(line))
            assertTrue("$name clips line $line horizontally", layout.getLineWidth(line) <= contentWidth + 1f)
            val local = Rect(
                label.totalPaddingLeft + floor(layout.getLineLeft(line).toDouble()).toInt(),
                label.totalPaddingTop + layout.getLineTop(line),
                label.totalPaddingLeft + ceil(layout.getLineRight(line).toDouble()).toInt(),
                label.totalPaddingTop + layout.getLineBottom(line),
            )
            label.requestRectangleOnScreen(local, true)
            settle()
            val visible = Rect()
            assertTrue("$name line $line must be reachable by scrolling", label.getGlobalVisibleRect(visible))
            val at = IntArray(2).also(label::getLocationInWindow)
            val global = Rect(local).apply { offset(at[0] - label.scrollX, at[1] - label.scrollY) }
            assertContains("$name line $line is clipped even after scrolling", visible, global)
        }
    }

    private fun assertContains(message: String, outer: Rect, inner: Rect) {
        assertTrue("$message: visible=$outer, required=$inner", inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
            inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1)
    }

    private fun geometry(view: View): String {
        val window = IntArray(2).also(view::getLocationInWindow)
        val screen = IntArray(2).also(view::getLocationOnScreen)
        return "${view.javaClass.simpleName} ${view.width}x${view.height}, shown=${view.isShown}, " +
            "window=${window.contentToString()}, screen=${screen.contentToString()}, scroll=${view.scrollY}, " +
            "parent=${view.parent?.javaClass?.simpleName}"
    }

    private fun inspect(dialog: AlertDialog, minimumDp: Int) {
        val choices = requireNotNull(dialog.listView) { "The actual entry must expose Temporary and Stop choices" }
        assertEquals(2, choices.adapter.count)
        for (index in 0 until choices.adapter.count) {
            choices.setSelection(index)
            settle()
            val row = requireNotNull(choices.getChildAt(index - choices.firstVisiblePosition))
            assertTarget(row, minimumDp, choices.adapter.getItem(index).toString())
            descendants(row).filterIsInstance<TextView>().filter { it.text.isNotBlank() }.forEach(::assertCompleteText)
        }
        dialog.window!!.decorView.findViewWithTag<SeekBar>("temporary-duration")?.takeIf { it.visibility == View.VISIBLE }?.let {
            assertTarget(it, minimumDp, "Temporary duration")
            assertEquals(app.getString(R.string.slider_minutes), it.contentDescription.toString())
        }
        // List rows are inspected above because selection scrolling can recycle their Views.
        descendants(dialog.window!!.decorView).filterIsInstance<TextView>().filter {
            it.isShown && it.text.isNotBlank() && generateSequence(it.parent) { parent -> parent.parent }.none { parent -> parent === choices }
        }.forEach(::assertCompleteText)
        val buttons = listOf(dialog.getButton(AlertDialog.BUTTON_POSITIVE), dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
        val rectangles = buttons.map { button ->
            assertTrue(button.isEnabled)
            assertTarget(button, minimumDp, button.text.toString())
            if (minimumDp == 56) {
                val minimumPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, button.resources.displayMetrics)
                assertTrue("Widget confirmation keeps at least 16sp", button.textSize + .01f >= minimumPx)
            }
            val at = IntArray(2).also(button::getLocationInWindow)
            Rect(at[0], at[1], at[0] + button.width, at[1] + button.height)
        }
        assertFalse("The actual dialog buttons must not overlap", Rect.intersects(rectangles[0], rectangles[1]))
    }

    private fun verify(activity: ActivityController<out Activity>, minimumDp: Int) {
        val before = graph.repository.activeNight.value
        val physical = graph.link.status.value
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertScreen(activity.get(), dialog)
            assertEquals(0, dialog.listView.checkedItemPosition)
            assertNotNull(dialog.window!!.decorView.findViewWithTag<SeekBar>("temporary-duration"))
            assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == app.getString(R.string.temporary_schedule) })
            inspect(dialog, minimumDp)
            dialog.listView.performItemClick(dialog.listView.getChildAt(1 - dialog.listView.firstVisiblePosition), 1, 1)
            settle()
            assertEquals(app.getString(R.string.guard_confirm), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            inspect(dialog, minimumDp)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(0, writes)
            assertEquals(before, graph.repository.activeNight.value)
            assertEquals(physical, graph.link.status.value)
            assertNull("Cancellation must not hand a request to the service", shadowOf(app).nextStartedService)
            assertNull("The small confirmation must not open MainActivity", shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun shortcutTemporaryChoicesAndBothActionsFitAtTwoHundredPercent() {
        verify(Robolectric.buildActivity(ShortcutActivity::class.java,
            Intent(app, ShortcutActivity::class.java).putExtra("command", Command.WARM.name)).setup(), 48)
    }

    private suspend fun widget(single: Boolean) {
        val provider = if (single) SingleActionReceiver::class.java else QuickControlsReceiver::class.java
        val info = AppWidgetProviderInfo().apply { this.provider = ComponentName(app, provider) }
        val widgetId = if (single) 107 else 106
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(widgetId, info)
        val id: GlanceId = GlanceAppWidgetManager(app).getGlanceIdBy(widgetId)
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            graph.repository.updateSettings { it.copy(themeMode = theme) }
            val rendered = if (single) SingleActionWidget().compose(app, id = id, size = DpSize(340.dp, 240.dp),
                state = preferencesOf(SingleActionWidget.KEY_COMMAND to Command.WARM.name,
                    SingleActionWidget.KEY_LABEL to app.getString(R.string.feature_warm)))
            else QuickControlsWidget().compose(app, id = id, size = DpSize(340.dp, 240.dp),
                // This 200% fixture shows one Quick action per page: Cool first, then Warm.
                state = preferencesOf(QuickControlsWidget.KEY_PAGE to "quick:1"))
            val button = descendants(rendered.apply(app, FrameLayout(app))).filterIsInstance<Button>()
                .single { it.text.toString() == app.getString(R.string.feature_warm) }
            button.performClick()
            val intent = requireNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(WidgetConfirmationActivity::class.java.name, intent.component!!.className)
            verify(Robolectric.buildActivity(WidgetConfirmationActivity::class.java, intent).setup(), 56)
        }
    }

    @Test fun quickWidgetTemporaryChoicesAndBothActionsFitAtTwoHundredPercent() = runBlocking { widget(single = false) }
    @Test fun singleWidgetTemporaryChoicesAndBothActionsFitAtTwoHundredPercent() = runBlocking { widget(single = true) }

    /** Independent of geometry: an earlier clipping failure must not hide low-contrast native button text. */
    @Test fun enabledNativeTextMeetsContrastOnShortcutAndBothWidgetPalettes() = runBlocking {
        val failures = mutableListOf<String>()
        inspectContrast(Robolectric.buildActivity(ShortcutActivity::class.java,
            Intent(app, ShortcutActivity::class.java).putExtra("command", Command.WARM.name)).setup(),
            "Shortcut", failures)
        for (single in listOf(false, true)) {
            val provider = if (single) SingleActionReceiver::class.java else QuickControlsReceiver::class.java
            val info = AppWidgetProviderInfo().apply { this.provider = ComponentName(app, provider) }
            val widgetId = if (single) 109 else 108
            shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(widgetId, info)
            val id = GlanceAppWidgetManager(app).getGlanceIdBy(widgetId)
            for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                graph.repository.updateSettings { it.copy(themeMode = theme) }
                val rendered = if (single) SingleActionWidget().compose(app, id = id, size = DpSize(340.dp, 240.dp),
                    state = preferencesOf(SingleActionWidget.KEY_COMMAND to Command.WARM.name,
                        SingleActionWidget.KEY_LABEL to app.getString(R.string.feature_warm)))
                else QuickControlsWidget().compose(app, id = id, size = DpSize(340.dp, 240.dp),
                    state = preferencesOf(QuickControlsWidget.KEY_PAGE to "quick:1"))
                descendants(rendered.apply(app, FrameLayout(app))).filterIsInstance<Button>()
                    .single { it.text.toString() == app.getString(R.string.feature_warm) }.performClick()
                val intent = requireNotNull(shadowOf(app).nextStartedActivity)
                assertEquals(WidgetConfirmationActivity::class.java.name, intent.component!!.className)
                inspectContrast(Robolectric.buildActivity(WidgetConfirmationActivity::class.java, intent).setup(),
                    "${if (single) "Single" else "Quick"}/$theme", failures)
            }
        }
        assertTrue("Native enabled text needs at least 4.5:1 contrast:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun inspectContrast(activity: ActivityController<out Activity>, route: String, failures: MutableList<String>) {
        val before = graph.repository.activeNight.value
        val physical = graph.link.status.value
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val value = TypedValue()
            assertTrue("$route needs a resolved native dialog surface",
                dialog.context.theme.resolveAttribute(android.R.attr.colorBackgroundFloating, value, true))
            val floating = if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data
                else dialog.context.getColor(value.resourceId)
            assertEquals("$route dialog surface must be opaque", 255, Color.alpha(floating))
            for (choice in 0..1) {
                dialog.listView.performItemClick(dialog.listView.getChildAt(choice - dialog.listView.firstVisiblePosition),
                    choice, choice.toLong())
                settle()
                val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                    .filter { it.isShown && it.isEnabled && it.text.isNotBlank() }
                assertTrue("$route positive button must be inspected", labels.contains(dialog.getButton(AlertDialog.BUTTON_POSITIVE)))
                assertTrue("$route Cancel button must be inspected", labels.contains(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)))
                for (label in labels) {
                    val chain = generateSequence<View>(label) { it.parent as? View }.toList().asReversed()
                    val center = IntArray(2).also(label::getLocationInWindow).apply {
                        this[0] += label.width / 2; this[1] += label.height / 2
                    }
                    var background = floating
                    for (view in chain) backgroundAt(view, center)?.let { color ->
                        background = ColorUtils.compositeColors(color, background) }
                    val alpha = chain.fold(1f) { result, view -> result * view.alpha }
                    val raw = label.currentTextColor
                    val foreground = ColorUtils.setAlphaComponent(raw, (Color.alpha(raw) * alpha).roundToInt().coerceIn(0, 255))
                    val composite = ColorUtils.compositeColors(foreground, background)
                    val ratio = ColorUtils.calculateContrast(composite, background)
                    val evidence = "$route choice=$choice text=${label.text.toString().replace('\n', ' ')} " +
                        "raw=${colorHex(raw)} composite=${colorHex(composite)} surface=${colorHex(background)} " +
                        "contrast=${String.format(Locale.ROOT, "%.3f", ratio)} " +
                        "backgrounds=${chain.mapNotNull { it.background?.javaClass?.simpleName }}"
                    println(evidence)
                    if (ratio < 4.5) failures += evidence
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(0, writes)
            assertEquals(before, graph.repository.activeNight.value)
            assertEquals(physical, graph.link.status.value)
            assertNull(shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    /** Include actual nine-patch/bitmap button backgrounds, not only flat theme colors. */
    private fun backgroundAt(view: View, pointInWindow: IntArray): Int? {
        val drawable = view.background ?: return null
        val at = IntArray(2).also(view::getLocationInWindow)
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(pixel)
            canvas.translate((at[0] - pointInWindow[0]).toFloat(), (at[1] - pointInWindow[1]).toFloat())
            drawable.draw(canvas)
            return pixel.getPixel(0, 0)
        } finally { pixel.recycle() }
    }

    private fun colorHex(color: Int): String = String.format(Locale.ROOT, "#%08X", color)
}

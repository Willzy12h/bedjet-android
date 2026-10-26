package io.github.willzy12h.nocturne.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.willzy12h.nocturne.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetProviderTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val android = "http://schemas.android.com/apk/res/android"
    @Test fun quickProviderAdvertisesAResizableTwoByOneWithAPreviewAndNoPeriodicWakeup() {
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.quick_controls_widget).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            assertEquals("2", xml.getAttributeValue(android, "targetCellWidth"))
            assertEquals("1", xml.getAttributeValue(android, "targetCellHeight"))
            assertEquals(0, xml.getAttributeIntValue(android, "updatePeriodMillis", -1))
            assertTrue("Real preview layout", xml.getAttributeResourceValue(android, "previewLayout", 0) != 0)
            assertEquals(3, xml.getAttributeIntValue(android, "resizeMode", 0))
        }
    }
    @Test fun singleProviderHasAPreviewAndNoPeriodicWakeup() {
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.single_action_widget).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            assertEquals(0, xml.getAttributeIntValue(android, "updatePeriodMillis", -1))
            assertTrue("Real preview layout", xml.getAttributeResourceValue(android, "previewLayout", 0) != 0)
        }
    }

    @Test @Config(qualifiers = "notnight") fun actualPreviewTextKeepsContrastInDayConfiguration() = previewContrast("day")
    @Test @Config(qualifiers = "night") fun actualPreviewTextKeepsContrastInNightConfiguration() = previewContrast("night")

    private fun previewContrast(configuration: String) {
        val context = RuntimeEnvironment.getApplication()
        val failures = mutableListOf<String>()
        for (layout in listOf(R.layout.quick_controls_widget_preview, R.layout.single_action_widget_preview)) {
            val name = context.resources.getResourceEntryName(layout)
            val root = LayoutInflater.from(context).inflate(layout, FrameLayout(context), false) as ViewGroup
            val density = context.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((200 * density).roundToInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((128 * density).roundToInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val capture = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(capture))
                val directory = File("build/reports/widget-preview-contrast").apply { mkdirs() }
                File(directory, "$name-$configuration.png").outputStream().use {
                    assertTrue(capture.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { capture.recycle() }
            val labels = descendants(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
            assertTrue("The actual preview must contain its visible labels", labels.isNotEmpty())
            for (label in labels) {
                val bounds = Rect(0, 0, label.width, label.height).also { root.offsetDescendantRectToMyCoords(label, it) }
                val layers = generateSequence<View>(label) { it.parent as? View }.toList().asReversed()
                var background = Color.TRANSPARENT
                for (view in layers) {
                    val drawable = view.background ?: continue
                    val at = Rect(0, 0, view.width, view.height)
                    if (view !== root) root.offsetDescendantRectToMyCoords(view, at)
                    val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(pixel)
                        canvas.translate((at.left - bounds.centerX()).toFloat(), (at.top - bounds.centerY()).toFloat())
                        drawable.draw(canvas)
                        background = ColorUtils.compositeColors(pixel.getPixel(0, 0), background)
                    } finally { pixel.recycle() }
                }
                assertEquals("$name/$configuration must have an actual opaque preview surface", 255, Color.alpha(background))
                val alpha = layers.fold(1f) { value, view -> value * view.alpha }
                val foreground = ColorUtils.setAlphaComponent(label.currentTextColor,
                    (Color.alpha(label.currentTextColor) * alpha).roundToInt().coerceIn(0, 255))
                val visible = ColorUtils.compositeColors(foreground, background)
                val ratio = ColorUtils.calculateContrast(visible, background)
                val evidence = "$name/$configuration ${label.text}: text=${String.format(Locale.ROOT, "#%08X", visible)} " +
                    "surface=${String.format(Locale.ROOT, "#%08X", background)} contrast=${String.format(Locale.ROOT, "%.3f", ratio)}"
                println(evidence)
                if (ratio < 4.5) failures += evidence
            }
        }
        assertTrue("Actual widget preview text needs 4.5:1 contrast:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}

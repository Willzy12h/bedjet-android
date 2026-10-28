package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.core.graphics.ColorUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.platform.PlatformGraphFixture
import io.github.willzy12h.nocturne.ui.tokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P7WidgetStripeTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    private data class Example(val command: String, val label: String, val page: String, val mode: Mode)

    @Test fun navigationAndOpenAppTextHasReadableContrast() = runBlocking {
        val size = DpSize(280.dp, 180.dp)
        for (palette in listOf(Palette.FOREST, Palette.OVERCAST)) {
            context.graph.repository.updateSettings { it.copy(demoMode = true,
                themeMode = if (palette.dark) ThemeMode.DARK else ThemeMode.LIGHT,
                nightPalette = Palette.FOREST, dayPalette = Palette.OVERCAST) }
            for (single in listOf(false, true)) {
                val widget: GlanceAppWidget = if (single) SingleActionWidget() else QuickControlsWidget()
                val state = if (single) preferencesOf(SingleActionWidget.KEY_COMMAND to "WARM", SingleActionWidget.KEY_LABEL to "Warm")
                    else preferencesOf(QuickControlsWidget.KEY_PAGE to "app:0")
                val root = widget.compose(context, size = size, state = state).apply(context, FrameLayout(context)) as ViewGroup
                val density = context.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val labels = if (single) listOf("Open app") else listOf("‹", "›", "Open app")
                for (label in labels) {
                    val button = descendants(root).filterIsInstance<Button>().single { it.text.toString() == label }
                    assertTrue("$label target", button.width / density >= 56 && button.height / density >= 56)
                    if (label != "Open app") assertTrue("$label description", !button.contentDescription.isNullOrBlank())
                    val rect = Rect().also { button.getDrawingRect(it); root.offsetDescendantRectToMyCoords(button, it) }
                    // The most common opaque pixel in the central half is the flat button fill, avoiding rounded corners and glyphs.
                    val counts = mutableMapOf<Int, Int>()
                    for (y in (rect.top + rect.height() / 4) until (rect.bottom - rect.height() / 4))
                        for (x in (rect.left + rect.width() / 4) until (rect.right - rect.width() / 4)) {
                            val color = bitmap.getPixel(x, y)
                            if (android.graphics.Color.alpha(color) == 255) counts[color] = (counts[color] ?: 0) + 1
                        }
                    val fill = counts.maxByOrNull { it.value }!!.key
                    assertTrue("$palette $label contrast must reach 4.5:1", ColorUtils.calculateContrast(button.currentTextColor, fill) >= 4.5)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun bothWidgetsRenderPaletteModeStripesAndKeepLabelledTargets() = runBlocking {
        val size = DpSize(280.dp, 180.dp)
        for (palette in listOf(Palette.MIDNIGHT_INK, Palette.FOREST, Palette.OVERCAST)) {
            context.graph.repository.updateSettings { it.copy(demoMode = true,
                themeMode = if (palette.dark) ThemeMode.DARK else ThemeMode.LIGHT,
                nightPalette = if (palette.dark) palette else Palette.MIDNIGHT_INK, dayPalette = Palette.OVERCAST) }
            for ((command, label, page, typedMode) in listOf(
                Example("WARM", "Warm", "quick:0", Mode.HEAT),
                Example("TURBO", "Turbo 10m", "night:2", Mode.TURBO),
                Example("OFF", "Off", "device:0", Mode.STANDBY))) {
                val temperature = if (typedMode == Mode.STANDBY) 0 else QuickDefaults.resolve(context.graph.repository.settings.value, typedMode).temperature
                val expected = palette.tokens.stepColor(typedMode, temperature).toArgb()
                for (single in listOf(false, true)) {
                    val widget: GlanceAppWidget = if (single) SingleActionWidget() else QuickControlsWidget()
                    val state = if (single) preferencesOf(SingleActionWidget.KEY_COMMAND to command,
                        SingleActionWidget.KEY_LABEL to label)
                    else preferencesOf(QuickControlsWidget.KEY_PAGE to page)
                    val root = widget.compose(context, size = size, state = state).apply(context, FrameLayout(context))
                    val density = context.resources.displayMetrics.density
                    root.measure(View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val action = descendants(root).filterIsInstance<Button>().first { it.text.toString() == label }
                    assertTrue("$label must be at least 48dp", action.width / density >= 48 && action.height / density >= 48)
                    assertTrue("$label must retain its action description", !action.contentDescription.isNullOrBlank())
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    val stripe = palette.tokens.stepColor(typedMode, temperature)
                    val tint = palette.tokens.tint(stripe).compositeOver(
                        palette.tokens.surface.compositeOver(palette.tokens.ground)).toArgb()
                    val width = (4 * density).toInt()
                    var foundStripeAndTint = false
                    for (y in 0 until bitmap.height) {
                        var x = 0
                        while (x < bitmap.width) {
                            if (bitmap.getPixel(x, y) != expected) { x++; continue }
                            val start = x
                            while (x < bitmap.width && bitmap.getPixel(x, y) == expected) x++
                            if (x - start == width && x < bitmap.width && bitmap.getPixel(x, y) == tint) {
                                foundStripeAndTint = true
                                break
                            }
                        }
                        if (foundStripeAndTint) break
                    }
                    bitmap.recycle()
                    assertTrue("$palette $command ${widget.javaClass.simpleName} needs a 4dp stripe beside its tinted row", foundStripeAndTint)
                }
            }
        }
    }
}

package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.willzy12h.nocturne.night.NightActions
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import java.time.Duration
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P6NativePaletteTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    @After fun dismiss() { ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss() }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun shortcutConfirmationUsesSelectedPaletteAndCancelDoesNotSend() {
        for (palette in listOf(Palette.FOREST, Palette.OVERCAST)) {
            ui.compose.runOnUiThread { runBlocking {
                ui.graph.repository.updateSettings { it.copy(themeMode = if (palette.dark) ThemeMode.DARK else ThemeMode.LIGHT,
                    nightPalette = Palette.FOREST, dayPalette = Palette.OVERCAST) }
            } }
            if (!palette.dark) {
                val prefs = ui.compose.activity.getSharedPreferences("appearance-consent", android.content.Context.MODE_PRIVATE)
                prefs.edit().putBoolean("lastDark", false).commit()
                approveLightSelection(ui.compose.activity, ui.graph.repository.settings.value, ui.graph.now())
            }
            val controller = Robolectric.buildActivity(ShortcutActivity::class.java,
                Intent(ui.compose.activity, ShortcutActivity::class.java).putExtra("command", NightActions.Command.WARM.name)).setup()
            try {
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                val root = dialog.window!!.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                ui.compose.runOnUiThread { root.draw(Canvas(bitmap)) }
                var matches = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                    if (bitmap.getPixel(x, y) == palette.tokens.sheet.toArgb()) matches++
                bitmap.recycle()
                assertTrue("$palette confirmation must render its sheet token", matches > 20)
                val body = views(root).filterIsInstance<TextView>().first { it.id == android.R.id.message }
                assertEquals(palette.tokens.ink.toArgb(), body.currentTextColor)
                ui.compose.runOnUiThread { dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() }
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(controller.get().isFinishing)
                assertNull(shadowOf(ui.compose.activity.application).nextStartedService)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun timePickerUsesSelectedSheetAndKeepsItsCallback() {
        for (palette in listOf(Palette.MIDNIGHT_INK, Palette.FOREST, Palette.OVERCAST)) {
            ui.compose.runOnUiThread { runBlocking {
                ui.graph.repository.updateSettings { it.copy(themeMode = if (palette.dark) ThemeMode.DARK else ThemeMode.LIGHT,
                    nightPalette = if (palette.dark) palette else Palette.MIDNIGHT_INK, dayPalette = Palette.OVERCAST) }
            } }
            if (!palette.dark) approveLightSelection(ui.compose.activity, ui.graph.repository.settings.value, ui.graph.now())
            var result = -1
            val settings = ui.graph.repository.settings.value
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(settings, ui.graph.now(), paletteOverride = palette) {
                    TimeRow("Wake", 390) { result = it }
                }
            } }
            ui.compose.onNodeWithText("06:30").performClick()
            val dialog = ShadowDialog.getLatestDialog() as TimePickerDialog
            assertTrue(dialog.isShowing)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val view = dialog.window!!.decorView
            assertTrue("The shown picker must be laid out", view.isShown && view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val expected = palette.tokens.sheet.toArgb()
            var matches = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                if (bitmap.getPixel(x, y) == expected) matches++
            bitmap.recycle()
            assertTrue("$palette picker must render its sheet token", matches > 20)
            ui.compose.runOnUiThread { dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
            assertEquals(390, result)
        }
    }
}

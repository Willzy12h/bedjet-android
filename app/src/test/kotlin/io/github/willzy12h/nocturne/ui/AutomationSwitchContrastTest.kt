package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.core.store.Palette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Enabled on/off states of the real automation switch on its actual Settings card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class AutomationSwitchContrastTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val palette = mutableStateOf(Palette.OVERCAST)

    @Test fun uncheckedAutomationThumbRemainsVisibleInEveryPalette() = inspect(false)
    @Test fun checkedAutomationThumbRemainsVisibleInEveryPalette() = inspect(true)

    private fun inspect(checked: Boolean) {
        val profile = ui.graph.repository.profiles.value.first()
        ui.compose.runOnUiThread { runBlocking {
            ui.graph.repository.updateSettings { current ->
                current.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF",
                    platform = current.platform.copy(autoStart = AutoStartSettings(
                        profileId = profile.id, deviceId = "ble:AA:BB:CC:DD:EE:FF", onlyIfCharging = checked,
                    )))
            }
        } }
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) {
                    SettingsScreen(ui.model, Modifier, alarmsRequest = 1)
                }
            }
        } }
        val failures = mutableListOf<String>()
        for (choice in Palette.entries) {
            ui.compose.runOnIdle { palette.value = choice }
            val row = ui.compose.onNodeWithContentDescription(ui.s(R.string.auto_start_only_charging))
                .performScrollTo().assertIsEnabled()
            if (checked) row.assertIsOn() else row.assertIsOff()
            ui.compose.waitForIdle()
            ui.capture("automation-switch-$checked-${choice.name}")
            val bounds = row.fetchSemanticsNode().boundsInRoot
            val density = ui.compose.density.density
            val view = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                // Native 52dp trailing track: thumb centre and unobstructed track on its opposite side.
                val thumbX = bounds.right - (if (checked) 16f else 36f) * density
                val trackX = bounds.right - (if (checked) 40f else 12f) * density
                val thumb = Color(bitmap.getPixel(thumbX.toInt(), bounds.center.y.toInt()))
                val track = Color(bitmap.getPixel(trackX.toInt(), bounds.center.y.toInt()))
                val a = thumb.luminance()
                val b = track.luminance()
                val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                if (contrast < 3f) failures += "$choice checked=$checked: thumb $thumb / track $track = $contrast:1"
            } finally { bitmap.recycle() }
        }
        assertTrue("Every enabled switch thumb must reach 3:1:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}

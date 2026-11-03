package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.Snapshot
import io.github.willzy12h.nocturne.core.store.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class RedesignThemeTest : ComposeActivityFixture() {
    private fun updateState(change: () -> Unit) {
        compose.runOnUiThread(change)
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test fun chosenPalettesReachMaterialAndModeColoursInEveryThemeMode() {
        val noon = ZonedDateTime.parse("2026-10-27T12:00Z")
        var settings by mutableStateOf(Settings(themeMode = ThemeMode.DARK, nightPalette = Palette.FOREST, dayPalette = Palette.PLUM))
        var at by mutableStateOf(noon)
        var background: Color? = null
        var heat: Color? = null
        setContent { NocturneTheme(settings, at) {
            val scheme = MaterialTheme.colorScheme
            val colours = nocturneColors
            SideEffect { background = scheme.background; heat = colours.heat }
            Box(Modifier.size(48.dp).background(scheme.background).testTag("theme-surface"))
        } }
        compose.onNodeWithTag("theme-surface").assertIsDisplayed()
        assertEquals(Color(0xFF08120E), background)
        assertEquals(Color(0xFFF45A40), heat)
        updateState { settings = settings.copy(themeMode = ThemeMode.LIGHT) }
        assertEquals(Color(0xFF120A18), background)
        updateState { settings = settings.copy(themeMode = ThemeMode.SYSTEM) }
        assertEquals(Color(0xFF120A18), background) // Robolectric's default configuration is light.
        updateState { settings = settings.copy(themeMode = ThemeMode.SCHEDULED) }
        assertEquals(Color(0xFF120A18), background)
        updateState { at = noon.withHour(23) }
        assertEquals(Color(0xFF08120E), background)
    }

    @Test fun bothPaletteChoicesSurviveBackupExportAndImport() {
        val original = BackupBundle(createdAt = "2026-10-27T12:00Z", profiles = emptyList(),
            settings = Settings(nightPalette = Palette.WINTER_STORM, dayPalette = Palette.AUTUMN_RUST))
        val restored = BackupCodec.decode(BackupCodec.encode(original)).settings
        assertEquals(Palette.WINTER_STORM, restored.nightPalette)
        assertEquals(Palette.AUTUMN_RUST, restored.dayPalette)
    }
}

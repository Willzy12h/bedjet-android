package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.willzy12h.nocturne.core.protocol.Mode

/** Material You colours from the wallpaper. */
@Composable
fun NocturneTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** One colour per mode, the same in light and dark, so the night curve reads at a glance. */
fun modeColor(mode: Mode): Color = when (mode) {
    Mode.COOL -> Color(0xFF4F8DF5)
    Mode.HEAT -> Color(0xFFF08A3C)
    Mode.EXTENDED_HEAT -> Color(0xFFE6B13A)
    Mode.TURBO -> Color(0xFFE5484D)
    Mode.DRY -> Color(0xFF34A79A)
    Mode.WAIT, Mode.STANDBY -> Color(0xFF8A8FA3)
}

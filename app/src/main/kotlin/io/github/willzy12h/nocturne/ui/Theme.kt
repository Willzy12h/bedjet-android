package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ThemeMode

// Two fixed palettes replace Material You: "Midnight Ink" for dark and "Daylight" for light. Fixed colours keep the
// mode colours, the night curve and the widgets readable whatever the wallpaper is. The type stays the system font.

/** Colours beyond the Material scheme: one per BedJet mode, plus the accents the Tonight screen uses. */
@Immutable
data class NocturneColors(
    val dark: Boolean,
    val cool: Color,
    val heat: Color,
    val gentleHeat: Color,
    val turbo: Color,
    val dry: Color,
    val idle: Color,
    /** The "connected" dot on the connection chip. */
    val connected: Color,
    /** Simple view's wake-time line and timeline accent. */
    val accent: Color,
) {
    fun mode(mode: Mode): Color = when (mode) {
        Mode.COOL -> cool
        Mode.HEAT -> heat
        Mode.EXTENDED_HEAT -> gentleHeat
        Mode.TURBO -> turbo
        Mode.DRY -> dry
        Mode.WAIT, Mode.STANDBY -> idle
    }
}

private val InkGround = Color(0xFF0E1220)
private val InkSurface = Color(0xFF171C2E)
private val InkRaised = Color(0xFF1F2640)
private val InkOutline = Color(0xFF343C5A)
private val InkText = Color(0xFFE8ECF7)
private val InkMuted = Color(0xFFA3ACC6)
private val InkCool = Color(0xFF6EA8FE)
private val InkHeat = Color(0xFFF59E5B)
private val InkTurbo = Color(0xFFF2707A)

private val DayGround = Color(0xFFF4F6FA)
private val DaySurface = Color(0xFFFFFFFF)
private val DayOutline = Color(0xFFDCE1EA)
private val DayText = Color(0xFF141A2A)
private val DayMuted = Color(0xFF4F5A73)
private val DayCool = Color(0xFF2459B8)
private val DayHeat = Color(0xFFC2561A)
private val DayTurbo = Color(0xFFC42F3E)
private val DayViolet = Color(0xFF5B4BC4)

val MidnightInkColors = NocturneColors(
    dark = true, cool = InkCool, heat = InkHeat, gentleHeat = Color(0xFFF2C46B), turbo = InkTurbo,
    dry = Color(0xFF4FC6B5), idle = Color(0xFF8A93AE), connected = Color(0xFF5ED39A), accent = InkCool,
)

val DaylightColors = NocturneColors(
    dark = false, cool = DayCool, heat = DayHeat, gentleHeat = Color(0xFF9A6B00), turbo = DayTurbo,
    dry = Color(0xFF1E7F72), idle = Color(0xFF6B7489), connected = Color(0xFF1E8A55), accent = DayViolet,
)

// The outline tokens are hairlines for cards and dividers (outlineVariant). Material also draws text field and switch
// borders with `outline`, which need 3:1 against the surface, so that role gets a stronger shade of the same hue.
val MidnightInkScheme: ColorScheme = darkColorScheme(
    primary = InkCool, onPrimary = InkGround,
    primaryContainer = Color(0xFF24365C), onPrimaryContainer = InkText,
    secondary = InkCool, onSecondary = InkGround,
    secondaryContainer = Color(0xFF2A3863), onSecondaryContainer = InkText,
    tertiary = InkHeat, onTertiary = InkGround,
    tertiaryContainer = Color(0xFF4A3220), onTertiaryContainer = InkText,
    error = InkTurbo, onError = InkGround,
    errorContainer = Color(0xFF4A2229), onErrorContainer = InkText,
    background = InkGround, onBackground = InkText,
    surface = InkGround, onSurface = InkText,
    surfaceVariant = InkRaised, onSurfaceVariant = InkMuted,
    surfaceTint = InkCool,
    inverseSurface = InkText, inverseOnSurface = InkGround, inversePrimary = DayCool,
    outline = Color(0xFF6B7493), outlineVariant = InkOutline,
    scrim = Color.Black,
    surfaceBright = InkRaised, surfaceDim = InkGround,
    surfaceContainerLowest = InkGround, surfaceContainerLow = Color(0xFF131829),
    surfaceContainer = InkSurface, surfaceContainerHigh = InkRaised, surfaceContainerHighest = Color(0xFF262E4C),
)

val DaylightScheme: ColorScheme = lightColorScheme(
    primary = DayText, onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E8F2), onPrimaryContainer = DayText,
    secondary = DayCool, onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F8), onSecondaryContainer = DayText,
    tertiary = DayHeat, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE4D6), onTertiaryContainer = DayText,
    error = DayTurbo, onError = Color.White,
    errorContainer = Color(0xFFFBE0E2), onErrorContainer = DayText,
    background = DayGround, onBackground = DayText,
    surface = DayGround, onSurface = DayText,
    surfaceVariant = Color(0xFFE9EDF4), onSurfaceVariant = DayMuted,
    surfaceTint = DayText,
    inverseSurface = DayText, inverseOnSurface = DayGround, inversePrimary = InkCool,
    outline = Color(0xFF7A849B), outlineVariant = DayOutline,
    scrim = Color.Black,
    surfaceBright = DaySurface, surfaceDim = Color(0xFFE6EAF1),
    surfaceContainerLowest = DaySurface, surfaceContainerLow = DaySurface,
    surfaceContainer = DaySurface, surfaceContainerHigh = Color(0xFFEEF1F6), surfaceContainerHighest = Color(0xFFE6EAF1),
)

val LocalNocturneColors = staticCompositionLocalOf { MidnightInkColors }

/** Whether a theme setting means dark right now. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
fun NocturneTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = mode.isDark(isSystemInDarkTheme())
    CompositionLocalProvider(LocalNocturneColors provides if (dark) MidnightInkColors else DaylightColors) {
        MaterialTheme(colorScheme = if (dark) MidnightInkScheme else DaylightScheme, content = content)
    }
}

/** The palette's extra colours, from inside [NocturneTheme]. */
val nocturneColors: NocturneColors
    @Composable @ReadOnlyComposable get() = LocalNocturneColors.current

/** One colour per mode so the night curve reads at a glance; Midnight Ink's in dark, Daylight's in light. */
@Composable
@ReadOnlyComposable
fun modeColor(mode: Mode): Color = nocturneColors.mode(mode)

package io.github.willzy12h.nocturne.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.Settings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime

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
    /** The rendered curve tint, including the quieter after-wake bars. */
    fun curveMode(mode: Mode, afterWake: Boolean): Color = mode(mode).copy(alpha = if (afterWake) 0.85f else 1f)

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
private val InkOutline = Color(0xFF6B7493)
private val InkText = Color(0xFFE8ECF7)
private val InkMuted = Color(0xFFA3ACC6)
private val InkCool = Color(0xFF6EA8FE)
private val InkHeat = Color(0xFFF59E5B)
private val InkTurbo = Color(0xFFF2707A)

private val DayGround = Color(0xFFF4F6FA)
private val DaySurface = Color(0xFFFFFFFF)
private val DayOutline = Color(0xFF7A849B)
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
    dark = false, cool = DayCool, heat = Color(0xFFAD6100), gentleHeat = Color(0xFFA46900), turbo = Color(0xFFD1431B),
    dry = Color(0xFF1E7F72), idle = Color(0xFF6B7489), connected = Color(0xFF1E8A55), accent = DayViolet,
)

/** Simple view keeps its established mode colours independently of the richer Daylight palette. */
private val SimpleDaylightColors = DaylightColors.copy(heat = DayHeat, gentleHeat = Color(0xFF9A6B00), turbo = DayTurbo)

// Decorative card borders, dividers and curve/timeline guides keep their original quieter shades.
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
    outline = InkOutline, outlineVariant = Color(0xFF343C5A),
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
    outline = DayOutline, outlineVariant = Color(0xFFDCE1EA),
    scrim = Color.Black,
    surfaceBright = DaySurface, surfaceDim = Color(0xFFE6EAF1),
    surfaceContainerLowest = DaySurface, surfaceContainerLow = DaySurface,
    surfaceContainer = DaySurface, surfaceContainerHigh = Color(0xFFEEF1F6), surfaceContainerHighest = Color(0xFFE6EAF1),
)

val LocalNocturneColors = staticCompositionLocalOf { MidnightInkColors }

/** The Quick controls' boundary, separate from decorative card borders. */
internal fun quickButtonBorder(scheme: ColorScheme): BorderStroke = BorderStroke(1.dp, scheme.outline)

/** Shared unit preference for curve descriptions; standalone previews default to Celsius. */
internal val LocalTemperatureFahrenheit = staticCompositionLocalOf { false }

/** Whether a theme setting means dark right now. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SCHEDULED -> systemDark
}

@Composable
fun NocturneTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = mode.isDark(isSystemInDarkTheme())
    NocturnePalette(dark, content)
}

@Composable
fun NocturneTheme(settings: Settings, at: ZonedDateTime, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTemperatureFahrenheit provides settings.fahrenheit) {
        NocturnePalette(settings.isDarkTheme(at, isSystemInDarkTheme()), content, simple = settings.simpleView)
    }
}

@Composable
private fun NocturnePalette(dark: Boolean, content: @Composable () -> Unit, simple: Boolean = false) {
    CompositionLocalProvider(LocalNocturneColors provides if (dark) MidnightInkColors else if (simple) SimpleDaylightColors else DaylightColors) {
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

/** Uses the injected app clock and sleeps until the next local theme boundary; resuming refreshes it immediately. */
@Composable
internal fun rememberThemeNow(settings: Settings, now: () -> ZonedDateTime): ZonedDateTime {
    var at by remember { mutableStateOf(now()) }
    val currentClock by rememberUpdatedState(now)
    var clockChange by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, settings.themeMode) {
        if (settings.themeMode != ThemeMode.SCHEDULED) onDispose { }
        else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { clockChange++ }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }
    LaunchedEffect(lifecycle, settings.themeMode, settings.scheduledDarkMinute, settings.scheduledLightMinute, clockChange) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = currentClock()
                at = current
                val next = nextThemeBoundary(settings, current) ?: awaitCancellation()
                delay(Duration.between(current, next).toMillis().coerceAtLeast(1))
            }
        }
    }
    return at
}

/** Clock changes at a daylight-saving transition can cross a boundary too, including a repeated local hour. */
internal fun nextThemeBoundary(settings: Settings, at: ZonedDateTime): ZonedDateTime? {
    if (settings.themeMode != ThemeMode.SCHEDULED || settings.scheduledDarkMinute == settings.scheduledLightMinute) return null
    val boundaries = (0L..1L).flatMap { day ->
        listOf(settings.scheduledDarkMinute, settings.scheduledLightMinute).flatMap { minute ->
            val boundary = at.toLocalDate().plusDays(day).atTime(minute / 60, minute % 60).atZone(at.zone)
            listOf(boundary.withEarlierOffsetAtOverlap(), boundary.withLaterOffsetAtOverlap())
        }
    }
    val transition = at.zone.rules.nextTransition(at.toInstant())?.instant?.atZone(at.zone)
    return (boundaries + listOfNotNull(transition)).filter { it.isAfter(at) }.minByOrNull { it.toInstant() }
}

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.Settings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

// Palette tokens keep Material and BedJet mode colours in sync with the chosen appearance.

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

/** Material roles and the app's mode colours share the selected palette. */
fun PaletteTokens.materialScheme(): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    fun contrastOn(color: Color) = if (color.luminance() > 0.179f) Color.Black else Color.White
    return scheme.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = surface2, onPrimaryContainer = ink,
        secondary = accent, onSecondary = contrastOn(accent),
        secondaryContainer = surface2, onSecondaryContainer = ink,
        tertiary = ember[1], onTertiary = contrastOn(ember[1]),
        tertiaryContainer = surface2, onTertiaryContainer = ink,
        error = bad, onError = contrastOn(bad),
        errorContainer = surface2, onErrorContainer = ink,
        background = ground, onBackground = ink,
        surface = surface, onSurface = ink,
        surfaceVariant = surface2, onSurfaceVariant = ink2,
        surfaceTint = primary, outline = hairline, outlineVariant = hairline,
        inverseSurface = ink, inverseOnSurface = ground, inversePrimary = ground,
        scrim = Color.Black,
        surfaceBright = surface2, surfaceDim = ground,
        surfaceContainerLowest = ground, surfaceContainerLow = sheet,
        surfaceContainer = surface, surfaceContainerHigh = sheet, surfaceContainerHighest = surface2,
    )
}

fun PaletteTokens.nocturneColors(): NocturneColors = NocturneColors(
    dark = dark, cool = cool, heat = ember[1], gentleHeat = ember[0], turbo = ember[3],
    dry = dry, idle = off, connected = if (dark) Color(0xFF5ED39A) else Color(0xFF1E8A55), accent = accent,
)

// Compatibility aliases used by widgets and existing callers until they migrate to selected tokens.
val MidnightInkColors = Palette.MIDNIGHT_INK.tokens.nocturneColors()
val DaylightColors = Palette.OVERCAST.tokens.nocturneColors()
val MidnightInkScheme = Palette.MIDNIGHT_INK.tokens.materialScheme()
val DaylightScheme = Palette.OVERCAST.tokens.materialScheme()

val LocalPaletteTokens = staticCompositionLocalOf { Palette.MIDNIGHT_INK.tokens }
val LocalNocturneColors = staticCompositionLocalOf { MidnightInkColors }

/** The Quick controls' boundary, separate from decorative card borders. */
internal fun quickButtonBorder(scheme: ColorScheme): BorderStroke = BorderStroke(1.dp, scheme.onSurfaceVariant)

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
    NocturnePalette((if (dark) Palette.MIDNIGHT_INK else Palette.OVERCAST).tokens, content)
}

@Composable
fun NocturneTheme(settings: Settings, at: ZonedDateTime, dark: Boolean? = null, paletteOverride: Palette? = null, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val palette = paletteOverride ?: if (dark == null) settings.palette(at, systemDark)
        else if (dark) settings.nightPalette else settings.dayPalette
    CompositionLocalProvider(LocalTemperatureFahrenheit provides settings.fahrenheit) {
        NocturnePalette(palette.tokens, content)
    }
}

@Composable
private fun NocturnePalette(tokens: PaletteTokens, content: @Composable () -> Unit) {
    val scheme = remember(tokens) { tokens.materialScheme() }
    val colors = remember(tokens) { tokens.nocturneColors() }
    CompositionLocalProvider(
        LocalPaletteTokens provides tokens,
        LocalNocturneColors provides colors,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The selected palette's tokens, from inside [NocturneTheme]. */
val paletteTokens: PaletteTokens
    @Composable @ReadOnlyComposable get() = LocalPaletteTokens.current

/** The palette's extra colours, from inside [NocturneTheme]. */
val nocturneColors: NocturneColors
    @Composable @ReadOnlyComposable get() = LocalNocturneColors.current

/** One default colour per mode; use paletteTokens.stepColor when the temperature is known. */
@Composable
@ReadOnlyComposable
fun modeColor(mode: Mode): Color = nocturneColors.mode(mode)

/** Uses the injected app clock and sleeps until the next local theme boundary; resuming refreshes it immediately. */
@Composable
internal fun rememberThemeNow(settings: Settings, now: () -> ZonedDateTime, holdUntil: Instant? = null): ZonedDateTime {
    var at by remember { mutableStateOf(now()) }
    val currentClock by rememberUpdatedState(now)
    var clockChange by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, settings.themeMode, holdUntil) {
        if (settings.themeMode != ThemeMode.SCHEDULED && holdUntil == null) onDispose { }
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
    LaunchedEffect(lifecycle, settings.themeMode, settings.scheduledDarkMinute, settings.scheduledLightMinute, clockChange, holdUntil) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = currentClock()
                at = current
                val next = listOfNotNull(nextThemeBoundary(settings, current),
                    holdUntil?.takeIf { it.isAfter(current.toInstant()) }?.atZone(current.zone))
                    .minByOrNull { it.toInstant() } ?: awaitCancellation()
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

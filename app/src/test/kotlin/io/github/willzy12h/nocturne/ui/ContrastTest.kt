package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG sRGB relative luminance from the actual palettes consumed by both normal and Simple view. */
class ContrastTest {
    private val looks = listOf(
        Triple("Midnight Ink", MidnightInkScheme, MidnightInkColors),
        Triple("Daylight", DaylightScheme, DaylightColors))
    private fun luminance(color: Color): Double {
        fun linear(component: Float): Double = component.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
    private fun contrast(foreground: Color, background: Color): Double {
        val fg = luminance(foreground.compositeOver(background))
        val bg = luminance(background)
        return (max(fg, bg) + 0.05) / (min(fg, bg) + 0.05)
    }
    private fun surfaces(s: ColorScheme) = mapOf(
        "background" to s.background, "surface" to s.surface, "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceVariant" to s.surfaceVariant, "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow, "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh, "surfaceContainerHighest" to s.surfaceContainerHighest)
    private fun check(label: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("$label contrast $ratio must be at least $minimum:1", ratio >= minimum)
    }

    @Test fun allTextRolesMeetFourPointFiveInBothSchemesAndSimpleView() {
        looks.forEach { (name, scheme, colors) ->
            listOf(false, true).forEach { simple ->
                val look = "$name simple=$simple"
                surfaces(scheme).forEach { (surface, background) ->
                    check("$look onSurface/$surface", scheme.onSurface, background, 4.5)
                    check("$look onSurfaceVariant/$surface", scheme.onSurfaceVariant, background, 4.5)
                    check("$look wake accent/$surface", colors.accent, background, 4.5)
                    check("$look primary text/$surface", scheme.primary, background, 4.5)
                    check("$look secondary text/$surface", scheme.secondary, background, 4.5)
                    check("$look error text/$surface", scheme.error, background, 4.5)
                }
                listOf(
                    Triple("primary", scheme.onPrimary, scheme.primary),
                    Triple("primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
                    Triple("secondary", scheme.onSecondary, scheme.secondary),
                    Triple("secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer),
                    Triple("tertiary", scheme.onTertiary, scheme.tertiary),
                    Triple("tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer),
                    Triple("error", scheme.onError, scheme.error),
                    Triple("errorContainer", scheme.onErrorContainer, scheme.errorContainer),
                    Triple("inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface),
                    Triple("inversePrimary", scheme.inversePrimary, scheme.inverseSurface),
                    Triple("background", scheme.onBackground, scheme.background),
                    Triple("primary text", scheme.primary, scheme.surfaceContainer),
                    Triple("secondary text", scheme.secondary, scheme.surfaceContainer),
                    Triple("error text", scheme.error, scheme.surfaceContainer),
                ).forEach { (role, foreground, background) -> check("$look $role", foreground, background, 4.5) }
            }
        }
    }

    @Test fun afterWakeBarsMeetThreeAgainstEveryCurveCardSurface() {
        looks.forEach { (name, scheme, colors) ->
            // Tonight uses surfaceContainer; History/Profiles use surfaceContainerHighest; the editor uses surface.
            listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHighest).forEach { background ->
                Mode.entries.forEach { mode -> check("$name after-wake $mode", colors.curveMode(mode, true), background, 3.0) }
            }
        }
    }

    @Test fun quickControlBoundaryOrFillMeetsThreeAgainstSurroundingsInAllFourLooks() {
        looks.forEach { (name, scheme, _) ->
            listOf(false, true).forEach { simple ->
                // Tonight QuickButton explicitly uses cardBorder(): outlineVariant over surfaceContainer.
                val boundary = contrast(scheme.outlineVariant, scheme.background)
                val fill = contrast(scheme.surfaceContainer, scheme.background)
                assertTrue("$name simple=$simple Quick control boundary=$boundary fill=$fill must reach 3:1", max(boundary, fill) >= 3.0)
                check("$name simple=$simple Quick boundary/fill", scheme.outlineVariant, scheme.surfaceContainer, 3.0)
            }
        }
    }
}

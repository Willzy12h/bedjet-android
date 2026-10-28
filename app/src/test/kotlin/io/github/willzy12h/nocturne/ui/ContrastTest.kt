package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG sRGB relative luminance from the actual palettes consumed by both normal and Simple view. */
class ContrastTest {
    private val looks = Palette.entries.map { Triple(it.name, it.tokens.materialScheme(), it.tokens.nocturneColors()) }
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

    @Test fun bodyAndContainerTextMeetFourPointFiveInEveryPalette() {
        looks.forEach { (name, scheme, _) ->
            val look = name
            surfaces(scheme).forEach { (surface, tint) ->
                val background = tint.compositeOver(scheme.background)
                check("$look onSurface/$surface", scheme.onSurface, background, 4.5)
                check("$look onSurfaceVariant/$surface", scheme.onSurfaceVariant, background, 4.5)
            }
            // The approved sky-blue/white pair has the foundation's 3:1 token floor.
            // Rendering must use large text for this pair; this token test does not verify font size.
            check("$look Start", scheme.onPrimary, scheme.primary, 3.0)
            listOf(
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
            ).forEach { (role, foreground, background) -> check("$look $role", foreground, background.compositeOver(scheme.background), 4.5) }
        }
    }

    // P2 replaces both fixed outline colours with the approved palette hairline token.
    @Test fun midnightInkDecorativeOutlinesFollowPaletteHairline() {
        val tokens = Palette.MIDNIGHT_INK.tokens
        assertEquals(tokens.hairline, tokens.materialScheme().outlineVariant)
        assertEquals(tokens.hairline, tokens.materialScheme().outline)
    }

    @Test fun overcastDecorativeOutlinesFollowPaletteHairline() {
        val tokens = Palette.OVERCAST.tokens
        assertEquals(tokens.hairline, tokens.materialScheme().outlineVariant)
        assertEquals(tokens.hairline, tokens.materialScheme().outline)
    }

    @Test fun stepTextContrastsWithEveryTemperatureTint() {
        // P2 changes bars to the approved temperature colours. Their adjacent text carries the meaning;
        // RedesignCurveColoursTest checks rendered bar colours; NightTimelineLayoutTest checks its text semantics.
        Palette.entries.forEach { palette ->
            val tokens = palette.tokens
            Mode.entries.forEach { mode ->
                listOf(56, 64, 72, 82).forEach { temperature ->
                    val background = tokens.tint(tokens.stepColor(mode, temperature)).compositeOver(tokens.ground)
                    check("$palette $mode label", tokens.ink, background, 4.5)
                    check("$palette $mode subtitle", tokens.ink2, background, 4.5)
                }
            }
        }
    }

    @Test fun quickControlBoundaryOrFillMeetsThreeAgainstSurroundingsInBothSharedPalettes() {
        looks.forEach { (name, scheme, _) ->
            // Read the same BorderStroke factory consumed by TonightScreen.QuickButton.
            val stroke = quickButtonBorder(scheme)
            assertTrue("$name Quick boundary must be drawn with positive width", stroke.width.value > 0f)
            val border = (stroke.brush as SolidColor).value
            val boundary = contrast(border, scheme.background)
            val surface = scheme.surfaceContainer.compositeOver(scheme.background)
            val fill = contrast(surface, scheme.background)
            assertTrue("$name Quick control boundary=$boundary fill=$fill must reach 3:1", max(boundary, fill) >= 3.0)
            check("$name Quick boundary/fill", border, surface, 3.0)
        }
    }
}

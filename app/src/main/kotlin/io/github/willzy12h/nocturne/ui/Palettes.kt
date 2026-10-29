package io.github.willzy12h.nocturne.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.plan.HeatScale
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette

// Redesign foundation (docs/redesign). Tokens are copied from the approved design's CSS so the two stay comparable:
// ground = --phbg, glow = --phglow, surface = --glass, surface2 = --glass2, accent = --fav, sheet = --sheetbg.

/** One palette's colours. Mode colours are shared by the dark palettes; Overcast has its own, deeper set. */
@Immutable
data class PaletteTokens(
    val dark: Boolean,
    val ground: Color,
    val glow: Color,
    val surface: Color,
    val surface2: Color,
    val hairline: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    /** The Start button. Dark palettes use [ink]; Overcast uses Sky blue. */
    val primary: Color,
    val onPrimary: Color,
    val sheet: Color,
    val menu: Color,
    val bad: Color,
    val warn: Color,
    val cool: Color,
    val cool2: Color,
    val dry: Color,
    val off: Color,
    /** Overcast's 1dp tile border (--edge); the dark palettes draw none. [hairline] is for dividers only. */
    val edge: Color? = null,
) {
    /** The border for cards, menus and outlined controls: [edge] where the palette has one, else [hairline]. */
    val outline: Color get() = edge ?: hairline

    /** The Ember scale, lightest to deepest; shared by every palette. */
    val ember: List<Color> get() = EMBER

    /** A step's colour: Ember by warmth for heating modes, otherwise the mode's own colour. */
    fun stepColor(mode: Mode, temperature: Int): Color = HeatScale.level(mode, temperature)?.let { EMBER[it - 1] } ?: when (mode) {
        Mode.COOL -> cool
        Mode.DRY -> dry
        else -> off
    }

    /** The flat, soft tint of [stripe] drawn over the surface of a side-stripe tile, at the design's [level]. */
    fun tint(stripe: Color, level: TintLevel = TintLevel.TILE): Color = stripe.copy(alpha = level.alpha)

    /** Keep the approved mode colour where it is readable; use the palette's ink for text otherwise. */
    fun readableText(color: Color, background: Color = ground, minimumContrast: Float = 4.5f): Color {
        val foreground = color.compositeOver(background).luminance()
        val behind = background.luminance()
        val contrast = (maxOf(foreground, behind) + .05f) / (minOf(foreground, behind) + .05f)
        return if (contrast >= minimumContrast) color else ink
    }
}

/** The design's stripe-tint strengths, the same in every palette. */
enum class TintLevel(val alpha: Float) {
    /** A preset or quick tile. */
    TILE(0.10f),
    /** A selected or running ("on") tile. */
    ON(0.24f),
    /** A step row in a timeline or the editor. */
    STEP(0.09f),
    /** A profile card. */
    PROFILE(0.08f),
}

private val EMBER = listOf(Color(0xFFFF8257), Color(0xFFF45A40), Color(0xFFDC3B32), Color(0xFFC0202E))

private fun darkPalette(ground: Long, glow: Long, glass: Long, glass2: Long, hair: Long, ink: Long, ink2: Long, ink3: Long,
    accent: Long, onPrimary: Long, sheet: Long) = PaletteTokens(
    dark = true, ground = Color(ground), glow = Color(glow), surface = Color(glass), surface2 = Color(glass2), hairline = Color(hair),
    ink = Color(ink), ink2 = Color(ink2), ink3 = Color(ink3), accent = Color(accent), primary = Color(ink), onPrimary = Color(onPrimary),
    sheet = Color(sheet), menu = Color(0xFF232740), bad = Color(0xFFFF9B91), warn = Color(0xFFFFCF5C),
    cool = Color(0xFF4FB3FF), cool2 = Color(0xFF9DD6FF), dry = Color(0xFFE6C35C), off = Color(0xFF9AA3B6),
)

// Menus use the design's --menubg fallback in every dark palette. Alpha: .07 = 0x12, .075 = 0x13, .085 = 0x16, .12 = 0x1F, .13 = 0x21. Forest, Graphite and Plum inherit the approved Midnight Ink sheet fallback, #171A2C.
private val MIDNIGHT_INK = darkPalette(0xFF0B0D18, 0xFF1A1F3A, 0x13FFFFFF, 0x1FFFFFFF, 0x12FFFFFF, 0xFFF1F3F9, 0xFFBCC2D4, 0xFF858CA2, 0xFFB48CFF, 0xFF12152A, 0xFF171A2C)
private val FOREST = darkPalette(0xFF08120E, 0xFF183428, 0x12D2FFE6, 0x1FD2FFE6, 0x14D2FFE6, 0xFFEEF6F1, 0xFFB5C8BC, 0xFF7E9587, 0xFF7FDCA6, 0xFF0C1A13, 0xFF171A2C)
private val GRAPHITE = darkPalette(0xFF000000, 0xFF161616, 0x12FFFFFF, 0x1FFFFFFF, 0x14FFFFFF, 0xFFF3F3F3, 0xFFBDBDBD, 0xFF858585, 0xFFDCDFE6, 0xFF000000, 0xFF171A2C)
private val PLUM = darkPalette(0xFF120A18, 0xFF33204A, 0x13F0DCFF, 0x1FF0DCFF, 0x14F0DCFF, 0xFFF5EFFA, 0xFFC9BCD6, 0xFF8F7FA0, 0xFFD6A6FF, 0xFF1A0F24, 0xFF171A2C)
private val AUTUMN_RUST = darkPalette(0xFF160D09, 0xFF4A2614, 0x13FFDCBE, 0x1FFFDCBE, 0x14FFDCBE, 0xFFFBF1E9, 0xFFD4C1B2, 0xFF9C8574, 0xFFFFB35C, 0xFF1E120B, 0xFF24160F)
private val WINTER_STORM = darkPalette(0xFF0F1626, 0xFF34466B, 0x16DCE8FF, 0x21DCE8FF, 0x14DCE8FF, 0xFFEEF3FB, 0xFFC3CFE2, 0xFF8A9AB4, 0xFFAAC4FF, 0xFF111A2C, 0xFF1B2438)

private val OVERCAST = PaletteTokens(
    dark = false, ground = Color(0xFFCED2D8), glow = Color(0xFFD9DCE1), surface = Color(0xFFE2E5E9), surface2 = Color(0xFFD4D8DE),
    hairline = Color(0x1F14171D), edge = Color(0xFFAAB0BA), ink = Color(0xFF14171D), ink2 = Color(0xFF444B57), ink3 = Color(0xFF6B7280),
    accent = Color(0xFF1F7CC2), primary = Color(0xFF2F93DC), onPrimary = Color.White, sheet = Color(0xFFDDE1E6), menu = Color(0xFFEEF0F3),
    bad = Color(0xFFC62F2A), warn = Color(0xFF9A6A00), cool = Color(0xFF2B8EE0), cool2 = Color(0xFF7FC1F2), dry = Color(0xFFB88C12), off = Color(0xFF7D8696),
)

val Palette.tokens: PaletteTokens get() = when (this) {
    Palette.MIDNIGHT_INK -> MIDNIGHT_INK
    Palette.FOREST -> FOREST
    Palette.GRAPHITE -> GRAPHITE
    Palette.PLUM -> PLUM
    Palette.AUTUMN_RUST -> AUTUMN_RUST
    Palette.WINTER_STORM -> WINTER_STORM
    Palette.OVERCAST -> OVERCAST
}

/**
 * The redesign's tile surface: a [width] stripe in [stripe] down the left edge over a flat soft tint of it. No
 * gradients. Used by preset tiles, steps, profile cards and preset rows. Clip the shape before this modifier.
 */
fun Modifier.sideStripe(stripe: Color, tokens: PaletteTokens, width: Dp = 4.dp, level: TintLevel = TintLevel.TILE): Modifier = drawBehind {
    drawRect(tokens.tint(stripe, level))
    drawRect(stripe, topLeft = Offset.Zero, size = Size(width.toPx(), size.height))
}

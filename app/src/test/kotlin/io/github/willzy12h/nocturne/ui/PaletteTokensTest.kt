package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.graphics.Color
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTokensTest {
    @Test
    fun onlyOvercastIsLight() {
        Palette.entries.forEach { assertEquals(it.name, it.dark, it.tokens.dark) }
        assertEquals(listOf(Palette.OVERCAST), Palette.entries.filterNot { it.dark })
    }

    @Test
    fun overcastStartButtonIsSkyBlueWithWhiteText() {
        assertEquals(Color(0xFF2F93DC), Palette.OVERCAST.tokens.primary)
        assertEquals(Color.White, Palette.OVERCAST.tokens.onPrimary)
    }

    @Test
    fun heatingStepsUseTheEmberScaleByWarmth() {
        val t = Palette.MIDNIGHT_INK.tokens
        assertEquals(t.ember[0], t.stepColor(Mode.HEAT, 56))
        assertEquals(t.ember[3], t.stepColor(Mode.TURBO, 60))
        assertEquals(t.cool, t.stepColor(Mode.COOL, 44))
        assertEquals(Palette.OVERCAST.tokens.cool, Palette.OVERCAST.tokens.stepColor(Mode.COOL, 44))
    }

    @Test
    fun inkContrastsWithTheGround() {
        Palette.entries.forEach {
            val t = it.tokens
            assertTrue("${it.name} ink on ground", contrast(t.ink, t.ground) >= 7.0)
            assertTrue("${it.name} Start button", contrast(t.onPrimary, t.primary) >= 3.0)
        }
    }

    private fun luminance(c: Color): Double {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}

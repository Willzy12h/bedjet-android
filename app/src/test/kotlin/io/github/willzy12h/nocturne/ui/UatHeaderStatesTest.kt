package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.willzy12h.nocturne.core.link.LinkState
import org.junit.Assert.*
import org.junit.Test

class UatHeaderStatesTest {
    private fun state(link: LinkState = LinkState.READY, live: Boolean = true, selected: Boolean = true,
        demo: Boolean = false, off: Boolean = false, released: Boolean = false) =
        headerConnection(demo, selected, released, off, link, live)
    @Test fun everyStateHasDistinctTextAndOnlyACurrentSelectedReportIsConnected() {
        assertEquals(HeaderConnection.CONNECTED, state())
        assertEquals(HeaderConnection.CONNECTING, state(live = false))
        assertEquals(HeaderConnection.CONNECTING, state(link = LinkState.CONNECTING))
        assertEquals(HeaderConnection.DISCONNECTED, state(selected = false))
        assertEquals(HeaderConnection.DISCONNECTED, state(released = true))
        assertEquals(HeaderConnection.DISCONNECTED, state(link = LinkState.SEARCHING))
        assertEquals(HeaderConnection.DISCONNECTED, state(link = LinkState.UNAUTHORIZED))
        assertEquals(HeaderConnection.BLUETOOTH_OFF, state(off = true))
        assertEquals(HeaderConnection.BLUETOOTH_OFF, state(link = LinkState.BLUETOOTH_OFF))
        assertEquals(HeaderConnection.DEMO, state(demo = true, off = true))
        assertEquals(5, HeaderConnection.entries.map { it.text }.distinct().size)
        // The approved compact status uses a dot beside explicit text, rather than the old shape icons.
        assertEquals(listOf(R.string.status_connected, R.string.uat_connecting, R.string.status_disconnected,
            R.string.status_bluetooth_off, R.string.status_demo), HeaderConnection.entries.map { it.text })
    }
    @Test fun allConnectionSymbolsExceedThreeToOneContrastInEveryPalette() {
        for (palette in Palette.entries) for (state in HeaderConnection.entries) {
            val symbol = connectionSymbolColour(state, palette.tokens.dark)
            val background = palette.tokens.ground
            val a = symbol.compositeOver(background).luminance(); val b = background.luminance()
            assertTrue("$palette $state", (maxOf(a,b)+.05f)/(minOf(a,b)+.05f) >= 3f)
        }
    }
}

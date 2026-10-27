package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.willzy12h.nocturne.core.link.LinkState
import org.junit.Assert.*
import org.junit.Test

class UatHeaderStatesTest {
    private fun state(link: LinkState = LinkState.READY, live: Boolean = true, selected: Boolean = true,
        demo: Boolean = false, off: Boolean = false, released: Boolean = false) =
        headerConnection(demo, selected, released, off, link, live)
    @Test fun everyStateHasDistinctTextAndShapeAndOnlyACurrentSelectedReportIsConnected() {
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
        assertEquals(5, HeaderConnection.entries.map { it.icon.name }.distinct().size)
    }
    @Test fun allConnectionSymbolsExceedThreeToOneContrastInBothThemeBackgrounds() {
        for (dark in listOf(false, true)) for (state in HeaderConnection.entries) {
            val symbol = connectionSymbolColour(state, dark)
            val plain = if (dark) Color(0xFF0E1220) else Color.White
            val container = if (dark) Color(0xFF171C2E) else Color(0xFFF3F5FA)
            val background = if (state == HeaderConnection.CONNECTED) symbol.copy(alpha = .08f).compositeOver(container) else plain
            val a = symbol.luminance(); val b = background.luminance()
            assertTrue("$dark $state", (maxOf(a,b)+.05f)/(minOf(a,b)+.05f) >= 3f)
        }
    }
}

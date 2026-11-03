package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState

internal enum class HeaderConnection(@StringRes val text: Int) {
    CONNECTED(R.string.status_connected),
    CONNECTING(R.string.uat_connecting),
    DISCONNECTED(R.string.status_disconnected),
    BLUETOOTH_OFF(R.string.status_bluetooth_off),
    DEMO(R.string.status_demo),
}

internal fun headerConnection(demo: Boolean, selected: Boolean, released: Boolean,
    bluetoothOff: Boolean, state: LinkState, currentReport: Boolean): HeaderConnection = when {
    demo -> HeaderConnection.DEMO
    bluetoothOff || state == LinkState.BLUETOOTH_OFF -> HeaderConnection.BLUETOOTH_OFF
    !selected || released || state == LinkState.UNAUTHORIZED -> HeaderConnection.DISCONNECTED
    state == LinkState.READY && currentReport -> HeaderConnection.CONNECTED
    state == LinkState.READY || state == LinkState.CONNECTING -> HeaderConnection.CONNECTING
    else -> HeaderConnection.DISCONNECTED
}

/** Fixed high-contrast symbol colours; the accompanying text uses onSurface, never colour alone. */
internal fun connectionSymbolColour(state: HeaderConnection, dark: Boolean): Color = when (state) {
    HeaderConnection.CONNECTED -> if (dark) Color(0xFF5ED39A) else Color(0xFF146C2E)
    HeaderConnection.CONNECTING -> if (dark) Color(0xFFFFD166) else Color(0xFF735500)
    HeaderConnection.BLUETOOTH_OFF -> if (dark) Color(0xFFFF9BA3) else Color(0xFFAF2030)
    else -> if (dark) Color(0xFFA3ACC6) else Color(0xFF4F5A73)
}

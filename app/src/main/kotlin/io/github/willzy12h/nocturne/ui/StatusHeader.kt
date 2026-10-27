package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState

internal enum class HeaderConnection(@StringRes val text: Int, val icon: ImageVector) {
    CONNECTED(R.string.status_connected, Icons.Default.CheckCircle),
    CONNECTING(R.string.uat_connecting, Icons.Default.Timelapse),
    DISCONNECTED(R.string.status_disconnected, Icons.Default.RadioButtonUnchecked),
    BLUETOOTH_OFF(R.string.status_bluetooth_off, Icons.Default.BluetoothDisabled),
    DEMO(R.string.status_demo, Icons.Default.Science),
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

/** Selected name, receipt-backed state and temperature stay outside every scrolling screen. */
@Composable
internal fun StatusHeader(model: AppViewModel) {
    val settings by model.settings.collectAsState()
    val state by model.linkState.collectAsState()
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val bluetoothOff by model.bluetoothOff.collectAsState()
    val released by model.graph.sessions.released.collectAsState()
    val selectedName by model.selectedBedJetName.collectAsState()
    val connection = headerConnection(settings.demoMode, settings.targetDevice != null, released,
        bluetoothOff, state, live && status != null)
    val name = when {
        settings.demoMode -> stringResource(R.string.status_demo_device)
        settings.targetDevice == null -> stringResource(R.string.status_no_device)
        else -> selectedName ?: DeviceId.address(settings.targetDevice!!) ?: stringResource(R.string.settings_bedjet)
    }
    val description = stringResource(R.string.uat_connection_description, name, stringResource(connection.text))
    val symbol = connectionSymbolColour(connection, nocturneColors.dark)
    val connected = connection == HeaderConnection.CONNECTED
    val background = if (connected) symbol.copy(alpha = .08f).compositeOver(MaterialTheme.colorScheme.surfaceContainer)
        else MaterialTheme.colorScheme.surface
    Surface(color = background, border = BorderStroke(1.dp,
        if (connected) symbol else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("status-header"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("status-device"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(connection.icon, description, tint = symbol,
                        modifier = Modifier.size(20.dp).testTag("status-symbol-${connection.name}"))
                    Text(stringResource(connection.text), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("status-connection"))
                }
            }
            Text(status?.let {
                if (live) tempText(it.actualTemperature, settings.fahrenheit)
                else stringResource(R.string.status_last_temperature, tempText(it.actualTemperature, settings.fahrenheit))
            } ?: stringResource(R.string.status_temperature_unavailable), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.widthIn(max = 132.dp).testTag("status-temperature"))
        }
    }
}

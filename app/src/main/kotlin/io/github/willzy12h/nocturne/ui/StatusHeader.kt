package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState

/** Shared outside every scrolling main screen; selected identity is independent of connection evidence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusHeader(model: AppViewModel) {
    val settings by model.settings.collectAsState()
    val state by model.linkState.collectAsState()
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val bluetoothOff by model.bluetoothOff.collectAsState()
    val released by model.graph.sessions.released.collectAsState()
    val selectedName by model.selectedBedJetName.collectAsState()
    val connection = when {
        settings.demoMode -> R.string.status_demo
        bluetoothOff || state == LinkState.BLUETOOTH_OFF -> R.string.status_bluetooth_off
        settings.targetDevice == null || released || state == LinkState.UNAUTHORIZED -> R.string.status_disconnected
        state == LinkState.READY -> R.string.status_connected
        state == LinkState.CONNECTING -> R.string.status_connecting
        else -> R.string.status_disconnected
    }
    val name = when {
        settings.demoMode -> stringResource(R.string.status_demo_device)
        settings.targetDevice == null -> stringResource(R.string.status_no_device)
        else -> selectedName ?: DeviceId.address(settings.targetDevice!!) ?: stringResource(R.string.settings_bedjet)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("status-header"),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(connection), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("status-connection").padding(end = 12.dp))
                Text(status?.let {
                    if (live) tempText(it.actualTemperature, settings.fahrenheit)
                    else stringResource(R.string.status_last_temperature, tempText(it.actualTemperature, settings.fahrenheit))
                } ?: stringResource(R.string.status_temperature_unavailable),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("status-temperature"))
            }
            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("status-device"))
        }
    }
}

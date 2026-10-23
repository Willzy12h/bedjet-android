package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.deviceId

/** Explicit selection only: discovery never connects to an unchosen unit. */
@Composable
fun SetupFlow(model: AppViewModel, compact: Boolean = false) {
    val settings by model.settings.collectAsState()
    val found by model.foundBedJets.collectAsState()
    val discovering by model.discovering.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_choose_title), style = MaterialTheme.typography.titleMedium)
            if (!compact) Text(stringResource(R.string.setup_choose_help), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.setup_selected, settings.targetDevice ?: stringResource(R.string.setup_none)) +
                if (settings.demoMode) stringResource(R.string.setup_demo_on) else "", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = model::discoverBedJets, enabled = !discovering) {
                Text(stringResource(if (discovering) R.string.setup_searching else R.string.setup_find))
            }
            found.forEach { unit ->
                OutlinedButton(onClick = { model.selectBedJet(unit.address) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_choose_unit, unit.name ?: stringResource(R.string.setup_unit_fallback), unit.address))
                }
            }
            if (settings.deviceId() != null && !settings.demoMode) {
                val checked = settings.setupFanCheckedDevice == settings.targetDevice
                Text(stringResource(if (checked) R.string.setup_fan_completed else R.string.setup_fan_help),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = model::runSetupFanCheck) { Text(stringResource(if (checked) R.string.setup_fan_repeat else R.string.setup_fan_run)) }
            }
            if (settings.demoMode) Text(stringResource(R.string.setup_demo_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

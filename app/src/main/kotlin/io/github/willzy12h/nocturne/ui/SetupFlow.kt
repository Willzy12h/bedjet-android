package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId

/** Explicit selection only: discovery never connects to an unchosen unit. */
@Composable
fun SetupFlow(model: AppViewModel, compact: Boolean = false, showDemoChoice: Boolean = true) =
    BedJetPicker(model, compact, showDemoChoice)

@Composable
fun BedJetPicker(model: AppViewModel, compact: Boolean = false, showDemoChoice: Boolean = true) {
    val settings by model.settings.collectAsState()
    val found by model.foundBedJets.collectAsState()
    val discovering by model.discovering.collectAsState()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = paletteTokens.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_choose_title), style = MaterialTheme.typography.titleMedium)
            if (!compact) Text(stringResource(R.string.setup_choose_help), style = MaterialTheme.typography.bodySmall)
            if (settings.demoMode) {
                Text(stringResource(R.string.gpt12_demo_pair_help), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.setup_selected, settings.targetDevice ?: stringResource(R.string.setup_none)),
                    style = MaterialTheme.typography.bodyMedium)
                if (settings.demoTargetMigrated) Text(stringResource(R.string.gpt12_pair_after_demo),
                    style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = model::discoverBedJets, enabled = !discovering) {
                    Text(stringResource(if (discovering) R.string.setup_searching else R.string.setup_find))
                }
                val remembered = settings.normalisedRememberedBedJets()
                val targets = (remembered + found.map { DeviceId.ble(it.address) }).distinct()
                targets.forEach { target ->
                    val address = DeviceId.address(target) ?: return@forEach
                    val name = found.firstOrNull { DeviceId.ble(it.address) == target }?.name
                        ?: model.graph.bleLink.deviceNameFor(target)
                        ?: stringResource(R.string.setup_unit_fallback)
                    OutlinedButton(onClick = { model.selectBedJet(address) },
                        modifier = Modifier.fillMaxWidth().sideStripe(paletteTokens.cool, paletteTokens)) {
                        Text(stringResource(R.string.setup_choose_unit, name, address))
                    }
                }
            }
            if (settings.targetDevice != null && !settings.demoMode) {
                val checked = settings.setupFanCheckedDevice == settings.targetDevice
                Text(stringResource(if (checked) R.string.setup_fan_completed else R.string.setup_fan_help),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = model::runSetupFanCheck) { Text(stringResource(if (checked) R.string.setup_fan_repeat else R.string.setup_fan_run)) }
            }
            if (!settings.demoMode && showDemoChoice && settings.targetDevice == null) {
                HorizontalDivider()
                DemoModePanel(model, showBanner = false)
            }
            if (!compact) {
                var samsung by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { samsung = !samsung }) { Text(stringResource(R.string.setup_samsung_title)) }
                if (samsung) Text(stringResource(R.string.setup_samsung_help), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

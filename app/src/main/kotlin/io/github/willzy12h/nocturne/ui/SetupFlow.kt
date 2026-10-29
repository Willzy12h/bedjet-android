package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    val tokens = paletteTokens
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
        .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          ProvideTextStyle(TextStyle(color = tokens.ink2, fontSize = 14.sp, lineHeight = 20.sp)) {
            Text(stringResource(R.string.setup_choose_title), color = tokens.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                    val chosen = settings.targetDevice == target
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .sideStripe(tokens.cool, tokens).clickable(role = Role.Button, onClickLabel = stringResource(R.string.setup_choose_unit, name, address)) {
                            model.selectBedJet(address)
                        }.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_nc_bt), null, Modifier.size(18.dp), tint = tokens.cool)
                        Column(Modifier.weight(1f)) {
                            Text(name, color = tokens.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(address, color = tokens.ink2, fontSize = 12.5.sp)
                        }
                        Text(stringResource(if (chosen) R.string.parity_setup_chosen else R.string.parity_setup_choose),
                            Modifier.clip(RoundedCornerShape(50)).background(if (chosen) tokens.surface2 else tokens.ink)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            color = if (chosen) tokens.ink2 else tokens.ground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
}

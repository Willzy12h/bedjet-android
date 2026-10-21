package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit) {
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    val night by model.activeNight.collectAsState()
    val alarm by model.nextAlarm.collectAsState()
    val busy by model.busy.collectAsState()
    val release by model.release.collectAsState()
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = ZonedDateTime.now(); model.refreshAlarm() } }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tonight", style = MaterialTheme.typography.headlineMedium)
        ConnectionLine(model)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        SetupChecks()
        release?.let { latest ->
            val uri = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = model::dismissRelease,
                title = { Text("Nocturne ${latest.tag} is available") },
                text = { Text(latest.notes.ifBlank { "A new version is ready to download." }) },
                confirmButton = { TextButton(onClick = { uri.openUri(latest.apkUrl ?: latest.pageUrl); model.dismissRelease() }) { Text("Download") } },
                dismissButton = { TextButton(onClick = model::dismissRelease) { Text("Later") } },
            )
        }

        val running = night
        if (running != null) {
            val plan = remember(running, profiles) { model.activePlan() }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val profile = profiles.firstOrNull { it.id == running.profileId }
                    Text(profile?.name ?: "Night running", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Waking you at ${Format.clock(running.wakeAt)} " + if (running.wakeFromAlarm) "(your alarm)" else "(profile's wake time)",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    plan?.let {
                        val current = it.stepAt(now)?.second
                        current?.let { step ->
                            Text(
                                "${blockName(step.block)} · ${Format.modeName(step.step.mode)} · fan ${(step.step.fanStep + 1) * 5}%",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        NightCurve(it, Modifier.padding(top = 8.dp), now = now)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.run(Command.END_NIGHT) }) { Text("End night") }
                        OutlinedButton(onClick = { model.run(Command.RETIME) }) { Text("Re-check alarm") }
                    }
                }
            }
        } else {
            var chosen by remember(settings.selectedProfileId, profiles) { mutableStateOf(model.tonightsProfile()) }
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    FilterChip(
                        selected = chosen?.id == profile.id,
                        onClick = { chosen = profile; model.updateSettings { it.copy(selectedProfileId = profile.id) } },
                        label = { Text(profile.name) },
                    )
                }
            }
            chosen?.let { profile ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val wake = model.wakePreview(profile, now)
                        Text(
                            "Would wake you at ${Format.clock(wake.time)} " + if (wake.fromAlarm) "(your alarm)" else "(profile's wake time)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        alarm?.let {
                            if (!it.counts) Text("Your next alarm at ${Format.clock(it.time)} is from ${it.creator}, which isn't a clock app. Allow it in Settings.", style = MaterialTheme.typography.bodySmall)
                        } ?: if (profile.wakeSource.useAlarm) Text("No alarm set, so the profile's wake time is used.", style = MaterialTheme.typography.bodySmall) else Unit
                        model.previewPlan(profile, startNow = true).fold(
                            onSuccess = { NightCurve(it, Modifier.padding(top = 4.dp)) },
                            onFailure = { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { model.run(Command.START_NIGHT, profile.id) }, enabled = !busy) { Text("Start night now") }
                            TextButton(onClick = { onEdit(profile) }) { Text("Edit") }
                        }
                    }
                }
            }
        }

        Text("Quick", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { model.run(Command.BOOST) }) { Text("Boost ${settings.boostMinutes}m") }
            FilledTonalButton(onClick = { model.run(Command.TURBO) }) { Text("Turbo") }
            FilledTonalButton(onClick = { model.run(Command.COOL) }) { Text("Cool") }
            FilledTonalButton(onClick = { model.run(Command.WARM) }) { Text("Warm") }
            OutlinedButton(onClick = { model.run(Command.OFF) }) { Text("Off") }
        }
    }
}

@Composable
fun ConnectionLine(model: AppViewModel) {
    val state by model.linkState.collectAsState()
    val status by model.status.collectAsState()
    val settings by model.settings.collectAsState()
    val text = when (state) {
        LinkState.READY -> status?.let { "${Format.modeName(it.mode)} · room ${Format.temp(it.ambientTemperature, settings.fahrenheit)} · air ${Format.temp(it.actualTemperature, settings.fahrenheit)}" } ?: "Connected"
        LinkState.CONNECTING -> "Connecting to your BedJet…"
        LinkState.SEARCHING -> "Not connected"
        LinkState.BLUETOOTH_OFF -> "Bluetooth is off"
        LinkState.UNAUTHORIZED -> "Allow Nearby devices to reach your BedJet"
    }
    AssistChip(onClick = model::onScreenVisible, label = { Text((if (settings.demoMode) "Demo · " else "") + text) })
}

fun blockName(block: Block) = when (block) {
    Block.START -> "Start"
    Block.MIDDLE -> "Middle"
    Block.WAKE -> "Wake-up"
    Block.AFTER_WAKE -> "After wake"
}

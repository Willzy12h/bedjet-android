package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.SnapshotInfo
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.WakeAction
import io.github.willzy12h.nocturne.notify.Notifications
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(model: AppViewModel, modifier: Modifier) {
    val settings by model.settings.collectAsState()
    val snapshots by model.snapshots.collectAsState()
    val slotBackups by model.slotBackups.collectAsState()
    val alarm by model.nextAlarm.collectAsState()
    val f = settings.fahrenheit
    var restoring by remember { mutableStateOf<SnapshotInfo?>(null) }
    var snapshotLabel by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Group("Appearance") {
            Text("Theme", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(settings.themeMode == mode, { model.updateSettings { it.copy(themeMode = mode) } }, label = { Text(themeName(mode)) })
                }
            }
            SwitchRow("Simple view", "Show tonight as a list of steps instead of the curve.", settings.simpleView) { on ->
                model.updateSettings { it.copy(simpleView = on) }
            }
        }

        Group("BedJet") {
            Text("Connected unit: ${model.bedJetName() ?: "none yet"}", style = MaterialTheme.typography.bodyMedium)
            SwitchRow("Demo BedJet", "Try everything with a simulated BedJet instead of the real one.", settings.demoMode) { on ->
                model.updateSettings { it.copy(demoMode = on) }
            }
            Text("Slot used for tonight's night", style = MaterialTheme.typography.bodyLarge)
            Text("The other two slots are left alone. What was in a slot before is kept below.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..2).forEach { slot -> FilterChip(settings.workingSlot == slot, { model.updateSettings { it.copy(workingSlot = slot) } }, label = { Text("Slot ${slot + 1}") }) }
            }
            slotBackups.forEach { backup ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved from slot ${backup.slot + 1}: ${backup.program.name} (${backup.program.steps.size} steps)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.restoreSlot(backup.slot, backup.program) }) { Text("Put back") }
                }
            }
            SwitchRow("Show °F", null, f) { on -> model.updateSettings { it.copy(fahrenheit = on) } }
            OutlinedButton(onClick = model::forgetBedJet) { Text("Forget this BedJet") }
        }

        Group("Wake-up notification") {
            Text("Choose up to three buttons, in order.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakeAction.entries.forEach { action ->
                    val selected = action in settings.notificationActions
                    FilterChip(
                        selected,
                        {
                            model.updateSettings {
                                val list = if (selected) it.notificationActions - action else (it.notificationActions + action).takeLast(3)
                                it.copy(notificationActions = list)
                            }
                        },
                        label = { Text(Notifications.title(action, settings)) },
                    )
                }
            }
            PresetEditor("Snooze warm", settings.snoozeWarm, f) { p -> model.updateSettings { it.copy(snoozeWarm = p) } }
            PresetEditor("Snooze cool", settings.snoozeCool, f) { p -> model.updateSettings { it.copy(snoozeCool = p) } }
            PresetEditor("Extend warm (also the Warm button)", settings.extendWarm, f) { p -> model.updateSettings { it.copy(extendWarm = p) } }
            PresetEditor("Extend cool (also the Cool button)", settings.extendCool, f) { p -> model.updateSettings { it.copy(extendCool = p) } }
            Text("Boost: Turbo, then Heat, for ${settings.boostMinutes} minutes")
            Slider(settings.boostMinutes.toFloat(), { v -> model.updateSettings { it.copy(boostMinutes = v.roundToInt()) } }, valueRange = 10f..30f, steps = 3)
        }

        Group("Alarms") {
            val next = alarm
            Text(
                if (next == null) "No alarm is set on this phone." else "Next alarm ${Format.clock(next.time)} from ${next.creator ?: "an unknown app"}" + if (next.counts) "." else " — not used.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (next?.creator != null && !next.counts) {
                FilledTonalButton(onClick = { model.updateSettings { it.copy(alarmPackages = it.alarmPackages + next.creator!!) }; model.refreshAlarm() }) {
                    Text("Use alarms from ${next.creator}")
                }
            }
            Text("Used from: ${settings.alarmPackages.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }

        Group("Phone setup") { SetupChecks(showAll = true) }

        Group("Backups and snapshots") {
            Text("A snapshot is taken automatically when you change profiles or settings (the last 20 are kept) and once a day. Your own snapshots are kept until you delete them.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(snapshotLabel, { snapshotLabel = it.take(40) }, label = { Text("Label") }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { model.snapshotNow(snapshotLabel); snapshotLabel = "" }) { Text("Snapshot now") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("nocturne-backup-${LocalDate.now()}.json") }) { Text("Export file") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Restore from file") }
            }
            snapshots.forEach { snap ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(snap.label ?: "Automatic", style = MaterialTheme.typography.bodyMedium)
                        Text(snap.createdAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { restoring = snap }) { Text("Restore") }
                    if (snap.manual) TextButton(onClick = { model.deleteSnapshot(snap.id) }) { Text("Delete") }
                }
            }
        }

        Group("Updates") {
            var repo by remember { mutableStateOf(settings.updateRepo) }
            var token by remember { mutableStateOf(model.updateToken) }
            OutlinedTextField(repo, { repo = it }, label = { Text("GitHub repository (owner/name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token, { token = it }, label = { Text("Token (only for a private repository)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Check weekly", "Only when the app is opened. This is the app's only internet use.", settings.autoUpdateCheck) { on ->
                model.updateSettings { it.copy(autoUpdateCheck = on) }
            }
            FilledTonalButton(onClick = {
                model.updateSettings { it.copy(updateRepo = repo.trim()) }
                model.updateToken = token
                model.checkForUpdates()
            }) { Text("Check now") }
            Text("Installed: ${model.version}", style = MaterialTheme.typography.bodySmall)
        }

        Group("About") {
            Text(
                "Nocturne plans BedJet 3 nights around your alarm. It talks to the BedJet over Bluetooth only. " +
                    "BedJet protocol work by the Lull project (github.com/rkanjani/lull, MIT). Not affiliated with BedJet LLC.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    restoring?.let { snap ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore this snapshot?") },
            text = { Text("Profiles, settings and history go back to ${snap.createdAt.take(16).replace('T', ' ')}. What you have now is saved as a snapshot first.") },
            confirmButton = { TextButton(onClick = { model.restoreSnapshot(snap.id); restoring = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } },
        )
    }
}

private fun themeName(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetEditor(title: String, preset: ActionPreset, f: Boolean, onChange: (ActionPreset) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("$title: ${Format.modeName(preset.mode)}, ${if (preset.mode.usesTemperature) Format.temp(preset.temperature, f) + ", " else ""}fan ${preset.fanPercent}%, ${preset.minutes} min", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY).forEach { mode ->
                    FilterChip(preset.mode == mode, { onChange(preset.copy(mode = mode, temperature = preset.temperature.coerceIn(mode.temperatureRange))) }, label = { Text(Format.modeName(mode)) })
                }
            }
            if (preset.mode.usesTemperature) {
                val range = preset.mode.temperatureRange
                Slider(preset.temperature.toFloat(), { onChange(preset.copy(temperature = it.roundToInt())) }, valueRange = range.first.toFloat()..range.last.toFloat())
            }
            Text("Fan", style = MaterialTheme.typography.labelSmall)
            Slider(preset.fanPercent.toFloat(), { onChange(preset.copy(fanPercent = ((it / 5).roundToInt() * 5).coerceIn(5, 100))) }, valueRange = 5f..100f, steps = 18)
            Text("Minutes", style = MaterialTheme.typography.labelSmall)
            Slider(preset.minutes.toFloat(), { onChange(preset.copy(minutes = (it / 5).roundToInt() * 5)) }, valueRange = 5f..120f, steps = 22)
        }
    }
}

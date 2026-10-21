package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Season
import java.util.UUID

@Composable
fun ProfilesScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit) {
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    model.nextAlarm.collectAsState().value
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Profiles", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
            items(profiles, key = { it.id }) { profile ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (settings.selectedProfileId == profile.id) Text("Tonight", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                        val wake = model.wakePreview(profile)
                        Text(
                            "Would wake you at ${Format.clock(wake.time)} " + if (wake.fromAlarm) "· your alarm" else "· default ${Format.clockOfDay(profile.wakeSource.defaultWakeMinute)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(describe(profile), style = MaterialTheme.typography.bodySmall)
                        model.previewPlan(profile).getOrNull()?.let { NightCurve(it) }
                        Row {
                            TextButton(onClick = { model.updateSettings { it.copy(selectedProfileId = profile.id) } }) { Text("Use tonight") }
                            TextButton(onClick = { onEdit(profile) }) { Text("Edit") }
                            TextButton(onClick = { model.saveProfile(profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} copy")) }) { Text("Copy") }
                            TextButton(onClick = { deleting = profile }) { Text("Delete") }
                        }
                    }
                }
            }
            item { Box(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("New profile") }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Start from") },
            text = {
                Column {
                    Presets.all.forEach { preset ->
                        TextButton(onClick = {
                            adding = false
                            onEdit(preset.copy(id = UUID.randomUUID().toString(), name = preset.name))
                        }) { Text("${preset.name} (${seasonName(preset.season)})") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("Snapshots keep a copy, so you can restore it from Settings.") },
            confirmButton = { TextButton(onClick = { model.deleteProfile(profile.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun describe(profile: Profile): String {
    val start = profile.start.joinToString(" → ") { "${Format.modeName(it.mode)} ${it.minutes}m" }.ifEmpty { "nothing" }
    val middle = "${Format.modeName(profile.middle.mode)} fan ${profile.middle.fanStart}→${profile.middle.fanEnd}%"
    val wake = profile.wake.joinToString(" → ") { "${Format.modeName(it.mode)} ${it.minutes}m" }.ifEmpty { "nothing" }
    return "Start: $start · Middle: $middle · Wake-up: $wake"
}

fun seasonName(season: Season) = when (season) {
    Season.NONE -> "any time"
    Season.SUMMER -> "summer"
    Season.WINTER -> "winter"
    Season.SPRING_AUTUMN -> "spring/autumn"
}

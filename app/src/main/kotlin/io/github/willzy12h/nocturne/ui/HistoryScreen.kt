package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.NightRecord
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One card per night, newest first: what was planned and everything that happened. */
@Composable
fun HistoryScreen(model: AppViewModel, modifier: Modifier) {
    val history by model.history.collectAsState()
    val settings by model.settings.collectAsState()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
        if (history.isEmpty()) item { Text("Nights you run appear here, with every change made during them.") }
        items(history.sortedByDescending { it.startedAt }, key = { it.id }) { night ->
            var open by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().clickable { open = !open }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val day = runCatching { ZonedDateTime.parse(night.startedAt).format(DateTimeFormatter.ofPattern("EEE d MMM")) }.getOrDefault("")
                    Text("$day · ${night.profileName}", style = MaterialTheme.typography.titleMedium)
                    Text("${Format.clock(night.startedAt)} → wake ${Format.clock(night.wakeAt)}" + if (night.wakeFromAlarm) " (alarm)" else "")
                    planOf(night)?.let { NightCurve(it) }
                    if (open) {
                        night.events.forEach { Text("${Format.clock(it.at)}  ${it.detail}", style = MaterialTheme.typography.bodySmall) }
                        night.steps.forEach { step ->
                            Text(
                                "${Format.clock(step.startsAt)}  ${blockName(step.block)}: ${Format.modeName(step.mode)}, " +
                                    (if (step.mode.usesTemperature) "${Format.temp(step.temperature, settings.fahrenheit)}, " else "") +
                                    "fan ${step.fanPercent}%, ${Format.duration(step.minutes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else if (night.events.size > 1) {
                        Text("${night.events.size} events · tap for details", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun planOf(night: NightRecord): Plan? = runCatching {
    val steps = night.steps.map {
        PlannedStep(it.block, ProgramStep(it.mode, it.fanPercent / 5 - 1, it.temperature, it.minutes / 60, it.minutes % 60), ZonedDateTime.parse(it.startsAt), it.minutes)
    }
    if (steps.isEmpty()) null else Plan(ZonedDateTime.parse(night.startedAt), ZonedDateTime.parse(night.wakeAt), steps)
}.getOrNull()

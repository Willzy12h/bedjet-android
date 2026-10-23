package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlin.math.roundToInt

/** Direct control, like the BedJet's own remote. Changing the mode takes over from a running night. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(model: AppViewModel, modifier: Modifier) {
    val status by model.status.collectAsState()
    val state by model.linkState.collectAsState()
    val settings by model.settings.collectAsState()
    val f = settings.fahrenheit
    val guard = rememberNightGuard(model)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Remote", style = MaterialTheme.typography.headlineMedium)
        ConnectionLine(model)
        val s = status
        if (state != LinkState.READY || s == null) {
            Text("Connect to your BedJet to use the remote.", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Format.modeName(s.mode), style = MaterialTheme.typography.headlineSmall)
                Text("Air ${Format.temp(s.actualTemperature, f)} · room ${Format.temp(s.ambientTemperature, f)}")
                if (s.isRunning) Text("Target ${Format.temp(s.targetTemperature, f)} · fan ${s.fanPercent}% · ${Format.duration(s.remainingSeconds / 60)} left")
                s.extra?.takeIf { it.programStep > 0 }?.let { Text("Running step ${it.programStep} of a night", style = MaterialTheme.typography.bodySmall) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.STANDBY).forEach { mode ->
                FilterChip(s.mode == mode, { guard.confirm(if (mode == Mode.STANDBY) "turn the BedJet off" else "switch to ${Format.modeName(mode)}") { model.remote(Format.modeName(mode), endsNight = true, confirms = { it.mode == mode }) { it.setMode(mode) } } }, label = { Text(Format.modeName(mode)) })
            }
        }
        if (s.isRunning && s.mode.usesTemperature) {
            var temp by remember(s.targetTemperature) { mutableFloatStateOf(s.targetTemperature.toFloat()) }
            Text("Temperature ${Format.temp(temp.roundToInt(), f)}")
            Slider(
                temp, { temp = it }, valueRange = s.minTemperature.toFloat()..s.maxTemperature.toFloat(),
                steps = (s.maxTemperature - s.minTemperature - 1).coerceAtLeast(0),
                onValueChangeFinished = { val t = temp.roundToInt(); model.remote("Temperature set", affectsProgramme = true, confirms = { it.targetTemperature == t }) { it.setTemperature(t) } },
            )
        }
        if (s.isRunning && s.mode.usesFan) {
            var fan by remember(s.fanStep) { mutableFloatStateOf(s.fanPercent.toFloat()) }
            Text("Fan ${fan.roundToInt()}%")
            Slider(
                fan, { fan = it }, valueRange = 5f..100f, steps = 18,
                onValueChangeFinished = { val step = (fan / 5).roundToInt() - 1; model.remote("Fan set", affectsProgramme = true, confirms = { it.fanStep == step }) { it.setFan(step) } },
            )
        }
        if (s.isRunning) {
            Text("Run for", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120, 240, 480).forEach { minutes ->
                    AssistChip({ model.remote("Timer set", affectsProgramme = true, confirms = { kotlin.math.abs(it.remainingSeconds - minutes * 60) <= 90 }) { it.setRuntime(minutes) } }, label = { Text(Format.duration(minutes)) })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ guard.confirm("start Boost") { model.run(Command.BOOST, replacementConfirmed = true) } }) { Text("Boost ${settings.boostMinutes}m") }
        }
        s.extra?.let { extra ->
            SwitchRow("Lights", null, extra.ledEnabled) { on -> model.remote("Lights ${if (on) "on" else "off"}", confirms = { it.extra?.ledEnabled == on }) { it.setLed(on) } }
            SwitchRow("Beeps", null, !extra.beepsMuted) { on -> model.remote("Beeps ${if (on) "on" else "off"}", confirms = { it.extra?.beepsMuted == !on }) { it.setMuted(!on) } }
            if (extra.notification == 1) {
                FilledTonalButton({ model.remote("Reminder cleared", confirms = { it.extra?.notification == 0 }) { it.acknowledge() } }) { Text("Filter cleaned — clear reminder") }
            }
        }
    }
}

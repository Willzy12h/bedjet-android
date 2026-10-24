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
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.core.store.deviceId
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
        Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.headlineMedium)
        ConnectionLine(model)
        BusyIndicator(model)
        // No BedJet chosen: the route to choosing one, as on Tonight.
        if (settings.deviceId() == null) {
            SetupFlow(model, compact = true)
            return@Column
        }
        val s = status
        if (state != LinkState.READY || s == null) {
            Text(stringResource(R.string.remote_connect), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(modeName(s.mode), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.remote_air_room, tempText(s.actualTemperature, f), tempText(s.ambientTemperature, f)))
                if (s.isRunning) Text(stringResource(R.string.remote_running, tempText(s.targetTemperature, f), s.fanPercent, durationText(s.remainingSeconds / 60)))
                s.extra?.takeIf { it.programStep > 0 }?.let { Text(stringResource(R.string.remote_program_step, it.programStep), style = MaterialTheme.typography.bodySmall) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.STANDBY).forEach { mode ->
                val name = modeName(mode)
                val what = if (mode == Mode.STANDBY) stringResource(R.string.guard_turn_off) else stringResource(R.string.guard_switch_to, name)
                FilterChip(s.mode == mode, { guard.confirm(what) { model.remote(name, endsNight = true, confirms = { it.mode == mode }) { it.setMode(mode) } } }, label = { Text(name) })
            }
        }
        if (s.isRunning && s.mode.usesTemperature) {
            var temp by remember(s.targetTemperature) { mutableFloatStateOf(s.targetTemperature.toFloat()) }
            val temperatureSet = stringResource(R.string.remote_temperature_set)
            Text(stringResource(R.string.label_temperature, tempText(temp.roundToInt(), f)))
            Slider(
                temp, { temp = it }, valueRange = s.minTemperature.toFloat()..s.maxTemperature.toFloat(),
                modifier = Modifier.sliderSemantics(stringResource(R.string.slider_temp), spokenTemp(temp.roundToInt(), f)),
                steps = (s.maxTemperature - s.minTemperature - 1).coerceAtLeast(0),
                onValueChangeFinished = { val t = temp.roundToInt(); model.remote(temperatureSet, affectsProgramme = true, confirms = { it.targetTemperature == t }) { it.setTemperature(t) } },
            )
        }
        if (s.isRunning && s.mode.usesFan) {
            var fan by remember(s.fanStep) { mutableFloatStateOf(s.fanPercent.toFloat()) }
            val fanSet = stringResource(R.string.remote_fan_set)
            Text(stringResource(R.string.label_fan, fan.roundToInt()))
            Slider(
                fan, { fan = it }, valueRange = 5f..100f, steps = 18,
                modifier = Modifier.sliderSemantics(stringResource(R.string.slider_fan), spokenPercent(fan.roundToInt())),
                onValueChangeFinished = { val step = (fan / 5).roundToInt() - 1; model.remote(fanSet, affectsProgramme = true, confirms = { it.fanStep == step }) { it.setFan(step) } },
            )
        }
        if (s.isRunning) {
            val timerSet = stringResource(R.string.remote_timer_set)
            Text(stringResource(R.string.remote_run_for), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120, 240, 480).forEach { minutes ->
                    AssistChip({ model.remote(timerSet, affectsProgramme = true, confirms = { kotlin.math.abs(it.remainingSeconds - minutes * 60) <= 90 }) { it.setRuntime(minutes) } }, label = { Text(durationText(minutes)) })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val startBoost = stringResource(R.string.guard_start_boost)
            FilledTonalButton({ guard.confirm(startBoost) { model.run(Command.BOOST, replacementConfirmed = true) } }) { Text(stringResource(R.string.remote_boost, settings.boostMinutes)) }
        }
        s.extra?.let { extra ->
            val lightsOn = stringResource(R.string.remote_lights_on)
            val lightsOff = stringResource(R.string.remote_lights_off)
            val beepsOn = stringResource(R.string.remote_beeps_on)
            val beepsOff = stringResource(R.string.remote_beeps_off)
            val reminderCleared = stringResource(R.string.remote_reminder_cleared)
            SwitchRow(stringResource(R.string.remote_lights), null, extra.ledEnabled) { on -> model.remote(if (on) lightsOn else lightsOff, confirms = { it.extra?.ledEnabled == on }) { it.setLed(on) } }
            SwitchRow(stringResource(R.string.remote_beeps), null, !extra.beepsMuted) { on -> model.remote(if (on) beepsOn else beepsOff, confirms = { it.extra?.beepsMuted == !on }) { it.setMuted(!on) } }
            if (extra.notification == 1) {
                FilledTonalButton({ model.remote(reminderCleared, confirms = { it.extra?.notification == 0 }) { it.acknowledge() } }) { Text(stringResource(R.string.remote_clear_reminder)) }
            }
        }
    }
}

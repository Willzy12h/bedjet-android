package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.night.boostDescription
import io.github.willzy12h.nocturne.night.boostLabel

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.manualRuntimeMaximum
import kotlin.math.roundToInt

/** Direct control, like the BedJet's own remote. Changing the mode takes over from a running night. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(model: AppViewModel, modifier: Modifier) {
    val status by model.status.collectAsState()
    val state by model.linkState.collectAsState()
    val hasLiveStatus by model.hasLiveStatus.collectAsState()
    val settings by model.settings.collectAsState()
    val bluetoothOff by model.bluetoothOff.collectAsState()
    val f = settings.fahrenheit
    val guard = rememberNightGuard(model)
    val canControl = state == LinkState.READY && hasLiveStatus
    val context = LocalContext.current
    val timerSet = stringResource(R.string.remote_timer_set)
    var customDuration by remember { mutableStateOf<CapturedDuration?>(null) }
    fun runDuration(captured: CapturedDuration) {
        val words = Format.Words { id, args -> context.getString(id, *args) }
        val what = context.getString(R.string.remote_custom_action, Format.duration(captured.request.actionPreset!!.minutes, words))
        guard.confirm(what, captured.temporary()) { model.remoteDuration(timerSet, captured.request) }
    }
    customDuration?.let { captured ->
        CustomRunDuration(captured, onDismiss = { customDuration = null }, onConfirm = {
            customDuration = null
            runDuration(it)
        })
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.headlineMedium)
        BusyIndicator(model)
        // No BedJet chosen: the route to choosing one, as on Tonight.
        if (settings.deviceId() == null) {
            SetupFlow(model, compact = true)
            return@Column
        }
        val s = status
        if (s == null) {
            if (!bluetoothOff) Text(stringResource(R.string.remote_connect), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!canControl) Text(stringResource(R.string.remote_last_reported), style = MaterialTheme.typography.bodySmall)
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
                FilterChip(s.mode == mode, {
                    if (mode == Mode.STANDBY) guard.confirm(what) {
                        model.remote(name, endsNight = true, confirms = { it.mode == mode }) { it.setMode(mode) }
                    } else {
                        val captured = captureRemoteTemporary(model.graph, mode, s)
                        guard.confirm(what, captured) { model.remoteCaptured(name, captured.request, mode) }
                    }
                }, enabled = canControl, label = { ModeLabel(mode, f) })
            }
        }
        if (s.isRunning && s.mode.usesTemperature) {
            var temp by remember(s.targetTemperature) { mutableFloatStateOf(s.targetTemperature.toFloat()) }
            val temperatureSet = stringResource(R.string.remote_temperature_set)
            Text(stringResource(R.string.label_temperature, tempText(temp.roundToInt(), f)))
            ScaledSlider(
                temp, { temp = it }, range = s.minTemperature.toFloat()..s.maxTemperature.toFloat(),
                enabled = canControl,
                modifier = Modifier.sliderSemantics(stringResource(R.string.slider_temp), spokenTemp(temp.roundToInt(), f)),
                steps = (s.maxTemperature - s.minTemperature - 1).coerceAtLeast(0),
                ticks = listOf(s.minTemperature, (s.minTemperature + s.maxTemperature) / 2, s.maxTemperature)
                    .map { it.toFloat() to tempText(it, f) }, tag = "temperature",
                onValueChangeFinished = { val t = temp.roundToInt(); model.remote(temperatureSet, affectsProgramme = true, confirms = { it.targetTemperature == t }) { it.setTemperature(t) } },
            )
        }
        if (s.isRunning && s.mode.usesFan) {
            var fan by remember(s.fanStep) { mutableFloatStateOf(s.fanPercent.toFloat()) }
            val fanSet = stringResource(R.string.remote_fan_set)
            Text(stringResource(R.string.label_fan, fan.roundToInt()))
            ScaledSlider(
                fan, { fan = it.coerceAtLeast(5f) }, range = 0f..100f, steps = 19,
                enabled = canControl,
                modifier = Modifier.sliderSemantics(stringResource(R.string.slider_fan), spokenPercent(fan.roundToInt())),
                ticks = listOf(0, 50, 100).map { it.toFloat() to stringResource(R.string.remote_percent_tick, it) }, tag = "fan",
                onValueChangeFinished = { val step = (fan / 5).roundToInt() - 1; model.remote(fanSet, affectsProgramme = true,
                    confirms = { it.fanStep == step }, announceConfirmation = false) { it.setFan(step) } },
            )
            Text(stringResource(R.string.remote_fan_minimum), style = MaterialTheme.typography.bodySmall)
        }
        if (s.isRunning) {
            val maximumMinutes = manualRuntimeMaximum(s)
            val timerChoices = listOf(15, 30, 60, 120, 240, 480).filter { it <= maximumMinutes }
                .ifEmpty { listOf(maximumMinutes).filter { it > 0 } }
            Text(stringResource(R.string.remote_run_for), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                timerChoices.forEach { minutes ->
                    AssistChip({ runDuration(captureDuration(model.graph, s, minutes)) }, enabled = canControl, label = { Text(durationText(minutes)) })
                }
                AssistChip({ customDuration = captureDuration(model.graph, s,
                    ((s.remainingSeconds + 59) / 60).coerceIn(1, maximumMinutes)) }, enabled = canControl && maximumMinutes > 0,
                    label = { Text(stringResource(R.string.remote_custom)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val startBoost = stringResource(R.string.guard_start_boost)
            val description = boostDescription(LocalContext.current, settings.boostMinutes)
            FilledTonalButton({
                val captured = model.quickAction(Command.BOOST)
                guard.confirm(startBoost, captured.temporary) { model.runCaptured(captured.end) }
            }, enabled = canControl, modifier = Modifier.semantics { contentDescription = description }) { Text(boostLabel(LocalContext.current, settings.boostMinutes)) }
        }
        s.extra?.let { extra ->
            val reminderCleared = stringResource(R.string.remote_reminder_cleared)
            if (extra.notification == 1) {
                FilledTonalButton({ model.remote(reminderCleared, confirms = { it.extra?.notification == 0 }) { it.acknowledge() } }, enabled = canControl) { Text(stringResource(R.string.remote_clear_reminder)) }
            }
        }
    }
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.manualRuntimeMaximum

internal data class CapturedDuration(val request: CommandRequest, val maximumMinutes: Int) {
    fun forMinutes(minutes: Int) = copy(request = request.copy(actionPreset = requireNotNull(request.actionPreset).copy(minutes = minutes)))
    fun temporary(): TemporaryChoice {
        val preset = requireNotNull(request.actionPreset)
        val maximum = minOf(maximumMinutes, if (preset.mode == Mode.TURBO) 10 else 120)
        return TemporaryChoice(request.copy(actionPreset = preset.copy(minutes = minOf(preset.minutes, maximum))),
            maximum, endPreset = preset)
    }
}

internal fun captureDuration(graph: Graph, status: DeviceStatus, minutes: Int): CapturedDuration = CapturedDuration(
    graph.actions.captureTemporary(ActionPreset(status.mode, status.targetTemperature, status.fanPercent, minutes)),
    manualRuntimeMaximum(status),
)

@Composable
internal fun CustomRunDuration(captured: CapturedDuration, onDismiss: () -> Unit, onConfirm: (CapturedDuration) -> Unit) {
    val initial = requireNotNull(captured.request.actionPreset).minutes.coerceIn(1, captured.maximumMinutes)
    var hours by remember(captured) { mutableStateOf((initial / 60).toString()) }
    var minutes by remember(captured) { mutableStateOf((initial % 60).toString()) }
    val h = hours.toIntOrNull()
    val m = minutes.toIntOrNull()
    val total = if (h != null && h in 0..255 && m != null && m in 0..59) h * 60 + m else null
    val valid = total != null && total in 1..captured.maximumMinutes
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_custom_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.remote_custom_bounds, durationText(captured.maximumMinutes)))
                OutlinedTextField(hours, { hours = it.take(4) }, label = { Text(stringResource(R.string.remote_custom_hours)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(minutes, { minutes = it.take(3) }, label = { Text(stringResource(R.string.remote_custom_minutes)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (!valid) Text(stringResource(R.string.remote_custom_invalid))
            }
        },
        confirmButton = { TextButton(onClick = { if (valid) onConfirm(captured.forMinutes(total!!)) }, enabled = valid) {
            Text(stringResource(R.string.remote_custom_set)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_cancel)) } },
    )
}

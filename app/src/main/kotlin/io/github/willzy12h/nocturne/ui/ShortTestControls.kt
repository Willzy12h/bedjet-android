package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.ShortTestPreview
import io.github.willzy12h.nocturne.night.bedJetTargetName

/** Local preview first; only the positive dialog action submits the captured equipment test. */
@Composable
fun ShortTestControls(model: AppViewModel) {
    val settings by model.settings.collectAsState()
    val night by model.activeNight.collectAsState()
    val busy by model.busy.collectAsState()
    var preview by remember { mutableStateOf<ShortTestPreview?>(null) }
    val words = rememberWords()
    Text(stringResource(R.string.short_test_help), style = MaterialTheme.typography.bodySmall)
    if (night != null) Text(stringResource(R.string.short_test_active), style = MaterialTheme.typography.bodySmall)
    else if (settings.deviceId() == null) Text(stringResource(R.string.runtime_choose_target), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { preview = model.shortTestPreview() },
        enabled = night == null && settings.deviceId() != null && !busy) { Text(stringResource(R.string.short_test_preview)) }
    preview?.let { shown ->
        AlertDialog(onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.short_test_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.short_test_target, bedJetTargetName(LocalContext.current, shown.deviceId), shown.slot + 1))
                    Text(stringResource(R.string.short_test_phases, modeName(Mode.COOL), Format.temp(44, settings.fahrenheit, words),
                        modeName(Mode.HEAT), Format.temp(60, settings.fahrenheit, words)))
                    Text(stringResource(R.string.short_test_confirm_help))
                }
            },
            confirmButton = { TextButton(onClick = { preview = null; model.startShortTest(shown) }, enabled = !busy) {
                Text(stringResource(R.string.short_test_start))
            } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.feature_cancel)) } },
        )
    }
}

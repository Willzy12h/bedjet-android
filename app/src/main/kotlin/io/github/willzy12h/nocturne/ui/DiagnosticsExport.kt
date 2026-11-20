package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.launch

@Composable
internal fun DiagnosticsExport(model: AppViewModel) {
    var includeAlarmTimes by rememberSaveable { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    // Android may recreate the activity while its document picker is in front.
    var reviewedReport by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingFile by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        choosingFile = false
        if (uri != null) {
            reviewedReport?.let { model.exportReviewedDiagnostics(uri, it) }
            reviewedReport = null
        }
    }

    Text(stringResource(R.string.settings_diagnostics_help), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.diagnostics_times), stringResource(R.string.diagnostics_times_help), includeAlarmTimes,
        enabled = !preparing) { includeAlarmTimes = it }
    NocturneOutlinedButton(onClick = {
        preparing = true
        scope.launch {
            try { reviewedReport = model.diagnosticsPreview(includeAlarmTimes) }
            finally { preparing = false }
        }
    }, enabled = !preparing) {
        Text(stringResource(if (preparing) R.string.diagnostics_preparing else R.string.settings_diagnostics_export))
    }

    reviewedReport?.takeUnless { choosingFile }?.let { report ->
        AlertDialog(onDismissRequest = { reviewedReport = null },
            title = { Text(stringResource(R.string.diagnostics_preview)) },
            text = { Text(report, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("diagnostics-preview-content"),
                style = MaterialTheme.typography.bodySmall) },
            confirmButton = { NocturneTextButton(onClick = {
                choosingFile = true
                picker.launch("nocturne-diagnostics-${model.now().toLocalDate()}.json")
            }, modifier = Modifier.testTag("diagnostics-save")) { Text(stringResource(R.string.diagnostics_save)) } },
            dismissButton = { NocturneTextButton(onClick = { reviewedReport = null }) { Text(stringResource(R.string.feature_cancel)) } })
    }
}

package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton

/**
 * On a test build: installing a release means uninstalling it first (they're signed with different keys), and that
 * deletes the profiles and settings. [onExport] is offered where the export button isn't already beside it.
 */
@Composable
internal fun TestBuildBackupNote(onExport: (() -> Unit)? = null) {
    StripeTile(stripe = paletteTokens.warn, title = stringResource(R.string.test_build_backup_title), subtitle = null,
        modifier = Modifier.fillMaxWidth().testTag("test-build-backup-note")) {
        Text(stringResource(R.string.test_build_backup_body), style = MaterialTheme.typography.bodySmall)
        if (onExport != null) FilledTonalButton(onClick = onExport) { Text(stringResource(R.string.settings_export_file)) }
    }
}

/**
 * On Tonight after a fresh install, until dismissed or a backup is restored: restore a backup file, as after moving
 * from a test build to a release. The file is checked and previewed before anything changes, as in Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RestoreOfferCard(model: AppViewModel) {
    val offered by model.restoreOffer.collectAsStateWithLifecycle()
    val importApplying by model.importApplying.collectAsStateWithLifecycle()
    val importPreview by model.importPreview.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
    if (offered) StripeTile(stripe = paletteTokens.accent, title = stringResource(R.string.restore_offer_title), subtitle = null,
        modifier = Modifier.fillMaxWidth().testTag("restore-offer")) {
        Text(stringResource(R.string.restore_offer_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = !importApplying, onClick = { importer.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.testTag("restore-offer-restore")) { Text(stringResource(R.string.restore_offer_restore)) }
            TextButton(enabled = !importApplying, onClick = model::dismissRestoreOffer,
                modifier = Modifier.testTag("restore-offer-dismiss")) { Text(stringResource(R.string.restore_offer_dismiss)) }
        }
    }
    importPreview?.let { preview ->
        androidx.compose.runtime.key(preview) {
            ImportPreviewDialog(preview, importApplying, onApply = { model.confirmImport(preview) }, onCancel = model::cancelImport)
        }
    }
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.SlotWrite

/**
 * What the unreadable-data banner says. A restore journal that is still pending is an unfinished restore, not a
 * file set aside, and it can't be dismissed; the originals barrier outlives a dismissal too (IR-03).
 */
data class UnreadableBannerState(val files: List<String>, val restoreUnfinished: Boolean, val originalsBlocked: Boolean) {
    /** Files set aside that a dismissal can clear. */
    val setAside: List<String> get() = if (restoreUnfinished) files - Repository.RESTORE_JOURNAL else files
    val visible: Boolean get() = files.isNotEmpty() || originalsBlocked
    val dismissible: Boolean get() = setAside.isNotEmpty()
}

/** A calm card near the top of Tonight (and in Settings > Backups): View, Restore and Dismiss. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnreadableDataBanner(state: UnreadableBannerState, onDismiss: () -> Unit, onRestore: (() -> Unit)?, onAcceptLoss: () -> Unit) {
    if (!state.visible) return
    var details by rememberSaveable { mutableStateOf(false) }
    var accepting by rememberSaveable { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.restoreUnfinished) {
                Text(stringResource(R.string.restore_unfinished_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.restore_unfinished_text), style = MaterialTheme.typography.bodyMedium)
                if (details) Text(stringResource(R.string.restore_unfinished_details), style = MaterialTheme.typography.bodySmall)
            }
            if (state.setAside.isNotEmpty()) {
                Text(stringResource(R.string.unreadable_banner), style = MaterialTheme.typography.bodyMedium)
                if (details) Text(stringResource(R.string.unreadable_details, state.setAside.joinToString()), style = MaterialTheme.typography.bodySmall)
            }
            if (state.originalsBlocked) Text(stringResource(R.string.originals_blocked_text), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.files.isNotEmpty()) TextButton(onClick = { details = !details }) {
                    Text(stringResource(if (details) R.string.unreadable_hide else R.string.unreadable_view))
                }
                onRestore?.let { TextButton(onClick = it) { Text(stringResource(R.string.unreadable_restore)) } }
                if (state.dismissible) TextButton(onClick = onDismiss) { Text(stringResource(R.string.unreadable_dismiss)) }
                if (state.originalsBlocked) TextButton(onClick = { accepting = true }) { Text(stringResource(R.string.originals_accept_button)) }
            }
        }
    }
    if (accepting) AcceptLostOriginalsDialog(onConfirm = { accepting = false; onAcceptLoss() }, onCancel = { accepting = false })
}

/** Asked before [AppViewModel.acceptLostOriginals]: says plainly that future originals are marked uncertain. */
@Composable
fun AcceptLostOriginalsDialog(onConfirm: () -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(stringResource(R.string.originals_accept_title)) },
    text = { Text(stringResource(R.string.originals_accept_text)) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.originals_accept_confirm)) } },
    dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.originals_accept_cancel)) } },
)

/** A slot write the BedJet never confirmed (IR-06), with the person's own "checked" acknowledgement. */
@Composable
fun SlotWriteNotice(write: SlotWrite, onChecked: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.slot_write_notice, write.label, write.slot + 1), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onChecked) { Text(stringResource(R.string.slot_write_checked)) }
        }
    }
}

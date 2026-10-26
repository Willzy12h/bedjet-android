package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.ui.features.PhoneHandoverMenuItems
import io.github.willzy12h.nocturne.core.store.deviceId
import java.time.ZonedDateTime

/** Recovery stays available without a running night, including release to another phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NightRecoveryControls(model: AppViewModel, night: ActiveNight?, guard: NightGuard, now: ZonedDateTime) {
    val runtime by model.runtime.collectAsState()
    val settings by model.settings.collectAsState()
    val offer = runtime.resumeOffer?.takeIf { candidate ->
        candidate.night.deviceId == settings.deviceId() && candidate.commandVersion == runtime.generation &&
            runCatching { now.isBefore(ZonedDateTime.parse(candidate.expiresAt)) }.getOrDefault(false)
    }
    val resumeSaved = stringResource(R.string.guard_resume_saved)
    val resumePrevious = stringResource(R.string.guard_resume_previous)
    var more by remember { mutableStateOf(false) }
    var stopTracking by remember { mutableStateOf(false) }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { more = true }) { Text(stringResource(R.string.recovery_more)) }
            if (night?.confidence == Confidence.UNKNOWN) {
                OutlinedButton(onClick = { model.run(Command.KEEP_TRACKING) }) { Text(stringResource(R.string.runtime_command_keep_tracking)) }
            }
            if (night != null) {
                OutlinedButton(onClick = { stopTracking = true }) { Text(stringResource(R.string.runtime_command_stop_tracking)) }
            }
            // The offer is for this night's own programme, which a manual run left (P3b).
            if (night?.trackingManual == true && offer?.night?.recordId == night.recordId) {
                OutlinedButton(onClick = { guard.resume(resumeSaved) { model.run(Command.RESUME, replacementConfirmed = true) } }) { Text(stringResource(R.string.recovery_resume_programme)) }
            }
            if (night == null && offer != null) {
                OutlinedButton(onClick = { model.run(Command.RESUME) }) { Text(stringResource(R.string.recovery_resume_previous)) }
            }
            // A night replaced by this one can still be resumed while its offer lasts.
            if (night != null && offer != null && offer.night.recordId != night.recordId) {
                OutlinedButton(onClick = { guard.resume(resumePrevious) { model.run(Command.RESUME, replacementConfirmed = true) } }) { Text(stringResource(R.string.recovery_resume_previous)) }
            }
        }
        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            if (night != null) DropdownMenuItem(text = { Text(stringResource(R.string.recovery_recheck_alarm)) }, onClick = {
                more = false
                model.recheckAlarm()
            })
            PhoneHandoverMenuItems(model.graph, onOutcome = model::report) { more = false }
        }
    }
    if (stopTracking) AlertDialog(
        onDismissRequest = { stopTracking = false },
        title = { Text(stringResource(R.string.recovery_stop_tracking_title)) },
        text = { Text(stringResource(R.string.stop_tracking_text)) },
        confirmButton = { TextButton(onClick = { stopTracking = false; model.run(Command.STOP_TRACKING) }) { Text(stringResource(R.string.runtime_command_stop_tracking)) } },
        dismissButton = { TextButton(onClick = { stopTracking = false }) { Text(stringResource(R.string.runtime_command_keep_tracking)) } },
    )
}

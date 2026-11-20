package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.AlarmReason
import io.github.willzy12h.nocturne.night.AlarmSettingsTarget
import io.github.willzy12h.nocturne.night.WakeExplanationState
import io.github.willzy12h.nocturne.night.description

@Composable
fun AlarmExplanationLine(explanation: AlarmExplanation, wakeTimeShown: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val tokens = paletteTokens
    var details by rememberSaveable { mutableStateOf(false) }
    val action = stringResource(when (explanation.target) {
        AlarmSettingsTarget.ALARM_SOURCES -> R.string.alarm_source_open_sources
        AlarmSettingsTarget.PROFILE_WAKE -> R.string.alarm_source_open_profile
        AlarmSettingsTarget.TONIGHT_WAKE -> R.string.alarm_source_open_tonight
        AlarmSettingsTarget.MISSING_ALARM -> R.string.alarm_source_open_missing
    })
    val label = when {
        explanation.unnamedOffer != null -> stringResource(R.string.alarm_row_unnamed, Format.clock(explanation.unnamedOffer))
        explanation.state == WakeExplanationState.SAVED -> stringResource(when (explanation.wakeOrigin) {
            WakeOrigin.ALARM -> R.string.alarm_row_saved_alarm
            WakeOrigin.OVERRIDE -> R.string.alarm_row_tonight
            WakeOrigin.AUTO_START -> R.string.wake_origin_auto_start
            else -> R.string.alarm_row_profile
        })
        explanation.state == WakeExplanationState.PENDING -> stringResource(R.string.alarm_row_pending)
        explanation.state == WakeExplanationState.FAILED -> stringResource(R.string.alarm_row_failed)
        explanation.state == WakeExplanationState.UNKNOWN -> stringResource(R.string.alarm_row_unknown)
        explanation.state == WakeExplanationState.MANUAL -> stringResource(R.string.alarm_row_manual)
        explanation.wakeOrigin == WakeOrigin.ALARM -> stringResource(R.string.alarm_row_alarm)
        explanation.wakeOrigin == WakeOrigin.OVERRIDE -> stringResource(R.string.alarm_row_tonight)
        explanation.reason == AlarmReason.NO_ALARM -> stringResource(R.string.alarm_row_missing)
        explanation.reason == AlarmReason.NO_PROFILE -> stringResource(R.string.alarm_source_no_profile)
        else -> stringResource(R.string.alarm_row_profile)
    }
    val time = explanation.wakeAt?.takeUnless { wakeTimeShown || explanation.unnamedOffer != null ||
        explanation.state == WakeExplanationState.MANUAL ||
        explanation.state == WakeExplanationState.UNKNOWN }?.let(Format::dayClock)
    val opensMissing = explanation.target == AlarmSettingsTarget.MISSING_ALARM
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tonight-alarm-row")
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.alarm_row_details)) {
            if (opensMissing) onClick() else details = true
        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_nc_alarm), null, Modifier.size(16.dp), tint = tokens.visible(tokens.accent))
        Text(time?.let { stringResource(R.string.alarm_row_with_time, label, it) } ?: label, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = tokens.ink2)
        Icon(painterResource(R.drawable.ic_nc_chevron_down), null, Modifier.size(16.dp).rotate(-90f), tint = tokens.ink2)
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, modifier = Modifier.testTag("alarm-row-details"),
        title = { Text(stringResource(R.string.alarm_row_details)) },
        text = { Text(explanation.description(context), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { NocturneTextButton(onClick = { details = false; onClick() },
            modifier = Modifier.testTag("alarm-row-open-settings")) { Text(action) } },
        dismissButton = { NocturneTextButton(onClick = { details = false }) { Text(stringResource(android.R.string.cancel)) } })
}

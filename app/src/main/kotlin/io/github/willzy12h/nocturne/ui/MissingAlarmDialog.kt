package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.AlarmReason

/**
 * Nocturne can't see the person's alarm (none in Android's next-alarm slot, or one Android didn't name). Says why,
 * and offers the two ways round it: a wake time for tonight, which the BedJet keeps even if the phone closes
 * Nocturne, or a fixed wake time in the profile. With "Ask each night", an unnamed alarm can be used for tonight with
 * one tap ([onUseUnnamed]). A null action is left out.
 */
@Composable
fun MissingAlarmDialog(explanation: AlarmExplanation, onDismiss: () -> Unit, onSetTonight: (() -> Unit)?,
    onFixedWake: (() -> Unit)?, onChooseAlarmApps: () -> Unit, onUseUnnamed: (() -> Unit)? = null) {
    val unnamed = explanation.reason == AlarmReason.UNKNOWN_SOURCE
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("missing-alarm-dialog"),
        title = { Text(stringResource(R.string.missing_alarm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (unnamed) explanation.alarm?.let {
                    Text(stringResource(R.string.missing_alarm_body_unnamed, Format.clock(it.time)),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.missing_alarm_body), style = MaterialTheme.typography.bodyMedium)
                explanation.unnamedOffer?.takeIf { onUseUnnamed != null }?.let { offer ->
                    Action(stringResource(R.string.missing_alarm_use_unnamed, Format.clock(offer)), "missing-alarm-use-unnamed") {
                        onDismiss(); onUseUnnamed?.invoke()
                    }
                    Text(stringResource(R.string.missing_alarm_use_unnamed_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                onSetTonight?.let {
                    Action(stringResource(R.string.missing_alarm_tonight), "missing-alarm-tonight") { onDismiss(); it() }
                    Text(stringResource(R.string.missing_alarm_tonight_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                onFixedWake?.let { Action(stringResource(R.string.missing_alarm_fixed), "missing-alarm-fixed") { onDismiss(); it() } }
                // An unnamed alarm can come from an app that could be chosen once Android names it.
                if (unnamed) Action(stringResource(R.string.missing_alarm_sources), "missing-alarm-sources") {
                    onDismiss(); onChooseAlarmApps()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun Action(label: String, tag: String, onClick: () -> Unit) {
    HorizontalDivider(Modifier.fillMaxWidth())
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
}

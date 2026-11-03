package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.AlarmApps
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.ClockApps
import io.github.willzy12h.nocturne.night.alarmSourceName

/** The current choice in a few words, for Settings and setup. */
@Composable
fun alarmAppsSummary(settings: Settings): String {
    val context = LocalContext.current
    return when (settings.alarmApps) {
        AlarmApps.CLOCK_APPS -> stringResource(R.string.alarm_apps_row_any)
        AlarmApps.ONE_APP -> stringResource(R.string.alarm_apps_row_only, alarmSourceName(context, ClockApps.chosenApp(settings)))
        AlarmApps.NONE -> stringResource(R.string.alarm_apps_row_none)
    }
}

/**
 * Which alarms set the wake-up: any clock app, one clock app, or none. Shows the phone's next alarm and whether it
 * counts, so a stray alarm from a calendar is easy to understand. [extra] adds actions below the choices, such as a
 * fixed wake time for tonight's profile.
 */
@Composable
fun AlarmAppsDialog(model: AppViewModel, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
    val settings by model.settings.collectAsState()
    val apps by model.clockApps.collectAsState()
    val next by model.nextAlarm.collectAsState()
    LaunchedEffect(Unit) { model.refreshClockApps(); model.refreshAlarm() }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("alarm-apps-dialog"),
        title = { Text(stringResource(R.string.alarm_apps_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AlarmAppsChoices(settings, apps, next) { choice, app -> model.chooseAlarmApps(choice, app) }
                extra()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.alarm_apps_done)) } })
}

/** The choices themselves; setup shows them on its own page. */
@Composable
fun AlarmAppsChoices(settings: Settings, apps: List<ClockApps.App>, next: AlarmSource.NextAlarm?,
    onChoose: (AlarmApps, String?) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.alarm_apps_question), style = MaterialTheme.typography.bodyMedium)
    val chosen = ClockApps.chosenApp(settings)
    val shownApps = apps.ifEmpty {
        // Lookup hidden or empty: still offer the two clock apps Nocturne knows.
        listOf(ClockApps.App("com.google.android.deskclock", alarmSourceName(context, "com.google.android.deskclock")),
            ClockApps.App("com.sec.android.app.clockpackage", alarmSourceName(context, "com.sec.android.app.clockpackage")))
    }
    ChoiceRow(settings.alarmApps == AlarmApps.CLOCK_APPS, stringResource(R.string.alarm_apps_any),
        if (apps.isEmpty()) stringResource(R.string.alarm_apps_any_none_found)
        else stringResource(R.string.alarm_apps_any_found, apps.joinToString { it.label }), "alarm-apps-any") {
        onChoose(AlarmApps.CLOCK_APPS, null)
    }
    shownApps.forEach { app ->
        val selected = settings.alarmApps == AlarmApps.ONE_APP && chosen != null &&
            app.packageName in ClockApps.packagesFor(AlarmApps.ONE_APP, apps, chosen)
        ChoiceRow(selected, stringResource(R.string.alarm_apps_only, app.label), null, "alarm-apps-only-${app.packageName}") {
            onChoose(AlarmApps.ONE_APP, app.packageName)
        }
    }
    ChoiceRow(settings.alarmApps == AlarmApps.NONE, stringResource(R.string.alarm_apps_none),
        stringResource(R.string.alarm_apps_none_help), "alarm-apps-none") { onChoose(AlarmApps.NONE, null) }
    Text(stringResource(R.string.alarm_apps_never), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    next?.let {
        Text(stringResource(if (it.counts) R.string.alarm_apps_next_used else R.string.alarm_apps_next_unused,
            Format.clock(it.time), alarmSourceName(context, it.identifiedCreator)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, help: String?, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .selectable(selected, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, colors = nocturneRadioColors())
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A plain action under the choices. */
@Composable
fun AlarmAppsAction(label: String, onClick: () -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

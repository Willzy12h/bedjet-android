package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.drawable.toBitmap
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
    fun names(packages: Collection<String>) = packages.map { alarmSourceName(context, it) }.distinct().sorted().joinToString()
    return when (settings.alarmApps) {
        AlarmApps.CLOCK_APPS -> {
            val clockApps = remember(context) { runCatching { ClockApps.installed(context) }.getOrDefault(emptyList()) }
            val extras = ClockApps.extraApps(settings, clockApps)
            if (extras.isEmpty()) stringResource(R.string.alarm_apps_row_any)
            else stringResource(R.string.alarm_apps_row_any_plus, names(extras))
        }
        AlarmApps.ONE_APP -> if (settings.alarmPackages.isEmpty()) stringResource(R.string.alarm_apps_row_nothing)
            else stringResource(R.string.alarm_apps_row_only, names(settings.alarmPackages))
        AlarmApps.NONE -> stringResource(R.string.alarm_apps_row_none)
    }
}

/**
 * Which alarms set the wake-up: any clock app, only the apps the person ticks (a white-noise or sleep app among them),
 * or none. Shows the phone's next alarm and whether it counts, so a stray alarm from a calendar is easy to place.
 * [extra] adds actions below the choices, such as a fixed wake time for tonight's profile.
 */
@Composable
fun AlarmAppsDialog(model: AppViewModel, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
    val settings by model.settings.collectAsState()
    val apps by model.clockApps.collectAsState()
    val others by model.otherAlarmApps.collectAsState()
    val allShown by model.allAlarmAppsShown.collectAsState()
    val next by model.nextAlarm.collectAsState()
    LaunchedEffect(Unit) { model.refreshClockApps(); model.refreshAlarm(); model.refreshOtherAlarmApps() }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("alarm-apps-dialog"),
        title = { Text(stringResource(R.string.alarm_apps_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AlarmAppsChoices(settings, apps, next, others, allShown, onShowMore = { model.refreshOtherAlarmApps(all = true) }) {
                    choice, chosen -> model.chooseAlarmApps(choice, chosen)
                }
                extra()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.alarm_apps_done)) } })
}

/** The choices themselves; setup shows them on its own page. */
@Composable
fun AlarmAppsChoices(settings: Settings, apps: List<ClockApps.App>, next: AlarmSource.NextAlarm?,
    others: List<ClockApps.App> = emptyList(), allShown: Boolean = true, onShowMore: () -> Unit = {},
    onChoose: (AlarmApps, Set<String>) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.alarm_apps_question), style = MaterialTheme.typography.bodyMedium)
    val shownApps = apps.ifEmpty {
        // Lookup hidden or empty: still offer the two clock apps Nocturne knows.
        listOf(ClockApps.App("com.google.android.deskclock", alarmSourceName(context, "com.google.android.deskclock")),
            ClockApps.App("com.sec.android.app.clockpackage", alarmSourceName(context, "com.sec.android.app.clockpackage")))
    }
    ChoiceRow(settings.alarmApps == AlarmApps.CLOCK_APPS, stringResource(R.string.alarm_apps_any),
        if (apps.isEmpty()) stringResource(R.string.alarm_apps_any_none_found)
        else stringResource(R.string.alarm_apps_any_found, apps.joinToString { it.label }), "alarm-apps-any") {
        onChoose(AlarmApps.CLOCK_APPS, ClockApps.extraApps(settings, shownApps) +
            if (settings.alarmApps == AlarmApps.ONE_APP) settings.alarmPackages - shownApps.map { it.packageName }.toSet() else emptySet())
    }
    ChoiceRow(settings.alarmApps == AlarmApps.ONE_APP, stringResource(R.string.alarm_apps_ticked),
        stringResource(R.string.alarm_apps_ticked_help), "alarm-apps-ticked") {
        val (choice, chosen) = ClockApps.onlyTicked(settings, shownApps)
        onChoose(choice, chosen)
    }
    ChoiceRow(settings.alarmApps == AlarmApps.NONE, stringResource(R.string.alarm_apps_none),
        stringResource(R.string.alarm_apps_none_help), "alarm-apps-none") { onChoose(AlarmApps.NONE, emptySet()) }

    fun toggle(app: ClockApps.App) { val (choice, chosen) = ClockApps.afterToggle(settings, shownApps, app); onChoose(choice, chosen) }
    fun hint(app: ClockApps.App): String? = when {
        next != null && app.packageName == next.identifiedCreator -> context.getString(R.string.alarm_apps_set_next, Format.clock(next.time))
        app.seen -> context.getString(R.string.alarm_apps_seen)
        else -> null
    }
    SectionLabel(stringResource(R.string.alarm_apps_clock_header))
    shownApps.forEach { app ->
        AppRow(app, ClockApps.isTicked(settings, app), hint(app), "alarm-apps-only-${app.packageName}") { toggle(app) }
    }
    SectionLabel(stringResource(R.string.alarm_apps_other_header))
    Text(stringResource(R.string.alarm_apps_other_help), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (others.isEmpty()) Text(stringResource(if (allShown) R.string.alarm_apps_other_none_at_all else R.string.alarm_apps_other_none),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("alarm-apps-other-none"))
    others.forEach { app ->
        AppRow(app, ClockApps.isTicked(settings, app), hint(app), "alarm-apps-other-${app.packageName}") { toggle(app) }
    }
    if (!allShown) TextButton(onClick = onShowMore, modifier = Modifier.heightIn(min = 48.dp).testTag("alarm-apps-more")) {
        Text(stringResource(R.string.alarm_apps_show_more))
    }
    Text(stringResource(R.string.alarm_apps_never), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    next?.let {
        Text(stringResource(if (it.counts) R.string.alarm_apps_next_used else R.string.alarm_apps_next_unused,
            Format.clock(it.time), alarmSourceName(context, it.identifiedCreator)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 12.dp).semantics { heading() })

/** One app with its own icon and name, and a tick box. */
@Composable
private fun AppRow(app: ClockApps.App, ticked: Boolean, help: String?, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .toggleable(ticked, role = Role.Checkbox, onValueChange = { onClick() }), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(ticked, onCheckedChange = null, colors = nocturneCheckboxColors())
        AppIcon(app.packageName, Modifier.padding(start = 8.dp).size(32.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The app's own launcher icon, or nothing where Android doesn't show it to Nocturne. */
@Composable
private fun AppIcon(packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = modifier) else Spacer(modifier)
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

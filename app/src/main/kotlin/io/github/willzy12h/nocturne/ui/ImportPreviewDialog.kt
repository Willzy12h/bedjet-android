package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.data.ImportArea
import io.github.willzy12h.nocturne.data.ImportDisposition
import io.github.willzy12h.nocturne.data.ImportEntry
import io.github.willzy12h.nocturne.data.ImportPreview
import io.github.willzy12h.nocturne.data.ImportWarning
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** File imports and saved snapshots share the same review and confirmation surface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportPreviewDialog(preview: ImportPreview, applying: Boolean, onApply: () -> Unit, onCancel: () -> Unit) {
    var expanded by remember(preview) { mutableStateOf<ImportArea?>(null) }
    var shown by remember(preview, expanded) { mutableIntStateOf(20) }
    var showDevices by remember(preview) { mutableStateOf(false) }
    var deviceCount by remember(preview) { mutableIntStateOf(20) }
    AlertDialog(
        onDismissRequest = { if (!applying) onCancel() },
        title = { Text(stringResource(R.string.import_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!preview.canApply) {
                    Text(stringResource(R.string.import_incompatible), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.import_review_help))
                    Text(stringResource(R.string.import_created_at, readableImportTime(preview.createdAt)), style = MaterialTheme.typography.bodySmall)
                    preview.warnings.forEach { warning -> Text(stringResource(importWarning(warning))) }
                    if (preview.deviceAssociations.isNotEmpty()) {
                        TextButton(onClick = { showDevices = !showDevices }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (showDevices) R.string.import_hide_devices else R.string.import_show_devices))
                        }
                        if (showDevices) {
                            preview.deviceAssociations.take(deviceCount).forEach { Text(importDevice(it), style = MaterialTheme.typography.bodySmall) }
                            if (deviceCount < preview.deviceAssociations.size) TextButton(onClick = { deviceCount += 20 }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.import_more_devices))
                            }
                        }
                    }
                    preview.sections.forEach { section ->
                        HorizontalDivider()
                        val area = stringResource(importArea(section.area))
                        Text(area, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ImportDisposition.entries.forEach { disposition ->
                                Text(stringResource(R.string.import_count, stringResource(importDisposition(disposition)), section.count(disposition)),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (section.entries.isNotEmpty()) {
                            val open = expanded == section.area
                            TextButton(onClick = { expanded = if (open) null else section.area }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(if (open) R.string.import_hide_details else R.string.import_show_details, area))
                            }
                            if (open) {
                                section.entries.take(shown).forEach { entry -> ImportEntryRow(section.area, entry) }
                                if (shown < section.entries.size) TextButton(onClick = { shown += 20 }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.import_more_entries))
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.import_recovery_snapshot), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onApply, enabled = preview.canApply && !applying, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (applying) R.string.import_applying else R.string.import_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !applying, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.feature_cancel))
            }
        },
    )
}

@Composable
private fun ImportEntryRow(area: ImportArea, entry: ImportEntry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val name = when (area) {
            ImportArea.SETTINGS -> stringResource(importSetting(entry.id))
            ImportArea.ORIGINALS -> stringResource(R.string.import_original_slot, requireNotNull(entry.slot) + 1,
                entry.name?.ifBlank { stringResource(R.string.import_unnamed_programme) } ?: stringResource(R.string.import_empty_slot))
            else -> entry.name.orEmpty()
        }
        Text(stringResource(R.string.import_entry_action, name, stringResource(importDisposition(entry.disposition))),
            style = MaterialTheme.typography.bodyMedium)
        entry.at?.let { Text(readableImportTime(it), style = MaterialTheme.typography.bodySmall) }
        entry.deviceId?.let { Text(importDevice(it), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun importDevice(id: String): String = when (id) {
    Migrations.UNKNOWN_DEVICE -> stringResource(R.string.import_device_unknown)
    DeviceId.DEMO -> stringResource(R.string.import_device_demo)
    else -> stringResource(R.string.import_device_address, DeviceId.address(id) ?: id)
}

private fun readableImportTime(value: String): String = runCatching {
    ZonedDateTime.parse(value).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(value)

@StringRes private fun importArea(area: ImportArea): Int = when (area) {
    ImportArea.PROFILES -> R.string.import_profiles
    ImportArea.SETTINGS -> R.string.import_settings
    ImportArea.HISTORY -> R.string.import_history
    ImportArea.ORIGINALS -> R.string.import_originals
}

@StringRes private fun importDisposition(disposition: ImportDisposition): Int = when (disposition) {
    ImportDisposition.ADDED -> R.string.import_added
    ImportDisposition.REPLACED -> R.string.import_replaced
    ImportDisposition.PRESERVED -> R.string.import_preserved
    ImportDisposition.UNCHANGED -> R.string.import_unchanged
    ImportDisposition.REMOVED -> R.string.import_removed
    ImportDisposition.OMITTED -> R.string.import_omitted
}

@StringRes private fun importWarning(warning: ImportWarning): Int = when (warning) {
    ImportWarning.UNCERTAIN_ORIGINALS -> R.string.import_uncertain_originals
    ImportWarning.UNASSIGNED_ORIGINALS -> R.string.import_unassigned_originals
    ImportWarning.DEVICE_ASSOCIATIONS -> R.string.import_device_associations
    ImportWarning.UNSUPPORTED_ORIGINALS -> R.string.import_unsupported_originals
    ImportWarning.ORIGINALS_PROTECTED -> R.string.import_originals_protected
    ImportWarning.HISTORY_OMITTED -> R.string.import_history_omitted
    ImportWarning.IMPORTED_HISTORY_UNVERIFIED -> R.string.import_history_unverified
    ImportWarning.AUTOMATIC_ACTIONS_DISABLED -> R.string.import_automatic_disabled
}

/** Serialized keys stay internal; the review uses the same plain-language concepts as Settings. */
@StringRes private fun importSetting(key: String): Int = when (key) {
    "workingSlot" -> R.string.import_setting_working_slot
    "selectedProfileId" -> R.string.import_setting_saved_profile
    "tonightChoice" -> R.string.import_setting_tonight_profile
    "tonightOverride" -> R.string.import_setting_tonight_wake
    "acceptUnknownAlarmSource" -> R.string.import_setting_unknown_alarms
    "notificationActions" -> R.string.import_setting_notification_actions
    "snoozeWarm" -> R.string.import_setting_snooze_warm
    "snoozeCool" -> R.string.import_setting_snooze_cool
    "extendWarm" -> R.string.import_setting_quick_warm
    "extendCool" -> R.string.import_setting_quick_cool
    "boostMinutes" -> R.string.import_setting_boost
    "fahrenheit" -> R.string.import_setting_units
    "demoMode" -> R.string.import_setting_demo
    "targetDevice" -> R.string.import_setting_target
    "setupFanCheckedDevice" -> R.string.import_setting_setup_check
    "rememberedBedJets" -> R.string.import_setting_remembered
    "demoTargetMigrated" -> R.string.import_setting_pairing
    "alarmPackages" -> R.string.import_setting_alarm_apps
    "themeMode" -> R.string.import_setting_theme
    "scheduledDarkMinute" -> R.string.import_setting_dark_from
    "scheduledLightMinute" -> R.string.import_setting_light_from
    "simpleView" -> R.string.import_setting_simple
    "quickControlsCollapsed" -> R.string.import_setting_quick_collapsed
    "collapsedTonightSections" -> R.string.import_setting_sections_collapsed
    "tonightSectionOrder" -> R.string.import_setting_section_order
    "quietNightNotification" -> R.string.import_setting_quiet_notification
    "platform.bedtimeReminder" -> R.string.import_setting_reminder
    "platform.reminderMinutes" -> R.string.import_setting_reminder_time
    "platform.chargerAutoStart", "platform.autoStart" -> R.string.import_setting_auto_start
    "platform.chargerDevice" -> R.string.import_setting_auto_start_device
    "platform.healthSleepOverlay" -> R.string.import_setting_health_overlay
    "platform.healthSleepExport" -> R.string.import_setting_health_export
    else -> R.string.import_setting_other
}

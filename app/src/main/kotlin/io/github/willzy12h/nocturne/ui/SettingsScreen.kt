package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.annotation.StringRes
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.SnapshotInfo
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.WakeAction
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.timedPresetRefusal
import io.github.willzy12h.nocturne.night.validCapturedPreset
import io.github.willzy12h.nocturne.ui.features.PlatformSettingsSection
import io.github.willzy12h.nocturne.ui.features.PhoneHandoverMenuItems
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(model: AppViewModel, modifier: Modifier, backupsRequest: Int = 0, alarmsRequest: Int = 0) {
    val settings by model.settings.collectAsState()
    val snapshots by model.snapshots.collectAsState()
    val slotBackups by model.slotBackups.collectAsState()
    val fromBackup = slotBackups.filter { it.deviceId != settings.deviceId() }
    val unreadable by model.unreadable.collectAsState()
    val restoreUnfinished by model.restoreUnfinished.collectAsState()
    val originalsBlocked by model.originalsBlocked.collectAsState()
    val backups = remember { BringIntoViewRequester() }
    LaunchedEffect(backupsRequest) { if (backupsRequest > 0) { delay(100); backups.bringIntoView() } }
    val alarms = remember { BringIntoViewRequester() }
    LaunchedEffect(alarmsRequest) { if (alarmsRequest > 0) { delay(100); alarms.bringIntoView() } }
    val alarm by model.nextAlarm.collectAsState()
    val alarmCheck by model.alarmCheck.collectAsState()
    val f = settings.fahrenheit
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    var pendingTheme by remember { mutableStateOf<ThemeMode?>(null) }
    var restoring by remember { mutableStateOf<SnapshotInfo?>(null) }
    var restoringSlot by remember { mutableStateOf<SlotBackup?>(null) }
    var snapshotLabel by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::exportDiagnostics) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }

    pendingTheme?.let { requested ->
        AlertDialog(onDismissRequest = { pendingTheme = null },
            text = { Text(stringResource(R.string.ux_light_theme_warning)) },
            confirmButton = { TextButton(onClick = {
                pendingTheme = null
                model.updateSettings { it.copy(themeMode = requested) }
            }) { Text(stringResource(R.string.ux_switch)) } },
            dismissButton = { TextButton(onClick = { pendingTheme = null }) { Text(stringResource(R.string.feature_cancel)) } })
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        Group(stringResource(R.string.settings_appearance)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(settings.themeMode == mode, {
                        if (settings.themeMode != mode && (mode == ThemeMode.LIGHT || (mode == ThemeMode.SYSTEM && !systemDark)))
                            pendingTheme = mode
                        else model.updateSettings { it.copy(themeMode = mode) }
                    }, label = { Text(stringResource(themeName(mode))) })
                }
            }
            if (settings.themeMode == ThemeMode.SCHEDULED) {
                Text(stringResource(R.string.settings_scheduled_help), style = MaterialTheme.typography.bodySmall)
                TimeRow(stringResource(R.string.settings_dark_from), settings.scheduledDarkMinute) { minute ->
                    model.updateSettings { it.copy(scheduledDarkMinute = minute) }
                }
                TimeRow(stringResource(R.string.settings_light_from), settings.scheduledLightMinute) { minute ->
                    model.updateSettings { it.copy(scheduledLightMinute = minute) }
                }
            }
            SwitchRow(stringResource(R.string.settings_simple_view), stringResource(R.string.settings_simple_view_help), settings.simpleView) { on ->
                model.updateSettings { it.copy(simpleView = on) }
            }
        }

        Group(stringResource(R.string.settings_bedjet)) {
            Text(stringResource(R.string.settings_connected_unit, model.bedJetName() ?: stringResource(R.string.settings_none_yet)), style = MaterialTheme.typography.bodyMedium)
            SwitchRow(stringResource(R.string.settings_demo), stringResource(R.string.settings_demo_help), settings.demoMode) { on ->
                model.updateSettings { it.copy(demoMode = on) }
            }
            Text(stringResource(R.string.settings_slot_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_slot_help), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..2).forEach { slot -> FilterChip(settings.workingSlot == slot, { model.updateSettings { it.copy(workingSlot = slot) } }, label = { Text(stringResource(R.string.settings_slot, slot + 1)) }) }
            }
            slotBackups.filter { it.deviceId == settings.deviceId() }.forEach { backup ->
                // An empty-slot marker has nothing to put back yet.
                val program = backup.program ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pluralStringResource(R.plurals.settings_saved_from, program.steps.size, backup.slot + 1, program.name, program.steps.size), style = MaterialTheme.typography.bodySmall)
                        if (backup.uncertain) Text(stringResource(R.string.settings_original_uncertain), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { restoringSlot = backup }) { Text(stringResource(R.string.settings_preview_put_back)) }
                }
            }
            SwitchRow(stringResource(R.string.settings_show_f), null, f) { on -> model.updateSettings { it.copy(fahrenheit = on) } }
            Text(stringResource(R.string.ux_release_help), style = MaterialTheme.typography.bodySmall)
            PhoneHandoverMenuItems(model.graph, onOutcome = model::report, asSettings = true) { }
            OutlinedButton(onClick = model::forgetBedJet) { Text(stringResource(R.string.settings_forget)) }
        }

        if (fromBackup.isNotEmpty()) {
            Group(stringResource(R.string.settings_backup_originals_title)) {
                Text(stringResource(R.string.settings_backup_originals_help), style = MaterialTheme.typography.bodySmall)
                // Imported unknown/legacy originals must never be silently assigned to the selected BedJet.
                // Keep these records read-only; matching originals use the existing guarded confirmation above.
                fromBackup.forEach { backup ->
                    Column {
                        val program = backup.program
                        Text(
                            if (program == null) stringResource(R.string.settings_backup_originals_empty, backup.slot + 1)
                            else pluralStringResource(R.plurals.settings_saved_from, program.steps.size, backup.slot + 1, program.name, program.steps.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (backup.deviceId == Migrations.UNKNOWN_DEVICE) stringResource(R.string.settings_backup_originals_unknown)
                            else stringResource(R.string.settings_backup_originals_source,
                                if (DeviceId.isDemo(backup.deviceId)) stringResource(R.string.settings_demo)
                                else DeviceId.address(backup.deviceId) ?: backup.deviceId),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (backup.uncertain) Text(stringResource(R.string.settings_original_uncertain), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Group(stringResource(R.string.settings_wake_notification)) {
            Text(stringResource(R.string.settings_wake_buttons_help), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakeAction.entries.forEach { action ->
                    val selected = action in settings.notificationActions
                    FilterChip(
                        selected,
                        {
                            model.updateSettings {
                                val list = if (selected) it.notificationActions - action else (it.notificationActions + action).takeLast(3)
                                it.copy(notificationActions = list)
                            }
                        },
                        label = { Text(Notifications.title(context, action, settings)) },
                    )
                }
            }
            SwitchRow(stringResource(R.string.settings_quiet_title), stringResource(R.string.settings_quiet_help), settings.quietNightNotification) { on ->
                model.updateSettings { it.copy(quietNightNotification = on) }
            }
            PresetEditor(stringResource(R.string.settings_preset_snooze_warm), settings.snoozeWarm, f) { p -> model.updateSettings { it.copy(snoozeWarm = p) } }
            PresetEditor(stringResource(R.string.settings_preset_snooze_cool), settings.snoozeCool, f) { p -> model.updateSettings { it.copy(snoozeCool = p) } }
            PresetEditor(stringResource(R.string.settings_preset_extend_warm), settings.extendWarm, f, quickCommand = Command.WARM) { p -> model.updateSettings { it.copy(extendWarm = p) } }
            PresetEditor(stringResource(R.string.settings_preset_extend_cool), settings.extendCool, f, quickCommand = Command.COOL) { p -> model.updateSettings { it.copy(extendCool = p) } }
            Text(io.github.willzy12h.nocturne.night.boostDescription(context, settings.boostMinutes))
            Slider(settings.boostMinutes.toFloat(), { v -> model.updateSettings { it.copy(boostMinutes = v.roundToInt()) } }, valueRange = 10f..30f, steps = 3,
                modifier = Modifier.sliderSemantics(stringResource(R.string.slider_boost), spokenMinutes(settings.boostMinutes)))
        }

        Group(stringResource(R.string.settings_alarms), Modifier.bringIntoViewRequester(alarms)) {
            val next = alarm
            Text(
                if (next == null) stringResource(R.string.settings_no_alarm)
                else stringResource(if (next.counts) R.string.settings_next_alarm else R.string.settings_next_alarm_unused,
                    Format.clock(next.time), next.creator ?: stringResource(R.string.settings_unknown_app)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (next?.creator != null && !next.counts) {
                FilledTonalButton(onClick = { model.useAlarmsFrom(next.creator!!) }) {
                    Text(stringResource(R.string.settings_use_alarms_from, next.creator.orEmpty()))
                }
            }
            SwitchRow(stringResource(R.string.settings_unknown_alarm_sources),
                stringResource(R.string.settings_unknown_alarm_sources_help), settings.acceptUnknownAlarmSource) { on ->
                model.updateSettings { it.copy(acceptUnknownAlarmSource = on) }
            }
            Text(stringResource(R.string.settings_used_from, settings.alarmPackages.joinToString()), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.alarm_next_only), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.alarm_normal_window), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::recheckAlarm) { Text(stringResource(R.string.recovery_recheck_alarm)) }
            alarmCheck?.let { result -> Text(result, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("alarm-check-result")) }
        }

        Group(stringResource(R.string.short_test_title)) {
            ShortTestControls(model)
        }

        Group(stringResource(R.string.settings_phone_setup)) {
            SetupChecks(showAll = true)
            SetupFlow(model)
        }

        PlatformSettingsSection(model.graph)

        Group(stringResource(R.string.settings_backups), Modifier.bringIntoViewRequester(backups)) {
            // The same calm banner as Tonight; the snapshots to restore from are listed just below it.
            UnreadableDataBanner(UnreadableBannerState(unreadable, restoreUnfinished, originalsBlocked),
                onDismiss = model::dismissUnreadable, onRestore = null, onAcceptLoss = model::acceptLostOriginals)
            Text(stringResource(R.string.settings_snapshots_help), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(snapshotLabel, { snapshotLabel = it.take(40) }, label = { Text(stringResource(R.string.settings_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { model.snapshotNow(snapshotLabel); snapshotLabel = "" }) { Text(stringResource(R.string.settings_snapshot_now)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("nocturne-backup-${model.now().toLocalDate()}.json") }) { Text(stringResource(R.string.settings_export_file)) }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text(stringResource(R.string.settings_restore_from_file)) }
            }
            snapshots.forEach { snap ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(snap.label ?: stringResource(R.string.settings_automatic), style = MaterialTheme.typography.bodyMedium)
                        Text(snap.createdAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { restoring = snap }) { Text(stringResource(R.string.settings_restore)) }
                    if (snap.manual) TextButton(onClick = { model.deleteSnapshot(snap.id) }) { Text(stringResource(R.string.settings_delete)) }
                }
            }
        }

        Group(stringResource(R.string.settings_diagnostics_title)) {
            Text(stringResource(R.string.settings_diagnostics_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { diagnostics.launch("nocturne-diagnostics-${model.now().toLocalDate()}.json") }) {
                Text(stringResource(R.string.settings_diagnostics_export))
            }
        }

        Group(stringResource(R.string.settings_updates)) {
            var repo by remember { mutableStateOf(settings.updateRepo) }
            var token by remember { mutableStateOf(model.updateToken) }
            OutlinedTextField(repo, { repo = it }, label = { Text(stringResource(R.string.settings_repo)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token, { token = it }, label = { Text(stringResource(R.string.settings_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(stringResource(R.string.settings_check_weekly), stringResource(R.string.settings_check_weekly_help), settings.autoUpdateCheck) { on ->
                model.updateSettings { it.copy(autoUpdateCheck = on) }
            }
            FilledTonalButton(onClick = {
                model.updateSettings { it.copy(updateRepo = repo.trim()) }
                model.updateToken = token
                model.checkForUpdates()
            }) { Text(stringResource(R.string.settings_check_now)) }
            Text(stringResource(R.string.settings_installed, model.version), style = MaterialTheme.typography.bodySmall)
        }

        Group(stringResource(R.string.settings_notice_log)) {
            val notices by model.notices.collectAsState()
            var showAll by remember { mutableStateOf(false) }
            if (notices.isEmpty()) Text(stringResource(R.string.settings_notice_log_empty), style = MaterialTheme.typography.bodySmall)
            (if (showAll) notices else notices.take(10)).forEach { notice ->
                Column {
                    Text("${noticeDay(notice.at)} ${Format.clock(notice.at)} · ${notice.title}", style = MaterialTheme.typography.bodyMedium)
                    if (notice.text.isNotBlank()) Text(notice.text, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notices.size > 10) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) stringResource(R.string.settings_show_fewer) else stringResource(R.string.settings_show_all, notices.size)) }
                if (notices.isNotEmpty()) TextButton(onClick = model::clearNotices) { Text(stringResource(R.string.settings_clear_log)) }
            }
        }

        Group(stringResource(R.string.settings_about)) {
            Text(
                stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    restoring?.let { snap ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(R.string.settings_restore_snapshot_title)) },
            text = { Text(stringResource(R.string.settings_restore_snapshot_text, snap.createdAt.take(16).replace('T', ' '))) },
            confirmButton = { TextButton(onClick = { model.restoreSnapshot(snap.id); restoring = null }) { Text(stringResource(R.string.settings_restore)) } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(stringResource(R.string.feature_cancel)) } },
        )
    }
    restoringSlot?.let { backup ->
        val program = backup.program
        AlertDialog(
            onDismissRequest = { restoringSlot = null },
            title = { Text(stringResource(R.string.settings_restore_slot_title, backup.slot + 1)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_restore_slot_text, backup.slot + 1))
                    if (backup.uncertain) Text(stringResource(R.string.settings_original_uncertain), style = MaterialTheme.typography.bodySmall)
                    if (program != null) {
                        Text(pluralStringResource(R.plurals.settings_saved_programme, program.steps.size, program.name, program.steps.size))
                        program.steps.forEachIndexed { index, step ->
                            val duration = if (step.endsAtClockTime) stringResource(R.string.settings_step_ends_at, Format.clockOfDay(step.hours * 60 + step.minutes))
                                else stringResource(R.string.minutes_short, step.durationMinutes)
                            Text(stringResource(R.string.settings_slot_step, index + 1, modeName(step.mode), (step.fanStep + 1) * 5, duration), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { model.restoreSlot(backup); restoringSlot = null }) { Text(stringResource(R.string.settings_put_original_back)) } },
            dismissButton = { TextButton(onClick = { restoringSlot = null }) { Text(stringResource(R.string.feature_cancel)) } },
        )
    }
}

@StringRes
private fun themeName(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.SCHEDULED -> R.string.theme_scheduled
}

@Composable
private fun Group(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetEditor(title: String, preset: ActionPreset, f: Boolean, quickCommand: Command? = null,
    onChange: (ActionPreset) -> Unit) {
    val context = LocalContext.current
    val timedRefusal = timedPresetRefusal(context, preset)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Timed Snooze/Extend and the Quick buttons which share Extend presets have distinct policies.
            if (timedRefusal != null) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_preset_unsupported), color = MaterialTheme.colorScheme.error)
                Text(timedRefusal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text(if (preset.mode.usesTemperature) stringResource(R.string.settings_preset_summary_temp, title, modeName(preset.mode),
                    tempText(preset.temperature, f), preset.fanPercent, preset.minutes)
                    else stringResource(R.string.settings_preset_summary, title, modeName(preset.mode), preset.fanPercent, preset.minutes), style = MaterialTheme.typography.bodyMedium)
                if (quickCommand != null && !validCapturedPreset(quickCommand, preset)) {
                    Text(stringResource(R.string.settings_preset_quick_unsupported, NightActions.commandLabel(context, quickCommand)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY).forEach { mode ->
                    FilterChip(preset.mode == mode, { onChange(preset.copy(mode = mode, temperature = preset.temperature.coerceIn(mode.temperatureRange))) }, label = { ModeLabel(mode, f) })
                }
            }
            if (preset.mode.usesTemperature) {
                val range = preset.mode.temperatureRange
                Slider(preset.temperature.toFloat(), { onChange(preset.copy(temperature = it.roundToInt())) }, valueRange = range.first.toFloat()..range.last.toFloat(),
                    modifier = Modifier.sliderSemantics(stringResource(R.string.settings_preset_slider, title, stringResource(R.string.slider_temp)), spokenTemp(preset.temperature, f)))
            }
            Text(stringResource(R.string.slider_fan), style = MaterialTheme.typography.labelSmall)
            Slider(preset.fanPercent.toFloat(), { onChange(preset.copy(fanPercent = ((it / 5).roundToInt() * 5).coerceIn(5, 100))) }, valueRange = 5f..100f, steps = 18,
                modifier = Modifier.sliderSemantics(stringResource(R.string.settings_preset_slider, title, stringResource(R.string.slider_fan)), spokenPercent(preset.fanPercent)))
            Text(stringResource(R.string.slider_minutes), style = MaterialTheme.typography.labelSmall)
            Slider(preset.minutes.toFloat(), { onChange(preset.copy(minutes = (it / 5).roundToInt() * 5)) }, valueRange = 5f..120f, steps = 22,
                modifier = Modifier.sliderSemantics(stringResource(R.string.settings_preset_slider, title, stringResource(R.string.slider_minutes)), spokenMinutes(preset.minutes)))
        }
    }
}

private fun noticeDay(iso: String): String =
    runCatching { java.time.ZonedDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) }.getOrDefault("")

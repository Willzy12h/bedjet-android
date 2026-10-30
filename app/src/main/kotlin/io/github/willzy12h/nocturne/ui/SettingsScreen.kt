package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.willzy12h.nocturne.core.link.LinkState
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.annotation.StringRes
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.Settings
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
fun SettingsScreen(model: AppViewModel, modifier: Modifier, backupsRequest: Int = 0, alarmsRequest: Int = 0, homeRequest: Int = 0,
    onBackupsRequestHandled: (Int) -> Unit = {}, onAlarmsRequestHandled: (Int) -> Unit = {}, sleepRequest: Int = 0,
    onSleepRequestHandled: (Int) -> Unit = {}) {
    val settings by model.settings.collectAsState()
    val selectedName by model.selectedBedJetName.collectAsState()
    val snapshots by model.snapshots.collectAsState()
    val importPreview by model.importPreview.collectAsState()
    val importApplying by model.importApplying.collectAsState()
    val slotBackups by model.slotBackups.collectAsState()
    val fromBackup = slotBackups.filter { it.deviceId != settings.deviceId() }
    val unreadable by model.unreadable.collectAsState()
    val restoreUnfinished by model.restoreUnfinished.collectAsState()
    val originalsBlocked by model.originalsBlocked.collectAsState()
    var page by rememberSaveable { mutableStateOf("home") }
    BackHandler(enabled = page != "home") { page = "home" }
    LaunchedEffect(homeRequest) { if (homeRequest > 0) page = "home" }
    val backups = remember { BringIntoViewRequester() }
    LaunchedEffect(backupsRequest) { if (backupsRequest > 0) {
        try { page = "backups"; withFrameNanos { }; backups.bringIntoView() }
        finally { onBackupsRequestHandled(backupsRequest) }
    } }
    val alarms = remember { BringIntoViewRequester() }
    LaunchedEffect(alarmsRequest) { if (alarmsRequest > 0) {
        try { page = "night"; withFrameNanos { }; alarms.bringIntoView() }
        finally { onAlarmsRequestHandled(alarmsRequest) }
    } }
    LaunchedEffect(sleepRequest) { if (sleepRequest > 0) {
        try { page = "sleep" } finally { onSleepRequestHandled(sleepRequest) }
    } }
    val alarm by model.nextAlarm.collectAsState()
    val alarmCheck by model.alarmCheck.collectAsState()
    val f = settings.fahrenheit
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val systemDark = isSystemInDarkTheme()
    var pendingTheme by remember { mutableStateOf<Settings?>(null) }
    fun saveAppearance(requested: Settings) {
        model.updateSettings { it.copy(themeMode = requested.themeMode,
            scheduledDarkMinute = requested.scheduledDarkMinute, scheduledLightMinute = requested.scheduledLightMinute,
            nightPalette = requested.nightPalette, dayPalette = requested.dayPalette) }
    }
    fun chooseAppearance(requested: Settings) {
        if (!requested.palette(model.now(), systemDark).tokens.dark) pendingTheme = requested
        else saveAppearance(requested)
    }
    var restoringSlot by remember { mutableStateOf<SlotBackup?>(null) }
    var snapshotLabel by remember { mutableStateOf("") }
    var quickDefaultsOpen by remember { mutableStateOf(false) }
    var licencesOpen by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::exportDiagnostics) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }

    pendingTheme?.let { requested ->
        AlertDialog(onDismissRequest = { pendingTheme = null },
            text = { Text(stringResource(R.string.ux_light_theme_warning)) },
            confirmButton = { TextButton(onClick = {
                pendingTheme = null
                approveLightSelection(context, requested, model.now())
                saveAppearance(requested)
            }) { Text(stringResource(R.string.ux_switch)) } },
            dismissButton = { TextButton(onClick = { pendingTheme = null }) { Text(stringResource(R.string.feature_cancel)) } })
    }

    // Sleep (the nights' history) has its own lazy list, so it replaces the settings column rather than nesting in it.
    if (page == "sleep") {
        HistoryScreen(model, modifier, onBack = { page = "home" }, onOpenPage = { page = it })
        return
    }
    var alarmDetails by rememberSaveable { mutableStateOf(false) }
    val settingsScroll = rememberScrollState()
    var settingsViewportHeight by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().onSizeChanged { settingsViewportHeight = it.height }.verticalScroll(settingsScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page == "home") {
            Text(stringResource(R.string.settings_title), color = paletteTokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            SettingsHome(model) { page = it }
        } else Column {
            AccentLink("‹ " + stringResource(R.string.settings_nav_back), { page = "home" }, Modifier.testTag("settings-back"), fontSize = 15.sp)
            Text(stringResource(pageTitle(page)), color = paletteTokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
        }
        if (page == "permissions") OutlinedButton(onClick = model::openSetupChecklist, modifier = Modifier.testTag("open-setup-checklist")) {
            Text(stringResource(R.string.uat_setup_title))
        }
        if (page == "bedjet") OutlinedButton(onClick = { quickDefaultsOpen = true }, modifier = Modifier.testTag("open-quick-defaults")) {
            Text(stringResource(R.string.uat_quick_defaults))
        }
        if (quickDefaultsOpen) AlertDialog(onDismissRequest = { quickDefaultsOpen = false },
            title = { Text(stringResource(R.string.uat_quick_defaults)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickDefaultsSection(model, settings)
            } }, confirmButton = { TextButton(onClick = { quickDefaultsOpen = false }) { Text(stringResource(R.string.uat_setup_done)) } })

        if (page == "appearance") {
            // The design's order; the enum's order is stored data and stays as it is.
            Segmented(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SCHEDULED).map { it to stringResource(themeName(it)) },
                settings.themeMode, tag = "settings-theme") { mode ->
                if (settings.themeMode != mode) chooseAppearance(settings.copy(themeMode = mode))
            }
            // UX-03: say which palette each choice shows; both palettes stay saved whichever is in use.
            Text(stringResource(when (settings.themeMode) {
                ThemeMode.SYSTEM -> R.string.appearance_help_system
                ThemeMode.LIGHT -> R.string.appearance_help_light
                ThemeMode.DARK -> R.string.appearance_help_dark
                ThemeMode.SCHEDULED -> R.string.appearance_help_scheduled
            }), Modifier.testTag("appearance-help"), color = paletteTokens.ink2, fontSize = 13.sp)
            if (settings.themeMode == ThemeMode.SCHEDULED) SettingsCard {
                ClockRow(R.drawable.ic_nc_moon, paletteTokens.accent, stringResource(R.string.parity_settings_night_from), settings.scheduledDarkMinute) { minute ->
                    if (minute != settings.scheduledDarkMinute) chooseAppearance(settings.copy(scheduledDarkMinute = minute))
                }
                ClockRow(R.drawable.ic_nc_sun, paletteTokens.warn, stringResource(R.string.settings_light_from), settings.scheduledLightMinute) { minute ->
                    if (minute != settings.scheduledLightMinute) chooseAppearance(settings.copy(scheduledLightMinute = minute))
                }
                DayNightBar(settings.scheduledDarkMinute, settings.scheduledLightMinute)
                Text(stringResource(R.string.settings_scheduled_help), color = paletteTokens.ink2, fontSize = 12.5.sp)
            }
            val darkNow = settings.isDarkTheme(model.now(), systemDark)
            PalettePicker(stringResource(R.string.settings_night_palette), settings.nightPalette, "night", inUse = darkNow) { palette ->
                if (settings.isDarkTheme(model.now(), systemDark)) chooseAppearance(settings.copy(nightPalette = palette))
                else saveAppearance(settings.copy(nightPalette = palette))
            }
            PalettePicker(stringResource(R.string.settings_day_palette), settings.dayPalette, "day", inUse = !darkNow) { palette ->
                if (!settings.isDarkTheme(model.now(), systemDark)) chooseAppearance(settings.copy(dayPalette = palette))
                else saveAppearance(settings.copy(dayPalette = palette))
            }
            SettingsCard(spacing = 0) {
                SwitchRow(stringResource(R.string.settings_show_f), null, f) { on -> model.updateSettings { it.copy(fahrenheit = on) } }
            }
        }

        if (page == "bedjet") Group(stringResource(R.string.settings_bedjet)) {
            Text(stringResource(R.string.settings_connected_unit, if (settings.demoMode) stringResource(R.string.status_demo_device) else selectedName ?: stringResource(R.string.settings_none_yet)), style = MaterialTheme.typography.bodyMedium)
            DeviceControls(model, settingsScroll, settingsViewportHeight)
            Text(stringResource(R.string.settings_slot_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_slot_help), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..2).forEach { slot -> FilterChip(settings.workingSlot == slot, { model.updateSettings { it.copy(workingSlot = slot) } }, label = { Text(stringResource(R.string.settings_slot, slot + 1)) }, colors = nocturneFilterChipColors()) }
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
            Text(stringResource(R.string.ux_release_help), style = MaterialTheme.typography.bodySmall)
            PhoneHandoverMenuItems(model.graph, onOutcome = model::report, asSettings = true) { }
            OutlinedButton(onClick = model::forgetBedJet) { Text(stringResource(R.string.settings_forget)) }
        }

        if (fromBackup.isNotEmpty()) {
            if (page == "bedjet") Group(stringResource(R.string.settings_backup_originals_title)) {
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

        if (page == "notifications") Group(stringResource(R.string.settings_wake_notification)) {
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
                        colors = nocturneFilterChipColors(),
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
            // Boost has one editor, the same one Home and Quick presets open.
            var editingBoost by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.settings_boost_edit)) { editingBoost = true }
                .testTag("settings-boost-row"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_boost_row), color = paletteTokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(io.github.willzy12h.nocturne.night.boostDescription(context, settings.boostMinutes), color = paletteTokens.ink2, fontSize = 13.sp)
                }
                Text(stringResource(R.string.settings_boost_edit), color = paletteTokens.readableText(paletteTokens.accent, paletteTokens.surface), fontWeight = FontWeight.Bold)
            }
            if (editingBoost) BoostEditor(model, settings) { editingBoost = false }
        }

        if (page == "night") Group(stringResource(R.string.settings_alarms), Modifier.bringIntoViewRequester(alarms)) {
            val next = alarm
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(paletteTokens.surface2), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_nc_alarm), null, Modifier.size(20.dp), tint = paletteTokens.ink2)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.parity_settings_alarm_source), color = paletteTokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (next == null) stringResource(R.string.settings_no_alarm)
                        else stringResource(if (next.counts) R.string.settings_next_alarm else R.string.settings_next_alarm_unused,
                            Format.clock(next.time), io.github.willzy12h.nocturne.night.alarmSourceName(context, next.identifiedCreator)),
                        color = paletteTokens.ink2, fontSize = 14.sp,
                    )
                }
            }
            if (next?.creator != null && !next.counts) {
                FilledTonalButton(onClick = { model.useAlarmsFrom(next.creator!!) }) {
                    Text(stringResource(R.string.settings_use_alarms_from, next.creator.orEmpty()))
                }
            }
            HorizontalDivider(color = paletteTokens.hairline)
            SwitchRow(stringResource(R.string.settings_unknown_alarm_sources),
                stringResource(R.string.settings_unknown_alarm_sources_help), settings.acceptUnknownAlarmSource) { on ->
                model.updateSettings { it.copy(acceptUnknownAlarmSource = on) }
            }
            HorizontalDivider(color = paletteTokens.hairline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AccentLink(stringResource(R.string.recovery_recheck_alarm), model::recheckAlarm, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                AccentLink(stringResource(if (alarmDetails) R.string.parity_settings_hide_details else R.string.parity_settings_details),
                    { alarmDetails = !alarmDetails }, Modifier.testTag("alarm-details"), fontSize = 14.sp)
            }
            alarmCheck?.let { result -> Text(result, color = paletteTokens.ink2, fontSize = 13.5.sp,
                modifier = Modifier.testTag("alarm-check-result")) }
            if (alarmDetails) {
                if (next != null) Text(stringResource(R.string.uat_alarm_raw_creator,
                    next.creator ?: stringResource(R.string.uat_alarm_null_creator)),
                    color = paletteTokens.ink2, fontSize = 12.5.sp, modifier = Modifier.testTag("alarm-raw-creator"))
                Text(stringResource(R.string.settings_used_from, settings.alarmPackages.joinToString()), color = paletteTokens.ink2, fontSize = 12.5.sp)
                Text(stringResource(R.string.alarm_next_only), color = paletteTokens.ink2, fontSize = 12.5.sp)
                Text(stringResource(R.string.alarm_normal_window), color = paletteTokens.ink2, fontSize = 12.5.sp)
            }
        }

        if (page == "night") Group(stringResource(R.string.short_test_title)) {
            ShortTestControls(model)
        }

        if (page == "permissions") {
            // The checks bring their own cards, so they sit straight on the page.
            SetupChecks(showAll = true)
            SetupFlow(model)
        }

        if (page == "night") SettingsCard { PlatformSettingsSection(model.graph) }
        if (page == "health") SettingsCard { PlatformSettingsSection(model.graph, healthOnly = true) }

        if (page == "backups") Group(stringResource(R.string.settings_backups), Modifier.bringIntoViewRequester(backups)) {
            // The same calm banner as Tonight; the snapshots to restore from are listed just below it.
            UnreadableDataBanner(UnreadableBannerState(unreadable, restoreUnfinished, originalsBlocked),
                onDismiss = model::dismissUnreadable, onRestore = null, onAcceptLoss = model::acceptLostOriginals)
            Text(stringResource(R.string.settings_snapshots_help), style = MaterialTheme.typography.bodySmall)
            if (settings.demoMode) Text(stringResource(R.string.import_demo_unavailable), style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(snapshotLabel, { snapshotLabel = it.take(40) }, label = { Text(stringResource(R.string.settings_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = nocturneTextFieldColors())
                FilledTonalButton(enabled = !settings.demoMode && !importApplying, onClick = { model.snapshotNow(snapshotLabel); snapshotLabel = "" }) { Text(stringResource(R.string.settings_snapshot_now)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !settings.demoMode && !importApplying, onClick = { exporter.launch("nocturne-backup-${model.now().toLocalDate()}.json") }) { Text(stringResource(R.string.settings_export_file)) }
                OutlinedButton(enabled = !settings.demoMode && !importApplying, onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text(stringResource(R.string.settings_restore_from_file)) }
            }
            Text(stringResource(R.string.settings_export_compat), style = MaterialTheme.typography.bodySmall)
            snapshots.forEach { snap ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column {
                        Text(snap.label ?: stringResource(R.string.settings_automatic), style = MaterialTheme.typography.bodyMedium)
                        Text(snap.createdAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !settings.demoMode && !importApplying, onClick = { model.restoreSnapshot(snap.id) }) { Text(stringResource(R.string.settings_restore)) }
                        if (snap.manual) TextButton(enabled = !settings.demoMode && !importApplying, onClick = { model.deleteSnapshot(snap.id) }) { Text(stringResource(R.string.settings_delete)) }
                    }
                }
            }
        }

        if (page == "about") Group(stringResource(R.string.settings_diagnostics_title)) {
            Text(stringResource(R.string.settings_diagnostics_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { diagnostics.launch("nocturne-diagnostics-${model.now().toLocalDate()}.json") }) {
                Text(stringResource(R.string.settings_diagnostics_export))
            }
        }

        if (page == "about") Group(stringResource(R.string.settings_updates)) {
            OutlinedButton(onClick = { uri.openUri("https://github.com/Willzy12h/bedjet-android/releases") }) {
                Text(stringResource(R.string.settings_github_releases))
            }
        }

        if (page == "notifications") Group(stringResource(R.string.settings_notice_log)) {
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

        if (page == "about") Group(stringResource(R.string.settings_about)) {
            Text(stringResource(R.string.settings_about_description), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_about_unaffiliated), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_installed, model.version), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openSourceCode(context) }) {
                Text(stringResource(R.string.settings_source_code))
            }
            Text(stringResource(R.string.settings_source_code_detail), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_protocol_attribution), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { licencesOpen = true }) { Text(stringResource(R.string.settings_licences)) }
        }
    }

    if (licencesOpen) {
        val licences = stringResource(R.string.font_licences_text)
        AlertDialog(onDismissRequest = { licencesOpen = false },
            title = { Text(stringResource(R.string.settings_licences)) },
            text = { Text(licences, modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { licencesOpen = false }) { Text(stringResource(R.string.uat_setup_done)) } })
    }

    importPreview?.let { preview ->
        key(preview) {
            ImportPreviewDialog(preview, importApplying, onApply = { model.confirmImport(preview) }, onCancel = model::cancelImport)
        }
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

private data class SettingsDestination(val id: String, val label: String, val subtitle: String, val icon: Int,
    val iconColor: Color? = null, val ok: Boolean = false)

@Composable
private fun SettingsHome(model: AppViewModel, open: (String) -> Unit) {
    val tokens = paletteTokens
    val context = LocalContext.current
    val settings by model.settings.collectAsState()
    val name by model.selectedBedJetName.collectAsState()
    val link by model.linkState.collectAsState()
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val bluetoothOff by model.bluetoothOff.collectAsState()
    val released by model.graph.sessions.released.collectAsState()
    val notices by model.notices.collectAsState()
    val nights = model.history.collectAsState().value.size
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val checks = remember(refresh) { checks(context) { } }
    val slot = settings.workingSlot + 1
    val connection = headerConnection(settings.demoMode, settings.targetDevice != null, released,
        bluetoothOff, link, live && status != null)
    val bedjetState = stringResource(when (connection) {
        HeaderConnection.DEMO -> R.string.parity_settings_demo
        HeaderConnection.DISCONNECTED -> R.string.parity_settings_not_connected
        else -> connection.text
    })
    val autoStart = settings.platform.autoStart?.enabled ?: settings.platform.chargerAutoStart
    val palettes = when (settings.themeMode) {
        ThemeMode.DARK -> stringResource(paletteName(settings.nightPalette))
        ThemeMode.LIGHT -> stringResource(paletteName(settings.dayPalette))
        else -> "${stringResource(paletteName(settings.nightPalette))} / ${stringResource(paletteName(settings.dayPalette))}"
    }
    val allowed = checks.count { it.ok }
    val health = settings.platform.healthSleepOverlay || settings.platform.healthSleepExport
    val green = if (tokens.dark) Color(0xFF4CD889) else Color(0xFF1F9D57)
    val groups = listOf(
        listOf(SettingsDestination("bedjet", name?.let { stringResource(R.string.parity_settings_bedjet_named, it) } ?: stringResource(R.string.settings_nav_bedjet),
            stringResource(R.string.parity_settings_slot, bedjetState, slot), R.drawable.ic_nc_bt, tokens.cool)),
        listOf(SettingsDestination("night", stringResource(R.string.settings_nav_night),
                stringResource(R.string.parity_settings_night,
                    stringResource(if (settings.alarmPackages.isEmpty()) R.string.parity_settings_alarm_ignored else R.string.parity_settings_clock_alarm),
                    stringResource(if (autoStart) R.string.parity_settings_on else R.string.parity_settings_off).lowercase()), R.drawable.ic_nc_alarm),
            SettingsDestination("appearance", stringResource(R.string.settings_nav_appearance),
                "${stringResource(themeName(settings.themeMode))} · $palettes", R.drawable.ic_nc_sun),
            SettingsDestination("permissions", stringResource(R.string.settings_nav_permissions),
                if (allowed == checks.size) pluralStringResource(R.plurals.parity_settings_all_allowed, checks.size, checks.size)
                else pluralStringResource(R.plurals.parity_settings_some_allowed, allowed, allowed, checks.size),
                R.drawable.ic_nc_shield, if (allowed == checks.size) green else tokens.warn, ok = allowed == checks.size)),
        listOf(SettingsDestination("backups", stringResource(R.string.settings_nav_backups), stringResource(R.string.parity_settings_backups), R.drawable.ic_nc_box),
            SettingsDestination("notifications", stringResource(R.string.settings_nav_notifications),
                if (notices.isEmpty()) stringResource(R.string.parity_settings_no_notices)
                else pluralStringResource(R.plurals.parity_settings_notices, notices.size, notices.size), R.drawable.ic_nc_bell),
            SettingsDestination("sleep", stringResource(R.string.settings_nav_sleep),
                if (nights == 0) stringResource(R.string.settings_sleep_none)
                else pluralStringResource(R.plurals.settings_sleep_nights, nights, nights), R.drawable.ic_nc_moon, tokens.accent),
            SettingsDestination("health", stringResource(R.string.settings_nav_health),
                stringResource(if (health) R.string.parity_settings_on else R.string.parity_settings_off), R.drawable.ic_nc_heart)),
        listOf(SettingsDestination("about", stringResource(R.string.settings_nav_about),
            stringResource(R.string.parity_settings_about, model.version), R.drawable.ic_nc_info)),
    )
    groups.forEach { group ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
            .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)) {
            group.forEachIndexed { index, destination ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = tokens.hairline)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("settings-page-${destination.id}")
                    .clickable(role = Role.Button) { open(destination.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tokens.surface2), contentAlignment = Alignment.Center) {
                        Icon(painterResource(destination.icon), null, Modifier.size(20.dp), tint = destination.iconColor ?: tokens.ink2)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(destination.label, color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(destination.subtitle, color = tokens.ink2, fontSize = 14.sp)
                    }
                    if (destination.ok) Icon(painterResource(R.drawable.ic_nc_check), null, Modifier.size(18.dp), tint = green)
                    Text(stringResource(R.string.settings_nav_chevron), color = tokens.ink2, fontSize = 16.sp)
                }
            }
        }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, modifier.semantics { heading() })
        SettingsCard(content = content)
    }
}

/** The design's settings card: surface, radius 18, the palette's edge. */
@Composable
private fun SettingsCard(spacing: Int = 10, content: @Composable () -> Unit) {
    val tokens = paletteTokens
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
        .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        ProvideTextStyle(TextStyle(color = tokens.ink2, fontSize = 14.sp, lineHeight = 20.sp)) { content() }
    }
}

/** An icon, a label and a clock pill that opens the time picker. */
@Composable
private fun ClockRow(icon: Int, tint: Color, label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val tokens = paletteTokens
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tint)
        Text(label, Modifier.weight(1f), color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(tokens.ink)
            .clickable(role = Role.Button, onClickLabel = label) {
                val themed = android.view.ContextThemeWrapper(context, if (tokens.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
                TimePickerDialog(themed, { _, h, m -> onChange(h * 60 + m) }, minuteOfDay / 60, minuteOfDay % 60, true).apply {
                    show(); styleNativeDialog(this, tokens)
                }
            }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(Format.clockOfDay(minuteOfDay), color = tokens.ground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A 24-hour bar: night colour where the dark theme applies, day colour where it does not. */
@Composable
private fun DayNightBar(darkFrom: Int, lightFrom: Int) {
    val tokens = paletteTokens
    val night = tokens.accent.copy(alpha = .35f)
    val day = tokens.ink2.copy(alpha = .8f)
    val bounds = listOf(0, lightFrom, darkFrom, 24 * 60).sorted()
    Row(Modifier.fillMaxWidth().height(18.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        bounds.zipWithNext().filter { (a, b) -> b > a }.forEach { (a, b) ->
            val mid = (a + b) / 2
            val dark = if (darkFrom > lightFrom) mid >= darkFrom || mid < lightFrom else mid in darkFrom until lightFrom
            Box(Modifier.weight((b - a).toFloat()).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(if (dark) night else day))
        }
    }
}

@StringRes
private fun pageTitle(page: String) = when (page) {
    "bedjet" -> R.string.settings_nav_bedjet
    "night" -> R.string.settings_nav_night
    "appearance" -> R.string.settings_nav_appearance
    "permissions" -> R.string.settings_nav_permissions
    "backups" -> R.string.settings_nav_backups
    "notifications" -> R.string.settings_nav_notifications
    "health" -> R.string.settings_nav_health
    "sleep" -> R.string.settings_nav_sleep
    else -> R.string.settings_nav_about
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetEditor(title: String, preset: ActionPreset, f: Boolean, quickCommand: Command? = null,
    onChange: (ActionPreset) -> Unit) {
    val context = LocalContext.current
    val timedRefusal = timedPresetRefusal(context, preset)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = paletteTokens.surface2),
        border = BorderStroke(1.dp, paletteTokens.outline)) {
        Column(Modifier.padding(12.dp)) {
            // Timed Snooze/Extend and the Quick buttons which share Extend presets have distinct policies.
            if (timedRefusal != null) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_preset_unsupported), color = MaterialTheme.colorScheme.onSurface)
                Text(timedRefusal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            } else {
                Text(if (preset.mode.usesTemperature) stringResource(R.string.settings_preset_summary_temp, title, modeName(preset.mode),
                    tempText(preset.temperature, f), preset.fanPercent, preset.minutes)
                    else stringResource(R.string.settings_preset_summary, title, modeName(preset.mode), preset.fanPercent, preset.minutes), style = MaterialTheme.typography.bodyMedium)
                if (quickCommand != null && !validCapturedPreset(quickCommand, preset)) {
                    Text(stringResource(R.string.settings_preset_quick_unsupported, NightActions.commandLabel(context, quickCommand)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY).forEach { mode ->
                    FilterChip(preset.mode == mode, { onChange(preset.copy(mode = mode, temperature = preset.temperature.coerceIn(mode.temperatureRange))) }, label = { ModeLabel(mode, f) }, colors = nocturneFilterChipColors())
                }
            }
            if (preset.mode.usesTemperature) {
                val range = preset.mode.temperatureRange
                Text(stringResource(R.string.slider_temp), style = MaterialTheme.typography.labelSmall)
                ScaledSlider(preset.temperature.coerceIn(range).toFloat(), { onChange(preset.copy(temperature = it.roundToInt())) },
                    range = range.first.toFloat()..range.last.toFloat(), steps = 0, enabled = true,
                    modifier = Modifier.sliderSemantics(stringResource(R.string.settings_preset_slider, title, stringResource(R.string.slider_temp)), spokenTemp(preset.temperature, f)),
                    ticks = temperatureTicks(range, f), tag = "preset-temperature", onValueChangeFinished = {},
                    colors = temperatureSliderColors(preset.mode, preset.temperature))
            }
            Text(stringResource(R.string.slider_fan), style = MaterialTheme.typography.labelSmall)
            ScaledSlider(preset.fanPercent.toFloat(), { onChange(preset.copy(fanPercent = ((it / 5).roundToInt() * 5).coerceIn(5, 100))) },
                range = 5f..100f, steps = 18, enabled = true,
                modifier = Modifier.sliderSemantics(stringResource(R.string.settings_preset_slider, title, stringResource(R.string.slider_fan)), spokenPercent(preset.fanPercent)),
                ticks = fanTicks(), tag = "preset-fan", onValueChangeFinished = {}, colors = fanSliderColors(preset.mode))
            Text(stringResource(R.string.slider_minutes), style = MaterialTheme.typography.labelSmall)
            ScaledSlider(preset.minutes.toFloat(), { onChange(preset.copy(minutes = (it / 5).roundToInt() * 5)) },
                range = 5f..120f, steps = 22, enabled = true,
                modifier = Modifier.sliderSemantics(stringResource(R.string.settings_preset_slider, title, stringResource(R.string.slider_minutes)), spokenMinutes(preset.minutes)),
                ticks = minuteTicks(5, 120).let { listOf(it[0], 60f to durationText(60), it[2]) }, tag = "preset-minutes", onValueChangeFinished = {},
                colors = amountSliderColors())
        }
    }
}

private fun noticeDay(iso: String): String =
    runCatching { java.time.ZonedDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) }.getOrDefault("")

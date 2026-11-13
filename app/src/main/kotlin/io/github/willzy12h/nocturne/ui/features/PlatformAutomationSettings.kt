package io.github.willzy12h.nocturne.ui.features

import io.github.willzy12h.nocturne.ui.nocturneRadioColors
import io.github.willzy12h.nocturne.ui.NocturneSlider
import io.github.willzy12h.nocturne.ui.amountSliderColors
import io.github.willzy12h.nocturne.ui.nocturneSwitchColors
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.platform.HealthConnectAccess
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.AutoStartMode
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.platform.*
import io.github.willzy12h.nocturne.ui.Format
import io.github.willzy12h.nocturne.ui.TimeRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable private fun AutomationSwitch(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled, Role.Switch, change)
        .semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = nocturneSwitchColors())
    }
}

@Composable fun AutoStartSettingsSection(graph: Graph) {
    val saved by graph.repository.settings.collectAsState()
    val profiles by graph.repository.profiles.collectAsState()
    val automation by graph.repository.automation.collectAsState()
    val config = AutoStartSchedule.settings(graph)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var choosingProfile by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<Pair<AutoStartSettings, String?>?>(null) }
    val context = graph.context
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resume by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resume++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val plan = remember(saved, profiles, resume, automation.claims) { AutoStartSchedule.nextScheduled(graph) }
    fun save(expected: AutoStartSettings? = null, expectedTarget: String? = null, transform: (AutoStartSettings) -> AutoStartSettings) {
        scope.launch {
            var rejected = false
            try {
                graph.repository.updateSettings { current ->
                    val before = AutoStartSchedule.settings(graph)
                    if (current.demoMode || (expected != null && (before != expected || current.targetDevice != expectedTarget))) {
                        rejected = true; current
                    } else {
                        val next = transform(before)
                        if (next.validationErrors().isNotEmpty() || (next.enabled && graph.repository.profile(next.profileId!!) == null)) {
                            rejected = true; current
                        } else current.copy(platform = current.platform.copy(autoStart = next, chargerAutoStart = false, chargerDevice = null))
                    }
                }
                if (rejected) message = context.getString(R.string.auto_start_review_settings)
                else { message = null; ChargerJobs.sync(graph) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message = context.getString(R.string.feature_saving_failed) }
        }
    }
    fun enable(expected: AutoStartSettings, target: String?) {
        if (!PlatformNotifications.available(context)) message = context.getString(R.string.feature_notification_permission)
        else save(expected, target) { it.copy(enabled = true, deviceId = target) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val expected = pendingPermission; pendingPermission = null
        if (granted && expected != null) enable(expected.first, expected.second)
        else message = context.getString(R.string.feature_notification_permission)
    }
    val editable = !saved.demoMode && saved.targetDevice != null && !DeviceId.isDemo(saved.targetDevice!!)
    val selected = profiles.firstOrNull { it.id == config.profileId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationSwitch(stringResource(R.string.auto_start_title), config.enabled,
            !saved.demoMode && (config.enabled || (editable && selected != null))) { enabled ->
            if (!enabled) save { it.copy(enabled = false) }
            else {
                val expected = config
                val target = saved.targetDevice
                pendingPermission = expected to target
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { pendingPermission = null; enable(expected, target) }
            }
        }
        Text(stringResource(R.string.auto_start_help), style = MaterialTheme.typography.bodySmall)
        if (!editable) Text(stringResource(if (saved.demoMode) R.string.platform_demo_unavailable else R.string.feature_select_device), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { choosingProfile = true }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.auto_start_profile, selected?.name ?: stringResource(R.string.auto_start_choose_profile)))
        }
        AutoStartMode.entries.forEach { mode ->
            val label = stringResource(if (mode == AutoStartMode.FIXED) R.string.auto_start_fixed else R.string.auto_start_before_wake)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(config.mode == mode, enabled = editable, role = Role.RadioButton,
                onClick = { save { it.copy(mode = mode) } }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(config.mode == mode, onClick = null, enabled = editable, colors = nocturneRadioColors())
                Text(label, Modifier.weight(1f))
            }
        }
        if (config.mode == AutoStartMode.FIXED) {
            if (editable) TimeRow(stringResource(R.string.auto_start_time), config.fixedMinute) { minute -> save { it.copy(fixedMinute = minute) } }
            else Text(stringResource(R.string.auto_start_time_value, Format.clockOfDay(config.fixedMinute)))
        } else {
            var offset by remember(config.minutesBeforeWake) { mutableFloatStateOf(config.minutesBeforeWake.toFloat()) }
            val duration = stringResource(R.string.auto_start_relative_value, offset.roundToInt() / 60, offset.roundToInt() % 60)
            Text(duration)
            NocturneSlider(value = offset, onValueChange = { offset = it }, valueRange = 60f..1080f, steps = 33,
                enabled = editable, onValueChangeFinished = { save { it.copy(minutesBeforeWake = offset.roundToInt()) } },
                modifier = Modifier.semantics { contentDescription = duration }, colors = amountSliderColors())
            val preview = plan ?: AutoStartSchedule.next(graph, graph.now())
            Text(when (preview?.wakeSource) {
                AutoStartWakeSource.ALARM -> stringResource(R.string.auto_start_alarm_wake, Format.clock(preview.wakeAt!!))
                AutoStartWakeSource.OVERRIDE -> stringResource(R.string.auto_start_override_wake, Format.clock(preview.wakeAt!!))
                else -> stringResource(R.string.auto_start_profile_fallback, Format.clockOfDay(selected?.wakeSource?.defaultWakeMinute ?: 7 * 60))
            }, style = MaterialTheme.typography.bodySmall)
        }
        AutomationSwitch(stringResource(R.string.auto_start_only_charging), config.onlyIfCharging, editable) { value -> save { it.copy(onlyIfCharging = value) } }
        if (config.onlyIfCharging) Text(stringResource(R.string.auto_start_charging_grace), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (AutoStartSchedule.exact(graph)) R.string.auto_start_exact else R.string.auto_start_inexact), style = MaterialTheme.typography.bodySmall)
        plan?.let { Text(stringResource(R.string.auto_start_next, Format.dayClock(it.startAt)), style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) }
    }
    if (choosingProfile) AlertDialog(onDismissRequest = { choosingProfile = false },
        title = { Text(stringResource(R.string.auto_start_choose_profile)) }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                profiles.forEach { profile ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(profile.id == config.profileId, role = Role.RadioButton,
                        onClick = { save { it.copy(profileId = profile.id) }; choosingProfile = false }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(profile.id == config.profileId, onClick = null, colors = nocturneRadioColors())
                        Text(profile.name, Modifier.weight(1f))
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton({ choosingProfile = false }) { Text(stringResource(R.string.feature_cancel)) } })
}

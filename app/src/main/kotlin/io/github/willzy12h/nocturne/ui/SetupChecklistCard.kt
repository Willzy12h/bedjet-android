package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.BluetoothAvailability
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.SetupItem
import io.github.willzy12h.nocturne.core.store.SetupSnapshot
import io.github.willzy12h.nocturne.night.NightAlarms
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.platform.HealthConnectAccess
import kotlinx.coroutines.CancellationException

internal fun setupSnapshot(context: Context, settings: Settings, healthGranted: Boolean): SetupSnapshot {
    val nearby = BluetoothAvailability.permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val bluetooth = nearby && runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
    val widgets = AppWidgetManager.getInstance(context)
    val hasWidget = listOf("QuickControlsReceiver", "SingleActionReceiver").any {
        widgets.getAppWidgetIds(ComponentName(context.packageName, "${context.packageName}.widget.$it")).isNotEmpty()
    }
    return SetupSnapshot(buildSet {
        if (!settings.demoMode && settings.targetDevice?.matches(Regex("ble:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) == true) add(SetupItem.PAIR)
        if (bluetooth) add(SetupItem.BLUETOOTH)
        if ((Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && Notifications.canPost(context)) add(SetupItem.NOTIFICATIONS)
        if (NightAlarms.canScheduleExact(context)) add(SetupItem.EXACT_ALARMS)
        if (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true &&
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted != true) add(SetupItem.BATTERY)
        if (healthGranted) add(SetupItem.HEALTH)
        if (hasWidget) add(SetupItem.WIDGET)
    })
}

internal fun setupItemTitle(item: SetupItem): Int = when (item) {
    SetupItem.PAIR -> R.string.uat_setup_pair
    SetupItem.BLUETOOTH -> R.string.uat_setup_bluetooth
    SetupItem.NOTIFICATIONS -> R.string.uat_setup_notifications
    SetupItem.EXACT_ALARMS -> R.string.uat_setup_exact
    SetupItem.BATTERY -> R.string.uat_setup_battery
    SetupItem.HEALTH -> R.string.uat_setup_health
    SetupItem.WIDGET -> R.string.uat_setup_widget
}

/** Re-samples OS authority on every resume. Persisted completion never overrides revoked permissions. */
@Composable
internal fun SetupChecklistCard(model: AppViewModel) {
    val context = LocalContext.current
    val settings by model.settings.collectAsState()
    val forced by model.setupChecklistOpen.collectAsState()
    val bluetoothOff by model.bluetoothOff.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var healthGranted by remember { mutableStateOf(false) }
    var pairDialog by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++ }
    LaunchedEffect(refresh, settings.demoMode) {
        healthGranted = if (!settings.demoMode && HealthConnectAccess.platformSupported) try {
            val granted = HealthConnectAccess.client(context).permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(SleepSessionRecord::class) in granted ||
                HealthPermission.getWritePermission(SleepSessionRecord::class) in granted
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false } else false
    }
    val snapshot = remember(refresh, settings.targetDevice, settings.demoMode, bluetoothOff, healthGranted) {
        setupSnapshot(context, settings, healthGranted)
    }
    LaunchedEffect(snapshot.requiredComplete, settings.demoMode, settings.setupChecklistCompleted) {
        if (!settings.demoMode && snapshot.requiredComplete && !settings.setupChecklistCompleted)
            model.updateSettings { it.copy(setupChecklistCompleted = true) }
    }
    val shown = snapshot.visible(settings, forced)
    if (shown.isEmpty() && !forced) return
    fun open(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { note = context.getString(R.string.uat_setup_unavailable) }
    }
    val fix: (SetupItem) -> Unit = { item ->
        note = null
        when (item) {
            SetupItem.PAIR -> if (settings.demoMode) note = context.getString(R.string.uat_setup_demo) else pairDialog = true
            SetupItem.BLUETOOTH -> if (BluetoothAvailability.permissions.any {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) permission.launch(BluetoothAvailability.permissions)
                else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            SetupItem.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else open(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
            SetupItem.EXACT_ALARMS -> open(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            SetupItem.BATTERY -> {
                @Suppress("BatteryLife")
                val intent = Intent(if (context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true)
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS else AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"))
                open(intent)
            }
            SetupItem.HEALTH -> if (HealthConnectAccess.available(context))
                    health.launch(setOf(HealthPermission.getReadPermission(SleepSessionRecord::class)))
                else note = HealthConnectAccess.unavailableMessage(context)
            SetupItem.WIDGET -> {
                val manager = AppWidgetManager.getInstance(context)
                val accepted = runCatching { manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
                    ComponentName(context.packageName, "${context.packageName}.widget.QuickControlsReceiver"), null, null) }.getOrDefault(false)
                if (!accepted) note = context.getString(R.string.uat_setup_widget_help)
            }
        }
    }
    Card(Modifier.fillMaxWidth().testTag("setup-checklist")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.uat_setup_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            if (snapshot.requiredComplete) Text(stringResource(R.string.uat_setup_complete), style = MaterialTheme.typography.bodySmall)
            shown.forEach { item ->
                val title = stringResource(setupItemTitle(item))
                Column(Modifier.fillMaxWidth().testTag("setup-${item.key}")) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { fix(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.uat_setup_fix))
                        }
                    }
                    if (!item.required) {
                        val dismiss = stringResource(R.string.uat_setup_dismiss_item, title)
                        TextButton(onClick = { model.updateSettings { it.copy(setupDismissedOptional = it.setupDismissedOptional + item.key) } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = dismiss }) {
                            Text(stringResource(R.string.uat_setup_skip))
                        }
                    }
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (forced) TextButton(onClick = model::closeSetupChecklist) { Text(stringResource(R.string.uat_setup_done)) }
        }
    }
    if (pairDialog) AlertDialog(onDismissRequest = { pairDialog = false },
        title = { Text(stringResource(R.string.uat_setup_pair)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { SetupFlow(model, showDemoChoice = false) } },
        confirmButton = { TextButton(onClick = { pairDialog = false; refresh++ }) { Text(stringResource(R.string.uat_setup_done)) } })
}

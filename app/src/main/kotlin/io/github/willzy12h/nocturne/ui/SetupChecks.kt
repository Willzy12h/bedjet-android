package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.willzy12h.nocturne.night.NightAlarms
import io.github.willzy12h.nocturne.notify.Notifications

/** Something the app needs from the phone to work overnight, and how to fix it. */
data class SetupCheck(val title: String, val why: String, val ok: Boolean, val fix: () -> Unit)

/**
 * Permissions and settings that make nights reliable, especially on Samsung phones which stop background apps.
 * Shows only what is missing; with [showAll] it lists everything with its state.
 */
@Composable
fun SetupChecks(showAll: Boolean = false) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val checks = remember(refresh) { checks(context) { permissions.launch(it) } }
    val shown = if (showAll) checks else checks.filterNot { it.ok }
    if (shown.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = paletteTokens.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (showAll) R.string.settings_phone_setup else R.string.setup_checks_finish), style = MaterialTheme.typography.titleMedium)
            shown.forEach { check ->
                StripeTile(
                    stripe = if (check.ok) paletteTokens.accent else paletteTokens.cool,
                    title = (if (check.ok) "✓ " else "• ") + check.title,
                    subtitle = check.why,
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        if (!check.ok) FilledTonalButton(onClick = check.fix) { Text(stringResource(R.string.setup_checks_fix)) }
                    },
                )
            }
        }
    }
}

internal fun checks(context: Context, request: (Array<String>) -> Unit): List<SetupCheck> {
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val bluetooth = io.github.willzy12h.nocturne.ble.BluetoothAvailability.permissions
    val power = context.getSystemService(PowerManager::class.java)
    return listOf(
        SetupCheck(context.getString(R.string.setup_checks_nearby), context.getString(R.string.setup_checks_nearby_why), bluetooth.all(::granted)) { request(bluetooth) },
        SetupCheck(context.getString(R.string.setup_checks_notifications), context.getString(R.string.setup_checks_notifications_why), Notifications.canPost(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        },
        SetupCheck(context.getString(R.string.setup_checks_alarms), context.getString(R.string.setup_checks_alarms_why), NightAlarms.canScheduleExact(context)) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        },
        SetupCheck(
            context.getString(R.string.setup_checks_battery),
            context.getString(R.string.setup_checks_battery_why),
            power.isIgnoringBatteryOptimizations(context.packageName),
        ) {
            @Suppress("BatteryLife")
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        },
    )
}

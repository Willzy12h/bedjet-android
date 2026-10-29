package io.github.willzy12h.nocturne.ui

import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    val tokens = paletteTokens
    val icons = listOf(R.drawable.ic_nc_bt, R.drawable.ic_nc_bell, R.drawable.ic_nc_alarm, R.drawable.ic_nc_shield)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(if (showAll) R.string.settings_phone_setup else R.string.setup_checks_finish))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
            .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)) {
            shown.forEachIndexed { index, check ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = tokens.hairline)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tokens.surface2), contentAlignment = Alignment.Center) {
                        Icon(painterResource(icons[checks.indexOf(check).coerceIn(icons.indices)]), null, Modifier.size(18.dp),
                            tint = if (checks.indexOf(check) == 0) tokens.cool else tokens.ink2)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(check.title, color = tokens.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(check.why, color = tokens.ink2, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                    if (check.ok) Icon(painterResource(R.drawable.ic_nc_check), stringResource(R.string.parity_setup_allowed), Modifier.size(18.dp), tint = tokens.ink2)
                    else Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = check.fix), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.setup_checks_fix), Modifier.clip(RoundedCornerShape(50)).background(tokens.ink)
                            .padding(horizontal = 14.dp, vertical = 7.dp), color = tokens.ground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
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
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
        },
        SetupCheck(
            context.getString(R.string.setup_checks_battery),
            context.getString(R.string.setup_checks_battery_why),
            power.isIgnoringBatteryOptimizations(context.packageName),
        ) {
            @Suppress("BatteryLife")
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
        },
    )
}

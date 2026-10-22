package io.github.willzy12h.nocturne.ui.features

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.SubmissionTarget
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.PlatformSettings
import io.github.willzy12h.nocturne.platform.AndroidSleepSource
import io.github.willzy12h.nocturne.platform.PlatformNotifications
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.launch

@Composable private fun FeatureSwitch(label:String,checked:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f).padding(top=12.dp,end=8.dp))
        Switch(checked,onChange,modifier=Modifier.semantics {contentDescription=label},enabled=enabled)
    }
}

@Composable fun PlatformSettingsSection(graph: Graph) {
    val settings by graph.repository.settings.collectAsState()
    val prefs=settings.platform
    val scope=rememberCoroutineScope()
    var message by remember {mutableStateOf<String?>(null)}
    var notificationChoice by remember {mutableStateOf<String?>(null)}
    fun change(transform:(PlatformSettings)->PlatformSettings) {
        scope.launch {
            try { graph.repository.updateSettings { it.copy(platform=transform(it.platform)) } }
            catch (_: Exception) {message=graph.context.getString(R.string.feature_saving_failed)}
        }
    }
    fun enableNotificationFeature(which:String) {
        if (!PlatformNotifications.available(graph.context)) {message=graph.context.getString(R.string.feature_notification_permission);return}
        change {if(which=="charger") it.copy(chargerAutoStart=true,chargerDevice=settings.targetDevice) else it.copy(bedtimeReminder=true)}
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) notificationChoice?.let(::enableNotificationFeature) else message=graph.context.getString(R.string.feature_notification_permission)
    }
    val advertisePermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) change {it.copy(lullHandover=true)} else message=graph.context.getString(R.string.feature_advertise_permission)
    }
    val healthPermission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {granted->
        change {it.copy(healthSleepOverlay=granted.containsAll(AndroidSleepSource.PERMISSIONS))}
        if(!granted.containsAll(AndroidSleepSource.PERMISSIONS)) message=graph.context.getString(R.string.feature_health_permission)
    }
    fun notificationToggle(which:String,enabled:Boolean) {
        if(!enabled) {change {if(which=="charger") it.copy(chargerAutoStart=false) else it.copy(bedtimeReminder=false)};return}
        notificationChoice=which
        if(Build.VERSION.SDK_INT>=33 && graph.context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else enableNotificationFeature(which)
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FeatureSwitch(stringResource(R.string.feature_reminders),prefs.bedtimeReminder) {notificationToggle("reminder",it)}
        if(prefs.bedtimeReminder) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.feature_reminder_minutes,prefs.reminderMinutes,prefs.reminderMinutes),Modifier.weight(1f))
            TextButton({change {it.copy(reminderMinutes=(it.reminderMinutes+5).coerceAtMost(120))}}) {Text(stringResource(R.string.feature_earlier))}
            TextButton({change {it.copy(reminderMinutes=(it.reminderMinutes-5).coerceAtLeast(0))}}) {Text(stringResource(R.string.feature_later))}
        }
        FeatureSwitch(stringResource(R.string.feature_charger),prefs.chargerAutoStart,!settings.demoMode && settings.targetDevice!=null) {notificationToggle("charger",it)}
        Text(stringResource(R.string.feature_charger_help),style=MaterialTheme.typography.bodySmall)
        if(settings.demoMode || settings.targetDevice==null) Text(stringResource(R.string.feature_select_device),style=MaterialTheme.typography.bodySmall)
        FeatureSwitch(stringResource(R.string.feature_health_title),prefs.healthSleepOverlay) {enabled->
            if(!enabled) change {it.copy(healthSleepOverlay=false)}
            else if(HealthConnectClient.getSdkStatus(graph.context)!=HealthConnectClient.SDK_AVAILABLE) message=graph.context.getString(R.string.feature_health_absent)
            else healthPermission.launch(AndroidSleepSource.PERMISSIONS)
        }
        Text(stringResource(R.string.feature_health_privacy),style=MaterialTheme.typography.bodySmall)
        FeatureSwitch(stringResource(R.string.feature_lull),prefs.lullHandover) {enabled->
            if(!enabled) change {it.copy(lullHandover=false)}
            else if(graph.context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED) change {it.copy(lullHandover=true)}
            else advertisePermission.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        Text(stringResource(R.string.feature_lull_help),style=MaterialTheme.typography.bodySmall)
        message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

@Composable fun PhoneHandoverMenuItems(graph:Graph,onDismiss:()->Unit) {
    val released by graph.sessions.released.collectAsState()
    val settings by graph.repository.settings.collectAsState()
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    fun work(action:suspend ()->io.github.willzy12h.nocturne.night.CommandOutcome) {
        if(busy)return
        busy=true
        val target=settings.deviceId()
        scope.launch {
            try {
                val outcome=withContext(SubmissionTarget(target)) {action()}
                android.widget.Toast.makeText(graph.context,outcome.message,android.widget.Toast.LENGTH_LONG).show()
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                android.widget.Toast.makeText(graph.context,R.string.feature_operation_timeout,android.widget.Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(graph.context,e.message ?: graph.context.getString(R.string.runtime_failed),android.widget.Toast.LENGTH_LONG).show()
            } finally {busy=false;onDismiss()}
        }
    }
    DropdownMenuItem(text={Text(stringResource(if(released)R.string.feature_take_back else R.string.feature_release))},enabled=!busy,onClick={
        work {if(released) BedJetService.runOwned(graph.context, notifyFailures=false) {graph.platform.handover.takeBack()} else graph.platform.handover.release()}
    })
    if(settings.platform.lullHandover) DropdownMenuItem(text={Text(stringResource(R.string.feature_request))},enabled=!busy,onClick={
        work {
            val request=graph.platform.handover.request()
            if(request.succeeded) BedJetService.runOwned(graph.context, notifyFailures=false) {graph.platform.handover.takeBack()} else request
        }
    })
}

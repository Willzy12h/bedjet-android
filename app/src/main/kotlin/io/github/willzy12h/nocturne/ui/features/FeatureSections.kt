package io.github.willzy12h.nocturne.ui.features

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.*
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.SubmissionTarget
import io.github.willzy12h.nocturne.night.CommandOrigin
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.platform.HealthConnectAccess
import androidx.health.connect.client.PermissionController
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.PlatformSettings
import io.github.willzy12h.nocturne.platform.AndroidSleepSource
import io.github.willzy12h.nocturne.platform.PlatformNotifications
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.launch

@Composable private fun FeatureSwitch(label:String,checked:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit) {
    // One toggle for the whole row, at least 48 dp tall; the Switch inside only shows the state (U05).
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
        .toggleable(value=checked,enabled=enabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onChange)
        .semantics {contentDescription=label},verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f).padding(end=8.dp))
        Switch(checked,onCheckedChange=null,enabled=enabled)
    }
}

@Composable fun PlatformSettingsSection(graph: Graph) {
    val settings by graph.repository.settings.collectAsState()
    val prefs=settings.platform
    val scope=rememberCoroutineScope()
    if (!HealthConnectAccess.platformSupported) Text(stringResource(R.string.health_platform_required))
    var message by remember {mutableStateOf<String?>(null)}
    fun change(transform:(PlatformSettings)->PlatformSettings) {
        scope.launch {
            try { graph.repository.updateSettings { it.copy(platform=transform(it.platform)) } }
            catch (_: Exception) {message=graph.context.getString(R.string.feature_saving_failed)}
        }
    }
    fun changeHealthOverlay(enabled: Boolean) {
        scope.launch {
            try {
                graph.repository.updateSettings { current ->
                    if (current.demoMode) current else current.copy(platform = current.platform.copy(healthSleepOverlay = enabled))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { message = graph.context.getString(R.string.feature_saving_failed) }
        }
    }
    fun enableReminder() {
        if (!PlatformNotifications.available(graph.context)) {message=graph.context.getString(R.string.feature_notification_permission);return}
        change {it.copy(bedtimeReminder=true)}
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) enableReminder() else message=graph.context.getString(R.string.feature_notification_permission)
    }
    val healthPermission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {granted->
        if (!graph.repository.settings.value.demoMode && HealthConnectAccess.platformSupported) {
            changeHealthOverlay(granted.containsAll(AndroidSleepSource.PERMISSIONS))
            if(!granted.containsAll(AndroidSleepSource.PERMISSIONS)) message=graph.context.getString(R.string.feature_health_permission)
        }
    }
    fun reminderToggle(enabled:Boolean) {
        if(!enabled) {change {it.copy(bedtimeReminder=false)};return}
        if(Build.VERSION.SDK_INT>=33 && graph.context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else enableReminder()
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FeatureSwitch(stringResource(R.string.feature_reminders),prefs.bedtimeReminder) {reminderToggle(it)}
        if(prefs.bedtimeReminder) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.feature_reminder_minutes,prefs.reminderMinutes,prefs.reminderMinutes),Modifier.weight(1f))
            TextButton({change {it.copy(reminderMinutes=(it.reminderMinutes+5).coerceAtMost(120))}}) {Text(stringResource(R.string.feature_earlier))}
            TextButton({change {it.copy(reminderMinutes=(it.reminderMinutes-5).coerceAtLeast(0))}}) {Text(stringResource(R.string.feature_later))}
        }
        AutoStartSettingsSection(graph)
        FeatureSwitch(stringResource(R.string.feature_health_title),prefs.healthSleepOverlay, !settings.demoMode) {enabled->
            if (!graph.repository.settings.value.demoMode && HealthConnectAccess.platformSupported) {
                if(!enabled) changeHealthOverlay(false)
                else if(!HealthConnectAccess.available(graph.context)) message=HealthConnectAccess.unavailableMessage(graph.context)
                else healthPermission.launch(AndroidSleepSource.PERMISSIONS)
            }
        }
        Text(stringResource(R.string.feature_health_privacy),style=MaterialTheme.typography.bodySmall)
        HealthExportSettingsSection(graph)
        message?.let {Text(it,color=MaterialTheme.colorScheme.onSurface)}
    }
}

/** [onOutcome] shows the result in the app's own feedback (once, with the owner's feed); without it, a Toast. */
@Composable fun PhoneHandoverMenuItems(graph:Graph,onOutcome:((io.github.willzy12h.nocturne.night.CommandOutcome)->Unit)?=null,
    includeRelease:Boolean=true,asSettings:Boolean=false,onDismiss:()->Unit) {
    val released by graph.sessions.released.collectAsState()
    val settings by graph.repository.settings.collectAsState()
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    fun show(outcome:io.github.willzy12h.nocturne.night.CommandOutcome) {
        onOutcome?.invoke(outcome) ?: android.widget.Toast.makeText(graph.context,outcome.message,android.widget.Toast.LENGTH_LONG).show()
    }
    fun work(action:suspend ()->io.github.willzy12h.nocturne.night.CommandOutcome) {
        if(busy)return
        busy=true
        val target=settings.deviceId()
        scope.launch {
            try {
                val outcome=withContext(SubmissionTarget(target)) {action()}
                show(outcome)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                show(io.github.willzy12h.nocturne.night.CommandOutcome(io.github.willzy12h.nocturne.night.OutcomeKind.UNKNOWN,graph.context.getString(R.string.feature_operation_timeout),origin=CommandOrigin.APP))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                show(io.github.willzy12h.nocturne.night.CommandOutcome(io.github.willzy12h.nocturne.night.OutcomeKind.FAILED,e.message ?: graph.context.getString(R.string.runtime_failed),origin=CommandOrigin.APP))
            } finally {busy=false;onDismiss()}
        }
    }
    if(includeRelease) {
        val label=stringResource(if(released)R.string.feature_take_back else R.string.feature_release)
        val handover:()->Unit={work {if(released) BedJetService.runOwned(graph.context, notifyFailures=false) {graph.platform.handover.takeBack()} else graph.platform.handover.release()}}
        if(asSettings) OutlinedButton(onClick=handover,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)) {Text(label)}
        else DropdownMenuItem(text={Text(label)},enabled=!busy,onClick=handover)
    }

}

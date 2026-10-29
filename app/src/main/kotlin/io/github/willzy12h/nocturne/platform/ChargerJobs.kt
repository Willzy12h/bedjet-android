package io.github.willzy12h.nocturne.platform

import androidx.core.net.toUri
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.*
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.data.OperationAdmission
import io.github.willzy12h.nocturne.core.store.LoggedNotice
import io.github.willzy12h.nocturne.core.store.NoticeKind
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PlatformNotifications {
    const val CHANNEL="bedtime_features"
    const val CHARGER_ID=6101
    const val REMINDER_ID=6102
    const val OUTCOME_ID=6105
    fun available(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,context.getString(R.string.feature_channel),NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun countdown(context: Context, profile: String, key: String): Boolean {
        channel(context)
        if (!available(context)) return false
        fun action(disable: Boolean): PendingIntent = PendingIntent.getBroadcast(context,if(disable) 2 else 1,
            Intent(context,AutomationCancelReceiver::class.java).setData("nocturne://automation/${android.net.Uri.encode(key)}/$disable".toUri())
                .putExtra("key",key).putExtra("disable",disable),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            NotificationManagerCompat.from(context).notify(CHARGER_ID,NotificationCompat.Builder(context,CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.feature_starting,profile))
                .setContentText(context.getString(R.string.feature_charging_grace)).setOngoing(true)
                .addAction(0,context.getString(R.string.feature_cancel),action(false))
                .addAction(0,context.getString(R.string.feature_disable),action(true))
                .setContentIntent(PendingIntent.getActivity(context,0,NightTileService.openIntent(context),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build());true
        } catch (_: SecurityException) {false}
    }
    fun outcome(context: Context, title: String, message: String) {
        channel(context)
        if (!available(context)) return
        try {
            NotificationManagerCompat.from(context).notify(OUTCOME_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, NightTileService.openIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        } catch (_: SecurityException) { /* Permission may be revoked after the check. */ }
    }

}

object ChargerJobs {
    // Glance's WorkManager uses the default nonnegative job-ID range; reserve a negative native ID.
    const val ID=-6100
    // The existing delivery lock also covers the local refusal claim, so a replay cannot pass it while it saves.
    private val delivery = Mutex()
    private enum class Admission { ENQUEUED, IMPORT_BUSY, SCHEDULE_FAILED, IGNORED }
    fun sync(graph: Graph) {
        val scheduler = graph.context.getSystemService(JobScheduler::class.java)
        val pending = scheduler.getPendingJob(ID)
        if (pending != null && (pending.isPeriodic ||
                AutoStartTicket.from(pending.extras)?.let { AutoStartSchedule.due(graph, it) } == null)) scheduler.cancel(ID)
        AutoStartSchedule.syncAlarm(graph)
    }

    suspend fun enqueue(graph: Graph, ticket: AutoStartTicket): Boolean = delivery.withLock {
        enqueueLocked(graph, ticket) == Admission.ENQUEUED
    }

    /** Longer hardware work stays in JobService; this delivery only schedules or records a local refusal. */
    suspend fun deliver(graph: Graph, ticket: AutoStartTicket) = delivery.withLock {
        try {
            val admission = enqueueLocked(graph, ticket)
            if ((admission == Admission.IMPORT_BUSY || admission == Admission.SCHEDULE_FAILED) &&
                !graph.repository.settings.value.demoMode && !AutoStartSchedule.claimed(graph, ticket.key)) {
                val title = graph.context.getString(R.string.auto_start_outcome)
                val importing = admission == Admission.IMPORT_BUSY
                val message = graph.context.getString(if (importing) R.string.auto_start_result_skipped else R.string.auto_start_result_failed,
                    graph.context.getString(if (importing) R.string.auto_start_skip_import else R.string.auto_start_schedule_failed))
                var announce = false
                try {
                    if (!graph.repository.claimAutomation("auto-skip:${ticket.key}") ||
                        graph.repository.settings.value.demoMode) return@withLock
                    announce = true
                    graph.repository.addNotice(LoggedNotice(graph.now().toString(), NoticeKind.PROBLEM, title, message))
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) {
                    // The admission refusal remains certain even when its local record cannot be saved.
                    announce = true
                    android.util.Log.w("AutoStart", "Automatic start refusal could not be saved")
                } finally {
                    if (announce && !graph.repository.settings.value.demoMode) PlatformNotifications.outcome(graph.context, title, message)
                }
            }
        } finally { sync(graph) }
    }

    /** Admission and the synchronous schedule call cannot straddle an import's exclusive gate. */
    private fun enqueueLocked(graph: Graph, ticket: AutoStartTicket): Admission {
        if (AutoStartSchedule.due(graph, ticket) == null || AutoStartSchedule.claimed(graph, ticket.key)) return Admission.IGNORED
        val scheduler = graph.context.getSystemService(JobScheduler::class.java)
        val pending = scheduler.getPendingJob(ID)
        if (pending != null && !pending.isPeriodic) return Admission.IGNORED
        val lease = graph.repository.operationAdmission.tryCommand() ?: return Admission.IMPORT_BUSY
        try {
            // An import may have completed between the early classification and admission.
            if (AutoStartSchedule.due(graph, ticket) == null || AutoStartSchedule.claimed(graph, ticket.key)) return Admission.IGNORED
            if (pending != null) scheduler.cancel(ID)
            // The owner evaluates charging once and reports a skip; the OS must not wait indefinitely for a plug.
            val result = scheduler.schedule(JobInfo.Builder(ID, ComponentName(graph.context, ChargerJobService::class.java))
                .setMinimumLatency(0).setOverrideDeadline(AutoStartSchedule.WINDOW_MS)
                .setExtras(ticket.extras()).build())
            return if (result == JobScheduler.RESULT_SUCCESS) Admission.ENQUEUED else Admission.SCHEDULE_FAILED
        } finally { lease.close() }
    }
    fun charging(context: Context): Boolean {
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) != 0 &&
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }
    fun permitted(context: Context): Boolean = PlatformNotifications.available(context) &&
        listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN).all {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
}

// ChargerJobs.ID is negative; the transitive WorkManager default range is nonnegative.
// PlatformAdapterTest verifies the installed WorkManager Configuration range and native ID do not overlap.
@android.annotation.SuppressLint("SpecifyJobSchedulerIdRange")
class ChargerJobService : JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Running(val params: JobParameters, val admission: OperationAdmission.Lease, var stopped: Boolean=false, var job: Job?=null)
    private var running: Running?=null
    // Injectable operation boundary; the service still owns the sole execution coroutine and cleanup.
    internal var ownedOperation: suspend () -> Unit = {
        val ticket = running?.params?.extras?.let(AutoStartTicket::from)
        if (ticket != null) ChargerRun(graph, { ChargerJobs.charging(this) },
            { profile, key -> PlatformNotifications.countdown(this, profile, key) },
            { delay(60_000) }, { ChargerJobs.permitted(this) }, ticket).execute()
    }
    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId != ChargerJobs.ID || running != null) return false
        val admission = graph.repository.operationAdmission.tryCommand() ?: return false
        val owner=Running(params, admission);running=owner
        owner.job=scope.launch {
            try {
                ownedOperation()
            } catch (e: CancellationException) {throw e
            } catch (_: Exception) {android.util.Log.w("AutoStart", "Automatic start ended")
            } finally {
                // Retain this generation until its operation (including cancellation cleanup) completes.
                if (running === owner) {
                    getSystemService(NotificationManager::class.java).cancel(PlatformNotifications.CHARGER_ID)
                    running=null
                    if (!owner.stopped) jobFinished(params,false)
                    ChargerJobs.sync(graph)
                }
                owner.admission.close()
            }
        }
        owner.job?.invokeOnCompletion { owner.admission.close() }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        // Binder unmarshals a distinct parameters object; the reserved job ID is the logical identity.
        running?.takeIf {it.params.jobId == params.jobId}?.let {it.stopped=true;it.job?.cancel()}
        return true // A system reschedule must revalidate the bounded ticket and repeat grace; claimed attempts never replay.
    }
    override fun onDestroy() { running?.stopped=true;scope.cancel();super.onDestroy() }
}

/** Notification actions are explicit immutable pending intents. Cancellation is durable BEFORE stopping the job. */
class AutomationCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val key=intent.getStringExtra("key")?.takeIf { it.length in 1..160 } ?: return
        val pending=goAsync()
        context.graph.scope.launch {
            try {
                withTimeout(8_000) {
                    context.graph.repository.claimAutomation("cancel:$key")
                    if (intent.getBooleanExtra("disable",false)) context.graph.repository.updateSettings {
                        it.copy(platform=it.platform.copy(chargerAutoStart=false,
                            autoStart=it.platform.autoStart?.copy(enabled=false)))
                    }
                    context.getSystemService(JobScheduler::class.java).cancel(ChargerJobs.ID)
                    context.getSystemService(NotificationManager::class.java).cancel(PlatformNotifications.CHARGER_ID)
                    ChargerJobs.sync(context.graph)
                }
            } finally { pending.finish() }
        }
    }
}

/** Explicit immutable alarm only; longer work is handed to the OS job lifetime, without an FGS exemption. */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ticket = AutoStartTicket.from(intent) ?: return
        val pending = goAsync()
        context.graph.scope.launch {
            try { withTimeout(8_000) {
                ChargerJobs.deliver(context.graph, ticket)
            } } finally { pending.finish() }
        }
    }
}

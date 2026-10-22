package io.github.willzy12h.nocturne.platform

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
import kotlinx.coroutines.*

object PlatformNotifications {
    const val CHANNEL="bedtime_features"
    const val CHARGER_ID=6101
    const val REMINDER_ID=6102
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
            Intent(context,AutomationCancelReceiver::class.java).setData(android.net.Uri.parse("nocturne://automation/${android.net.Uri.encode(key)}/$disable"))
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
}

object ChargerJobs {
    // Glance's WorkManager uses the default nonnegative job-ID range; reserve a negative native ID.
    const val ID=-6100
    fun sync(graph: Graph) {
        val scheduler=graph.context.getSystemService(JobScheduler::class.java)
        val enabled=graph.repository.settings.value.platform.chargerAutoStart
        if (!enabled) { scheduler.cancel(ID); return }
        if (scheduler.getPendingJob(ID) == null) scheduler.schedule(JobInfo.Builder(ID,ComponentName(graph.context,ChargerJobService::class.java))
            .setRequiresCharging(true).setPeriodic(15*60*1000L).setPersisted(true).build())
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

class ChargerJobService : JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Running(val params: JobParameters, var stopped: Boolean=false, var job: Job?=null)
    private var running: Running?=null
    override fun onStartJob(params: JobParameters): Boolean {
        if (running != null) return false
        val owner=Running(params);running=owner
        owner.job=scope.launch {
            try {
                ChargerRun(graph,{ChargerJobs.charging(this@ChargerJobService)},
                    {profile,key->PlatformNotifications.countdown(this@ChargerJobService,profile,key)},
                    {delay(60_000)},{ChargerJobs.permitted(this@ChargerJobService)}).execute()
            } catch (e: CancellationException) {throw e
            } catch (e: Exception) {android.util.Log.w("ChargerJob","Automatic start ended",e)
            } finally {
                getSystemService(NotificationManager::class.java).cancel(PlatformNotifications.CHARGER_ID)
                if (running === owner) running=null
                if (!owner.stopped) jobFinished(params,false)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running?.takeIf {it.params === params}?.let {it.stopped=true;it.job?.cancel()}
        return true // Any later periodic execution repeats the entire grace; attempts remain suppressed.
    }
    override fun onDestroy() { running?.stopped=true;scope.cancel();super.onDestroy() }
}

/** Notification actions are explicit immutable pending intents. Cancellation is durable BEFORE stopping the job. */
class AutomationCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val key=intent.getStringExtra("key") ?: return
        val pending=goAsync()
        context.graph.scope.launch {
            try {
                withTimeout(8_000) {
                    context.graph.repository.claimAutomation("cancel:$key")
                    if (intent.getBooleanExtra("disable",false)) context.graph.repository.updateSettings {
                        it.copy(platform=it.platform.copy(chargerAutoStart=false))
                    }
                    context.getSystemService(JobScheduler::class.java).cancel(ChargerJobs.ID)
                    context.getSystemService(NotificationManager::class.java).cancel(PlatformNotifications.CHARGER_ID)
                    ChargerJobs.sync(context.graph)
                }
            } finally { pending.finish() }
        }
    }
}

package io.github.willzy12h.nocturne.widget

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.glance.appwidget.updateAll
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the widgets' BedJet status fresh while the app is closed: every 15 minutes or so the OS runs one read-only
 * status read through the command owner ([io.github.willzy12h.nocturne.night.NightActions.readForWidget]). The job
 * exists only while a widget is placed and a real BedJet is chosen with nearby-device access.
 */
object WidgetRefresh {
    // Glance's WorkManager uses the default nonnegative job-ID range; ChargerJobs holds -6100.
    const val ID = -6101
    private const val PERIOD_MS = 15 * 60_000L

    fun placed(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return listOf(QuickControlsReceiver::class.java, SingleActionReceiver::class.java).any {
            manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
        }
    }

    private fun wanted(context: Context): Boolean {
        val settings = context.graph.repository.settings.value
        return !settings.demoMode && settings.deviceId() != null && placed(context) &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /** Schedules or cancels the periodic read to match whether a widget needs it. */
    fun sync(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val pending = scheduler.getPendingJob(ID)
        if (!runCatching { wanted(context) }.getOrDefault(false)) {
            if (pending != null) scheduler.cancel(ID)
            return
        }
        if (pending != null) return
        runCatching {
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, WidgetRefreshService::class.java))
                .setPeriodic(PERIOD_MS).setPersisted(true).build())
        }
    }
}

// WidgetRefresh.ID is negative, outside WorkManager's default nonnegative range, as ChargerJobs.ID is.
@android.annotation.SuppressLint("SpecifyJobSchedulerIdRange")
class WidgetRefreshService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId != WidgetRefresh.ID || running != null) return false
        stopped = false
        running = scope.launch {
            try {
                if (graph.actions.readForWidget()) {
                    QuickControlsWidget().updateAll(this@WidgetRefreshService)
                    SingleActionWidget().updateAll(this@WidgetRefreshService)
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { android.util.Log.w("Widget", "Background status read ended")
            } finally {
                running = null
                if (!stopped) jobFinished(params, false)
                WidgetRefresh.sync(this@WidgetRefreshService)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        running?.cancel()
        return false // The next period reads again; a stopped read is not retried early.
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

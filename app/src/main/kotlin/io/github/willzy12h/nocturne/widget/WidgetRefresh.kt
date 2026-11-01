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
 *
 * To spare the battery, nothing is read while the screen is off (nobody sees the widget then), and a BedJet that is
 * not found is tried less often ([WidgetReadBackoff]) until it answers again or the app is opened.
 */
object WidgetRefresh {
    // Glance's WorkManager uses the default nonnegative job-ID range; ChargerJobs holds -6100.
    const val ID = -6101
    private const val PERIOD_MS = 15 * 60_000L

    private const val PREFS = "widget_refresh"
    private const val KEY_MISSES = "misses"
    private const val KEY_LAST_MISS = "last_miss"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether a read is worth trying now: the screen is on and any back-off after missed reads has passed. */
    internal fun due(context: Context, nowMs: Long): Boolean {
        val interactive = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        val prefs = prefs(context)
        return interactive && WidgetReadBackoff.due(nowMs, prefs.getLong(KEY_LAST_MISS, 0), prefs.getInt(KEY_MISSES, 0))
    }

    internal fun record(context: Context, read: io.github.willzy12h.nocturne.night.WidgetRead, nowMs: Long) {
        when (read) {
            io.github.willzy12h.nocturne.night.WidgetRead.READ -> resetBackoff(context)
            io.github.willzy12h.nocturne.night.WidgetRead.NOT_REACHED -> prefs(context).edit()
                .putInt(KEY_MISSES, prefs(context).getInt(KEY_MISSES, 0) + 1).putLong(KEY_LAST_MISS, nowMs).apply()
            io.github.willzy12h.nocturne.night.WidgetRead.STEPPED_ASIDE -> Unit
        }
    }

    /** Back to the normal 15 minutes: the BedJet answered, or the app was opened and connects by itself. */
    fun resetBackoff(context: Context) {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_MISSES, 0) != 0) prefs.edit().remove(KEY_MISSES).remove(KEY_LAST_MISS).apply()
    }

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
        if (!WidgetRefresh.due(this, System.currentTimeMillis())) return false
        stopped = false
        running = scope.launch {
            try {
                val read = graph.actions.readForWidget()
                WidgetRefresh.record(this@WidgetRefreshService, read, System.currentTimeMillis())
                if (read == io.github.willzy12h.nocturne.night.WidgetRead.READ) {
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

/** Reads less often while the BedJet is not found: 30 minutes after one miss, an hour after two, then two hours. */
object WidgetReadBackoff {
    fun waitMinutes(misses: Int): Long = when {
        misses <= 0 -> 0
        misses == 1 -> 30
        misses == 2 -> 60
        else -> 120
    }

    fun due(nowMs: Long, lastMissMs: Long, misses: Int): Boolean =
        misses <= 0 || nowMs < lastMissMs || nowMs - lastMissMs >= waitMinutes(misses) * 60_000
}

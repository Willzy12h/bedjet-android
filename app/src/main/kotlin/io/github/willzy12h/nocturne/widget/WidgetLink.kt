package io.github.willzy12h.nocturne.widget

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.WidgetRead
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How the widgets show the link to the BedJet. Every state but [AWAKE] is the greyed "asleep" look. */
enum class WidgetLinkState {
    /** Connected at least once and reading as normal: the buttons send. */
    AWAKE,
    /** Not connected: never yet, after missed reads, after a force stop. A grey tap reconnects. */
    ASLEEP,
    /** A reconnect tap is looking for the BedJet now. */
    CONNECTING,
    /** The last reconnect tap did not find the BedJet. */
    MISSING,
}

/** What the widgets remember about the link, kept across process restarts. */
data class WidgetLinkFacts(
    val everConnected: Boolean = false,
    val sleeping: Boolean = false,
    val misses: Int = 0,
    val lastMissMs: Long = 0,
    val lastReadMs: Long = 0,
    val connectingUntilMs: Long = 0,
    val failedAtMs: Long = 0,
    val presenceReadMs: Long = 0,
)

/** The pure rules behind the asleep widget, kept apart from Android so they can be tested. */
object WidgetLinkRules {
    /** Out of range, the background read tries after 30 minutes, an hour and two hours, then sleeps. */
    const val SLEEP_AFTER_MISSES = 4
    /** How long a reconnect tap shows "Connecting…" at most, should the process end before it answers. */
    const val CONNECTING_MS = 45_000L
    /** A BedJet seen nearby starts at most one read in this time, whatever the radio reports. */
    const val PRESENCE_GAP_MS = 10 * 60_000L

    fun state(facts: WidgetLinkFacts, nowMs: Long, hasDevice: Boolean, demo: Boolean): WidgetLinkState = when {
        demo || !hasDevice -> WidgetLinkState.AWAKE
        facts.connectingUntilMs > nowMs && facts.connectingUntilMs - nowMs <= CONNECTING_MS -> WidgetLinkState.CONNECTING
        facts.everConnected && !facts.sleeping && facts.misses == 0 -> WidgetLinkState.AWAKE
        facts.failedAtMs > 0 -> WidgetLinkState.MISSING
        else -> WidgetLinkState.ASLEEP
    }

    /** A good read: awake, with the miss count and any failed tap cleared. */
    fun read(facts: WidgetLinkFacts, nowMs: Long) = facts.copy(everConnected = true, sleeping = false, misses = 0,
        lastMissMs = 0, lastReadMs = nowMs, connectingUntilMs = 0, failedAtMs = 0)

    /** A missed background read; the fourth in a row puts the widget to sleep. */
    fun missed(facts: WidgetLinkFacts, nowMs: Long): WidgetLinkFacts {
        val misses = facts.misses + 1
        return facts.copy(misses = misses, lastMissMs = nowMs, sleeping = facts.sleeping || misses >= SLEEP_AFTER_MISSES)
    }

    /** Opening Nocturne wakes the widget: the app connects by itself, and background reads start again. */
    fun opened(facts: WidgetLinkFacts) = facts.copy(sleeping = false, misses = 0, lastMissMs = 0)

    /** The background read runs only once Nocturne has connected and while the widget is not asleep. */
    fun readsInBackground(facts: WidgetLinkFacts) = facts.everConnected && !facts.sleeping

    /**
     * Whether the BedJet appearing nearby should start a read: only while the widget is grey and not already
     * connecting, and not again within [PRESENCE_GAP_MS]. Our own reads can make the BedJet advertise again, so
     * an awake widget never reads on presence.
     */
    fun presenceReads(facts: WidgetLinkFacts, nowMs: Long, hasDevice: Boolean, demo: Boolean): Boolean {
        val state = state(facts, nowMs, hasDevice, demo)
        if (demo || !hasDevice || state == WidgetLinkState.AWAKE || state == WidgetLinkState.CONNECTING) return false
        return facts.presenceReadMs == 0L || nowMs < facts.presenceReadMs || nowMs - facts.presenceReadMs >= PRESENCE_GAP_MS
    }

    /**
     * The force stop to act on: the newest user-requested exit later than the one already handled, or null.
     * Android gives no callback at force-stop time, so this is found when the process next starts.
     */
    fun forceStopSince(exits: List<Pair<Int, Long>>, handledMs: Long): Long? =
        exits.filter { it.first == ApplicationExitInfo.REASON_USER_REQUESTED && it.second > handledMs }.maxOfOrNull { it.second }
}

/** The widgets' link state, stored with the background read's back-off. */
object WidgetLink {
    private const val PREFS = "widget_refresh"
    private const val KEY_MISSES = "misses"
    private const val KEY_LAST_MISS = "last_miss"
    private const val KEY_EVER = "ever_connected"
    private const val KEY_SLEEPING = "sleeping"
    private const val KEY_LAST_READ = "last_read"
    private const val KEY_CONNECTING = "connecting_until"
    private const val KEY_FAILED = "failed_at"
    private const val KEY_PRESENCE = "presence_read"
    private const val KEY_EXIT = "exit_handled"
    /** "Last read" changes in whole minutes, so the time is written at most once a minute. */
    private const val READ_WRITE_MS = 60_000L

    private val _changes = MutableStateFlow(0)
    /** Bumped on every change, so the widgets redraw. */
    val changes: StateFlow<Int> = _changes

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun facts(context: Context): WidgetLinkFacts = prefs(context).let {
        WidgetLinkFacts(it.getBoolean(KEY_EVER, false), it.getBoolean(KEY_SLEEPING, false), it.getInt(KEY_MISSES, 0),
            it.getLong(KEY_LAST_MISS, 0), it.getLong(KEY_LAST_READ, 0), it.getLong(KEY_CONNECTING, 0),
            it.getLong(KEY_FAILED, 0), it.getLong(KEY_PRESENCE, 0))
    }

    @Synchronized private fun update(context: Context, change: (WidgetLinkFacts) -> WidgetLinkFacts): Boolean {
        val before = facts(context)
        val after = change(before)
        if (after == before) return false
        prefs(context).edit {
            putBoolean(KEY_EVER, after.everConnected).putBoolean(KEY_SLEEPING, after.sleeping)
                .putInt(KEY_MISSES, after.misses).putLong(KEY_LAST_MISS, after.lastMissMs).putLong(KEY_LAST_READ, after.lastReadMs)
                .putLong(KEY_CONNECTING, after.connectingUntilMs).putLong(KEY_FAILED, after.failedAtMs)
                .putLong(KEY_PRESENCE, after.presenceReadMs)
        }
        // Every stored change redraws. The look also depends on the time ("Connecting…" runs out), so comparing
        // looks would miss clearing an expired try and leave a stale "Looking…" drawn. Writes are rare: the read
        // time is stored at most once a minute.
        _changes.value++
        return true
    }

    fun state(context: Context, nowMs: Long = System.currentTimeMillis()): WidgetLinkState {
        val settings = context.graph.repository.settings.value
        return WidgetLinkRules.state(facts(context), nowMs, settings.deviceId() != null, settings.demoMode)
    }

    /** The chosen BedJet reported: awake. Called for every report, so the read time is written once a minute. */
    fun markRead(context: Context, nowMs: Long = System.currentTimeMillis()) {
        update(context) { facts ->
            val next = WidgetLinkRules.read(facts, nowMs)
            if (next.copy(lastReadMs = facts.lastReadMs) == facts && nowMs - facts.lastReadMs in 0 until READ_WRITE_MS) facts else next
        }
    }

    internal fun recordMiss(context: Context, nowMs: Long) { update(context) { WidgetLinkRules.missed(it, nowMs) } }

    /** Nocturne opened: background reads resume and the widget wakes. */
    fun opened(context: Context) {
        update(context) { WidgetLinkRules.opened(it) }
        WidgetRefresh.sync(context)
    }

    internal fun beginConnecting(context: Context, nowMs: Long) {
        update(context) { it.copy(connectingUntilMs = nowMs + WidgetLinkRules.CONNECTING_MS) }
    }

    internal fun failed(context: Context, nowMs: Long) { update(context) { it.copy(connectingUntilMs = 0, failedAtMs = nowMs) } }

    internal fun stopConnecting(context: Context) { update(context) { it.copy(connectingUntilMs = 0) } }

    internal fun presenceRead(context: Context, nowMs: Long) { update(context) { it.copy(presenceReadMs = nowMs) } }

    /**
     * On process start: a force stop since the last start puts the widget to sleep until Nocturne is opened.
     * Android tells the app nothing at force-stop time, and cancels its jobs and widget taps, so the widget can
     * only redraw as asleep once Nocturne runs again.
     */
    fun checkForceStop(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { manager.getHistoricalProcessExitReasons(null, 0, 5) }.getOrNull() ?: return
        val handled = prefs(context).getLong(KEY_EXIT, 0)
        val stop = WidgetLinkRules.forceStopSince(exits.map { it.reason to it.timestamp }, handled) ?: return
        prefs(context).edit { putLong(KEY_EXIT, stop) }
        update(context) { it.copy(sleeping = true, connectingUntilMs = 0) }
    }

    /** For the asleep header: when the chosen BedJet last reported, kept across restarts. */
    fun lastReadMs(context: Context): Long = facts(context).lastReadMs.takeIf { it > 0 } ?: 0

    internal fun clearBackoff(context: Context) { update(context) { it.copy(misses = 0, lastMissMs = 0) } }
}

/**
 * A grey widget button: one short try to reach the BedJet, through the command owner. It sends nothing; once the
 * BedJet answers the buttons light up and the next tap sends as normal.
 */
/**
 * A grey tap. A header or Reconnect tap carries no action and only reconnects. A grey preset or Start carries its
 * captured request: once the BedJet answers, that request goes to the command owner as an awake tap would, so a tap
 * made before the connection is not lost. If the BedJet is not reached, nothing is sent.
 */
class WidgetReconnectCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext
        val then = widgetRequest(parameters)?.let {
            WidgetReconnect.Deferred(glanceId, parameters[WidgetActionCallback.KIND] ?: WidgetActionCallback.QUICK, it)
        }
        WidgetReconnect.start(app, then)
    }
}

object WidgetReconnect {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A tapped action waiting for the connection; see [WidgetReconnectCallback]. */
    class Deferred(val id: GlanceId, val kind: String, val request: CommandRequest)
    /** The latest grey tap's action. A newer tap replaces it; it is sent at most once. */
    private val deferred = java.util.concurrent.atomic.AtomicReference<Deferred?>(null)

    fun start(context: Context, then: Deferred? = null) {
        if (WidgetLink.state(context) == WidgetLinkState.AWAKE) {
            // The widget woke between drawing the grey button and this tap: send it as an awake tap.
            then?.let { context.graph.scope.launch { send(context, it) } }
            return
        }
        if (then != null) deferred.set(then)
        if (!running.compareAndSet(false, true)) return
        WidgetLink.beginConnecting(context, System.currentTimeMillis())
        context.graph.scope.launch {
            try {
                redraw(context)
                val read = try {
                    BedJetService.runOwned(context, notifyFailures = false) { context.graph.actions.readForWidget() }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { WidgetRead.NOT_REACHED }
                apply(context, read, System.currentTimeMillis())
                // Only a widget that woke sends the waiting tap; a missed BedJet drops it unsent.
                val waiting = deferred.getAndSet(null)
                if (waiting != null && WidgetLink.state(context) == WidgetLinkState.AWAKE) send(context, waiting)
            } finally {
                deferred.set(null)
                running.set(false)
                WidgetLink.stopConnecting(context)
                WidgetRefresh.sync(context)
                redraw(context)
            }
        }
    }

    internal fun apply(context: Context, read: WidgetRead, nowMs: Long) = when (read) {
        WidgetRead.READ -> WidgetLink.markRead(context, nowMs)
        WidgetRead.NOT_REACHED -> WidgetLink.failed(context, nowMs)
        // The app holds the BedJet: its own fresh report is the read, so the widget wakes from that.
        WidgetRead.STEPPED_ASIDE -> if (freshlyRead(context)) WidgetLink.markRead(context, nowMs) else WidgetLink.stopConnecting(context)
    }

    /** How recent the app's own report must be for a grey tap to count it as the read. */
    internal const val FRESH_READ_MS = 2 * 60_000L

    /** Whether the chosen BedJet reported in this process within [FRESH_READ_MS]. */
    private fun freshlyRead(context: Context): Boolean {
        val graph = context.graph
        val at = graph.statusReadAt.value ?: return false
        val age = java.time.Duration.between(at, graph.now()).toMillis()
        return age in 0..FRESH_READ_MS
    }

    /**
     * Hands a waiting tap to the same delivery as an awake tap. A request that now needs the night's confirmation is
     * not sent from here: the widget is awake, and the next tap opens the confirmation.
     */
    internal suspend fun send(context: Context, waiting: Deferred) {
        if (widgetNeedsConfirmation(waiting.request, context.graph.repository.activeNight.value)) return
        try { WidgetDelivery.submit(context, waiting.id, waiting.kind, waiting.request) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }

    suspend fun redraw(context: Context) {
        runCatching { QuickControlsWidget().updateAll(context) }
        runCatching { SingleActionWidget().updateAll(context) }
    }
}

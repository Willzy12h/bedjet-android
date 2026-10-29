package io.github.willzy12h.nocturne

import android.app.Application
import android.content.Context
import io.github.willzy12h.nocturne.ble.AndroidBedJetLink
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.NightRunner
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.LinkSessions
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.ZonedDateTime

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifications.createChannels(this)
        graph.startRuntime()
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph

/** The app's shared objects. One per process. */
class Graph(val context: Context, private val linkOverride: BedJetLink? = null,
    repositoryFactory: ((CoroutineScope, () -> ZonedDateTime) -> Repository)? = null) {
    /** Every "now" in the app comes from here, so tests and screenshots can fix the time. */
    @Volatile var clock: Clock = object : Clock() {
        override fun getZone() = java.time.ZoneId.systemDefault()
        override fun withZone(zone: java.time.ZoneId): Clock = Clock.system(zone)
        override fun instant(): java.time.Instant = java.time.Instant.now()
    }
    fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = repositoryFactory?.invoke(scope) { now() } ?: Repository(context, scope) { now() }
    val bleLink by lazy { AndroidBedJetLink(context) { repository.settings.value.deviceId() } }
    private var demoTicker: Job? = null
    @Volatile private var demoLinkHolder = newDemoLink()
    private val demoLink: DemoBedJet get() = demoLinkHolder.value
    private fun newDemoLink(): Lazy<DemoBedJet> = lazy {
        DemoBedJet(now = { now() }).also { demo ->
            demoTicker = scope.launch {
                combine(sessions.statusDemand, repository.settings.map { it.demoMode }.distinctUntilChanged(),
                    repository.activeNight.map { it != null }.distinctUntilChanged(),
                    demo.status.map { it?.let { status -> status.isRunning || (status.extra?.programStep ?: 0) > 0 } == true }
                        .distinctUntilChanged()) { shownOrOwned, enabled, night, programme ->
                    enabled && (shownOrOwned || night || programme)
                }.distinctUntilChanged().collectLatest { needed ->
                    if (needed) {
                        demo.tick()
                        while (true) { delay(1_000); demo.tick() }
                    }
                }
            }
        }
    }

    /** Called under command ownership after disconnecting the outgoing simulator. */
    internal fun resetDemoSession() {
        demoTicker?.cancel()
        demoTicker = null
        demoLinkHolder = newDemoLink()
    }

    /** The real BedJet, or the simulated one when demo mode is on. */
    val link: BedJetLink get() = linkOverride ?: if (repository.settings.value.demoMode) demoLink else bleLink
    val sessions = LinkSessions(this)
    val actions = NightActions(this)
    val platform = io.github.willzy12h.nocturne.platform.PlatformRuntime(this)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun startRuntime() {
        // The application does not expose a first screen or start side effects until this durable boundary passes.
        if (!runBlocking(Dispatchers.IO) { repository.migrateLegacyDemoSelection() }) return
        platform.start()
        WidgetUpdater.start(context)
        scope.launch { actions.initialise() }
        scope.launch {
            // A pending wake-up change re-posts the quiet status too, so it never says "Waking at" while Tonight says pending.
            combine(repository.settingsPublication, repository.activeNight,
                repository.runtime.map { it.retime != null || it.alarmInbox != null }.distinctUntilChanged()) { _, _, _ -> Unit }.collect {
                actions.refreshQuietNotification()
            }
        }
        scope.launch { Notifications.observeWakeResume(context) }
        scope.launch {
            repository.settings.map { it.deviceId() }.distinctUntilChanged().flatMapLatest { target ->
                link.sourcedStatus.map { target to it }
            }.collect { (target, report) ->
                // A report from a session still on the previous BedJet says nothing about the chosen one.
                if (report != null && report.isFrom(target)) {
                    actions.observeStatus(report, target, now())
                    platform.observe(report.status, target)
                }
            }
        }
    }

    fun controller() = BedJetController(link) { now() }
    fun runner() = NightRunner(controller()) { now() }
}

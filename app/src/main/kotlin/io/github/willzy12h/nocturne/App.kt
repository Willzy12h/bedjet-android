package io.github.willzy12h.nocturne

import android.app.Application
import android.content.Context
import io.github.willzy12h.nocturne.ble.AndroidBedJetLink
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.NightRunner
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.LinkSessions
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifications.createChannels(this)
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph

/** The app's shared objects. One per process. */
class Graph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = Repository(context, scope)
    val bleLink by lazy { AndroidBedJetLink(context) }
    private val demoLink by lazy {
        DemoBedJet().also { demo -> scope.launch { while (true) { delay(1_000); demo.tick() } } }
    }

    /** The real BedJet, or the simulated one when demo mode is on. */
    val link: BedJetLink get() = if (repository.settings.value.demoMode) demoLink else bleLink
    val sessions = LinkSessions(this)
    val actions = NightActions(this)

    fun controller() = BedJetController(link) { ZonedDateTime.now() }
    fun runner() = NightRunner(controller()) { ZonedDateTime.now() }
}

package io.github.willzy12h.nocturne.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Redraws both widgets when what they show changes: profile names (the Start buttons), settings (theme, demo mode,
 * tonight's profile) and the BedJet's name. The name isn't observable itself; it's read on connect, so a change in the
 * link's state is when to look at it again. Runs for the life of the process once the app has been opened.
 */
object WidgetUpdater {
    private val started = AtomicBoolean(false)

    private data class Shown(val profiles: List<Profile>, val settings: Settings, val name: String, val runtime: Any?, val minute: Long, val dark: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val graph = app.graph
        val repo = graph.repository
        val linkState = repo.settings.map { graph.link }.distinctUntilChanged().flatMapLatest { it.state }
        val time = flow { while (true) { emit(graph.now()); delay(60_000) } }
        val shownState = combine(repo.activeNight, repo.automation, repo.runtime, time) { active, observation, runtime, at ->
            Triple(Triple(active,observation,runtime),at.toEpochSecond()/60,at)
        }
        graph.scope.launch {
            combine(repo.profiles, repo.settings, linkState, shownState) { profiles, settings, _, shown -> Shown(profiles, settings, widgetBedJetName(app, settings), shown.first, shown.second, settings.isDarkTheme(shown.third,false)) }
                .distinctUntilChanged()
                // A slider drag writes settings many times a second; widgets only need the result.
                .debounce(500)
                .collect {
                    runCatching {
                        QuickControlsWidget().updateAll(app)
                        SingleActionWidget().updateAll(app)
                    }
                }
        }
    }
}

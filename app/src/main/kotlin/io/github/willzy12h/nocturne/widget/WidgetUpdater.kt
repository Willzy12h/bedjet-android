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

    private data class Shown(val profiles: List<Profile>, val settings: Settings, val name: String, val runtime: Any?, val dark: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val graph = app.graph
        graph.scope.launch {
            observeUpdates(app) {
                runCatching {
                    QuickControlsWidget().updateAll(app)
                    SingleActionWidget().updateAll(app)
                }
            }
        }
    }

    /** Shared by the process observer and a background-only regression with a captured redraw. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    internal suspend fun observeUpdates(app: Context, redraw: suspend () -> Unit) {
        val graph = app.graph
        val repo = graph.repository
        val linkState = repo.settings.map { graph.link }.distinctUntilChanged().flatMapLatest { it.state }
        // These are the finite deadlines of the tracked plan, not an unconditional minute poll.
        // Ending/changing the night cancels its old deadlines through flatMapLatest.
        val boundaries = combine(repo.activeNight, repo.profiles) { active, profiles -> active to profiles }
            .flatMapLatest { (active, profiles) -> flow {
                emit(Unit)
                if (active?.confidence != io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED || active.pending != null) return@flow
                val times = buildList {
                    active.activePlan(profiles.firstOrNull { it.id == active.profileId })?.steps?.forEach { add(it.startsAt); add(it.endsAt) }
                    add(java.time.ZonedDateTime.parse(active.wakeAt))
                    add(java.time.ZonedDateTime.parse(active.endsAt))
                }.distinct().filter { it.isAfter(graph.now()) }.sorted()
                for (at in times) {
                    delay(java.time.Duration.between(graph.now(), at).toMillis().coerceAtLeast(0))
                    emit(Unit)
                }
            } }
        val shownState = combine(repo.activeNight, repo.automation, repo.runtime, boundaries, graph.actions.status) { active, observation, runtime, _, status ->
            listOf(active, observation, runtime, status, widgetPhase(app, active), widgetWake(app, active, runtime))
        }
        combine(repo.profiles, repo.settings, linkState, shownState) { profiles, settings, _, shown ->
            Shown(profiles, settings, widgetBedJetName(app, settings), shown, settings.isDarkTheme(graph.now(), false))
        }
            .distinctUntilChanged()
            // A slider drag writes settings many times a second; widgets only need the result.
            .debounce(500)
            .collect { redraw() }
    }
}

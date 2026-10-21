package io.github.willzy12h.nocturne.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.service.BedJetService

/** A widget button: what it says, and the command it sends. */
data class WidgetAction(val label: String, val command: Command, val profileId: String? = null)

fun widgetActions(context: Context): List<WidgetAction> {
    val repo = context.graph.repository
    val tonight = repo.tonightsProfile()
    val fixed = listOf(
        WidgetAction("Start ${tonight?.name ?: "tonight"}", Command.START_NIGHT, tonight?.id),
        WidgetAction("Boost", Command.BOOST),
        WidgetAction("Turbo", Command.TURBO),
        WidgetAction("Cool", Command.COOL),
        WidgetAction("Warm", Command.WARM),
        WidgetAction("Off", Command.OFF),
    )
    val profiles = repo.profiles.value.filter { it.id != tonight?.id }.map { WidgetAction("Start ${it.name}", Command.START_NIGHT, it.id) }
    return fixed + profiles
}

@Composable
private fun ActionButton(context: Context, action: WidgetAction, modifier: GlanceModifier = GlanceModifier) {
    Button(
        text = action.label,
        onClick = actionStartService(BedJetService.intent(context, action.command, action.profileId), isForegroundService = true),
        modifier = modifier,
        colors = if (action.command == Command.OFF) ButtonDefaults.buttonColors(
            backgroundColor = GlanceTheme.colors.secondaryContainer, contentColor = GlanceTheme.colors.onSecondaryContainer,
        ) else ButtonDefaults.buttonColors(),
    )
}

/**
 * Quick controls that grow with the widget: one button at 1×1, a row at 4×1, a grid at 4×2, and a scrolling list of
 * every action and profile when it's bigger still.
 */
class QuickControlsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(60.dp, 60.dp), DpSize(130.dp, 60.dp), DpSize(250.dp, 60.dp), DpSize(250.dp, 130.dp), DpSize(250.dp, 220.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val actions = widgetActions(context)
                val size = LocalSize.current
                val columns = when { size.width >= 250.dp -> 3; size.width >= 130.dp -> 2; else -> 1 }
                val rows = when { size.height >= 220.dp -> 0; size.height >= 130.dp -> 2; else -> 1 }
                Box(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(6.dp)) {
                    if (rows == 0) {
                        LazyColumn(GlanceModifier.fillMaxSize()) {
                            items(actions.size) { i -> ActionButton(context, actions[i], GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) }
                        }
                    } else {
                        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            actions.take(columns * rows).chunked(columns).forEach { row ->
                                Row(GlanceModifier.fillMaxWidth()) {
                                    row.forEach { ActionButton(context, it, GlanceModifier.defaultWeight().padding(2.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class QuickControlsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickControlsWidget()
}

/** One configurable button. */
class SingleActionWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs = currentState<Preferences>()
                val command = prefs[KEY_COMMAND]?.let { runCatching { Command.valueOf(it) }.getOrNull() } ?: Command.START_NIGHT
                val label = prefs[KEY_LABEL] ?: "Start tonight"
                Box(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ActionButton(context, WidgetAction(label, command, prefs[KEY_PROFILE]), GlanceModifier.fillMaxSize())
                }
            }
        }
    }

    companion object {
        val KEY_COMMAND = stringPreferencesKey("command")
        val KEY_LABEL = stringPreferencesKey("label")
        val KEY_PROFILE = stringPreferencesKey("profile")
    }
}

class SingleActionReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SingleActionWidget()
}


package io.github.willzy12h.nocturne.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.color.ColorProviders
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders as materialColors
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.DaylightScheme
import io.github.willzy12h.nocturne.ui.MidnightInkScheme

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

/** Same rule as the app's Settings screen: the demo unit's name, else the paired BedJet's, else a plain "BedJet". */
fun widgetBedJetName(context: Context, settings: Settings): String =
    if (settings.demoMode) "BEDJET-DEMO" else context.graph.bleLink.deviceName ?: "BedJet"

/** The app's palettes, so widgets match it: Midnight Ink in dark, Daylight in light, following the theme setting. */
fun widgetColors(mode: ThemeMode): ColorProviders = when (mode) {
    ThemeMode.SYSTEM -> materialColors(light = DaylightScheme, dark = MidnightInkScheme)
    ThemeMode.DARK -> materialColors(MidnightInkScheme)
    ThemeMode.LIGHT -> materialColors(DaylightScheme)
}

@Composable
private fun Caption(text: String, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
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
 * every action and profile when it's bigger still. From two rows up there's room to name the BedJet above them.
 */
class QuickControlsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(60.dp, 60.dp), DpSize(130.dp, 60.dp), DpSize(250.dp, 60.dp), DpSize(250.dp, 130.dp), DpSize(250.dp, 220.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repository
        provideContent {
            // Read as state so a running widget session redraws when these change; WidgetUpdater covers the rest.
            val settings by repo.settings.collectAsState()
            val profiles by repo.profiles.collectAsState()
            GlanceTheme(colors = widgetColors(settings.themeMode)) {
                val actions = remember(settings, profiles) { widgetActions(context) }
                val size = LocalSize.current
                val columns = when { size.width >= 250.dp -> 3; size.width >= 130.dp -> 2; else -> 1 }
                val rows = when { size.height >= 220.dp -> 0; size.height >= 130.dp -> 2; else -> 1 }
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(16.dp).padding(6.dp)) {
                    if (rows != 1) Caption(widgetBedJetName(context, settings), GlanceModifier.padding(start = 6.dp, bottom = 2.dp))
                    if (rows == 0) {
                        LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            items(actions.size) { i -> ActionButton(context, actions[i], GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) }
                        }
                    } else {
                        Column(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
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

/** One configurable button, with the BedJet's name under it when there's room or in its label when there isn't. */
class SingleActionWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repository
        provideContent {
            val settings by repo.settings.collectAsState()
            GlanceTheme(colors = widgetColors(settings.themeMode)) {
                val prefs = currentState<Preferences>()
                val command = prefs[KEY_COMMAND]?.let { runCatching { Command.valueOf(it) }.getOrNull() } ?: Command.START_NIGHT
                val label = prefs[KEY_LABEL] ?: "Start tonight"
                val name = widgetBedJetName(context, settings)
                val roomy = LocalSize.current.height >= 100.dp
                Column(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(16.dp).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val action = WidgetAction(if (roomy) label else "$label · $name", command, prefs[KEY_PROFILE])
                    ActionButton(context, action, GlanceModifier.fillMaxWidth().defaultWeight())
                    if (roomy) Caption(name, GlanceModifier.padding(top = 2.dp))
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


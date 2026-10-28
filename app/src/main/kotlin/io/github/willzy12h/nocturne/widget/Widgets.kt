package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders as materialColors
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.compactBoostLabel
import io.github.willzy12h.nocturne.ui.MainActivity
import io.github.willzy12h.nocturne.ui.PaletteTokens
import io.github.willzy12h.nocturne.ui.materialScheme
import io.github.willzy12h.nocturne.ui.tokens

/** One command, resolved through the service and the shared command owner. */
data class WidgetAction(val label: String, val command: Command, val profileId: String? = null)

fun widgetActions(context: Context): List<WidgetAction> {
    val repo = context.graph.repository
    return listOf(
        WidgetAction(context.getString(R.string.feature_start_tonight), Command.START_NIGHT),
        WidgetAction(compactBoostLabel(context, repo.settings.value.boostMinutes), Command.BOOST),
        WidgetAction(context.getString(R.string.widget_turbo_duration, 10), Command.TURBO),
        WidgetAction(context.getString(R.string.feature_cool), Command.COOL),
        WidgetAction(context.getString(R.string.feature_warm), Command.WARM),
        WidgetAction(context.getString(R.string.feature_off), Command.OFF),
        WidgetAction(context.getString(R.string.runtime_command_stop_tracking), Command.STOP_TRACKING),
    ) + repo.profiles.value.map {
        WidgetAction(context.getString(R.string.feature_start_profile, it.name), Command.START_NIGHT, it.id)
    }
}

fun widgetBedJetName(context: Context, settings: Settings): String =
    if (settings.demoMode) "BEDJET-DEMO" else settings.deviceId()?.let { context.graph.bleLink.deviceNameFor(it) }
        ?: context.getString(R.string.widget_bedjet_fallback)

fun widgetColors(settings: Settings, at: java.time.ZonedDateTime): ColorProviders =
    if (settings.themeMode == ThemeMode.SYSTEM) materialColors(light = settings.dayPalette.tokens.materialScheme(), dark = settings.nightPalette.tokens.materialScheme())
    else materialColors(settings.palette(at, systemDark = false).tokens.materialScheme())

private fun widgetColor(settings: Settings, at: java.time.ZonedDateTime, color: (PaletteTokens) -> Color): ColorProvider =
    if (settings.themeMode == ThemeMode.SYSTEM) ColorProvider(day = color(settings.dayPalette.tokens), night = color(settings.nightPalette.tokens))
    else ColorProvider(color(settings.palette(at, false).tokens))

internal enum class WidgetLayout(val size: DpSize, val capacity: Int) {
    COMPACT(DpSize(200.dp, 128.dp), 1), MEDIUM(DpSize(280.dp, 180.dp), 2), LARGE(DpSize(340.dp, 240.dp), 3);
    companion object {
        fun forSize(size: DpSize): WidgetLayout = entries.lastOrNull {
            size.width >= it.size.width && size.height >= it.size.height
        } ?: COMPACT
    }
}

@Composable
private fun Caption(text: String, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp), maxLines = 1)
}

@Composable
private fun MainValue(text: String, modifier: GlanceModifier = GlanceModifier, centred: Boolean = false) {
    Text(text, modifier, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 20.sp,
        textAlign = if (centred) TextAlign.Center else TextAlign.Start), maxLines = 1)
}

@Composable
private fun ActionButton(context: Context, action: WidgetAction, kind: String, id: GlanceId, modifier: GlanceModifier,
    settings: Settings, at: java.time.ZonedDateTime) {
    val active by context.graph.repository.activeNight.collectAsState()
    val request = widgetRequest(context, action)
    fun stripe(tokens: PaletteTokens): Color = request.actionPreset?.let { tokens.stepColor(it.mode, it.temperature) }
        ?: if (action.command == Command.OFF || action.command == Command.END_NIGHT) tokens.stepColor(Mode.STANDBY, 0) else tokens.accent
    val stripeColor = widgetColor(settings, at, ::stripe)
    val tintColor = widgetColor(settings, at) { tokens -> tokens.tint(stripe(tokens)).compositeOver(tokens.surface.compositeOver(tokens.ground)) }
    val click = if (widgetNeedsConfirmation(request, active))
        actionStartActivity(WidgetConfirmationActivity.intent(context, request, id, kind))
        else actionRunCallback<WidgetActionCallback>(widgetParameters(request, kind))
    Row(modifier.height(56.dp).background(tintColor).cornerRadius(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(4.dp).height(56.dp).background(stripeColor)) { }
        Button(text = action.label,
        onClick = click,
        modifier = GlanceModifier.defaultWeight().height(56.dp).padding(horizontal = 0.dp, vertical = 0.dp).semantics { contentDescription = widgetActionDescription(context, action, request) },
        style = TextStyle(fontSize = 16.sp), maxLines = if (context.resources.configuration.fontScale > 1.3f) 1 else 2,
        colors = ButtonDefaults.buttonColors(backgroundColor = tintColor, contentColor = GlanceTheme.colors.onSurface))
    }
}

@Composable
private fun PageButton(context: Context, previous: Boolean, page: WidgetPage) {
    Button(text = context.getString(if (previous) R.string.widget_previous_glyph else R.string.widget_next_glyph),
        onClick = actionRunCallback<WidgetPageCallback>(actionParametersOf(WidgetPageCallback.PAGE to page.key)),
        modifier = GlanceModifier.width(56.dp).height(56.dp).padding(horizontal = 0.dp, vertical = 0.dp).semantics {
            contentDescription = context.getString(if (previous) R.string.widget_previous_page else R.string.widget_next_page, page.label)
        }, style = TextStyle(fontSize = 16.sp), colors = ButtonDefaults.buttonColors(
            backgroundColor = GlanceTheme.colors.surfaceVariant, contentColor = GlanceTheme.colors.onSurface))
}

@Composable
private fun OpenAppButton(context: Context, modifier: GlanceModifier) {
    Button(context.getString(R.string.widget_open_app),
        actionStartActivity(Intent(context, MainActivity::class.java)), modifier.height(56.dp).padding(horizontal = 4.dp, vertical = 0.dp),
        style = TextStyle(fontSize = 16.sp), maxLines = if (context.resources.configuration.fontScale > 1.3f) 1 else 2,
        colors = ButtonDefaults.buttonColors(backgroundColor = GlanceTheme.colors.surfaceVariant,
            contentColor = GlanceTheme.colors.onSurface))
}

/** A compact symbol never replaces the complete state, command and outcome description. */
@Composable
private fun Status(context: Context, prefs: Preferences, observation: io.github.willzy12h.nocturne.platform.DeviceObservation?,
    caption: String, symbols: Boolean, largeText: Boolean, temperature: Boolean = false) {
    val result = WidgetFeedback.visible(context, prefs)
    val title = result?.let { context.getString(it.state.title) } ?: widgetHeadline(context, observation)
    val description = result?.let { context.getString(R.string.widget_result_description,
        context.getString(it.state.title), it.action, it.message) } ?: widgetMode(context, observation)
    val glyph = when (result?.state) {
        WidgetFeedbackState.PENDING -> R.string.widget_status_pending_glyph
        WidgetFeedbackState.CONFIRMED -> R.string.widget_status_confirmed_glyph
        WidgetFeedbackState.FAILED -> R.string.widget_status_failed_glyph
        WidgetFeedbackState.UNKNOWN -> R.string.widget_status_unknown_glyph
        WidgetFeedbackState.LOCAL -> R.string.widget_status_local_glyph
        null -> if (title == context.getString(R.string.feature_unknown)) R.string.widget_status_unknown_glyph
            else R.string.widget_status_last_glyph
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        MainValue(if (symbols) context.getString(glyph) else title,
            GlanceModifier.semantics { contentDescription = context.getString(R.string.widget_status_description,
                description, widgetBedJetName(context, context.graph.repository.settings.value)) }, centred = true)
        if (temperature) {
            Spacer(GlanceModifier.width(8.dp))
            Temperature(context, observation, compact = true)
        }
    }
    if (!largeText) Caption(caption)
}

@Composable
private fun Temperature(context: Context, observation: io.github.willzy12h.nocturne.platform.DeviceObservation?, compact: Boolean) {
    MainValue(widgetTemperature(context, compact), GlanceModifier.semantics {
        contentDescription = context.getString(R.string.widget_temperature_description,
            widgetTemperature(context), widgetReportTime(context, observation))
    })
}

@Composable
private fun Details(context: Context, layout: WidgetLayout, largeText: Boolean,
    observation: io.github.willzy12h.nocturne.platform.DeviceObservation?,
    active: io.github.willzy12h.nocturne.core.night.ActiveNight?, runtime: io.github.willzy12h.nocturne.night.RuntimeState) {
    if (layout != WidgetLayout.COMPACT && !(largeText && layout == WidgetLayout.LARGE)) {
        Temperature(context, observation, compact = largeText)
        if (!largeText) Caption(widgetReportTime(context, observation))
    }
    if (layout == WidgetLayout.LARGE) {
        val phase = widgetPhaseText(context, active, compact = largeText)
        Caption(phase.title, GlanceModifier.semantics { contentDescription = widgetPhase(context, active) })
        phase.span?.let { Caption(it) }
        Caption(if (largeText) widgetWakeShort(context, active, runtime) else widgetWake(context, active, runtime),
            GlanceModifier.semantics { contentDescription = widgetWake(context, active, runtime) })
    }
}

/** One/two/three-action budgets; enlarged text uses one full-width action and keeps overflow on pages. */
class QuickControlsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(WidgetLayout.entries.map { it.size }.toSet())

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repository
        provideContent {
            val settings by repo.settings.collectAsState()
            val active by repo.activeNight.collectAsState()
            val observed by repo.automation.collectAsState()
            val profiles by repo.profiles.collectAsState()
            val runtime by repo.runtime.collectAsState()
            val prefs = currentState<Preferences>()
            val layout = WidgetLayout.forSize(LocalSize.current)
            val largeText = context.resources.configuration.fontScale > 1.3f
            // Read publications here and in the process updater; rendering never requests a connection.
            val pages = widgetPages(context, if (largeText) 1 else layout.capacity, profiles)
            val selected = selectedWidgetPage(pages, prefs[KEY_PAGE])
            val page = pages[selected]
            val appearanceAt = context.graph.now()
            GlanceTheme(colors = widgetColors(settings, appearanceAt)) {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(16.dp)
                    .padding(horizontal = 8.dp, vertical = if (largeText && layout == WidgetLayout.LARGE) 4.dp else 8.dp)) {
                    Row(GlanceModifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        PageButton(context, true, pages[(selected + pages.size - 1) % pages.size])
                        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalAlignment = Alignment.CenterVertically) {
                            Status(context, prefs, observed.observed, page.label,
                                symbols = largeText || layout == WidgetLayout.COMPACT, largeText = largeText,
                                temperature = largeText && layout == WidgetLayout.LARGE)
                        }
                        PageButton(context, false, pages[(selected + 1) % pages.size])
                    }
                    Details(context, layout, largeText, observed.observed, active, runtime)
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page.opensApp) OpenAppButton(context, GlanceModifier.fillMaxWidth())
                        else page.actions.forEachIndexed { index, action ->
                            if (index > 0) Spacer(GlanceModifier.width(4.dp))
                            ActionButton(context, action, WidgetActionCallback.QUICK, id, GlanceModifier.defaultWeight(), settings, appearanceAt)
                        }
                    }
                }
            }
        }
    }
    companion object { val KEY_PAGE = stringPreferencesKey("widget_page") }
}

class QuickControlsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickControlsWidget()
}

/** The configured command shares delivery, readable type and honest status with Quick controls. */
class SingleActionWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(WidgetLayout.entries.map { it.size }.toSet())
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repository
        provideContent {
            val settings by repo.settings.collectAsState()
            val active by repo.activeNight.collectAsState()
            val observed by repo.automation.collectAsState()
            val runtime by repo.runtime.collectAsState()
            val prefs = currentState<Preferences>()
            val command = prefs[KEY_COMMAND]?.let { runCatching { Command.valueOf(it) }.getOrNull() } ?: Command.START_NIGHT
            val configured = configuredWidgetAction(context, command,
                prefs[KEY_LABEL] ?: context.getString(R.string.feature_start_tonight), prefs[KEY_PROFILE])
            val layout = WidgetLayout.forSize(LocalSize.current)
            val largeText = context.resources.configuration.fontScale > 1.3f
            val appearanceAt = context.graph.now()
            GlanceTheme(colors = widgetColors(settings, appearanceAt)) {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(16.dp)
                    .padding(horizontal = 8.dp, vertical = if (largeText && layout == WidgetLayout.LARGE) 4.dp else 8.dp)) {
                    Row(GlanceModifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalAlignment = Alignment.CenterVertically) {
                            Status(context, prefs, observed.observed,
                                if (layout == WidgetLayout.COMPACT) context.getString(R.string.widget_bedjet_fallback) else widgetBedJetName(context, settings),
                                symbols = largeText || layout == WidgetLayout.COMPACT, largeText = largeText,
                                temperature = largeText && layout == WidgetLayout.LARGE)
                        }
                        OpenAppButton(context, GlanceModifier.width(if (largeText) 132.dp else 80.dp))
                    }
                    Details(context, layout, largeText, observed.observed, active, runtime)
                    Spacer(GlanceModifier.defaultWeight())
                    ActionButton(context, configured, WidgetActionCallback.SINGLE, id, GlanceModifier.fillMaxWidth(), settings, appearanceAt)
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

package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders as materialColors
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.compactBoostLabel
import io.github.willzy12h.nocturne.ui.BoostColor
import io.github.willzy12h.nocturne.ui.Format
import io.github.willzy12h.nocturne.ui.MainActivity
import io.github.willzy12h.nocturne.ui.PaletteTokens
import io.github.willzy12h.nocturne.ui.TintLevel
import io.github.willzy12h.nocturne.ui.materialScheme
import io.github.willzy12h.nocturne.ui.modeIcon
import io.github.willzy12h.nocturne.ui.presetDetail
import io.github.willzy12h.nocturne.ui.quickMatches
import io.github.willzy12h.nocturne.ui.tokens
import kotlin.math.floor

/**
 * One command, resolved through the service and the shared command owner. [key] names a quick preset, a
 * profile or Off as stored by a widget; [preset] is the quick preset's saved run, captured again at each tap.
 */
data class WidgetAction(val label: String, val command: Command, val profileId: String? = null,
    val key: String = command.name, val preset: ActionPreset? = null)

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

internal const val PRESET_OFF = "OFF"
internal const val PRESET_START = "START"
internal const val PROFILE_PREFIX = "profile:"

/** The Tonight screen's quick presets that a widget can run, in the Tonight screen's order. */
internal val QUICK_PRESETS = listOf("TURBO", "HEAT", "COOL", "DRY", "BOOST")

/** What Controls shows until the person chooses: Turbo, Heat and Cool first, then Boost and Dry as it grows. */
internal val DEFAULT_CONTROLS = listOf("TURBO", "HEAT", "COOL", "BOOST", "DRY")

/** A quick preset, Start tonight, a profile or Off by its stored key; null for a removed profile or an unknown key. */
internal fun widgetPreset(context: Context, key: String): WidgetAction? {
    val repo = context.graph.repository
    val settings = repo.settings.value
    fun quick(mode: Mode, command: Command) = WidgetAction(context.getString(Format.modeNameRes(mode)), command,
        key = key, preset = QuickDefaults.resolve(settings, mode))
    return when (key) {
        "TURBO" -> quick(Mode.TURBO, Command.TURBO)
        "HEAT" -> quick(Mode.HEAT, Command.WARM)
        "COOL" -> quick(Mode.COOL, Command.COOL)
        "DRY" -> quick(Mode.DRY, Command.WARM)
        "BOOST" -> WidgetAction(context.getString(R.string.shortcut_boost), Command.BOOST, key = key)
        PRESET_START -> WidgetAction(context.getString(R.string.feature_start_tonight), Command.START_NIGHT, key = key)
        PRESET_OFF -> WidgetAction(context.getString(R.string.feature_off), Command.OFF, key = key)
        else -> key.takeIf { it.startsWith(PROFILE_PREFIX) }?.let { repo.profile(it.removePrefix(PROFILE_PREFIX)) }
            ?.let { WidgetAction(it.name, Command.START_NIGHT, it.id, key = key) }
    }
}

/** Everything a preset tile can run: the quick presets, Start tonight, each profile and Off. */
internal fun widgetPresetChoices(context: Context): List<WidgetAction> =
    (QUICK_PRESETS + PRESET_START + context.graph.repository.profiles.value.map { PROFILE_PREFIX + it.id } + PRESET_OFF)
        .mapNotNull { widgetPreset(context, it) }

/** The saved Controls list, without Off (always shown) and without presets that no longer exist. */
internal fun controlKeys(context: Context, stored: String?): List<String> =
    (stored?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_CONTROLS).distinct()
        .filter { it != PRESET_OFF && widgetPreset(context, it) != null }

fun widgetBedJetName(context: Context, settings: Settings): String =
    if (settings.demoMode) "BEDJET-DEMO" else settings.deviceId()?.let { context.graph.bleLink.deviceNameFor(it) }
        ?: context.getString(R.string.widget_bedjet_fallback)

fun widgetColors(settings: Settings, at: java.time.ZonedDateTime): ColorProviders =
    if (settings.themeMode == ThemeMode.SYSTEM) materialColors(light = settings.dayPalette.tokens.materialScheme(), dark = settings.nightPalette.tokens.materialScheme())
    else materialColors(settings.palette(at, systemDark = false).tokens.materialScheme())

private fun widgetColor(settings: Settings, at: java.time.ZonedDateTime, color: (PaletteTokens) -> Color): ColorProvider =
    if (settings.themeMode == ThemeMode.SYSTEM) ColorProvider(day = color(settings.dayPalette.tokens), night = color(settings.nightPalette.tokens))
    else ColorProvider(color(settings.palette(at, false).tokens))

/** What one widget control shows and does, worked out once per render. */
private class Control(val action: WidgetAction, val request: CommandRequest, val click: Action,
    val stripe: (PaletteTokens) -> Color, val icon: Int, val detail: String?, val short: String?,
    val running: Boolean, val sending: Boolean, val description: String, val brief: String = action.label)

private fun stripeOf(tokens: PaletteTokens, action: WidgetAction, request: CommandRequest): Color =
    (request.actionPreset ?: action.preset)?.takeIf { action.command != Command.BOOST }?.let { tokens.stepColor(it.mode, it.temperature) }
        ?: when (action.command) {
            Command.OFF, Command.END_NIGHT, Command.STOP_TRACKING -> tokens.off
            Command.BOOST -> BoostColor
            else -> tokens.accent
        }

private fun iconOf(action: WidgetAction, request: CommandRequest): Int = when (action.command) {
    Command.OFF, Command.END_NIGHT, Command.STOP_TRACKING -> R.drawable.ic_nc_off
    Command.BOOST -> R.drawable.ic_nc_ramp
    Command.START_NIGHT -> R.drawable.ic_nc_moon
    else -> (request.actionPreset ?: action.preset)?.let { modeIcon(it.mode) } ?: R.drawable.ic_nc_ramp
}

/** Whether a tracked night's programme is not known to be running as planned. */
private fun widgetUnconfirmed(context: Context, active: ActiveNight) =
    widgetPhaseText(context, active).title == context.getString(R.string.widget_phase_unknown)

/** The live report from the chosen BedJet only; the widget never asks for a connection. */
private fun widgetReport(context: Context): DeviceStatus? {
    val settings = context.graph.repository.settings.value
    return context.graph.link.latestSourcedStatus()?.takeIf { it.isFrom(settings.deviceId()) }?.status
}

private fun control(context: Context, action: WidgetAction, kind: String, id: GlanceId, prefs: Preferences,
    active: ActiveNight?, report: DeviceStatus?): Control {
    val settings = context.graph.repository.settings.value
    val words = Format.Words { res, args -> context.getString(res, *args) }
    val request = widgetRequest(context, action)
    val preset = request.actionPreset ?: action.preset
    val click = when {
        settings.deviceId() == null && !settings.demoMode -> actionStartActivity(Intent(context, MainActivity::class.java))
        widgetNeedsConfirmation(request, active) -> actionStartActivity(WidgetConfirmationActivity.intent(context, request, id, kind))
        else -> actionRunCallback<WidgetActionCallback>(widgetParameters(request, kind))
    }
    val detail = when {
        active != null && action.command == Command.START_NIGHT && widgetUnconfirmed(context, active) -> context.getString(R.string.widget_phase_unknown)
        action.command == Command.BOOST -> (settings.boostMinutes - 10).let {
            if (it > 0) context.getString(R.string.boost_tile_detail, it) else context.getString(R.string.boost_tile_turbo_only) }
        action.command == Command.OFF -> null
        action.command == Command.START_NIGHT -> if (action.profileId != null) context.getString(R.string.widget_start_short)
            else context.graph.repository.tonightsProfile()?.name
        preset == null -> null
        preset.mode.usesTemperature -> context.getString(R.string.quick_tile_detail,
            Format.temp(preset.temperature, settings.fahrenheit, words), Format.duration(preset.minutes, words))
        else -> Format.duration(preset.minutes, words)
    }
    val short = preset?.takeIf { action.command != Command.BOOST && it.mode.usesTemperature }
        ?.let { Format.temp(it.temperature, settings.fahrenheit, words) }
    val running = report != null && when (action.command) {
        Command.OFF -> report.mode == Mode.STANDBY
        Command.TURBO, Command.WARM, Command.COOL -> preset != null && active == null && quickMatches(report, preset)
        else -> false
    }
    val feedback = WidgetFeedback.visible(context, prefs)
    val sending = feedback?.state == WidgetFeedbackState.PENDING && feedback.action == widgetActionLabel(context, request)
    val base = when {
        preset != null && action.command != Command.BOOST && action.command != Command.TURBO -> presetDetail(context, preset, settings.fahrenheit)
        action.profileId != null -> context.getString(R.string.feature_start_profile, action.label)
        else -> widgetActionDescription(context, action, request)
    }
    val description = when {
        sending -> context.getString(R.string.widget_control_sending, base)
        running -> context.getString(R.string.widget_control_running, base)
        else -> base
    }
    // A narrow pill names Start tonight by its verb; the spoken description keeps the full name.
    val brief = if (action.command == Command.START_NIGHT && action.profileId == null) context.getString(R.string.widget_start_short) else action.label
    return Control(action, request, click, { stripeOf(it, action, request) }, iconOf(action, request), detail, short,
        running, sending, description, brief)
}

@Composable
private fun Dot(color: ColorProvider, size: Dp = 8.dp) {
    Box(GlanceModifier.size(size).cornerRadius(size / 2).background(color)) { }
}

/** The width of [text] as a 16sp widget label, with a margin for launcher fonts; Glance itself cannot measure. */
private fun labelWidthDp(context: Context, text: String, bold: Boolean = true): Float {
    val metrics = context.resources.displayMetrics
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, metrics)
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }
    return paint.measureText(text) / metrics.density * 1.05f
}

/** One pill: the mode's stripe and tint, its icon and name, and a dot while it runs or is being sent. */
@Composable
private fun Pill(context: Context, control: Control, width: Dp, modifier: GlanceModifier, settings: Settings, at: java.time.ZonedDateTime) {
    val level = if (control.running || control.sending) TintLevel.ON else TintLevel.TILE
    val tint = widgetColor(settings, at) { it.tint(control.stripe(it), level).compositeOver(it.surface.compositeOver(it.ground)) }
    val stripe = widgetColor(settings, at, control.stripe)
    val roomy = width >= 112.dp && context.resources.configuration.fontScale <= 1.3f
    val room = width.value - 16f - (if (roomy) 22f else 0f) - (if (control.running || control.sending) 16f else 0f)
    val fits = { text: String -> labelWidthDp(context, text) <= room }
    val label = when {
        roomy && width >= 132.dp && control.short != null && fits("${control.action.label} ${control.short}") -> "${control.action.label} ${control.short}"
        fits(control.action.label) -> control.action.label
        else -> control.brief
    }
    Row(modifier.height(56.dp).cornerRadius(14.dp).background(tint).clickable(control.click)
        .semantics { contentDescription = control.description }, verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(4.dp).fillMaxHeight().background(stripe)) { }
        Spacer(GlanceModifier.width(if (roomy) 8.dp else 4.dp))
        if (roomy) {
            Image(ImageProvider(control.icon), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(stripe))
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(label, GlanceModifier.defaultWeight(), maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        if (control.running || control.sending) {
            Dot(if (control.sending) widgetColor(settings, at) { it.warn } else stripe)
            Spacer(GlanceModifier.width(8.dp))
        }
    }
}

/** The status line: the last tap's outcome, else what the BedJet last reported, else why nothing is shown. */
private fun statusLine(context: Context, prefs: Preferences, report: DeviceStatus?, active: ActiveNight?): Pair<String, String> {
    val settings = context.graph.repository.settings.value
    val words = Format.Words { res, args -> context.getString(res, *args) }
    if (settings.deviceId() == null && !settings.demoMode)
        return context.getString(R.string.widget_choose_bedjet).let { it to it }
    WidgetFeedback.visible(context, prefs)?.let { result ->
        val title = context.getString(result.state.title)
        val shown = if (result.state == WidgetFeedbackState.PENDING) context.getString(R.string.widget_sending, result.action)
            else context.getString(R.string.widget_status_result, result.action, title)
        return shown to context.getString(R.string.widget_result_description, title, result.action, result.message)
    }
    val mode = report?.mode ?: return context.getString(R.string.widget_no_report).let { it to it }
    val name = context.getString(Format.modeNameRes(mode))
    val reading = if (mode.usesTemperature) context.getString(R.string.widget_mode_temperature, name,
        Format.temp(report.targetTemperature, settings.fahrenheit, words)) else name
    val where = when {
        settings.demoMode -> context.getString(R.string.widget_demo)
        // The BedJet's own report stays; an unconfirmed programme says so instead of naming its profile.
        active != null && widgetUnconfirmed(context, active) -> context.getString(R.string.widget_phase_unknown)
        active != null -> active.profile?.name ?: context.graph.repository.profile(active.profileId)?.name
        else -> null
    }
    val shown = where?.let { context.getString(R.string.quick_tile_detail, reading, it) } ?: reading
    return shown to context.getString(R.string.widget_status_description, shown, widgetBedJetName(context, settings))
}

/** Room temperature and status; tapping it opens Nocturne. */
@Composable
private fun Header(context: Context, prefs: Preferences, report: DeviceStatus?, active: ActiveNight?, stacked: Boolean,
    modifier: GlanceModifier, settings: Settings, at: java.time.ZonedDateTime, strip: Boolean = false) {
    val words = Format.Words { res, args -> context.getString(res, *args) }
    val temperature = report?.let { Format.temp(it.ambientTemperature, settings.fahrenheit, words) }
        ?: context.getString(R.string.widget_temperature_unknown_short)
    val (status, statusDescription) = statusLine(context, prefs, report, active)
    val sending = WidgetFeedback.visible(context, prefs)?.state == WidgetFeedbackState.PENDING
    val running = report != null && report.mode != Mode.STANDBY
    val open = actionStartActivity(Intent(context, MainActivity::class.java))
    val description = context.getString(R.string.widget_header_description,
        if (report == null) context.getString(R.string.widget_temperature_unknown) else context.getString(R.string.widget_room_temperature, temperature),
        statusDescription)
    @Composable fun Value() = Text(temperature, maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (strip) 20.sp else 24.sp, fontWeight = FontWeight.Bold))
    @Composable fun Status(modifier: GlanceModifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (sending || running) {
            Dot(widgetColor(settings, at) { tokens ->
                if (sending) tokens.warn else report?.let { tokens.stepColor(it.mode, it.targetTemperature) } ?: tokens.accent })
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(status, GlanceModifier.defaultWeight(), maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp))
    }
    val tappable = modifier.clickable(open).semantics { contentDescription = description }
    if (stacked) Column(tappable, verticalAlignment = Alignment.CenterVertically) {
        Value()
        Status(GlanceModifier.fillMaxWidth())
    } else Row(tappable, verticalAlignment = Alignment.CenterVertically) {
        Value()
        Spacer(GlanceModifier.width(10.dp))
        Status(GlanceModifier.defaultWeight())
    }
}

/** Tonight's plan while a night runs: its profile, the step it is in and the wake-up. */
@Composable
private fun PlanCard(context: Context, active: ActiveNight, modifier: GlanceModifier, settings: Settings, at: java.time.ZonedDateTime) {
    val runtime = context.graph.repository.runtime.value
    val name = active.profile?.name ?: context.graph.repository.profile(active.profileId)?.name
        ?: context.getString(R.string.feature_deleted_profile)
    val phase = widgetPhaseText(context, active, compact = true)
    val surface = widgetColor(settings, at) { it.surface.compositeOver(it.ground) }
    Column(modifier.cornerRadius(14.dp).background(surface).padding(horizontal = 10.dp, vertical = 6.dp)
        .semantics { contentDescription = context.getString(R.string.widget_plan_description, name,
            widgetPhase(context, active), widgetWake(context, active, runtime)) }) {
        Text(context.getString(R.string.widget_running, name), maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Row(GlanceModifier.fillMaxWidth()) {
            Text(phase.title, GlanceModifier.defaultWeight(), maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp))
            Spacer(GlanceModifier.width(8.dp))
            Text(widgetWakeShort(context, active, runtime), maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp))
        }
    }
}

/** How Controls fills the space it has: columns of pills, how many rows and whether tonight's plan fits. */
internal data class ControlsLayout(val columns: Int, val rows: Int, val plan: Boolean, val stackedHeader: Boolean) {
    /** Off's place: the start of the last row when there is more than one row, else the end of the row. */
    fun arrange(presets: List<String>): List<List<String?>> {
        val chosen = presets.take(columns * rows - 1)
        val used = ((chosen.size + 1 + columns - 1) / columns).coerceIn(1, rows)
        val cells: List<String?> = if (used == 1) chosen + PRESET_OFF
            else chosen.take((used - 1) * columns) + PRESET_OFF + chosen.drop((used - 1) * columns)
        // A single row shares the whole width; with more rows the last keeps the grid's columns.
        if (used == 1) return listOf(cells)
        return cells.chunked(columns).map { it + List(columns - it.size) { null } }
    }

    companion object {
        private const val PILL = 56f
        private const val GAP = 6f
        private const val PADDING = 8f

        fun of(widthDp: Float, heightDp: Float, fontScale: Float, nightRunning: Boolean): ControlsLayout {
            val large = fontScale > 1.3f
            val width = widthDp - 2 * PADDING
            val height = heightDp - 2 * PADDING
            val narrowest = if (large) 128f else 64f
            val columns = floor((width + GAP) / (narrowest + GAP)).toInt().coerceIn(1, 4)
            val stacked = large || width < 200f
            val header = (if (stacked) 50f else 32f) * fontScale.coerceAtLeast(1f)
            // Below one row of pills under the header, the header and Off share a single row.
            val rows = floor((height - header - GAP + GAP) / (PILL + GAP)).toInt().coerceAtLeast(0)
            if (rows == 0) return ControlsLayout(1, 0, false, stacked)
            val card = 66f * fontScale.coerceAtLeast(1f) + GAP
            val plan = nightRunning && rows >= 2 && floor((height - header - card) / (PILL + GAP)).toInt() >= 1
            val left = if (plan) floor((height - header - card) / (PILL + GAP)).toInt() else rows
            return ControlsLayout(columns, left.coerceIn(1, if (large) 2 else 4), plan, stacked)
        }
    }
}

/** No pages: the layout grows to show more of the person's presets, and Off is always there. */
class QuickControlsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repository
        provideContent {
            val settings by repo.settings.collectAsState()
            val active by repo.activeNight.collectAsState()
            @Suppress("UNUSED_VARIABLE") val observed by repo.automation.collectAsState()
            @Suppress("UNUSED_VARIABLE") val profiles by repo.profiles.collectAsState()
            @Suppress("UNUSED_VARIABLE") val runtime by repo.runtime.collectAsState()
            val ownerStatus by context.graph.actions.status.collectAsState()
            val prefs = currentState<Preferences>()
            val size = LocalSize.current
            val at = context.graph.now()
            // Status triggers a fresh read of the owner's identity/target-scoped uncertainty.
            key(ownerStatus) {
                val report = widgetReport(context)
                val layout = ControlsLayout.of(size.width.value, size.height.value,
                    context.resources.configuration.fontScale, active != null)
                fun controlFor(key: String) = widgetPreset(context, key)?.let { control(context, it, WidgetActionCallback.QUICK, id, prefs, active, report) }
                GlanceTheme(colors = widgetColors(settings, at)) {
                    Column(GlanceModifier.fillMaxSize().background(widgetColor(settings, at) { it.ground }).cornerRadius(22.dp).padding(8.dp)) {
                        if (layout.rows == 0) Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Header(context, prefs, report, active, stacked = true, GlanceModifier.defaultWeight(), settings, at, strip = true)
                            Spacer(GlanceModifier.width(6.dp))
                            controlFor(PRESET_OFF)?.let { Pill(context, it, 56.dp, GlanceModifier.width(56.dp), settings, at) }
                        } else {
                            Header(context, prefs, report, active, layout.stackedHeader, GlanceModifier.fillMaxWidth(), settings, at)
                            Spacer(GlanceModifier.defaultWeight())
                            active?.takeIf { layout.plan }?.let {
                                PlanCard(context, it, GlanceModifier.fillMaxWidth(), settings, at)
                                Spacer(GlanceModifier.height(6.dp))
                            }
                            layout.arrange(controlKeys(context, prefs[KEY_PRESETS])).forEachIndexed { index, row ->
                                val pillWidth = ((size.width.value - 16f - 6f * (row.size - 1)) / row.size).dp
                                if (index > 0) Spacer(GlanceModifier.height(6.dp))
                                Row(GlanceModifier.fillMaxWidth()) {
                                    row.forEachIndexed { column, key ->
                                        if (column > 0) Spacer(GlanceModifier.width(6.dp))
                                        val control = key?.let(::controlFor)
                                        if (control == null) Spacer(GlanceModifier.defaultWeight().height(56.dp))
                                        else Pill(context, control, pillWidth, GlanceModifier.defaultWeight(), settings, at)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    companion object {
        /** The chosen presets in order, comma separated. Absent means [DEFAULT_CONTROLS]. */
        val KEY_PRESETS = stringPreferencesKey("widget_presets")
    }
}

class QuickControlsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickControlsWidget()
}

/** The preset tile chosen when it was placed, or the legacy single-action command for older placements. */
internal fun tileAction(context: Context, prefs: Preferences): WidgetAction {
    prefs[SingleActionWidget.KEY_PRESET]?.let { key -> widgetPreset(context, key)?.let { return it } }
    val command = prefs[SingleActionWidget.KEY_COMMAND]?.let { runCatching { Command.valueOf(it) }.getOrNull() } ?: Command.START_NIGHT
    return configuredWidgetAction(context, command,
        prefs[SingleActionWidget.KEY_LABEL] ?: context.getString(R.string.feature_start_tonight), prefs[SingleActionWidget.KEY_PROFILE])
}

/** A one-cell preset tile: the whole tile is the button. */
class SingleActionWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repository
        provideContent {
            val settings by repo.settings.collectAsState()
            val active by repo.activeNight.collectAsState()
            @Suppress("UNUSED_VARIABLE") val observed by repo.automation.collectAsState()
            @Suppress("UNUSED_VARIABLE") val profiles by repo.profiles.collectAsState()
            @Suppress("UNUSED_VARIABLE") val runtime by repo.runtime.collectAsState()
            val ownerStatus by context.graph.actions.status.collectAsState()
            val prefs = currentState<Preferences>()
            val size = LocalSize.current
            val at = context.graph.now()
            key(ownerStatus) {
                val control = control(context, tileAction(context, prefs), WidgetActionCallback.SINGLE, id, prefs, active, widgetReport(context))
                val large = context.resources.configuration.fontScale > 1.3f
                val level = if (control.running || control.sending) TintLevel.ON else TintLevel.TILE
                val tint = widgetColor(settings, at) { it.tint(control.stripe(it), level).compositeOver(it.surface.compositeOver(it.ground)) }
                val stripe = widgetColor(settings, at, control.stripe)
                val words = 21f * context.resources.configuration.fontScale
                val showIcon = !large && size.height.value - 16f >= 20f + 2 * words
                // A one-cell tile keeps the detail whole: the full line, else just its temperature or Boost's total time.
                val detail = listOfNotNull(control.detail, control.short,
                    if (control.action.command == Command.BOOST) Format.duration(settings.boostMinutes, Format.Words { res, args -> context.getString(res, *args) }) else null)
                    .firstOrNull { labelWidthDp(context, it, bold = false) <= size.width.value - 20f } ?: control.detail
                val showDetail = detail != null && size.height.value - 16f >= 2 * words || control.sending
                GlanceTheme(colors = widgetColors(settings, at)) {
                    Row(GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(widgetColor(settings, at) { it.ground })
                        .clickable(control.click).semantics { contentDescription = control.description }) {
                        Row(GlanceModifier.fillMaxSize().background(tint)) {
                            Box(GlanceModifier.width(4.dp).fillMaxHeight().background(stripe)) { }
                            Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (showIcon) Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Image(ImageProvider(control.icon), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(stripe))
                                    Spacer(GlanceModifier.defaultWeight())
                                    if (control.running || control.sending) Dot(if (control.sending) widgetColor(settings, at) { it.warn } else stripe)
                                }
                                Text(if (labelWidthDp(context, control.action.label) <= size.width.value - 20f) control.action.label
                                    else control.brief, maxLines = 1,
                                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                                if (showDetail) Text(if (control.sending) context.getString(R.string.widget_tile_sending) else detail.orEmpty(),
                                    maxLines = 1, style = TextStyle(fontSize = 16.sp, color = if (control.sending)
                                        widgetColor(settings, at) { it.readableText(it.warn, it.tint(control.stripe(it), level).compositeOver(it.surface.compositeOver(it.ground))) }
                                        else GlanceTheme.colors.onSurfaceVariant))
                            }
                        }
                    }
                }
            }
        }
    }
    companion object {
        val KEY_PRESET = stringPreferencesKey("preset")
        val KEY_COMMAND = stringPreferencesKey("command")
        val KEY_LABEL = stringPreferencesKey("label")
        val KEY_PROFILE = stringPreferencesKey("profile")
    }
}

class SingleActionReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SingleActionWidget()
}

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

/** What Controls shows until the person chooses: Turbo, Heat and Cool first, then the first two profiles, Dry and Boost. */
internal fun defaultControls(context: Context): List<String> =
    listOf("TURBO", "HEAT", "COOL") + context.graph.repository.profiles.value.take(2).map { PROFILE_PREFIX + it.id } + listOf("DRY", "BOOST")

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
    (stored?.split(',')?.filter { it.isNotBlank() } ?: defaultControls(context)).distinct()
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
    val running: Boolean, val sending: Boolean, val description: String, val brief: String = action.label,
    /** A profile button for the night that is running now: ringed, with a green dot. */
    val current: Boolean = false)

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
    val current = action.profileId != null && active?.profileId == action.profileId
    val detail = when {
        active != null && action.command == Command.START_NIGHT && widgetUnconfirmed(context, active) -> context.getString(R.string.widget_phase_unknown)
        current -> context.getString(R.string.widget_profile_running)
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
        current -> context.getString(R.string.widget_control_current, base)
        else -> base
    }
    // A narrow button names Start tonight by its verb and a profile by its first word; the spoken description keeps
    // the full name.
    val brief = when {
        action.command == Command.START_NIGHT && action.profileId == null -> context.getString(R.string.widget_start_short)
        action.profileId != null -> action.label.substringBefore(' ')
        else -> action.label
    }
    return Control(action, request, click, { stripeOf(it, action, request) }, iconOf(action, request), detail, short,
        running, sending, description, brief, current)
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

/** The green of a running night's dot, as on the Tonight screen. */
private val RunningGreen = Color(0xFF3DDC84)

/**
 * One preset button: the mode's stripe and tint, its name, and its detail when the tile is tall enough. A long name
 * wraps onto two lines in a tall tile, else it shortens. The running night's profile is ringed with a green dot.
 */
@Composable
private fun Tile(context: Context, control: Control, widthDp: Float, heightDp: Float, modifier: GlanceModifier,
    settings: Settings, at: java.time.ZonedDateTime) {
    val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    val level = if (control.running || control.sending || control.current) TintLevel.ON else TintLevel.TILE
    val tint = widgetColor(settings, at) { it.tint(control.stripe(it), level).compositeOver(it.surface.compositeOver(it.ground)) }
    val stripe = widgetColor(settings, at, control.stripe)
    val marked = control.running || control.sending || control.current
    val compact = widthDp < 80f
    val room = widthDp - 4f - (if (compact) 8f else 12f) - (if (control.current) 4f else 0f)
    val tall = heightDp >= ControlsLayout.DETAIL * scale
    val fits = { text: String -> labelWidthDp(context, text) <= room }
    val words = control.action.label.split(' ')
    val wraps = !fits(control.action.label) && tall && words.size > 1 && words.all(fits) &&
        heightDp >= (2 * 21f + 16f) * scale
    val label = if (fits(control.action.label) || wraps) control.action.label else control.brief
    val detail = if (!tall || wraps) null else listOfNotNull(control.detail, control.short)
        .firstOrNull { labelWidthDp(context, it, bold = false) <= room }
    // The dot gives way to the name: the tint, the ring and the spoken description still say it is running.
    val dotted = marked && labelWidthDp(context, if (wraps) words.maxBy { labelWidthDp(context, it) } else label) <= room - 14f
    val dot = when {
        !dotted -> null
        control.sending -> widgetColor(settings, at) { it.warn }
        control.current -> ColorProvider(RunningGreen)
        control.running -> stripe
        else -> null
    }
    val outer = modifier.cornerRadius(14.dp).clickable(control.click).semantics { contentDescription = control.description }
    @Composable fun Face(face: GlanceModifier) = Row(face.background(tint)) {
        Box(GlanceModifier.width(4.dp).fillMaxHeight().background(stripe)) { }
        Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(start = if (compact) 6.dp else 8.dp,
            end = if (compact) 2.dp else 4.dp, top = if (tall) 8.dp else 0.dp),
            verticalAlignment = if (tall) Alignment.Top else Alignment.CenterVertically) {
            Text(label, maxLines = if (wraps) 2 else 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            if (detail != null) Text(detail, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp))
        }
        if (dot != null) Column(GlanceModifier.fillMaxHeight().padding(top = if (tall) 12.dp else 0.dp, end = 8.dp),
            verticalAlignment = if (tall) Alignment.Top else Alignment.CenterVertically) { Dot(dot, 10.dp) }
    }
    // Glance draws no borders: the ring is the stripe colour showing round a face inset by 2dp.
    if (control.current) Box(outer.background(stripe).padding(2.dp)) { Face(GlanceModifier.fillMaxSize().cornerRadius(12.dp)) }
    else Face(outer)
}

/** The status line: the last tap's outcome, else what the BedJet last reported, else why nothing is shown. */
private fun statusLine(context: Context, prefs: Preferences, report: DeviceStatus?, active: ActiveNight?,
    withNight: Boolean = true): Pair<String, String> {
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
        !withNight -> null
        active != null && widgetUnconfirmed(context, active) -> context.getString(R.string.widget_phase_unknown)
        active != null -> active.profile?.name ?: context.graph.repository.profile(active.profileId)?.name
        else -> null
    }
    val shown = where?.let { context.getString(R.string.quick_tile_detail, reading, it) } ?: reading
    return shown to context.getString(R.string.widget_status_description, shown, widgetBedJetName(context, settings))
}

/** How long ago the chosen BedJet last reported in this process, or null with no report to date. */
internal fun widgetReadAge(context: Context, long: Boolean): String? {
    val at = context.graph.statusReadAt.value ?: return null
    val minutes = java.time.Duration.between(at, context.graph.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> context.getString(if (long) R.string.widget_read_now else R.string.widget_age_now)
        minutes < 60 -> context.getString(if (long) R.string.widget_read_minutes else R.string.widget_age_minutes, minutes.toInt())
        else -> context.getString(if (long) R.string.widget_read_hours else R.string.widget_age_hours, (minutes / 60).toInt())
    }
}

/** Tonight at a glance: the running night and its wake, else tonight's profile and when it starts by itself. */
private data class NightSummary(val name: String, val running: Boolean, val time: String)

private fun nightSummary(context: Context, active: ActiveNight?, runtime: io.github.willzy12h.nocturne.night.RuntimeState): NightSummary? {
    val graph = context.graph
    if (active != null) {
        val name = active.profile?.name ?: graph.repository.profile(active.profileId)?.name
            ?: context.getString(R.string.feature_deleted_profile)
        return NightSummary(name, true, widgetWakeShort(context, active, runtime))
    }
    val planned = runCatching { io.github.willzy12h.nocturne.platform.AutoStartSchedule.next(graph) }.getOrNull()
    val profile = planned?.profile ?: graph.repository.tonightsProfile() ?: return null
    val time = planned?.let { context.getString(R.string.widget_night_starts, it.startAt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))) }
        ?: context.getString(R.string.widget_night_tonight)
    return NightSummary(profile.name, false, time)
}

/** Room temperature, the BedJet's status and when it was read; tapping it opens Nocturne. */
@Composable
private fun Header(context: Context, prefs: Preferences, report: DeviceStatus?, active: ActiveNight?, layout: ControlsLayout,
    night: NightSummary?, modifier: GlanceModifier, settings: Settings, at: java.time.ZonedDateTime) {
    val words = Format.Words { res, args -> context.getString(res, *args) }
    val temperature = report?.let { Format.temp(it.ambientTemperature, settings.fahrenheit, words) }
        ?: context.getString(R.string.widget_temperature_unknown_short)
    val style = layout.header
    val (status, statusDescription) = statusLine(context, prefs, report, active, withNight = style == HeaderStyle.STACKED)
    val sending = WidgetFeedback.visible(context, prefs)?.state == WidgetFeedbackState.PENDING
    val running = report != null && report.mode != Mode.STANDBY
    val age = report?.let { widgetReadAge(context, long = style == HeaderStyle.WIDE && layout.columns >= 3) }
    val open = actionStartActivity(Intent(context, MainActivity::class.java))
    val description = context.getString(R.string.widget_header_description,
        if (report == null) context.getString(R.string.widget_temperature_unknown) else context.getString(R.string.widget_room_temperature, temperature),
        age?.let { context.getString(R.string.widget_read_description, statusDescription, it) } ?: statusDescription)
    val quiet = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp)
    @Composable fun Value() = Text(temperature, maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 28.sp, fontWeight = FontWeight.Bold))
    @Composable fun Status(modifier: GlanceModifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (sending || running) {
            Dot(widgetColor(settings, at) { tokens ->
                if (sending) tokens.warn else report?.let { tokens.stepColor(it.mode, it.targetTemperature) } ?: tokens.accent })
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(status, GlanceModifier.defaultWeight(), maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
    }
    val tappable = modifier.clickable(open).semantics { contentDescription = description }
    when (style) {
        HeaderStyle.STACKED -> Column(tappable, verticalAlignment = Alignment.CenterVertically) {
            Value()
            Status(GlanceModifier.fillMaxWidth())
        }
        HeaderStyle.WIDE -> Row(tappable, verticalAlignment = Alignment.CenterVertically) {
            Value()
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Status(GlanceModifier.fillMaxWidth())
                if (age != null) Text(age, maxLines = 1, style = quiet)
            }
        }
        HeaderStyle.STRIP -> Row(tappable, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Value()
                if (age != null) Text(age, maxLines = 1, style = quiet)
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Status(GlanceModifier.fillMaxWidth())
                if (night != null) Text(if (night.running) night.name else context.getString(R.string.widget_night_line, night.name, night.time),
                    maxLines = 1, style = quiet)
            }
        }
    }
}

/** Tonight between the header and the buttons, in the form the layout chose; tapping it opens Nocturne. */
@Composable
private fun NightCardView(context: Context, kind: NightCard, night: NightSummary, active: ActiveNight?, widthDp: Float,
    modifier: GlanceModifier, settings: Settings, at: java.time.ZonedDateTime) {
    val surface = widgetColor(settings, at) { it.surface.compositeOver(it.ground) }
    val dot = if (night.running) ColorProvider(RunningGreen) else widgetColor(settings, at) { it.accent }
    val strong = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val quiet = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp)
    val card = modifier.cornerRadius(14.dp).background(surface).padding(horizontal = 10.dp)
        .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
        .semantics { contentDescription = context.getString(R.string.widget_night_description, night.name, night.time) }
    @Composable fun Name(modifier: GlanceModifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(dot)
        Spacer(GlanceModifier.width(8.dp))
        Text(night.name, GlanceModifier.defaultWeight(), maxLines = 1, style = strong)
    }
    when (kind) {
        NightCard.LINE -> Row(card, verticalAlignment = Alignment.CenterVertically) {
            Dot(dot)
            Spacer(GlanceModifier.width(8.dp))
            Text(context.getString(R.string.widget_night_line, night.name, night.time), maxLines = 1, style = strong)
        }
        NightCard.TWO -> Column(card, verticalAlignment = Alignment.CenterVertically) {
            Name(GlanceModifier.fillMaxWidth())
            Text(night.time, maxLines = 1, style = quiet)
        }
        NightCard.TIMELINE -> Column(card.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Name(GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(8.dp))
                Text(night.time, maxLines = 1, style = quiet)
            }
            Spacer(GlanceModifier.height(4.dp))
            val steps = active?.takeIf { !widgetUnconfirmed(context, it) && !it.trackingManual }
                ?.let { it.activePlan(context.graph.repository.profile(it.profileId)) }?.steps.orEmpty().filter { it.minutes > 0 }
            if (steps.isEmpty()) Text(widgetPhaseText(context, active, compact = true).title, maxLines = 1, style = quiet)
            else PlanBar(context, steps, widthDp - 20f, settings, at)
        }
        NightCard.NONE -> Unit
    }
}

/** The plan as coloured spans by length, with a marker at now. */
@Composable
private fun PlanBar(context: Context, steps: List<io.github.willzy12h.nocturne.core.plan.PlannedStep>, widthDp: Float,
    settings: Settings, at: java.time.ZonedDateTime) {
    val gap = 2f
    val total = steps.sumOf { it.minutes }.coerceAtLeast(1)
    val usable = (widthDp - gap * (steps.size - 1)).coerceAtLeast(steps.size.toFloat())
    val start = steps.first().startsAt
    val elapsed = java.time.Duration.between(start, context.graph.now()).toMinutes().coerceIn(0, total.toLong())
    val marker = (elapsed.toFloat() / total * widthDp - 2f).coerceIn(0f, (widthDp - 4f).coerceAtLeast(0f))
    Box(GlanceModifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
        Row(GlanceModifier.height(10.dp).cornerRadius(5.dp)) {
            steps.forEachIndexed { index, step ->
                if (index > 0) Spacer(GlanceModifier.width(gap.dp))
                Box(GlanceModifier.width((step.minutes.toFloat() / total * usable).dp).fillMaxHeight()
                    .background(widgetColor(settings, at) { it.stepColor(step.step.mode, step.step.temperature) })) { }
            }
        }
        Row(GlanceModifier.fillMaxHeight()) {
            Spacer(GlanceModifier.width(marker.dp))
            Box(GlanceModifier.width(4.dp).fillMaxHeight().cornerRadius(2.dp).background(GlanceTheme.colors.onSurface)) { }
        }
    }
}

/** What sits between the header and the buttons. */
internal enum class NightCard { NONE, LINE, TIMELINE, TWO }

/** How the header sets out the temperature and the BedJet's status. */
internal enum class HeaderStyle {
    /** Narrow widgets: the temperature over one status line. */
    STACKED,
    /** The temperature beside the status and when it was read. */
    WIDE,
    /** The temperature with its read age under it, beside the status and tonight's night. */
    STRIP,
}

/**
 * How Controls fills the space it has, as agreed for each size: buttons only at two cells high; at 2 × 3 a night
 * card above four buttons (or six buttons, by choice); at 3 × 3 the night in the header; from 4 × 3 a night line,
 * with the plan graph while a night runs. Buttons are at least 56dp tall and grow to fill the rest.
 */
internal data class ControlsLayout(val columns: Int, val rows: Int, val header: HeaderStyle, val card: NightCard,
    val headerDp: Float, val cardDp: Float, val tileDp: Float, val padding: Float = PADDING) {
    /** Off's place: the last column of the last row used, with any gap before it. */
    fun arrange(presets: List<String>): List<List<String?>> {
        if (rows == 0) return emptyList()
        val chosen = presets.take(columns * rows - 1)
        val used = (chosen.size + columns) / columns
        return (chosen + List(used * columns - 1 - chosen.size) { null } + PRESET_OFF).chunked(columns)
    }

    companion object {
        const val PADDING = 12f
        const val NARROW_PADDING = 8f
        const val GAP = 6f
        const val TILE = 56f
        /** The least tile height that shows a detail line under the name. */
        const val DETAIL = 69f
        private const val HEADER = 56f

        /** The card's height, or 0 for none. */
        private fun cardHeight(card: NightCard, scale: Float) = scale * when (card) {
            NightCard.NONE -> 0f
            NightCard.LINE -> 37f
            NightCard.TIMELINE -> 56f
            NightCard.TWO -> 60f
        }

        fun of(widthDp: Float, heightDp: Float, fontScale: Float, nightRunning: Boolean, presetCount: Int,
            sixButtons: Boolean = false): ControlsLayout {
            val scale = fontScale.coerceAtLeast(1f)
            val large = fontScale > 1.3f
            val cellsWide = when { widthDp >= 280f -> 4; widthDp >= 200f -> 3; else -> 2 }
            val tall = heightDp >= 230f
            val header = when {
                cellsWide == 2 -> HeaderStyle.STACKED
                cellsWide == 3 && tall -> HeaderStyle.STRIP
                else -> HeaderStyle.WIDE
            }
            val wanted = when {
                !tall || cellsWide == 3 -> NightCard.NONE
                cellsWide == 2 -> if (sixButtons) NightCard.NONE else NightCard.TWO
                nightRunning -> NightCard.TIMELINE
                else -> NightCard.LINE
            }
            val columns = (if (cellsWide == 4) 3 else 2).let { if (large) (it - 1).coerceAtLeast(1) else it }
            val headerDp = HEADER * scale
            val tile = maxOf(TILE, 44f * fontScale)
            // Two cells wide, a narrower edge leaves each button room for its name.
            val padding = if (cellsWide == 2) NARROW_PADDING else PADDING
            fun space(card: NightCard) = heightDp - 2 * padding - headerDp - GAP -
                cardHeight(card, scale).let { if (it > 0f) it + GAP else 0f }
            fun fit(card: NightCard) = floor((space(card) + GAP) / (tile + GAP)).toInt().coerceAtLeast(0)
            // A card only shows with at least one row of buttons under it.
            val card = if (wanted != NightCard.NONE && fit(wanted) == 0) NightCard.NONE else wanted
            val rows = minOf(fit(card), (presetCount + columns) / columns)
            val tileDp = if (rows == 0) 0f else (space(card) - GAP * (rows - 1)) / rows
            return ControlsLayout(columns, rows, header, card, headerDp, cardHeight(card, scale), tileDp, padding)
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
            @Suppress("UNUSED_VARIABLE") val read by context.graph.statusReadAt.collectAsState()
            val runtime by repo.runtime.collectAsState()
            val ownerStatus by context.graph.actions.status.collectAsState()
            val prefs = currentState<Preferences>()
            val size = LocalSize.current
            val at = context.graph.now()
            // Status triggers a fresh read of the owner's identity/target-scoped uncertainty.
            key(ownerStatus) {
                val report = widgetReport(context)
                val keys = controlKeys(context, prefs[KEY_PRESETS])
                val layout = ControlsLayout.of(size.width.value, size.height.value, context.resources.configuration.fontScale,
                    active != null, keys.size, prefs[KEY_LAYOUT] == LAYOUT_SIX)
                val night = nightSummary(context, active, runtime)
                fun controlFor(key: String) = widgetPreset(context, key)?.let { control(context, it, WidgetActionCallback.QUICK, id, prefs, active, report) }
                GlanceTheme(colors = widgetColors(settings, at)) {
                    Column(GlanceModifier.fillMaxSize().background(widgetColor(settings, at) { it.ground }).cornerRadius(22.dp)
                        .padding(layout.padding.dp)) {
                        if (layout.rows == 0) Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Header(context, prefs, report, active, layout.copy(header = HeaderStyle.STACKED), night,
                                GlanceModifier.defaultWeight(), settings, at)
                            Spacer(GlanceModifier.width(ControlsLayout.GAP.dp))
                            controlFor(PRESET_OFF)?.let { Tile(context, it, ControlsLayout.TILE, ControlsLayout.TILE,
                                GlanceModifier.width(ControlsLayout.TILE.dp).height(ControlsLayout.TILE.dp), settings, at) }
                        } else {
                            Header(context, prefs, report, active, layout, night,
                                GlanceModifier.fillMaxWidth().height(layout.headerDp.dp), settings, at)
                            Spacer(GlanceModifier.height(ControlsLayout.GAP.dp))
                            if (layout.card != NightCard.NONE && night != null) {
                                NightCardView(context, layout.card, night, active, size.width.value - 2 * layout.padding,
                                    GlanceModifier.fillMaxWidth().height(layout.cardDp.dp), settings, at)
                                Spacer(GlanceModifier.height(ControlsLayout.GAP.dp))
                            }
                            val rows = layout.arrange(keys)
                            val tileWidth = (size.width.value - 2 * layout.padding - ControlsLayout.GAP * (layout.columns - 1)) / layout.columns
                            // Without a night to show, the card's space goes to the buttons.
                            val tileHeight = layout.tileDp + if (layout.card != NightCard.NONE && night == null)
                                (layout.cardDp + ControlsLayout.GAP) / rows.size else 0f
                            Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
                                rows.forEachIndexed { index, row ->
                                    if (index > 0) Spacer(GlanceModifier.height(ControlsLayout.GAP.dp))
                                    Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                                        row.forEachIndexed { column, key ->
                                            if (column > 0) Spacer(GlanceModifier.width(ControlsLayout.GAP.dp))
                                            val control = key?.let(::controlFor)
                                            if (control == null) Spacer(GlanceModifier.defaultWeight().fillMaxHeight())
                                            else Tile(context, control, tileWidth, tileHeight,
                                                GlanceModifier.defaultWeight().fillMaxHeight(), settings, at)
                                        }
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
        /** The chosen presets in order, comma separated. Absent means [defaultControls]. */
        val KEY_PRESETS = stringPreferencesKey("widget_presets")
        /** The 2 × 3 layout: [LAYOUT_NIGHT] (the default) or [LAYOUT_SIX]. */
        val KEY_LAYOUT = stringPreferencesKey("widget_layout")
        const val LAYOUT_NIGHT = "night"
        const val LAYOUT_SIX = "six"
    }
}

class QuickControlsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickControlsWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); WidgetRefresh.sync(context) }
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetRefresh.sync(context) }
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
    override fun onEnabled(context: Context) { super.onEnabled(context); WidgetRefresh.sync(context) }
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetRefresh.sync(context) }
}

package io.github.willzy12h.nocturne.widget

import android.content.Context
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.compactBoostLabel
import io.github.willzy12h.nocturne.ui.captureQuickAction

fun widgetRequest(context: Context, action: WidgetAction): CommandRequest {
    val request = context.graph.actions.capture(action.command, action.profileId, requireIdentity = true)
        .copy(origin = io.github.willzy12h.nocturne.night.CommandOrigin.WIDGET)
    // Preserve the original End preset. The shared dialog derives its separately capped Temporary copy.
    // Idle actions retain the same behaviour as the other quick entries, which do not show this choice.
    return request.copy(actionPreset = captureQuickAction(context.graph, request)?.end?.actionPreset)
}
fun configuredWidgetAction(context: Context, command: io.github.willzy12h.nocturne.night.NightActions.Command,
    label: String, profile: String?): WidgetAction {
    val saved = profile?.let { context.graph.repository.profile(it) }
    val shown = when {
        profile != null && saved == null -> context.getString(io.github.willzy12h.nocturne.R.string.feature_deleted_profile)
        saved != null -> context.getString(io.github.willzy12h.nocturne.R.string.feature_start_profile, saved.name)
        command == io.github.willzy12h.nocturne.night.NightActions.Command.BOOST ->
            compactBoostLabel(context, context.graph.repository.settings.value.boostMinutes)
        command == io.github.willzy12h.nocturne.night.NightActions.Command.TURBO ->
            context.getString(io.github.willzy12h.nocturne.R.string.widget_turbo_duration, 10)
        else -> label
    }
    return WidgetAction(shown, command, profile)
}

fun widgetWake(context: Context, night: io.github.willzy12h.nocturne.core.night.ActiveNight?,
    runtime: io.github.willzy12h.nocturne.night.RuntimeState = context.graph.repository.runtime.value): String {
    if (night == null) return context.getString(io.github.willzy12h.nocturne.R.string.feature_no_wake)
    when (widgetConfidence(context, night, runtime)) {
        io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN -> return context.getString(io.github.willzy12h.nocturne.R.string.widget_wake_unknown)
        io.github.willzy12h.nocturne.core.night.Confidence.FAILED -> return context.getString(io.github.willzy12h.nocturne.R.string.widget_wake_failed)
        io.github.willzy12h.nocturne.core.night.Confidence.PENDING -> return context.getString(io.github.willzy12h.nocturne.R.string.feature_pending_wake)
        io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED -> Unit
    }
    if (night.pending != null || context.graph.actions.wakeChangePending(night, runtime))
        return context.getString(io.github.willzy12h.nocturne.R.string.feature_pending_wake)
    // A kept manual run has no wake programme; it runs until its own end.
    if (night.trackingManual) return if (!java.time.ZonedDateTime.parse(night.endsAt).isAfter(context.graph.now()))
        context.getString(io.github.willzy12h.nocturne.R.string.feature_no_wake)
        else context.getString(io.github.willzy12h.nocturne.R.string.feature_manual_until,
            java.time.ZonedDateTime.parse(night.endsAt).format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")))
    if (!java.time.ZonedDateTime.parse(night.wakeAt).isAfter(context.graph.now()))
        return context.getString(io.github.willzy12h.nocturne.R.string.feature_no_wake)
    return context.getString(io.github.willzy12h.nocturne.R.string.feature_wake,
        java.time.ZonedDateTime.parse(night.wakeAt).format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")))
}
fun widgetMode(context: Context, observation: io.github.willzy12h.nocturne.platform.DeviceObservation?): String {
    if (observation == null || observation.device != context.graph.repository.settings.value.deviceId()) return context.getString(io.github.willzy12h.nocturne.R.string.feature_unknown)
    val age=java.time.Duration.between(java.time.ZonedDateTime.parse(observation.at),context.graph.now()).toMinutes().coerceAtLeast(0)
    // The observation keeps the enum's name; the caption shows the mode in words, or Unknown for a name this version lacks.
    val mode=runCatching { io.github.willzy12h.nocturne.core.protocol.Mode.valueOf(observation.mode) }.getOrNull()
        ?: return context.getString(io.github.willzy12h.nocturne.R.string.feature_unknown)
    return context.getString(io.github.willzy12h.nocturne.R.string.feature_mode_age,
        context.getString(io.github.willzy12h.nocturne.ui.Format.modeNameRes(mode)),age,java.time.ZonedDateTime.parse(observation.at).format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm XXX")))
}


internal data class WidgetPage(val key: String, val label: String, val actions: List<WidgetAction>, val opensApp: Boolean = false)

/** Chunk each group, including the primary group, so resizing never makes its later actions unreachable. */
internal fun widgetPages(context: Context, capacity: Int,
    profiles: List<io.github.willzy12h.nocturne.core.plan.Profile> = context.graph.repository.profiles.value): List<WidgetPage> {
    val fixed = widgetActions(context).filter { it.profileId == null }
    val groups = listOf(
        Triple("night", io.github.willzy12h.nocturne.R.string.widget_page_night, fixed.take(3)),
        Triple("quick", io.github.willzy12h.nocturne.R.string.widget_page_quick, fixed.filter { it.command in setOf(
            io.github.willzy12h.nocturne.night.NightActions.Command.COOL, io.github.willzy12h.nocturne.night.NightActions.Command.WARM) }),
        Triple("device", io.github.willzy12h.nocturne.R.string.widget_page_device, fixed.filter { it.command in setOf(
            io.github.willzy12h.nocturne.night.NightActions.Command.OFF, io.github.willzy12h.nocturne.night.NightActions.Command.STOP_TRACKING) }),
        Triple("profiles", io.github.willzy12h.nocturne.R.string.widget_page_profiles, profiles.map {
            WidgetAction(context.getString(io.github.willzy12h.nocturne.R.string.feature_start_profile, it.name),
                io.github.willzy12h.nocturne.night.NightActions.Command.START_NIGHT, it.id)
        }),
    )
    return groups.flatMap { (key, title, actions) ->
        val chunks = actions.chunked(capacity)
        chunks.mapIndexed { index, chunk -> WidgetPage("$key:${index * capacity}",
            if (chunks.size == 1) context.getString(title) else context.getString(
                io.github.willzy12h.nocturne.R.string.widget_page_position, context.getString(title), index + 1, chunks.size), chunk) }
    } + WidgetPage("app:0", context.getString(io.github.willzy12h.nocturne.R.string.widget_page_app), emptyList(), opensApp = true)
}

internal fun selectedWidgetPage(pages: List<WidgetPage>, stored: String?): Int {
    pages.indexOfFirst { it.key == stored }.takeIf { it >= 0 }?.let { return it }
    // Preserve the group and nearest earlier action when a resize changes the number of actions per page.
    val group = stored?.substringBefore(':') ?: return 0
    val offset = stored.substringAfter(':', "0").toIntOrNull() ?: return 0
    return pages.indexOfLast { it.key.substringBefore(':') == group && it.key.substringAfter(':').toInt() <= offset }
        .coerceAtLeast(0)
}

internal fun widgetHeadline(context: Context, observation: io.github.willzy12h.nocturne.platform.DeviceObservation?): String {
    val mode = observation?.takeIf { it.device == context.graph.repository.settings.value.deviceId() }
        ?.mode?.let { runCatching { io.github.willzy12h.nocturne.core.protocol.Mode.valueOf(it) }.getOrNull() }
        ?: return context.getString(io.github.willzy12h.nocturne.R.string.feature_unknown)
    return context.getString(io.github.willzy12h.nocturne.R.string.widget_last_mode,
        context.getString(io.github.willzy12h.nocturne.ui.Format.modeNameRes(mode)))
}

internal fun widgetReportTime(context: Context, observation: io.github.willzy12h.nocturne.platform.DeviceObservation?): String {
    val at = observation?.takeIf { it.device == context.graph.repository.settings.value.deviceId() }?.at
        ?.let { runCatching { java.time.ZonedDateTime.parse(it) }.getOrNull() }
        ?: return context.getString(io.github.willzy12h.nocturne.R.string.widget_no_report)
    return context.getString(io.github.willzy12h.nocturne.R.string.widget_report_time,
        at.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm XXX")))
}

internal fun widgetTemperature(context: Context, compact: Boolean = false): String {
    val graph = context.graph
    val settings = graph.repository.settings.value
    val report = graph.link.latestSourcedStatus()?.takeIf { it.isFrom(settings.deviceId()) }
        ?: return context.getString(if (compact) io.github.willzy12h.nocturne.R.string.widget_temperature_unknown_short
            else io.github.willzy12h.nocturne.R.string.widget_temperature_unknown)
    return context.getString(if (compact) io.github.willzy12h.nocturne.R.string.widget_room_temperature_short
        else io.github.willzy12h.nocturne.R.string.widget_room_temperature,
        io.github.willzy12h.nocturne.ui.Format.temp(report.status.ambientTemperature, settings.fahrenheit,
            io.github.willzy12h.nocturne.ui.Format.Words { id, args -> context.getString(id, *args) }))
}

/** A retained receipt can downgrade only its exact night/target; a new pending command remains pending. */
private fun widgetConfidence(context: Context, night: io.github.willzy12h.nocturne.core.night.ActiveNight,
    runtime: io.github.willzy12h.nocturne.night.RuntimeState): io.github.willzy12h.nocturne.core.night.Confidence =
    if (night.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED &&
        context.graph.actions.hasProgrammeUncertainty(night)) io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN
    else runtime.unresolvedSlotWrite?.takeIf { it.displaced == night.identity }?.confidence ?: night.confidence

internal data class WidgetPhaseText(val title: String, val span: String? = null)

internal fun widgetPhase(context: Context, night: io.github.willzy12h.nocturne.core.night.ActiveNight?): String =
    widgetPhaseText(context, night).let { if (it.span == null) it.title else
        context.getString(io.github.willzy12h.nocturne.R.string.widget_phase_with_span, it.title, it.span) }

internal fun widgetPhaseText(context: Context, night: io.github.willzy12h.nocturne.core.night.ActiveNight?, compact: Boolean = false): WidgetPhaseText {
    if (night == null) return WidgetPhaseText(context.getString(io.github.willzy12h.nocturne.R.string.widget_no_tracked_night))
    if (widgetConfidence(context, night, context.graph.repository.runtime.value) != io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED || night.pending != null)
        return WidgetPhaseText(context.getString(io.github.willzy12h.nocturne.R.string.widget_phase_unknown))
    if (night.trackingManual) return WidgetPhaseText(context.getString(io.github.willzy12h.nocturne.R.string.widget_phase_manual))
    val now = context.graph.now()
    val current = night.activePlan(context.graph.repository.profile(night.profileId))?.steps?.firstOrNull {
        !now.isBefore(it.startsAt) && now.isBefore(it.startsAt.plusMinutes(it.minutes.toLong()))
    } ?: return WidgetPhaseText(context.getString(io.github.willzy12h.nocturne.R.string.widget_phase_unknown))
    val title = when (current.block) {
        io.github.willzy12h.nocturne.core.plan.Block.START -> io.github.willzy12h.nocturne.R.string.block_start
        io.github.willzy12h.nocturne.core.plan.Block.MIDDLE -> io.github.willzy12h.nocturne.R.string.block_middle
        io.github.willzy12h.nocturne.core.plan.Block.WAKE -> io.github.willzy12h.nocturne.R.string.block_wake
        io.github.willzy12h.nocturne.core.plan.Block.AFTER_WAKE -> io.github.willzy12h.nocturne.R.string.block_after_wake
        io.github.willzy12h.nocturne.core.plan.Block.TEMPORARY -> io.github.willzy12h.nocturne.R.string.temporary_block
    }
    val end = current.startsAt.plusMinutes(current.minutes.toLong())
    val span = context.getString(io.github.willzy12h.nocturne.R.string.widget_phase_span,
        current.startsAt.format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")),
        end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
    return WidgetPhaseText(context.getString(if (compact) io.github.willzy12h.nocturne.R.string.widget_phase_short
        else io.github.willzy12h.nocturne.R.string.widget_phase, context.getString(title)), span)
}


internal fun widgetActionDescription(context: Context, action: WidgetAction, request: CommandRequest? = null): String = when (action.command) {
    io.github.willzy12h.nocturne.night.NightActions.Command.BOOST ->
        io.github.willzy12h.nocturne.night.boostDescription(context, request?.actionPreset?.minutes ?: context.graph.repository.settings.value.boostMinutes)
    io.github.willzy12h.nocturne.night.NightActions.Command.TURBO -> io.github.willzy12h.nocturne.night.boostDescription(context, request?.actionPreset?.minutes ?: 10)
    else -> action.label
}

/** Short visible labels keep large-font rows readable; the full wording remains in their description. */
internal fun widgetWakeShort(context: Context, night: io.github.willzy12h.nocturne.core.night.ActiveNight?,
    runtime: io.github.willzy12h.nocturne.night.RuntimeState): String {
    val full = widgetWake(context, night, runtime)
    val simple = mapOf(
        io.github.willzy12h.nocturne.R.string.feature_no_wake to io.github.willzy12h.nocturne.R.string.widget_no_wake_short,
        io.github.willzy12h.nocturne.R.string.feature_pending_wake to io.github.willzy12h.nocturne.R.string.widget_wake_pending_short,
        io.github.willzy12h.nocturne.R.string.widget_wake_unknown to io.github.willzy12h.nocturne.R.string.widget_wake_unknown_short,
        io.github.willzy12h.nocturne.R.string.widget_wake_failed to io.github.willzy12h.nocturne.R.string.widget_wake_failed_short)
    simple.entries.firstOrNull { context.getString(it.key) == full }?.let { return context.getString(it.value) }
    night ?: return full
    val at = java.time.ZonedDateTime.parse(if (night.trackingManual) night.endsAt else night.wakeAt)
    return context.getString(if (night.trackingManual) io.github.willzy12h.nocturne.R.string.widget_manual_until_short
        else io.github.willzy12h.nocturne.R.string.widget_wake_short,
        at.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")))
}

package io.github.willzy12h.nocturne.widget

import android.content.Context
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandRequest

fun widgetRequest(context: Context, action: WidgetAction): CommandRequest =
    context.graph.actions.capture(action.command, action.profileId, requireIdentity = true)
fun configuredWidgetAction(context: Context, command: io.github.willzy12h.nocturne.night.NightActions.Command,
    label: String, profile: String?): WidgetAction = WidgetAction(if (profile != null && context.graph.repository.profile(profile) == null)
        context.getString(io.github.willzy12h.nocturne.R.string.feature_deleted_profile) else label, command, profile)

fun widgetWake(context: Context, night: io.github.willzy12h.nocturne.core.night.ActiveNight?): String {
    if (night == null) return context.getString(io.github.willzy12h.nocturne.R.string.feature_no_wake)
    if (night.confidence != io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED || night.pending != null ||
        context.graph.repository.runtime.value.retime != null || context.graph.repository.runtime.value.alarmInbox != null)
        return context.getString(io.github.willzy12h.nocturne.R.string.feature_pending_wake)
    if (!java.time.ZonedDateTime.parse(night.wakeAt).isAfter(context.graph.now()))
        return context.getString(io.github.willzy12h.nocturne.R.string.feature_no_wake)
    return context.getString(io.github.willzy12h.nocturne.R.string.feature_wake,
        java.time.ZonedDateTime.parse(night.wakeAt).format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")))
}
fun widgetMode(context: Context, observation: io.github.willzy12h.nocturne.platform.DeviceObservation?): String {
    if (observation == null || observation.device != context.graph.repository.settings.value.deviceId()) return context.getString(io.github.willzy12h.nocturne.R.string.feature_unknown)
    val age=java.time.Duration.between(java.time.ZonedDateTime.parse(observation.at),context.graph.now()).toMinutes().coerceAtLeast(0)
    return context.getString(io.github.willzy12h.nocturne.R.string.feature_mode_age,observation.mode,age,java.time.ZonedDateTime.parse(observation.at).format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm XXX")))
}

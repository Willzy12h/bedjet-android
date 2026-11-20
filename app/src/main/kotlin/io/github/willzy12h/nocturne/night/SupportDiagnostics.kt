package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.BuildConfig
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.data.CrashLog
import io.github.willzy12h.nocturne.platform.PhoneDiagnostics
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.ZonedDateTime

/** Deliberate export fields. New backup or journal fields never flow into a support file automatically. */
internal fun supportDiagnostics(graph: Graph, includeAlarmTimes: Boolean): String {
    val repo = graph.repository
    val settings = repo.settings.value
    val night = repo.activeNight.value
    val now = graph.now()
    val units = (listOfNotNull(settings.deviceId(), night?.deviceId) + settings.rememberedBedJets).distinct()
    fun unit(id: String?) = units.indexOf(id).takeIf { it >= 0 }?.let { "Unit ${it + 1}" }
    fun ageMinutes(at: String) = runCatching { Duration.between(ZonedDateTime.parse(at), now).toMinutes() }.getOrNull()
    val alarm = AlarmSource.diagnostics(graph.context, settings, night?.let { repo.profile(it.profileId) } ?: repo.tonightsProfile(),
        night, repo.runtime.value, graph.actions.status.value, now)
    return buildJsonObject {
        put("version", BuildConfig.VERSION_NAME)
        put("deviceModel", android.os.Build.MODEL)
        put("phone", PhoneDiagnostics.snapshot(graph.context))
        put("linkState", graph.link.state.value.name)
        put("unreadable", JsonArray(repo.unreadable.value.map { JsonPrimitive(it) }))
        put("originalsBlocked", repo.originalsBlocked.value)
        put("hasRuntimeReason", !repo.runtime.value.reason.isNullOrEmpty())
        put("nightConfidence", night?.confidence?.name)
        putJsonObject("alarm") {
            for (key in listOf("reason", "wakeState", "wakeOrigin", "creatorPackage", "sourceAccepted", "matchesSavedWake", "unnamedAlarms"))
                alarm[key]?.let { put(key, it) }
            for (key in listOf("alarmAt", "wakeAt", "requestedWakeAt")) {
                val at = (alarm[key] as? JsonPrimitive)?.contentOrNull ?: continue
                ageMinutes(at)?.let { put("${key}MinutesFromReport", -it) }
                if (includeAlarmTimes) put(key, at)
            }
        }
        putJsonObject("settings") {
            put("target", unit(settings.deviceId()))
            put("demo", settings.demoMode)
            put("workingSlot", settings.workingSlot)
            put("alarmSelection", settings.alarmApps.name)
        }
        put("activeNight", night?.let {
            buildJsonObject {
                put("unit", unit(it.deviceId))
                put("confidence", it.confidence.name)
                put("manual", it.trackingManual)
                put("pending", it.pending?.kind?.name)
                put("test", it.isTestRun)
            }
        } ?: JsonNull)
        putJsonArray("events") {
            repo.history.value.flatMap { it.events }.sortedByDescending { it.at }.take(50).forEach {
                add(buildJsonObject { put("kind", it.kind.name); put("minutesAgo", ageMinutes(it.at)) })
            }
        }
        putJsonArray("notices") {
            repo.notices.value.take(50).forEach {
                add(buildJsonObject { put("kind", it.kind.name); put("minutesAgo", ageMinutes(it.at)) })
            }
        }
        put("lastCrash", CrashLog.read(graph.context.filesDir)?.let { trace ->
            JsonArray(trace.lineSequence().map { it.trim() }.mapNotNull { frame.matchEntire(it)?.groupValues?.get(1) }
                .take(12).map(::JsonPrimitive).toList())
        } ?: JsonNull)
    }.toString()
}

// Share code locations only; exception messages, paths and thread names can contain free text.
private val frame = Regex("""at (io\.github\.willzy12h\.nocturne\.[A-Za-z0-9_$.<>]+)\([^)]*\)""")

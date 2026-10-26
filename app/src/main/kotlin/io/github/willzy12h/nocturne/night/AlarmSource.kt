package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.content.Context
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.Settings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Android's next exposed alarm-clock event (from any setAlarmClock producer), never a list of alarms.
 * A rejected event cannot be skipped here to inspect a later clock alarm. The nullable show-intent creator
 * identifies that PendingIntent's original app; it is not permanent provenance for a saved night.
 */
object AlarmSource {
    data class NextAlarm(val time: ZonedDateTime, val creator: String?, val counts: Boolean)

    fun describe(context: Context, settings: Settings): NextAlarm? {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val creator = info.showIntent?.creatorPackage
        val counts = io.github.willzy12h.nocturne.core.plan.AlarmPolicy(settings).acceptsSource(creator)
        return NextAlarm(Instant.ofEpochMilli(info.triggerTime).atZone(ZoneId.systemDefault()), creator, counts)
    }

    fun next(context: Context, settings: Settings): ZonedDateTime? = describe(context, settings)?.takeIf { it.counts }?.time

    /** Read the current Android event once and export the same decision/text shown beneath Tonight's wake. */
    fun diagnostics(context: Context, settings: Settings, profile: Profile?, night: ActiveNight?, runtime: RuntimeState,
        status: NightStatus, now: ZonedDateTime): JsonObject {
        val explanation = AlarmExplanation.resolve(settings, profile, now, describe(context, settings), night, runtime, status)
        return buildJsonObject {
            put("reason", JsonPrimitive(explanation.reason.name))
            put("wakeState", JsonPrimitive(explanation.state.name))
            put("isTestRun", JsonPrimitive(night?.isTestRun == true))
            put("explanation", JsonPrimitive(explanation.description(context)))
            put("wakeAt", JsonPrimitive(explanation.wakeAt?.toString()))
            put("wakeOrigin", JsonPrimitive(explanation.wakeOrigin?.name))
            put("alarmAt", JsonPrimitive(explanation.alarm?.time?.toString()))
            put("creatorPackage", JsonPrimitive(explanation.alarm?.creator))
            put("sourceAccepted", explanation.sourceAccepted?.let(::JsonPrimitive) ?: JsonNull)
            put("nightStart", JsonPrimitive(explanation.nightStart.toString()))
            put("windowStart", JsonPrimitive(explanation.nightStart.plus(WakeResolver.MIN_NIGHT).toString()))
            put("windowEnd", JsonPrimitive(explanation.nightStart.plus(WakeResolver.MAX_NIGHT).toString()))
            put("requestedWakeAt", JsonPrimitive(explanation.requestedWakeAt?.toString()))
            put("matchesSavedWake", JsonPrimitive(explanation.matchesSavedWake))
            put("settingsTarget", JsonPrimitive(explanation.target.name))
            put("onlyNextAlarmExposed", JsonPrimitive(true))
        }
    }
}

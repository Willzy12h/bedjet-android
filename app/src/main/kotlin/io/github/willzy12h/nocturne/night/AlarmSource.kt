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

/** The next exposed alarm-clock event only. Display identification never widens acceptance. */
object AlarmSource {
    data class NextAlarm(val time: ZonedDateTime, val creator: String?, val counts: Boolean,
        val identifiedCreator: String? = creator, val creatorUid: Int? = null)

    fun describe(context: Context, settings: Settings): NextAlarm? {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val show = info.showIntent
        val creator = show?.creatorPackage
        val uid = show?.creatorUid
        val counts = io.github.willzy12h.nocturne.core.plan.AlarmPolicy(settings).acceptsSource(creator)
        return NextAlarm(Instant.ofEpochMilli(info.triggerTime).atZone(ZoneId.systemDefault()), creator, counts,
            identifyAlarmCreator(context, creator, uid), uid)
    }

    fun next(context: Context, settings: Settings): ZonedDateTime? = describe(context, settings)?.takeIf { it.counts }?.time

    /** Export raw provenance and the separate display inference without querying or launching the show intent. */
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
            put("creatorUid", explanation.alarm?.creatorUid?.let(::JsonPrimitive) ?: JsonNull)
            put("identifiedCreatorPackage", JsonPrimitive(explanation.alarm?.identifiedCreator))
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

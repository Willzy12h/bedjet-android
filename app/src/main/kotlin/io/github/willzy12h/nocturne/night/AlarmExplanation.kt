package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** ELIGIBLE describes the current candidate, never proof that a running BedJet has applied it. */
enum class AlarmReason { ELIGIBLE, NO_ALARM, UNKNOWN_SOURCE, SOURCE_NOT_ALLOWED, OUTSIDE_WINDOW, PROFILE_ALARMS_OFF, OVERRIDE, NO_PROFILE, TEST_RUN }
enum class WakeExplanationState { PREVIEW, SAVED, PENDING, FAILED, UNKNOWN, MANUAL }
enum class AlarmSettingsTarget { ALARM_SOURCES, PROFILE_WAKE, TONIGHT_WAKE }

/** One assessment supplies Tonight and diagnostics. Saved wake provenance never comes from a current alarm. */
data class AlarmExplanation(
    val reason: AlarmReason,
    val state: WakeExplanationState,
    val wakeAt: ZonedDateTime?,
    val wakeOrigin: WakeOrigin?,
    val alarm: AlarmSource.NextAlarm?,
    val nightStart: ZonedDateTime,
    val requestedWakeAt: ZonedDateTime?,
    val sourceAccepted: Boolean?,
    val matchesSavedWake: Boolean,
    val target: AlarmSettingsTarget,
) {
    companion object {
        fun resolve(settings: Settings, profile: Profile?, now: ZonedDateTime, alarm: AlarmSource.NextAlarm?,
            night: ActiveNight? = null, runtime: RuntimeState = RuntimeState(), status: NightStatus? = null,
            previewWake: WakeChoice? = null): AlarmExplanation {
            val effectiveProfile = night?.profile ?: profile
            val start = night?.startedAt?.parseTime() ?: now
            val policy = AlarmPolicy(settings)
            val preview = if (night == null && effectiveProfile != null) previewWake ?: NightPreview.forStartNow(
                effectiveProfile, now, alarm?.time, settings.resolveWakeOverride(now), settings, alarm?.creator).wake else null
            val wake = night?.wakeAt?.parseTime() ?: preview?.time
            val origin = night?.wakeOrigin ?: preview?.origin
            val sourceAccepted = alarm?.let { policy.acceptsSource(it.creator) }
            val reason = when {
                night?.isTestRun == true -> AlarmReason.TEST_RUN
                origin == WakeOrigin.OVERRIDE -> AlarmReason.OVERRIDE
                effectiveProfile == null -> AlarmReason.NO_PROFILE
                !effectiveProfile.wakeSource.useAlarm -> AlarmReason.PROFILE_ALARMS_OFF
                alarm == null -> AlarmReason.NO_ALARM
                sourceAccepted == false && alarm.creator == null -> AlarmReason.UNKNOWN_SOURCE
                sourceAccepted == false -> AlarmReason.SOURCE_NOT_ALLOWED
                !policy.accept(alarm.time, alarm.creator, start) -> AlarmReason.OUTSIDE_WINDOW
                else -> AlarmReason.ELIGIBLE
            }
            val targetDevice = settings.deviceId()
            val pendingRetime = runtime.retime?.takeIf { night != null && night.deviceId == targetDevice &&
                it.expected == night.identity && it.deviceId == targetDevice }
            val observationPending = night != null && night.deviceId == targetDevice && runtime.alarmInbox?.let {
                it.nightId == night.recordId && it.deviceId == targetDevice
            } == true
            val confidence = when {
                night?.confidence != Confidence.CONFIRMED -> night?.confidence
                status?.confidence != null && status.confidence != Confidence.CONFIRMED -> status.confidence
                pendingRetime != null || observationPending -> Confidence.PENDING
                else -> Confidence.CONFIRMED
            }
            val state = when {
                night == null -> WakeExplanationState.PREVIEW
                // Manual tracking describes a confirmed run; it must never conceal uncertainty.
                confidence == Confidence.PENDING -> WakeExplanationState.PENDING
                confidence == Confidence.FAILED -> WakeExplanationState.FAILED
                confidence != Confidence.CONFIRMED -> WakeExplanationState.UNKNOWN
                night.trackingManual -> WakeExplanationState.MANUAL
                else -> WakeExplanationState.SAVED
            }
            val canChooseWake = state == WakeExplanationState.PREVIEW || state == WakeExplanationState.SAVED
            val target = when {
                reason in setOf(AlarmReason.OVERRIDE, AlarmReason.OUTSIDE_WINDOW) && canChooseWake -> AlarmSettingsTarget.TONIGHT_WAKE
                reason in setOf(AlarmReason.PROFILE_ALARMS_OFF, AlarmReason.NO_ALARM) -> AlarmSettingsTarget.PROFILE_WAKE
                else -> AlarmSettingsTarget.ALARM_SOURCES
            }
            return AlarmExplanation(reason, state, wake, origin, alarm, start,
                (night?.pending?.requestedWake ?: pendingRetime?.requestedWakeAt ?: pendingRetime?.alarmAt)?.parseTime(), sourceAccepted,
                state == WakeExplanationState.SAVED && origin == WakeOrigin.ALARM && alarm != null && wake != null &&
                    wake?.toInstant() == alarm?.time?.toInstant(), target)
        }
    }
}

private fun String.parseTime(): ZonedDateTime? = runCatching { ZonedDateTime.parse(this) }.getOrNull()
private fun ZonedDateTime?.clock(): String = this?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "--:--"

/** App-facing words stay in resources; packages other than the two built-in clock sources remain identifiable. */
fun alarmSourceName(context: Context, creator: String?): String = when (creator) {
    "com.sec.android.app.clockpackage" -> context.getString(R.string.alarm_source_samsung_clock)
    "com.google.android.deskclock" -> context.getString(R.string.alarm_source_google_clock)
    null -> context.getString(R.string.alarm_source_unknown_app)
    else -> creator
}

fun AlarmExplanation.description(context: Context): String {
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    val wake = wakeAt.clock()
    val next = alarm?.time.clock()
    val source = alarmSourceName(context, alarm?.creator)
    fun ignored(): String = text(R.string.alarm_source_ignored, next, text(when (reason) {
        AlarmReason.UNKNOWN_SOURCE -> R.string.alarm_source_reason_unknown
        AlarmReason.SOURCE_NOT_ALLOWED -> R.string.alarm_source_reason_not_allowed
        AlarmReason.OUTSIDE_WINDOW -> if (alarm!!.time.isBefore(nightStart.plus(WakeResolver.MIN_NIGHT)))
            R.string.alarm_source_reason_too_soon else R.string.alarm_source_reason_too_late
        AlarmReason.PROFILE_ALARMS_OFF -> R.string.alarm_source_reason_profile_off
        AlarmReason.OVERRIDE -> R.string.alarm_source_reason_override
        else -> error("Only a rejected candidate has an ignored-alarm description")
    }))
    if (state == WakeExplanationState.PREVIEW) return when (reason) {
        AlarmReason.ELIGIBLE -> text(R.string.alarm_source_used, next, source)
        AlarmReason.NO_ALARM -> text(R.string.alarm_source_no_alarm, wake)
        AlarmReason.PROFILE_ALARMS_OFF -> if (alarm == null) text(R.string.alarm_source_profile_only, wake) else ignored()
        AlarmReason.OVERRIDE -> text(R.string.alarm_source_override, wake)
        AlarmReason.NO_PROFILE -> text(R.string.alarm_source_no_profile)
        else -> ignored()
    }
    val saved = text(when (state) {
        WakeExplanationState.PENDING -> R.string.alarm_source_saved_pending
        WakeExplanationState.FAILED -> R.string.alarm_source_saved_failed
        WakeExplanationState.UNKNOWN -> R.string.alarm_source_saved_unknown
        WakeExplanationState.MANUAL -> R.string.alarm_source_manual
        else -> when (wakeOrigin) {
            WakeOrigin.ALARM -> R.string.alarm_source_saved_alarm
            WakeOrigin.OVERRIDE -> R.string.alarm_source_saved_override
            else -> R.string.alarm_source_saved_profile
        }
    }, wake)
    if (reason == AlarmReason.TEST_RUN) return if (state == WakeExplanationState.SAVED)
        text(R.string.short_test_saved, wake) else text(R.string.short_test_state, saved)
    // An override needs no current alarm; keep the saved state's confidence wording.
    if (reason == AlarmReason.OVERRIDE && alarm == null) return saved
    val current = when {
        alarm == null -> text(R.string.alarm_source_none)
        // Manual/uncertain states can describe a current candidate, but cannot declare it applied or unapplied.
        reason == AlarmReason.ELIGIBLE -> text(when {
            matchesSavedWake -> R.string.alarm_source_current_matches
            state == WakeExplanationState.SAVED -> R.string.alarm_source_current_not_applied
            else -> R.string.alarm_source_current
        }, next, source)
        reason == AlarmReason.NO_PROFILE -> text(R.string.alarm_source_current, next, source)
        else -> ignored()
    }
    return text(R.string.alarm_source_saved_and_current, saved, current)
}

/** An explicit check always reports Android's current answer, even when the selected wake did not change. */
fun AlarmExplanation.recheckDescription(context: Context): String {
    var result = description(context)
    if (state == WakeExplanationState.PREVIEW && wakeOrigin == WakeOrigin.PROFILE_DEFAULT && alarm != null)
        result = context.getString(R.string.alarm_recheck_fallback, result, wakeAt.clock())
    val found = alarm ?: return context.getString(R.string.alarm_recheck_none, result)
    return context.getString(R.string.alarm_recheck_found, found.time.clock(), alarmSourceName(context, found.creator),
        result)
}

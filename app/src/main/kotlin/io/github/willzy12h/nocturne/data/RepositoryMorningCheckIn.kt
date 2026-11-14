package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.core.store.NightRecord
import kotlinx.serialization.json.*
import io.github.willzy12h.nocturne.core.plan.ProfileLimits

// Part of [Repository], split out of Repository.kt: the morning check-in and the Middle edit it may suggest (P5).

/** A proposed edit to the reusable profile; the expected values make an old preview safe to refuse. */
data class MiddleSuggestion(
    val nightId: String,
    val profileId: String,
    val comfort: MorningComfort,
    val expectedMiddle: Middle,
    val expectedLimits: ProfileLimits,
    val proposedMiddle: Middle,
) {
    /** A fan change (Cool or Dry Middle) rather than a temperature change. */
    val changesFan: Boolean get() = proposedMiddle.fanSpeeds != expectedMiddle.fanSpeeds
}

/** Middles whose comfort is adjusted by fan speed, not temperature (P5). */
private val FAN_COMFORT_MODES = setOf(io.github.willzy12h.nocturne.core.protocol.Mode.COOL, io.github.willzy12h.nocturne.core.protocol.Mode.DRY)

data class MorningCheckIn(
    val night: NightRecord,
    val endedAt: String,
    val comfort: MorningComfort,
    val suggestion: MiddleSuggestion?,
)

internal fun Repository.makeMorningCheckIn(night: NightRecord, endedAt: String, comfort: MorningComfort): MorningCheckIn {
    val suggestion = if (night.events.any { it.kind == EventKind.MORNING_SUGGESTION_APPLIED }) null else
        _profiles.value.firstOrNull { it.id == night.profileId }?.let { profile ->
            proposedMiddle(profile, comfort)?.let { MiddleSuggestion(night.id, profile.id, comfort, profile.middle, profile.limits, it) }
        }
    return MorningCheckIn(night, endedAt, comfort, suggestion)
}

internal fun Repository.proposedMiddle(profile: Profile, comfort: MorningComfort): Middle? {
    if (comfort == MorningComfort.JUST_RIGHT) return null
    // Cooling is felt as fan speed: a Cool or Dry Middle moves its fan by 10 per cent (more when too hot).
    if (profile.middle.mode in FAN_COMFORT_MODES) return proposedFan(profile, comfort)
    if (!profile.middle.mode.usesTemperature) return null
    val delta = if (comfort == MorningComfort.TOO_HOT) -2 else 2
    val middle = profile.middle.copy(tempStart = profile.middle.tempStart + delta, tempEnd = profile.middle.tempEnd + delta)
    val limits = profile.limits
    val modeRange = profile.middle.mode.temperatureRange
    fun validTemperature(value: Int) = value in limits.minTemp..limits.maxTemp && value in modeRange
    if (limits.minFan !in 5..100 || limits.maxFan !in 5..100 || limits.minFan > limits.maxFan ||
        profile.middle.fanSpeeds.any { it !in limits.minFan..limits.maxFan } ||
        middle.rampSteps !in 1..8 || limits.minTemp !in 38..86 || limits.maxTemp !in 38..86 ||
        limits.minTemp > limits.maxTemp || !validTemperature(profile.middle.tempStart) ||
        !validTemperature(profile.middle.tempEnd) || !validTemperature(middle.tempStart) ||
        !validTemperature(middle.tempEnd)) return null
    return middle
}

private fun Repository.proposedFan(profile: Profile, comfort: MorningComfort): Middle? {
    val delta = if (comfort == MorningComfort.TOO_HOT) 10 else -10
    val middle = profile.middle.shiftFan(delta)
    val limits = profile.limits
    fun valid(fan: Int) = fan in 5..100 && fan % 5 == 0 && fan in limits.minFan..limits.maxFan
    if (limits.minFan > limits.maxFan || middle.rampSteps !in 1..8 || !profile.middle.fanSpeeds.all(::valid) ||
        !middle.fanSpeeds.all(::valid)) return null
    return middle
}

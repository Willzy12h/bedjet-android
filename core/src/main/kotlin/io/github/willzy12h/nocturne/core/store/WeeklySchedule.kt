package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Profile
import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/** A missing weekday uses the fallback. A missing fallback leaves that day without a planned profile. */
@Serializable
data class WeeklySchedule(
    val profileByDay: Map<String, String> = emptyMap(),
    val fallbackProfileId: String? = null,
) {
    fun profileIdFor(day: DayOfWeek): String? = profileByDay[day.name]

    fun withProfile(day: DayOfWeek, profileId: String?): WeeklySchedule = copy(profileByDay =
        if (profileId == null) profileByDay - day.name else profileByDay + (day.name to profileId))

    fun retainingProfiles(profiles: List<Profile>): WeeklySchedule {
        val ids = profiles.map { it.id }.toSet()
        return copy(profileByDay = profileByDay.filterValues { it in ids }, fallbackProfileId = fallbackProfileId?.takeIf { it in ids })
    }

    companion object {
        internal fun fromLegacy(profiles: List<Profile>): WeeklySchedule {
            val assignments = buildMap {
                for (day in DayOfWeek.values()) {
                    val profile = profiles.firstOrNull { day.value in it.days } ?: continue
                    put(day.name, profile.id)
                }
            }
            return WeeklySchedule(assignments, profiles.firstOrNull()?.id)
        }
    }
}

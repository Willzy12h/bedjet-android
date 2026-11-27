package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Presets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class WeeklyScheduleTest {
    private val wednesday = ZonedDateTime.parse("2026-10-21T22:30+01:00[Europe/London]")
    private val profiles = Presets.all
    private val settings = BackupCodec.json.decodeFromString(Settings.serializer(),
        """{"weeklySchedule":{"profileByDay":{"WEDNESDAY":"preset-winter"},"fallbackProfileId":"preset-spring-autumn"}}""")

    @Test fun explicitWeekdayAndFallbackSurviveBackupRoundTrip() {
        val bundle = BackupBundle(createdAt = wednesday.toString(), profiles = profiles.reversed(), settings = settings)
        val restored = BackupCodec.decode(BackupCodec.verifiedBytes(BackupCodec.encode(bundle)).decodeToString())

        assertEquals("preset-winter", restored.settings.resolveProfile(restored.profiles, wednesday)?.id)
        assertEquals("preset-spring-autumn", restored.settings.resolveProfile(restored.profiles, wednesday.plusHours(2))?.id)
    }

    @Test fun oneNightChoiceTakesPrecedenceUntilNoon() {
        val override = settings.chooseTonight("preset-hot-sleeper", wednesday)
        assertEquals("preset-hot-sleeper", override.resolveProfile(profiles, wednesday.plusHours(2))?.id)
        assertEquals("preset-spring-autumn", override.resolveProfile(profiles, wednesday.plusHours(13).plusMinutes(30))?.id)
        assertEquals("preset-winter", override.followSchedule().resolveProfile(profiles, wednesday)?.id)
    }

    @Test fun missingAssignmentFallsBackAndMissingFallbackLeavesNoProfile() {
        val remaining = profiles.filterNot { it.id == "preset-winter" }
        assertEquals("preset-spring-autumn", settings.resolveProfile(remaining, wednesday)?.id)

        val withoutFallback = remaining.filterNot { it.id == "preset-spring-autumn" }
        assertNull(settings.resolveProfile(withoutFallback, wednesday))
    }
}

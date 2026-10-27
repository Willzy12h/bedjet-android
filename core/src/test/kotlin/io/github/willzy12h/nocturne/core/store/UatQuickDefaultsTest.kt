package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UatQuickDefaultsTest {
    @Test fun legacyWarmMigratesToItsActualModeWithoutLosingValues() {
        val warm = ActionPreset(Mode.EXTENDED_HEAT, 65, 45, 47)
        val cool = ActionPreset(Mode.COOL, 43, 65, 23)
        val old = Settings(extendWarm = warm, extendCool = cool)
        assertEquals(warm, QuickDefaults.resolve(old, Mode.EXTENDED_HEAT))
        assertEquals(cool, QuickDefaults.resolve(old, Mode.COOL))
        assertEquals(QuickDefaults.builtIn(Mode.HEAT), QuickDefaults.resolve(old, Mode.HEAT))
        val changed = QuickDefaults.withPreset(old, QuickDefaults.builtIn(Mode.DRY).copy(minutes = 17))
        assertEquals(warm, changed.extendWarm)
        assertEquals(cool, changed.extendCool)
        assertEquals(warm, QuickDefaults.resolve(changed, Mode.EXTENDED_HEAT))
        val restored = Json.decodeFromString<Settings>(Json.encodeToString(changed))
        assertEquals(changed, restored)
    }
    @Test fun oldJsonWithoutQuickOverridesStillResolvesTheSavedWarmPreset() {
        val saved = Json.decodeFromString<Settings>("""{"extendWarm":{"mode":"HEAT","temperature":69,"fanPercent":55,"minutes":43}}""")
        assertEquals(saved.extendWarm, QuickDefaults.resolve(saved, Mode.HEAT))
        assertEquals(69, QuickDefaults.resolve(saved, Mode.HEAT).temperature)
        assertEquals(43, QuickDefaults.resolve(saved, Mode.HEAT).minutes)
    }
    @Test fun everyNewDefaultMeetsTheFinalModeSafetyPolicy() {
        assertEquals(5, QuickDefaults.modes.size)
        QuickDefaults.modes.forEach { assertTrue(QuickDefaults.valid(QuickDefaults.builtIn(it)), it.name) }
        assertEquals(10, QuickDefaults.maximumMinutes(QuickDefaults.builtIn(Mode.TURBO)))
    }
    @Test fun unsafeSavedValuesRemainInspectableButCannotBeSavedAsQuickOverrides() {
        val edited = ActionPreset(Mode.DRY, 63, 50, 30)
        val saved = Settings(extendWarm = edited)
        assertEquals(edited, QuickDefaults.resolve(saved, Mode.DRY))
        assertFalse(QuickDefaults.valid(edited))
        assertThrows<IllegalArgumentException> { QuickDefaults.withPreset(saved, edited) }
        assertEquals(edited, saved.extendWarm)
    }
    @Test fun turboDryFanAndHeatingRuntimeLimitsCannotBeWidenedByDefaults() {
        val turbo = QuickDefaults.builtIn(Mode.TURBO)
        assertFalse(QuickDefaults.valid(turbo.copy(minutes = 11)))
        assertFalse(QuickDefaults.valid(turbo.copy(temperature = 85)))
        assertFalse(QuickDefaults.valid(QuickDefaults.builtIn(Mode.DRY).copy(temperature = 63)))
        assertFalse(QuickDefaults.valid(QuickDefaults.builtIn(Mode.HEAT).copy(temperature = 80, fanPercent = 100, minutes = 120)))
        for (fan in listOf(0, 4, 101, 255)) assertFalse(QuickDefaults.valid(turbo.copy(fanPercent = fan)))
        assertFalse(QuickDefaults.valid(turbo.copy(minutes = 0)))
    }
    @Test fun overridesAreBoundedUniqueAndCannotContainOffOrWait() {
        val turbo = QuickDefaults.builtIn(Mode.TURBO)
        assertFalse(QuickDefaults.validOverrides(listOf(turbo, turbo)))
        assertFalse(QuickDefaults.validOverrides(listOf(turbo.copy(mode = Mode.STANDBY))))
        assertFalse(QuickDefaults.validOverrides(listOf(turbo.copy(mode = Mode.WAIT))))
        assertTrue(QuickDefaults.validOverrides(QuickDefaults.modes.map(QuickDefaults::builtIn)))
    }
    @Test fun editingOneModeDoesNotOverwriteAnotherOrItsLegacySource() {
        val old = Settings(extendWarm = ActionPreset(Mode.HEAT, 70, 40, 20))
        val edited = QuickDefaults.withPreset(old, QuickDefaults.builtIn(Mode.HEAT).copy(temperature = 62))
        assertEquals(70, edited.extendWarm.temperature)
        assertEquals(62, QuickDefaults.resolve(edited, Mode.HEAT).temperature)
        assertEquals(old.extendCool, QuickDefaults.resolve(edited, Mode.COOL))
    }
}

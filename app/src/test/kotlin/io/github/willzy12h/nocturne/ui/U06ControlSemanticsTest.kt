package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.platform.PlatformNotifications
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class PlatformRowsSeed

/** U06: every slider instance and switch row in the real app, at 320 dp / 200% text. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class U06ControlSemanticsTest(look: U06Look) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf<Any>(it) }

        private const val TARGET = "ble:AA:BB:CC:DD:EE:FF"
    }

    private val fixture = U06Fixture(look) { f, description ->
        val platformRows = description.getAnnotation(PlatformRowsSeed::class.java) != null
        // Fixed, distinct values make a missing/wrong unit or another preset's state observable.
        runBlocking {
            f.graph.repository.saveProfile(Presets.summerCool)
            f.graph.repository.updateSettings {
                it.copy(
                    boostMinutes = 15,
                    snoozeWarm = ActionPreset(Mode.HEAT, 65, 40, 10),
                    snoozeCool = ActionPreset(Mode.COOL, 45, 50, 15),
                    extendWarm = ActionPreset(Mode.HEAT, 64, 30, 30),
                    extendCool = ActionPreset(Mode.COOL, 44, 40, 35),
                ).chooseTonight(Presets.summerCool.id, f.graph.now())
            }
            if (platformRows) {
                f.graph.repository.updateSettings {
                    it.copy(demoMode = false, targetDevice = TARGET,
                        platform = it.platform.copy(healthSleepOverlay = true))
                }
            }
        }
        if (platformRows) {
            val app: Application = RuntimeEnvironment.getApplication()
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_ADVERTISE)
            PlatformNotifications.channel(app)
        }
    }
    @get:Rule val rules: TestRule = fixture.rules
    private val compose get() = fixture.compose
    private val graph get() = fixture.graph
    private fun s(id: Int, vararg args: Any) = fixture.s(id, *args)

    private val adjustable = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun percent(value: Int) = s(R.string.a11y_percent, value)
    private fun minutes(value: Int) = compose.activity.resources.getQuantityString(R.plurals.a11y_minutes, value, value)
    private fun temperature(celsius: String, fahrenheit: String, useFahrenheit: Boolean) =
        s(if (useFahrenheit) R.string.a11y_fahrenheit else R.string.a11y_celsius,
            if (useFahrenheit) fahrenheit else celsius)

    private fun fahrenheit(value: Boolean) {
        val repository = graph.repository
        compose.runOnUiThread { runBlocking { repository.updateSettings { it.copy(fahrenheit = value) } } }
        compose.waitForIdle()
    }

    private fun scalar(label: String, value: Float, state: String, index: Int = 0): SemanticsNodeInteraction {
        assertTrue("A slider needs a meaningful label", label.isNotBlank())
        assertTrue("$label needs a spoken value and unit", state.isNotBlank())
        val node = compose.onAllNodes(hasContentDescription(label) and progress)[index]
        fixture.checkLayout(node, "$label [$index]")
        val semantics = node.fetchSemanticsNode().config
        assertEquals("$label spoken state", state, semantics.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("$label must remain adjustable", semantics.contains(SemanticsActions.SetProgress))
        assertEquals("$label numeric value", value, semantics[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        return node
    }

    private fun scalarGroup(label: String, expected: List<Pair<Float, String>>) {
        compose.onAllNodes(hasContentDescription(label) and progress).assertCountEquals(expected.size)
        expected.forEachIndexed { index, (value, state) -> scalar(label, value, state, index) }
    }

    /** A RangeSlider names the pair on its parent and exposes two separately adjustable thumbs. */
    private fun range(label: String, first: Float, last: Float, state: String) {
        val parent = compose.onNode(hasContentDescription(label))
        parent.performScrollTo().assertIsDisplayed()
        val semantics = parent.fetchSemanticsNode().config
        assertTrue("A range needs a meaningful label", label.isNotBlank())
        assertEquals("$label spoken endpoints and units", state, semantics.getOrNull(SemanticsProperties.StateDescription))
        val thumbs = compose.onAllNodes(
            hasAnyAncestor(hasContentDescription(label)) and progress and adjustable,
            useUnmergedTree = true,
        )
        thumbs.assertCountEquals(2)
        listOf(first, last).forEachIndexed { index, value ->
            fixture.checkLayout(thumbs[index], "$label ${if (index == 0) "lower" else "upper"} bound")
            assertEquals("$label thumb $index value", value,
                thumbs[index].fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        }
    }

    private fun switchMatcher(label: String) = hasContentDescription(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun switchRow(label: String, checked: Boolean): SemanticsNodeInteraction {
        val matcher = switchMatcher(label)
        val row = compose.onNode(matcher)
        fixture.checkControl(row, label).assertHasClickAction()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,
            if (checked) ToggleableState.On else ToggleableState.Off))
        // Inspect the unmerged tree too: the visual Switch must not create another action within the row.
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(matcher), useUnmergedTree = true).assertCountEquals(0)
        return row
    }

    private fun toggleTwice(label: String, read: () -> Boolean) {
        val original = read()
        switchRow(label, original).assertIsEnabled().performClick()
        fixture.waitUntil { read() != original }
        switchRow(label, !original).performClick()
        fixture.waitUntil { read() == original }
        switchRow(label, original)
    }

    @Test fun settingsSlidersExposeExactCelsiusAndFahrenheitStates() {
        fixture.tab(s(R.string.tab_settings))
        fixture.snackbar()
        for (useFahrenheit in listOf(false, true)) {
            fahrenheit(useFahrenheit)
            data class Preset(val title: Int, val rawTemperature: Float, val celsius: String, val fahrenheit: String,
                val fan: Int, val minutes: Int)
            val presets = listOf(
                Preset(R.string.settings_preset_snooze_warm, 65f, "32.5", "91", 40, 10),
                Preset(R.string.settings_preset_snooze_cool, 45f, "22.5", "73", 50, 15),
                Preset(R.string.settings_preset_extend_warm, 64f, "32", "90", 30, 30),
                Preset(R.string.settings_preset_extend_cool, 44f, "22", "72", 40, 35),
            )
            // All four preset editors are expanded in Settings; no instance may be skipped.
            presets.forEach { preset ->
                fun label(kind: Int) = s(R.string.settings_preset_slider, s(preset.title), s(kind))
                scalar(label(R.string.slider_temp), preset.rawTemperature,
                    temperature(preset.celsius, preset.fahrenheit, useFahrenheit))
                scalar(label(R.string.slider_fan), preset.fan.toFloat(), percent(preset.fan))
                scalar(label(R.string.slider_minutes), preset.minutes.toFloat(), minutes(preset.minutes))
            }
            scalar(s(R.string.slider_boost), 15f, minutes(15))
            compose.onAllNodes(adjustable, useUnmergedTree = true).assertCountEquals(13)
        }
    }

    @Test fun remoteSlidersExposeExactCelsiusAndFahrenheitStates() {
        // Hold controlled feedback before any command can enqueue its own snackbar.
        fixture.snackbar()
        fixture.control(s(R.string.feature_warm)).performClick()
        fixture.waitUntil {
            graph.link.status.value?.let { it.mode == Mode.HEAT && it.targetTemperature == 64 && it.fanPercent == 30 } == true &&
                graph.actions.busy.value.runningLabel == null
        }
        fixture.tab(s(R.string.tab_remote))
        fixture.waitUntil { compose.onAllNodes(hasContentDescription(s(R.string.slider_temp)) and progress).fetchSemanticsNodes().isNotEmpty() }
        for (useFahrenheit in listOf(false, true)) {
            fahrenheit(useFahrenheit)
            val temp = scalar(s(R.string.slider_temp), 64f, temperature("32", "90", useFahrenheit))
            val fan = scalar(s(R.string.slider_fan), 30f, percent(30))
            compose.onAllNodes(adjustable, useUnmergedTree = true).assertCountEquals(2)
            // The semantics action must update both the real demo status and the spoken value.
            temp.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(65f)) }
            fixture.waitUntil { graph.link.status.value?.targetTemperature == 65 }
            scalar(s(R.string.slider_temp), 65f, temperature("32.5", "91", useFahrenheit))
            temp.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(64f)) }
            fixture.waitUntil { graph.link.status.value?.targetTemperature == 64 }
            fan.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(35f)) }
            fixture.waitUntil { graph.link.status.value?.fanPercent == 35 }
            scalar(s(R.string.slider_fan), 35f, percent(35))
            fan.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(30f)) }
            fixture.waitUntil { graph.link.status.value?.fanPercent == 30 }
        }
    }

    @Test fun settingsRowsAreSingleToggleableTargets() {
        fixture.tab(s(R.string.tab_settings))
        fixture.snackbar()
        toggleTwice(s(R.string.settings_simple_view)) { graph.repository.settings.value.simpleView }
        toggleTwice(s(R.string.settings_show_f)) { graph.repository.settings.value.fahrenheit }
        toggleTwice(s(R.string.settings_quiet_title)) { graph.repository.settings.value.quietNightNotification }
        toggleTwice(s(R.string.settings_check_weekly)) { graph.repository.settings.value.autoUpdateCheck }
        toggleTwice(s(R.string.settings_demo)) { graph.repository.settings.value.demoMode }
    }

    @Test fun remoteRowsAreSingleToggleableTargets() {
        fixture.waitUntil { graph.link.state.value == LinkState.READY && graph.link.status.value?.extra != null }
        fixture.tab(s(R.string.tab_remote))
        fixture.snackbar()
        toggleTwice(s(R.string.remote_lights)) { graph.link.status.value!!.extra!!.ledEnabled }
        toggleTwice(s(R.string.remote_beeps)) { !graph.link.status.value!!.extra!!.beepsMuted }
    }

    @Test @PlatformRowsSeed
    fun platformRowsAreSingleToggleableTargets() {
        fixture.tab(s(R.string.tab_settings))
        fixture.snackbar()
        toggleTwice(s(R.string.feature_reminders)) { graph.repository.settings.value.platform.bedtimeReminder }
        toggleTwice(s(R.string.feature_charger)) { graph.repository.settings.value.platform.chargerAutoStart }
        toggleTwice(s(R.string.feature_lull)) { graph.repository.settings.value.platform.lullHandover }
        // Disabling is a real supported action without a Health Connect provider or permission activity.
        switchRow(s(R.string.feature_health_title), true).assertIsEnabled().performClick()
        fixture.waitUntil { !graph.repository.settings.value.platform.healthSleepOverlay }
        switchRow(s(R.string.feature_health_title), false)
    }

    @Test fun chargerRowKeepsItsExplainedDisabledStateInDemo() {
        fixture.tab(s(R.string.tab_settings))
        switchRow(s(R.string.feature_charger), false).assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.feature_select_device)).performScrollTo().assertIsDisplayed()
    }

    private fun openEditor() {
        fixture.control(s(R.string.action_edit)).performClick()
        compose.onNodeWithText(s(R.string.editor_title)).assertIsDisplayed()
    }

    @Test fun editorSlidersAndBothRangeThumbsExposeTheirValuesInBothUnits() {
        openEditor()
        for (useFahrenheit in listOf(false, true)) {
            fahrenheit(useFahrenheit)
            range(s(R.string.slider_fan_limits), 5f, 100f,
                s(R.string.a11y_range, percent(5), percent(100)))
            range(s(R.string.slider_temp_limits), 38f, 86f,
                s(R.string.a11y_range, temperature("19", "66", useFahrenheit), temperature("43", "109", useFahrenheit)))
            // Start, Wake and After wake each have their own Temperature, Fan and Length controls.
            scalarGroup(s(R.string.slider_temp), listOf(
                44f to temperature("22", "72", useFahrenheit),
                60f to temperature("30", "86", useFahrenheit),
                44f to temperature("22", "72", useFahrenheit),
            ))
            scalarGroup(s(R.string.slider_fan), listOf(70f to percent(70), 30f to percent(30), 40f to percent(40)))
            scalarGroup(s(R.string.slider_length), listOf(30f to minutes(30), 20f to minutes(20), 20f to minutes(20)))
            scalar(s(R.string.slider_fan_start), 50f, percent(50))
            scalar(s(R.string.slider_fan_end), 25f, percent(25))
            scalar(s(R.string.slider_temp_start), 44f, temperature("22", "72", useFahrenheit))
            scalar(s(R.string.slider_temp_end), 44f, temperature("22", "72", useFahrenheit))
            scalar(s(R.string.slider_steps), 3f,
                compose.activity.resources.getQuantityString(R.plurals.a11y_steps, 3, 3))
            // Four range thumbs plus fourteen scalar sliders: this also catches newly added unlabelled sliders.
            compose.onAllNodes(adjustable, useUnmergedTree = true).assertCountEquals(18)
        }
    }

    @Test fun editorRowsToggleTheDraftAndSaveTheirState() {
        openEditor()
        switchRow(s(R.string.editor_wake_with_alarm), true).performClick()
        switchRow(s(R.string.editor_wake_with_alarm), false)
        switchRow(s(R.string.editor_repeat_turbo), false).performClick()
        switchRow(s(R.string.editor_repeat_turbo), true)
        fixture.control(s(R.string.editor_save), scroll = false).performClick()
        fixture.waitUntil {
            graph.repository.profiles.value.first { it.id == Presets.summerCool.id }.let {
                !it.wakeSource.useAlarm && it.turboChain == TurboChain.REPEAT_TURBO
            }
        }
    }
}

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
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
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
                        platform = it.platform.copy(healthSleepOverlay = true, healthSleepExport = true,
                            autoStart = AutoStartSettings(profileId = Presets.summerCool.id, deviceId = TARGET)))
                }
            }
        }
        if (platformRows) {
            val app: Application = RuntimeEnvironment.getApplication()
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
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
        compose.openSettingsPage("notifications")
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
            // Boost is a row that opens its editor; the page itself holds no Boost slider.
            compose.onAllNodes(adjustable, useUnmergedTree = true).assertCountEquals(12)
        }
    }

    @Test fun remoteSlidersExposeExactCelsiusAndFahrenheitStates() {
        // Hold controlled feedback before any command can enqueue its own snackbar.
        fixture.snackbar()
        fixture.quick(fixture.graph.repository.settings.value.extendWarm).performClick()
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
        compose.openSettingsPage("appearance")
        // UX-04: Simple view is retired; its switch is gone from Settings.
        compose.onAllNodes(switchMatcher("Simple view")).assertCountEquals(0)
        toggleTwice(s(R.string.settings_show_f)) { graph.repository.settings.value.fahrenheit }
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        compose.openSettingsPage("notifications")
        toggleTwice(s(R.string.settings_quiet_title)) { graph.repository.settings.value.quietNightNotification }
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        compose.openSettingsPage("about")
        fixture.control(s(R.string.settings_github_releases))
        fixture.control(s(R.string.settings_source_code))
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        fixture.tab(s(R.string.tab_tonight))
        fixture.control(s(R.string.gpt12_exit_demo), scroll = false).performClick()
        fixture.waitUntil { !graph.repository.settings.value.demoMode }
        fixture.tab(s(R.string.tab_settings))
        fixture.snackbar() // The mode boundary disposes the old session and its SnackbarHost.
        compose.openSettingsPage("bedjet")
        fixture.control(s(R.string.setup_use_demo)).performClick()
        fixture.waitUntil { graph.repository.settings.value.demoMode }
        fixture.snackbar()
        fixture.tab(s(R.string.tab_tonight))
        fixture.control(s(R.string.gpt12_exit_demo), scroll = false)
    }

    @Test fun deviceRowsInSettingsAreSingleToggleableTargets() {
        fixture.waitUntil { graph.link.state.value == LinkState.READY && graph.link.status.value?.extra != null }
        fixture.tab(s(R.string.tab_settings))
        fixture.snackbar()
        compose.openSettingsPage("bedjet")
        toggleTwice(s(R.string.remote_lights)) { graph.link.status.value!!.extra!!.ledEnabled }
        toggleTwice(s(R.string.remote_beeps)) { !graph.link.status.value!!.extra!!.beepsMuted }
    }

    @Test @PlatformRowsSeed
    fun platformRowsAreSingleToggleableTargets() {
        fixture.tab(s(R.string.tab_settings))
        fixture.snackbar()
        compose.openSettingsPage("night")
        toggleTwice(s(R.string.feature_reminders)) { graph.repository.settings.value.platform.bedtimeReminder }
        toggleTwice(s(R.string.auto_start_title)) { graph.repository.settings.value.platform.autoStart?.enabled == true }
        toggleTwice(s(R.string.auto_start_only_charging)) { graph.repository.settings.value.platform.autoStart?.onlyIfCharging == true }
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        compose.openSettingsPage("health")
        switchRow(s(R.string.health_export_title), true).assertIsEnabled().performClick()
        fixture.waitUntil { !graph.repository.settings.value.platform.healthSleepExport }
        switchRow(s(R.string.health_export_title), false)
        // Disabling is a real supported action without a Health Connect provider or permission activity.
        switchRow(s(R.string.feature_health_title), true).assertIsEnabled().performClick()
        fixture.waitUntil { !graph.repository.settings.value.platform.healthSleepOverlay }
        switchRow(s(R.string.feature_health_title), false)
    }

    @Test fun automaticFeatureRowsKeepTheirExplainedDisabledStateInDemo() {
        fixture.tab(s(R.string.tab_settings))
        compose.openSettingsPage("night")
        switchRow(s(R.string.auto_start_title), false).assertIsNotEnabled()
        switchRow(s(R.string.auto_start_only_charging), false).assertIsNotEnabled()
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        compose.openSettingsPage("health")
        switchRow(s(R.string.health_export_title), false).assertIsNotEnabled()
        compose.onAllNodesWithText(s(R.string.platform_demo_unavailable)).onFirst().performScrollTo().assertIsDisplayed()
    }

    private fun openEditor() {
        compose.openTonightProfileEditor()
        compose.onNodeWithTag("editor-save").assertIsDisplayed()
    }

    @Test fun editorSlidersAndBothRangeThumbsExposeTheirValuesInBothUnits() {
        openEditor()
        for (useFahrenheit in listOf(false, true)) {
            fahrenheit(useFahrenheit)
            range(s(R.string.slider_fan_limits), 5f, 100f,
                s(R.string.a11y_range, percent(5), percent(100)))
            range(s(R.string.slider_temp_limits), 38f, 86f,
                s(R.string.a11y_range, temperature("19", "66", useFahrenheit), temperature("43", "109", useFahrenheit)))
            compose.onAllNodes(adjustable, useUnmergedTree = true).assertCountEquals(4)
            data class PhaseValues(val block: String, val temp: Float, val celsius: String, val fahrenheit: String, val fan: Int, val minutes: Int)
            for (phase in listOf(PhaseValues("START", 44f, "22", "72", 70, 30),
                PhaseValues("WAKE", 60f, "30", "86", 30, 20),
                PhaseValues("AFTER_WAKE", 44f, "22", "72", 40, 20))) {
                compose.onNodeWithTag("editor-phase-${phase.block}-0").performScrollTo().performClick()
                scalar(s(R.string.slider_temp), phase.temp, temperature(phase.celsius, phase.fahrenheit, useFahrenheit))
                scalar(s(R.string.slider_fan), phase.fan.toFloat(), percent(phase.fan))
                scalar(s(R.string.slider_length), phase.minutes.toFloat(), minutes(phase.minutes))
                compose.onAllNodes(adjustable and hasAnyAncestor(hasTestTag("editor-phase-controls-${phase.block}-0")), useUnmergedTree = true).assertCountEquals(3)
                compose.onNodeWithText(s(R.string.uat_setup_done)).performScrollTo().performClick()
            }
            compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
            scalar(s(R.string.slider_fan_start), 50f, percent(50))
            scalar(s(R.string.slider_fan_end), 25f, percent(25))
            scalar(s(R.string.slider_temp_start), 44f, temperature("22", "72", useFahrenheit))
            scalar(s(R.string.slider_temp_end), 44f, temperature("22", "72", useFahrenheit))
            scalar(s(R.string.slider_steps), 3f,
                compose.activity.resources.getQuantityString(R.plurals.a11y_steps, 3, 3))
            compose.onAllNodes(adjustable and hasAnyAncestor(hasTestTag("editor-middle-sheet")), useUnmergedTree = true).assertCountEquals(5)
            compose.onNodeWithText(s(R.string.uat_setup_done)).performScrollTo().performClick()
        }
    }

    @Test fun editorRowsToggleTheDraftAndSaveTheirState() {
        openEditor()
        switchRow(s(R.string.editor_wake_with_alarm), true).performClick()
        switchRow(s(R.string.editor_wake_with_alarm), false)
        switchRow(s(R.string.editor_repeat_turbo), false).performClick()
        switchRow(s(R.string.editor_repeat_turbo), true)
        fixture.checkLayout(compose.onNodeWithText(s(R.string.editor_repeat_turbo_help)), "Repeat Turbo explanation")
        fixture.control(s(R.string.editor_save), scroll = false).performClick()
        fixture.waitUntil {
            graph.repository.profiles.value.first { it.id == Presets.summerCool.id }.let {
                !it.wakeSource.useAlarm && it.turboChain == TurboChain.REPEAT_TURBO
            }
        }
    }
}

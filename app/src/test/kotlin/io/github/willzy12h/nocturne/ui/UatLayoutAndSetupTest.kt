package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UatLayoutAndSetupTest {
    private val graphs = GraphLifecycle()
    private val compose = createComposeRule()
    private val models = ViewModelStore()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)
    @After fun clear() = models.clear()
    private val app get() = RuntimeEnvironment.getApplication() as App
    private class Owner : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }

    private fun model(demo: Boolean): AppViewModel {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS)
        @Suppress("DEPRECATION")
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        shadowOf(app.getSystemService(AlarmManager::class.java)).setCanScheduleExactAlarms(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        val link = object : BedJetLink {
            override val state = MutableStateFlow(LinkState.READY)
            override val status = MutableStateFlow<DeviceStatus?>(null)
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun send(command: ByteArray) = Unit
            override suspend fun query(request: Int) = ByteArray(0)
        }
        val graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-27T22:30:00Z"), ZoneOffset.UTC)
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = demo,
            targetDevice = "ble:AA:BB:CC:DD:EE:01", themeMode = ThemeMode.DARK, quickControlsCollapsed = false) } }
        return AppViewModel(app).also { models.put("uat", it) }
    }

    @Test fun U4_resumeRechecksRevocationWithoutReturningCompletedOrOptionalItems() {
        val model = model(false)
        val owner = Owner().also { it.lifecycle.currentState = Lifecycle.State.RESUMED }
        assertTrue(setupSnapshot(app, model.settings.value, false).requiredComplete)
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            NocturneTheme(model.settings.value, model.now()) { SetupChecklistCard(model) }
        } }
        compose.waitUntil(5_000) { model.settings.value.setupChecklistCompleted }
        compose.onNodeWithTag("setup-checklist").assertDoesNotExist()
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.onNodeWithTag("setup-notifications").assertIsDisplayed()
        listOf("pair", "bluetooth", "exact", "battery", "health", "widget").forEach {
            compose.onNodeWithTag("setup-$it").assertDoesNotExist()
        }
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, false)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.onNodeWithTag("setup-notifications").assertDoesNotExist()
        compose.onNodeWithTag("setup-battery").assertIsDisplayed()
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.onNodeWithTag("setup-checklist").assertDoesNotExist()
    }

    @Test fun U4_settingsEntryRequestsReopeningAndClearsOnlyOptionalDismissals() {
        val model = model(true)
        runBlocking { model.graph.repository.updateSettings { it.copy(setupDismissedOptional = setOf("health", "widget")) } }
        compose.setContent { NocturneTheme(model.settings.value, model.now()) { SettingsScreen(model, Modifier) } }
        compose.onNodeWithTag("open-setup-checklist").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.setupChecklistOpen.value && model.settings.value.setupDismissedOptional.isEmpty() }
        assertEquals(1, model.setupChecklistRequest.value)
        assertTrue(model.settings.value.demoMode)
        compose.runOnIdle { model.closeSetupChecklist() }
        assertFalse(model.setupChecklistOpen.value)
    }

    @Test fun U5_sixQuickTargetsFitTwoColumnsAt320dpAndTwoHundredPercentText() {
        val model = model(true)
        val settings = model.settings.value
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                NocturneTheme(settings, model.now()) {
                    Column(Modifier.width(320.dp).height(700.dp).verticalScroll(rememberScrollState())) {
                        ModeQuickButtons(model, settings, rememberNightGuard(model))
                    }
                }
            }
        }
        val modes = QuickDefaults.modes + Mode.STANDBY
        modes.forEach { mode ->
            val node = compose.onNodeWithTag("quick-mode-${mode.name}")
            node.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val label = if (mode == Mode.STANDBY) app.getString(Format.modeNameRes(mode))
                else quickModeLabel(app, QuickDefaults.resolve(settings, mode), false)
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(label, results.isNotEmpty())
            assertFalse("No clipped or ellipsised label: $label", results.single().hasVisualOverflow)
        }
        compose.onNodeWithTag("quick-mode-TURBO").performScrollTo()
        val left = compose.onNodeWithTag("quick-mode-TURBO").getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag("quick-mode-HEAT").getUnclippedBoundsInRoot()
        assertEquals(left.top.value, right.top.value, .5f)
        assertEquals(left.width.value, right.width.value, .5f)
        assertTrue(left.right < right.left)
    }

    @Test fun U5_everyModeCapturesTheReviewedPresetAndPreservesTemporaryOrEndConsent() {
        val model = model(true)
        for (mode in QuickDefaults.modes) {
            val preset = QuickDefaults.resolve(model.settings.value, mode)
            val captured = captureModeQuick(model, preset)
            assertEquals(preset, captured.end.actionPreset)
            assertEquals(preset, captured.temporary.preset)
            assertTrue(captured.end.requireIdentity)
            assertTrue(captured.end.replacementConfirmed)
            assertFalse(captured.temporary.request.replacementConfirmed)
            assertEquals(io.github.willzy12h.nocturne.night.NightActions.Command.TEMPORARY, captured.temporary.request.command)
            runBlocking { model.graph.repository.updateSettings { QuickDefaults.withPreset(it, preset.copy(minutes = 1)) } }
            assertEquals(preset, captured.end.actionPreset)
            assertEquals(preset, captured.temporary.preset)
        }
    }
}

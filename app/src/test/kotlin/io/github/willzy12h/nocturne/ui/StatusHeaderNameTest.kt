package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.ble.DeviceNames
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StatusHeaderNameTest {
    private val graphs = GraphLifecycle()
    private val compose = createComposeRule()
    private val models = ViewModelStore()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)
    @After fun closeModels() { models.clear() }

    private fun showThenName(verified: Boolean) {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = graphs.fresh { io.github.willzy12h.nocturne.Graph(app) }
        ReflectionHelpers.setField(app, "graph", graph)
        val target = "ble:AA:BB:CC:DD:EE:01"
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target) } }
        val model = AppViewModel(app).also { models.put("header", it) }
        compose.setContent { MaterialTheme { StatusHeader(model) } }
        compose.onNodeWithTag("status-device").assertTextEquals("AA:BB:CC:DD:EE:01")
        // Invoke the same target-bound name callback used by the live GATT adapter; no status or settings change.
        val names = ReflectionHelpers.getField<DeviceNames>(graph.bleLink, "names")
        compose.runOnIdle {
            if (verified) names.verify(target, "BEDJET Bedroom") else names.display(target, "BEDJET Bedroom")
        }
        compose.onNodeWithTag("status-device").assertTextEquals("BEDJET Bedroom")
    }

    @Test fun aNameOnlyDeviceNameReplyUpdatesTheHeaderImmediately() = showThenName(false)
    @Test fun aNameOnlyVerifiedReplyUpdatesTheHeaderImmediately() = showThenName(true)
}

package io.github.willzy12h.nocturne.platform

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.health.connect.client.HealthConnectClient
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.ui.features.PlatformSettingsSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class HealthReadDemoBoundaryTest : PlatformGraphFixture() {
    @get:Rule val compose = createComposeRule()
    private class PermissionRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
        }
        fun grant() { assertNotNull(requestCode); dispatchResult(requestCode!!, AndroidSleepSource.PERMISSIONS) }
    }
    private fun setup(demo: Boolean): Graph {
        val application: Context = RuntimeEnvironment.getApplication()
        // getSdkStatus only needs service presence. No real Health client or data is constructed by this permission test.
        val context = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any? = if (name == "healthconnect") Any() else super.getSystemService(name)
        }
        val graph = fresh { Graph(context, DemoBedJet()) }
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = demo) } }
        return graph
    }
    @Test fun theLegacyHealthReadToggleIsDisabledDuringDemo() {
        val graph = setup(demo = true)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { PlatformSettingsSection(graph, healthOnly = true) } } }
        compose.onNodeWithContentDescription(graph.context.getString(R.string.feature_health_title)).performScrollTo().assertIsNotEnabled()
    }
    @Test fun aHealthReadPermissionResultArrivingAfterDemoEntryCannotEnableRealAccess() {
        val graph = setup(demo = false)
        assertEquals(HealthConnectClient.SDK_AVAILABLE, HealthConnectClient.getSdkStatus(graph.context))
        val registry = PermissionRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { PlatformSettingsSection(graph, healthOnly = true) } }
        } }
        compose.onNodeWithContentDescription(graph.context.getString(R.string.feature_health_title)).performScrollTo().performClick()
        assertNotNull("The explicit read-permission request must have launched", registry.requestCode)
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = true) } }
        compose.runOnIdle { registry.grant() }
        // The callback's write joins the same serial writer before this barrier.
        runBlocking { graph.repository.updateSettings { it } }
        compose.waitForIdle()
        assertFalse("A stale permission callback must not cross the demo boundary", graph.repository.settings.value.platform.healthSleepOverlay)
    }
}

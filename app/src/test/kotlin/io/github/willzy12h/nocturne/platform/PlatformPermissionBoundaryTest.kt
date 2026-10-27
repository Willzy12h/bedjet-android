package io.github.willzy12h.nocturne.platform

import android.Manifest
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.ui.features.AutoStartSettingsSection
import io.github.willzy12h.nocturne.ui.features.HealthExportSettingsSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class PlatformPermissionBoundaryTest : PlatformGraphFixture() {
    @get:Rule val compose = createComposeRule()
    private class PermissionRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        var requested: Any? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.requestCode = requestCode; requested = input
        }
        fun health(granted: Boolean) = dispatchResult(requireNotNull(requestCode), if (granted) AndroidSleepWriter.PERMISSIONS else emptySet())
        fun notification() = dispatchResult(requireNotNull(requestCode), true)
    }
    private fun setup(): Graph {
        val application: Context = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any? = if (name == "healthconnect") Any() else super.getSystemService(name)
        }
        val graph = fresh { Graph(context, DemoBedJet()) }
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
        PlatformNotifications.channel(context)
        return graph
    }
    private fun content(graph: Graph, registry: PermissionRegistry) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                AutoStartSettingsSection(graph); HealthExportSettingsSection(graph)
            } }
        } }
    }
    private fun barrier(graph: Graph) {
        runBlocking { graph.repository.updateSettings { it } }
        compose.waitForIdle()
    }

    @Test fun writeOptInRequiresTheExplicitWriteGrantWithoutRequestingReadAccess() {
        val graph = setup(); val registry = PermissionRegistry(); content(graph, registry)
        compose.onNodeWithContentDescription("Export BedJet programmes").performScrollTo().performClick()
        assertEquals(AndroidSleepWriter.PERMISSIONS, registry.requested)
        assertFalse(graph.repository.settings.value.platform.healthSleepExport)
        compose.runOnIdle { registry.health(false) }; barrier(graph)
        assertFalse(graph.repository.settings.value.platform.healthSleepExport)
        compose.onNodeWithContentDescription("Export BedJet programmes").performClick()
        compose.runOnIdle { registry.health(true) }
        compose.waitUntil(5_000) { graph.repository.settings.value.platform.healthSleepExport }
    }

    @Test fun aWritePermissionResultAfterDemoEntryCannotEnableExport() {
        val graph = setup(); val registry = PermissionRegistry(); content(graph, registry)
        compose.onNodeWithContentDescription("Export BedJet programmes").performScrollTo().performClick()
        assertNotNull(registry.requestCode)
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = true) } }
        compose.runOnIdle { registry.health(true) }; barrier(graph)
        assertFalse(graph.repository.settings.value.platform.healthSleepExport)
    }

    @Test fun aNotificationGrantAfterTargetChangeCannotEnableAutoStartOnTheNewBedJet() {
        val graph = setup(); val registry = PermissionRegistry()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        content(graph, registry)
        compose.onNodeWithContentDescription("Auto-start").performClick()
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, registry.requested)
        runBlocking { graph.repository.updateSettings { it.copy(targetDevice = "ble:11:22:33:44:55:66") } }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.runOnIdle { registry.notification() }; barrier(graph)
        assertFalse(AutoStartSchedule.settings(graph).enabled)
    }

    @Test fun aNotificationGrantAfterDemoEntryCannotEnableAutoStart() {
        val graph = setup(); val registry = PermissionRegistry()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        content(graph, registry)
        compose.onNodeWithContentDescription("Auto-start").performClick()
        assertNotNull(registry.requestCode)
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = true) } }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.runOnIdle { registry.notification() }; barrier(graph)
        assertFalse(AutoStartSchedule.settings(graph).enabled)
    }
}

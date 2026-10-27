package io.github.willzy12h.nocturne.platform

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthNativeBoundarySecurityTest {
    @Test fun android14SelectsThePlatformClientWithoutLegacyBinding() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(HealthConnectAccess.platformSupported)
        val client = HealthConnectAccess.client(context)
        assertEquals("HealthConnectClientUpsideDownImpl", client.javaClass.simpleName)
        assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
    }
    @Test fun nativeBuildDoesNotReintroduceTheLegacyExportedService() {
        val context = RuntimeEnvironment.getApplication()
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getServiceInfo(ComponentName(context.packageName,
                "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService"), 0)
        }
    }
}

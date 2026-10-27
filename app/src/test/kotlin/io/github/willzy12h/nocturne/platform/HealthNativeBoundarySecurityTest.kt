package io.github.willzy12h.nocturne.platform

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.health.connect.HealthConnectManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthNativeBoundarySecurityTest {
    @Test fun android14SelectsThePlatformClientWithoutLegacyBinding() {
        val app = RuntimeEnvironment.getApplication()
        // Robolectric 4.14 has no registered Health Connect service. Supply only the
        // platform service object; the real AndroidX factory must still choose its native client.
        // No method on this object is called: record I/O still requires emulator/device verification.
        val manager = Shadow.newInstanceOf(HealthConnectManager::class.java)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.HEALTHCONNECT_SERVICE) manager else super.getSystemService(name)
        }
        assertTrue(HealthConnectAccess.platformSupported)
        val client = HealthConnectAccess.client(context)
        assertEquals("HealthConnectClientUpsideDownImpl", client.javaClass.simpleName)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    }
    @Test fun nativeBuildDoesNotReintroduceTheLegacyExportedService() {
        val context = RuntimeEnvironment.getApplication()
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getServiceInfo(ComponentName(context.packageName,
                "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService"), 0)
        }
    }
}

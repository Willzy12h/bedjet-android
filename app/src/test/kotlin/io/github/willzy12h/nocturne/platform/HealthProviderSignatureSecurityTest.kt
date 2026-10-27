package io.github.willzy12h.nocturne.platform

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.os.Binder
import androidx.health.connect.client.HealthConnectClient
import androidx.health.platform.client.HealthDataService
import androidx.health.platform.client.impl.sdkservice.HealthDataSdkService
import androidx.health.platform.client.impl.sdkservice.IGetPermissionTokenCallback
import androidx.health.platform.client.impl.sdkservice.IHealthDataSdkService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the dependency, including the exact-package case missed by foreign-UID tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33], application = Application::class)
class HealthProviderSignatureSecurityTest {
    private val provider = "com.google.android.apps.healthdata"
    @Suppress("DEPRECATION")
    private fun installWrongSigner() {
        val context = RuntimeEnvironment.getApplication()
        val pm = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply { packageName = provider; enabled = true; uid = Binder.getCallingUid() }
        pm.installPackage(PackageInfo().apply {
            packageName = provider; applicationInfo = app; setLongVersionCode(999999L)
            signatures = arrayOf(Signature(byteArrayOf(1, 2, 3, 4)))
        })
        pm.setPackagesForUid(Binder.getCallingUid(), arrayOf(provider))
        val intent = Intent(HealthDataService.ANDROID_HEALTH_PLATFORM_SERVICE_BIND_ACTION).setPackage(provider)
        pm.addResolveInfoForIntent(intent, ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = provider; name = "$provider.FakeProvider"; applicationInfo = app }
        })
    }
    @Test fun exactPackageWrongSignerCannotReadPermissionTokens() {
        installWrongSigner()
        val controller = Robolectric.buildService(HealthDataSdkService::class.java).create()
        try {
            val binder = controller.get().onBind(Intent("androidx.health.platform.client.ACTION_BIND_SDK_SERVICE"))
            assertNotNull(binder)
            var received = false
            val callback = object : IGetPermissionTokenCallback.Stub() { override fun onSuccess(token: String?) { received = true } }
            assertThrows(SecurityException::class.java) {
                IHealthDataSdkService.Stub.asInterface(binder).getPermissionToken(provider, callback)
            }
            assertFalse("Wrong signer must not receive a permission token", received)
        } finally { controller.destroy() }
    }
    @Test fun exactPackageWrongSignerIsNotAnAvailableOutboundProvider() {
        installWrongSigner()
        assertEquals(HealthConnectClient.SDK_UNAVAILABLE,
            HealthConnectClient.getSdkStatus(RuntimeEnvironment.getApplication()))
        assertThrows(UnsupportedOperationException::class.java) {
            HealthConnectClient.getOrCreate(RuntimeEnvironment.getApplication())
        }
    }
}

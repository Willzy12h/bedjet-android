package io.github.willzy12h.nocturne.platform

import android.content.Intent
import androidx.health.platform.client.impl.sdkservice.HealthDataSdkService
import androidx.health.platform.client.impl.sdkservice.IHealthDataSdkService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class HealthTrustBoundaryTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun publicActionAcceptsBindButForeignUidCannotCallProviderMethods() {
        val controller=Robolectric.buildService(HealthDataSdkService::class.java).create()
        val service=controller.get()
        assertNull(service.onBind(Intent("wrong.action")))
        val binder=service.onBind(Intent("androidx.health.platform.client.ACTION_BIND_SDK_SERVICE"))
        assertNotNull("Public action is NOT authentication",binder)
        val api=IHealthDataSdkService.Stub.asInterface(binder)
        assertThrows(SecurityException::class.java) {api.getPermissionToken("com.google.android.apps.healthdata",null)}
        assertThrows(SecurityException::class.java) {api.setPermissionToken("com.google.android.apps.healthdata","attacker",null)}
        assertThrows(SecurityException::class.java) {api.getIsInForeground("com.google.android.apps.healthdata",null)}
        controller.destroy()
    }
}

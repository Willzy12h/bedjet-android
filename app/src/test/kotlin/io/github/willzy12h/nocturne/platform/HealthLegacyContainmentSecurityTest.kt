package io.github.willzy12h.nocturne.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.os.Binder
import androidx.health.connect.client.records.SleepSessionRecord
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Tests the shipped app boundary, not an unregistered library class constructed by a test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33])
class HealthLegacyContainmentSecurityTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val provider = "com.google.android.apps.healthdata"

    @Suppress("DEPRECATION")
    private fun installImitationProvider() {
        val pm = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply { packageName = provider; enabled = true; uid = Binder.getCallingUid() }
        pm.installPackage(PackageInfo().apply {
            packageName = provider; applicationInfo = app; setLongVersionCode(999999L)
            signatures = arrayOf(Signature(byteArrayOf(1, 2, 3, 4)))
        })
        pm.setPackagesForUid(Binder.getCallingUid(), provider)
        pm.addResolveInfoForIntent(Intent("androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE").setPackage(provider),
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = provider; name = "$provider.ImitationProvider"; applicationInfo = app
            } })
    }

    @Test fun noExportedSdkServiceExistsForExactPackageOrOrdinaryCallers() {
        installImitationProvider()
        val component = ComponentName(context.packageName,
            "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService")
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getServiceInfo(component, 0)
        }
    }

    @Test fun directReaderConstructionFailsClosedBeforeLegacyProviderIpc() {
        installImitationProvider()
        assertThrows(UnsupportedOperationException::class.java) { AndroidSleepSource(context) }
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).boundServiceConnections.isEmpty())
    }

    @Test fun savedExportOptInCannotConstructOrWriteToAnImitationProvider() = runBlocking {
        installImitationProvider()
        val graph = fresh { Graph(context, DemoBedJet()) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF",
            platform = it.platform.copy(healthSleepExport = true)) }
        graph.repository.addRecord(NightRecord("real", "profile", "Profile", "2026-10-26T22:30Z", "2026-10-27T07:00Z", false,
            deviceId = "ble:AA:BB:CC:DD:EE:FF", bedJetRunConfirmed = true,
            confirmedCompletionAt = "2026-10-27T07:15Z", healthExportVersion = 1793085300000,
            events = listOf(HistoryEvent("2026-10-27T07:15Z", EventKind.ENDED, "Programme ended"))))
        var clients = 0
        var records = 0
        val exporter = HealthSleepExporter(graph, writer = {
            clients++
            object : SleepSessionWriter {
                override suspend fun permitted() = true
                override suspend fun upsert(record: SleepSessionRecord) { records++ }
            }
        })
        assertEquals(HealthExportStatus.ABSENT, exporter.sync().status)
        assertEquals(0, clients)
        assertEquals(0, records)
        assertEquals(0L, graph.repository.history.value.single().healthExportedVersion)
    }
}

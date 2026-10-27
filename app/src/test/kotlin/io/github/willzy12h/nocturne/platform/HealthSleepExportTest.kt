package io.github.willzy12h.nocturne.platform

import android.content.Context
import androidx.health.connect.client.records.SleepSessionRecord
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class HealthSleepExportTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private suspend fun setup(enabled: Boolean = true, demo: Boolean = false): Graph {
        val graph = fresh { Graph(context, DemoBedJet()) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = demo, targetDevice = "ble:AA:BB:CC:DD:EE:FF",
            platform = BackupCodec.json.decodeFromString(PlatformSettings.serializer(), """{"healthSleepExport":$enabled}""")) }
        return graph
    }
    private fun completed(id: String = "night-real", device: String = "ble:AA:BB:CC:DD:EE:FF") = NightRecord(
        id, "p", "Profile", "2026-10-26T22:30Z", "2026-10-27T07:00Z", false,
        deviceId = device, bedJetRunConfirmed = true, confirmedCompletionAt = "2026-10-27T07:15Z",
        healthExportVersion = 1793085300000,
        events = listOf(HistoryEvent("2026-10-27T07:15Z", EventKind.ENDED, "Programme ended")))
    private class Writer : SleepSessionWriter {
        var permission = true
        var permissionChecks = 0
        var permissionWork: suspend () -> Unit = {}
        var writing: suspend (SleepSessionRecord) -> Unit = {}
        val attempts = mutableListOf<SleepSessionRecord>()
        override suspend fun permitted(): Boolean { permissionChecks++; permissionWork(); return permission }
        override suspend fun upsert(record: SleepSessionRecord) { attempts += record; writing(record) }
    }

    @Test fun disabledByDefaultAndDemoNeverConstructAHealthClient() = runBlocking {
        val graph = setup(enabled = false)
        graph.repository.addRecord(completed())
        var clients = 0
        val exporter = HealthSleepExporter(graph, { true }) { clients++; Writer() }
        assertEquals(HealthExportStatus.DISABLED, exporter.sync().status)
        assertEquals(0, clients)
        graph.repository.updateSettings { it.copy(demoMode = true,
            platform = BackupCodec.json.decodeFromString(PlatformSettings.serializer(), """{"healthSleepExport":true}""")) }
        assertEquals(HealthExportStatus.DEMO, exporter.sync().status)
        assertEquals(0, clients)
    }

    @Test fun onlyCompletedConfirmedNormalRealNightsReachTheProvider() = runBlocking {
        val graph = setup()
        val valid = completed()
        val excluded = listOf(
            completed("test").copy(isTestRun = true), completed("demo", DeviceId.DEMO),
            completed("unconfirmed").copy(bedJetRunConfirmed = false),
            completed("incomplete").copy(confirmedCompletionAt = null),
            completed("legacy").copy(bedJetRunConfirmed = false, confirmedCompletionAt = null, healthExportVersion = 0),
            completed("unknown").copy(events = valid.events + HistoryEvent("2026-10-27T06:00Z", EventKind.ERROR, "Unknown")),
            completed("stopped").copy(events = valid.events + HistoryEvent("2026-10-27T06:00Z", EventKind.STOPPED, "Stopped locally")),
            completed("unknown-device", Migrations.UNKNOWN_DEVICE),
            completed("future").copy(confirmedCompletionAt = "2026-10-28T07:15Z"),
            completed("reversed").copy(confirmedCompletionAt = "2026-10-26T21:00Z"),
            completed("no-version").copy(healthExportVersion = 0))
        (excluded + valid).forEach { graph.repository.addRecord(it) }
        val writer = Writer()
        val result = HealthSleepExporter(graph, { true }) { writer }.sync()
        assertEquals(HealthExportStatus.EXPORTED, result.status)
        assertEquals(1, result.exported)
        val session = writer.attempts.single()
        assertEquals("BedJet programme ran", session.title)
        assertTrue(session.notes.orEmpty().contains("not verified sleep", ignoreCase = true))
        assertTrue(session.stages.isEmpty())
        assertEquals(Instant.parse("2026-10-26T22:30:00Z"), session.startTime)
        assertEquals(Instant.parse("2026-10-27T07:15:00Z"), session.endTime)
        assertEquals(ZoneOffset.UTC, session.startZoneOffset)
        assertEquals(valid.healthExportVersion, session.metadata.clientRecordVersion)
        assertFalse(session.metadata.clientRecordId.orEmpty().contains("AA:BB"))
        assertEquals(valid.healthExportVersion, graph.repository.history.value.single { it.id == valid.id }.healthExportedVersion)
        assertTrue(graph.repository.history.value.filter { it.id != valid.id }.all { it.healthExportedVersion == 0L })
    }

    @Test fun activeTrackingCannotBeExportedEvenWithAnInventedCompletionField() = runBlocking {
        val graph = setup()
        val record = completed()
        graph.repository.addRecord(record)
        graph.repository.setActiveNight(ActiveNight(record.id, record.profileId, record.startedAt, record.wakeAt,
            false, 2, record.wakeAt, record.confirmedCompletionAt!!, record.deviceId, confidence = Confidence.UNKNOWN))
        val writer = Writer()
        HealthSleepExporter(graph, { true }) { writer }.sync()
        assertTrue(writer.attempts.isEmpty())
        assertEquals(0L, graph.repository.history.value.single().healthExportedVersion)
    }

    @Test fun stableClientIdSuppressesDuplicatesAndUpdatesTheSameSessionAtANewerVersion() = runBlocking {
        val graph = setup()
        val record = completed()
        graph.repository.addRecord(record)
        val writer = Writer()
        val exporter = HealthSleepExporter(graph, { true }) { writer }
        exporter.sync(); exporter.sync()
        assertEquals(1, writer.attempts.size)
        graph.repository.addRecord(graph.repository.history.value.single().copy(
            confirmedCompletionAt = "2026-10-27T07:20Z", healthExportVersion = record.healthExportVersion + 1))
        exporter.sync()
        assertEquals(2, writer.attempts.size)
        assertEquals(writer.attempts[0].metadata.clientRecordId, writer.attempts[1].metadata.clientRecordId)
        assertTrue(writer.attempts[1].metadata.clientRecordVersion > writer.attempts[0].metadata.clientRecordVersion)
        assertEquals(Instant.parse("2026-10-27T07:20:00Z"), writer.attempts[1].endTime)
    }

    @Test fun failedWriteKeepsNoSuccessMarkerAndManualRetryReusesItsClientIdentity() = runBlocking {
        val graph = setup(); graph.repository.addRecord(completed())
        val writer = Writer().apply { writing = { throw java.io.IOException("Provider unavailable") } }
        val exporter = HealthSleepExporter(graph, { true }) { writer }
        assertEquals(HealthExportStatus.FAILED, exporter.sync().status)
        assertEquals(0L, graph.repository.history.value.single().healthExportedVersion)
        writer.writing = {}
        assertEquals(HealthExportStatus.EXPORTED, exporter.sync().status)
        assertEquals(2, writer.attempts.size)
        assertEquals(writer.attempts[0].metadata.clientRecordId, writer.attempts[1].metadata.clientRecordId)
        assertEquals(writer.attempts[0].metadata.clientRecordVersion, writer.attempts[1].metadata.clientRecordVersion)
    }

    @Test fun permissionIsCheckedAndRevocationDuringWriteRemainsRetryable() = runBlocking {
        val graph = setup(); graph.repository.addRecord(completed())
        val writer = Writer().apply { permission = false }
        val exporter = HealthSleepExporter(graph, { true }) { writer }
        assertEquals(HealthExportStatus.PERMISSION, exporter.sync().status)
        assertTrue(writer.attempts.isEmpty())
        writer.permission = true
        writer.writing = { throw SecurityException("Permission revoked") }
        assertEquals(HealthExportStatus.PERMISSION, exporter.sync().status)
        assertEquals(0L, graph.repository.history.value.single().healthExportedVersion)
        writer.writing = {}
        assertEquals(HealthExportStatus.EXPORTED, exporter.sync().status)
        assertEquals(3, writer.permissionChecks)
    }

    @Test fun enteringDemoDuringPermissionReadPreventsTheWrite() = runBlocking {
        val graph = setup(); graph.repository.addRecord(completed())
        val writer = Writer().apply { permissionWork = { graph.repository.updateSettings { it.copy(demoMode = true) } } }
        val exporter = HealthSleepExporter(graph, { true }) { writer }
        assertEquals(HealthExportStatus.DEMO, exporter.sync().status)
        assertTrue(writer.attempts.isEmpty())
        assertEquals(0L, graph.repository.history.value.single().healthExportedVersion)
    }

    @Test fun automaticObservationMakesOnlyOneFailedAttemptUntilManualRetry() = runBlocking {
        val graph = setup(); graph.repository.addRecord(completed())
        val writer = Writer().apply { writing = { throw java.io.IOException("Provider unavailable") } }
        val exporter = HealthSleepExporter(graph, { true }) { writer }
        exporter.sync(manual = false)
        repeat(4) { exporter.sync(manual = false) }
        assertEquals(1, writer.attempts.size)
        writer.writing = {}
        exporter.sync()
        assertEquals(2, writer.attempts.size)
        assertTrue(graph.repository.history.value.single().healthExportedVersion > 0)
    }

    @Test fun aProviderSuccessCannotMarkAChangedLocalVersionAsExported() = runBlocking {
        val graph = setup(); val record = completed(); graph.repository.addRecord(record)
        val writer = Writer().apply { writing = {
            graph.repository.addRecord(record.copy(healthExportVersion = record.healthExportVersion + 1))
        } }
        val exporter = HealthSleepExporter(graph, { true }) { writer }
        exporter.sync()
        assertEquals(1, writer.attempts.size)
        assertEquals(0L, graph.repository.history.value.single().healthExportedVersion)
        writer.writing = {}
        exporter.sync()
        assertEquals(2, writer.attempts.size)
        assertEquals(writer.attempts[0].metadata.clientRecordId, writer.attempts[1].metadata.clientRecordId)
        assertEquals(record.healthExportVersion + 1, writer.attempts[1].metadata.clientRecordVersion)
        assertEquals(record.healthExportVersion + 1, graph.repository.history.value.single().healthExportedVersion)
    }

    @Test fun cancellationNeverMarksAnUnacknowledgedWriteSuccessful() = runBlocking {
        val graph = setup(); graph.repository.addRecord(completed())
        val writer = Writer().apply { writing = { throw CancellationException("Stopped") } }
        try {
            HealthSleepExporter(graph, { true }) { writer }.sync()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0L, graph.repository.history.value.single().healthExportedVersion)
    }
}

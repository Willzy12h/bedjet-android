package io.github.willzy12h.nocturne.platform

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.NightRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.time.ZonedDateTime

interface SleepSessionWriter {
    suspend fun permitted(): Boolean
    /** Success acknowledges an idempotent client-ID/version upsert, including an already stored equal version. */
    suspend fun upsert(record: SleepSessionRecord)
}
enum class HealthExportStatus { DISABLED, DEMO, ABSENT, PERMISSION, NOTHING, EXPORTED, FAILED }
data class HealthExportResult(val status: HealthExportStatus, val exported: Int = 0, val failed: Int = 0)

/** Optional programme activity export. No sensor sleep claim, stages, polling, or background retry loop. */
class HealthSleepExporter(private val graph: Graph,
    private val available: () -> Boolean = { HealthConnectClient.getSdkStatus(graph.context) == HealthConnectClient.SDK_AVAILABLE },
    private val writer: () -> SleepSessionWriter = { AndroidSleepWriter(graph.context) }) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(HealthExportResult(HealthExportStatus.DISABLED))
    val state = _state.asStateFlow()
    private val automaticallyAttempted = mutableSetOf<Pair<String, Long>>()

    private fun blocked(): HealthExportStatus? = when {
        graph.repository.settings.value.demoMode -> HealthExportStatus.DEMO
        !graph.repository.settings.value.platform.healthSleepExport -> HealthExportStatus.DISABLED
        else -> null
    }
    private fun publish(status: HealthExportStatus, exported: Int = 0, failed: Int = 0): HealthExportResult =
        HealthExportResult(status, exported, failed).also { _state.value = it }

    suspend fun sync(manual: Boolean = true): HealthExportResult = lock.withLock {
        blocked()?.let { return@withLock publish(it) }
        val history = graph.repository.history.value
        automaticallyAttempted.retainAll(history.map { it.id to it.healthExportVersion }.toSet())
        val pending = history.filter { eligible(it) && it.healthExportedVersion < it.healthExportVersion }
            .filter { manual || (it.id to it.healthExportVersion) !in automaticallyAttempted }
        if (pending.isEmpty()) return@withLock if (manual || _state.value.status in setOf(HealthExportStatus.DISABLED, HealthExportStatus.DEMO))
            publish(HealthExportStatus.NOTHING) else _state.value
        // A failed automatic pass is retried by Sync now or a later app process, not by each temperature/history update.
        automaticallyAttempted.addAll(pending.map { it.id to it.healthExportVersion })
        var exported = 0
        try {
            if (!available()) return@withLock publish(HealthExportStatus.ABSENT)
            val client = writer()
            for (record in pending) {
                blocked()?.let { return@withLock publish(it, exported) }
                if (!eligible(record) || graph.repository.history.value.none { it == record }) continue
                if (!withTimeout(PROVIDER_MS) { client.permitted() }) return@withLock publish(HealthExportStatus.PERMISSION, exported)
                blocked()?.let { return@withLock publish(it, exported) }
                // Permission UI and provider IPC can suspend. Recheck the exact local proof before sending.
                if (!eligible(record) || graph.repository.history.value.none { it == record }) continue
                withTimeout(PROVIDER_MS) { client.upsert(session(record)) }
                blocked()?.let { return@withLock publish(it, exported) }
                if (!graph.repository.markHealthExported(record)) return@withLock publish(HealthExportStatus.FAILED, exported, 1)
                exported++
            }
            publish(if (exported == 0) HealthExportStatus.NOTHING else HealthExportStatus.EXPORTED, exported)
        } catch (_: SecurityException) {
            publish(HealthExportStatus.PERMISSION, exported)
        } catch (_: TimeoutCancellationException) {
            publish(HealthExportStatus.FAILED, exported, 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A single failure stops this pass. Do not retry a failing provider once for every saved night.
            publish(HealthExportStatus.FAILED, exported, 1)
        }
    }

    private fun eligible(record: NightRecord): Boolean {
        if (record.isTestRun || DeviceId.isDemo(record.deviceId) || !REAL_DEVICE.matches(record.deviceId) ||
            !record.bedJetRunConfirmed || record.confirmedCompletionAt == null || record.healthExportVersion <= 0 ||
            graph.repository.activeNight.value?.recordId == record.id ||
            record.events.any { it.kind in UNCERTAIN_EVENTS } || record.events.none { it.kind == EventKind.ENDED }) return false
        val start = runCatching { ZonedDateTime.parse(record.startedAt).toInstant() }.getOrNull() ?: return false
        val end = runCatching { ZonedDateTime.parse(record.confirmedCompletionAt).toInstant() }.getOrNull() ?: return false
        return start < end && end <= graph.now().toInstant()
    }
    private fun session(record: NightRecord): SleepSessionRecord {
        val start = ZonedDateTime.parse(record.startedAt)
        val end = ZonedDateTime.parse(record.confirmedCompletionAt!!)
        val identity = "${record.deviceId}\u0000${record.id}"
        val clientId = "nocturne-night-" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return SleepSessionRecord(startTime = start.toInstant(), startZoneOffset = start.offset,
            endTime = end.toInstant(), endZoneOffset = end.offset,
            metadata = Metadata.unknownRecordingMethod(clientRecordId = clientId, clientRecordVersion = record.healthExportVersion),
            title = graph.context.getString(R.string.health_programme_title),
            notes = graph.context.getString(R.string.health_programme_notes), stages = emptyList())
    }
    private companion object {
        const val PROVIDER_MS = 15_000L
        val REAL_DEVICE = Regex("ble:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        val UNCERTAIN_EVENTS = setOf(EventKind.ERROR, EventKind.STOPPED, EventKind.REMOTE_CHANGE, EventKind.RECOVERED)
    }
}

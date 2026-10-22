package io.github.willzy12h.nocturne.platform
import io.github.willzy12h.nocturne.core.store.NightRecord
import java.time.Instant

data class SleepStage(val start: Instant, val end: Instant, val stage: Int)
enum class SleepAvailability { AVAILABLE, ABSENT, PERMISSION, EMPTY, ERROR }
data class SleepOverlay(val availability: SleepAvailability, val stages: List<SleepStage> = emptyList())
interface SleepSource {
    suspend fun permitted(): Boolean
    suspend fun sessions(start: Instant, end: Instant): List<SleepStage>
}
class SleepOverlayReader(private val available: () -> Boolean, private val source: () -> SleepSource) {
    suspend fun read(record: NightRecord): SleepOverlay {
        if (!available()) return SleepOverlay(SleepAvailability.ABSENT)
        return try {
            val client = source()
            if (!client.permitted()) return SleepOverlay(SleepAvailability.PERMISSION)
            val start = java.time.ZonedDateTime.parse(record.startedAt).toInstant()
            val end = java.time.ZonedDateTime.parse(record.wakeAt).toInstant()
            val stages = client.sessions(start, end).filter { it.end > start && it.start < end }
                .map { it.copy(start=maxOf(it.start,start), end=minOf(it.end,end)) }.sortedBy { it.start }
            SleepOverlay(if (stages.isEmpty()) SleepAvailability.EMPTY else SleepAvailability.AVAILABLE, stages)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: SecurityException) { SleepOverlay(SleepAvailability.PERMISSION)
        } catch (_: Exception) { SleepOverlay(SleepAvailability.ERROR) }
    }
}

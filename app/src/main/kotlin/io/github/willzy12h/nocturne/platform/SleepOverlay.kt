package io.github.willzy12h.nocturne.platform
import io.github.willzy12h.nocturne.core.store.NightRecord
import java.time.Instant

data class SleepStage(val start: Instant, val end: Instant, val stage: Int)
enum class SleepAvailability { AVAILABLE, ABSENT, PERMISSION, EMPTY, ERROR }
data class SleepOverlay(val availability: SleepAvailability, val stages: List<SleepStage> = emptyList())
interface SleepSource {
    suspend fun permitted(): Boolean
    suspend fun sessions(start: Instant, end: Instant): List<SleepStage>
    /** Other apps' sleep sessions ending in [start]..[end]; Nocturne's own exported sessions are left out. */
    suspend fun nights(start: Instant, end: Instant): List<io.github.willzy12h.nocturne.sleep.SleepNight> = emptyList()
}

data class SleepNights(
    val availability: SleepAvailability,
    val nights: List<io.github.willzy12h.nocturne.sleep.SleepNight> = emptyList(),
    val partial: Boolean = false,
)

/** Historical access can end inside the selected range. Read newer dates first so they survive that boundary. */
class SleepNightsReader(private val available: () -> Boolean, private val source: () -> SleepSource) {
    suspend fun read(start: Instant, end: Instant, comparisonStart: Instant = start, overlapStart: Instant = start): SleepNights {
        try {
            if (!available()) return SleepNights(SleepAvailability.ABSENT)
            val client = source()
            if (!client.permitted()) return SleepNights(SleepAvailability.PERMISSION)
            val nights = mutableListOf<io.github.willzy12h.nocturne.sleep.SleepNight>()
            var readable = false
            var partial = false
            var until = end
            // Read the preceding evening separately: a tracker can index an overnight session by its start.
            // A denied comparison week must not swallow a readable session ending on the first visible date.
            for (limit in listOf(start, overlapStart, comparisonStart).distinct()) {
                val optional = limit < overlapStart
                var window = java.time.Duration.ofDays(7)
                while (until > limit) {
                    val from = maxOf(limit, until.minus(window))
                    try {
                        nights += client.nights(from, until).filter { it.end > it.start }
                        readable = true
                        until = from
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!client.permitted()) return SleepNights(SleepAvailability.PERMISSION)
                        if (e is SecurityException && window.toDays() > 1 && !optional) {
                            // The historical permission boundary can bisect a week.
                            window = java.time.Duration.ofDays(1)
                            continue
                        }
                        // The current sleep permission was checked above. A failed read alone cannot prove revocation.
                        if (!readable) return SleepNights(SleepAvailability.ERROR)
                        partial = !optional
                        break
                    }
                }
                if (until > limit) break
            }
            if (!client.permitted()) return SleepNights(SleepAvailability.PERMISSION)
            val sorted = nights.distinctBy { it.sourceId ?: "${it.start}/${it.end}" }.sortedByDescending { it.start }
            return SleepNights(if (sorted.isEmpty()) SleepAvailability.EMPTY else SleepAvailability.AVAILABLE, sorted, partial)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: SecurityException) { return SleepNights(SleepAvailability.PERMISSION)
        } catch (_: Exception) { return SleepNights(SleepAvailability.ERROR) }
    }
}
class SleepOverlayReader(private val available: () -> Boolean, private val source: () -> SleepSource) {
    suspend fun read(record: NightRecord): SleepOverlay {
        if (record.isTestRun || io.github.willzy12h.nocturne.core.link.DeviceId.isDemo(record.deviceId))
            return SleepOverlay(SleepAvailability.EMPTY)
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

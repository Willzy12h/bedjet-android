package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedPublicationCancellationTest {
    private var now = ZonedDateTime.parse("2026-10-21T22:00:00Z")
    private val link = DemoBedJet { now }
    private var writes = 0
    private val observed = object : BedJetLink by link {
        override suspend fun send(command: ByteArray) { writes++; link.send(command) }
    }
    private val runner = NightRunner(BedJetController(observed) { now }) { now }
    private fun start(): ActiveNight = (runner.prepareStart(Presets.springAutumn, now.plusHours(9), 2, "demo", null, null) as Preparation.Ready).change.intended
    private fun change(night: ActiveNight): PreparedChange {
        now = now.plusHours(1)
        return (runner.reapply(night, night.profile!!, night.identity) as Preparation.Ready).change
    }

    @Test fun cancellationAsTheInitialPendingSaveCommitsRestoresTheExactPreviousSnapshot() = runTest {
        val old = start()
        val change = change(old)
        var current = old
        var durable = Json.encodeToString(old)
        val operation = launch {
            runner.apply(change, { current }, publish = {
                current = it
                durable = Json.encodeToString(it)
                if (it.confidence == Confidence.PENDING) {
                    currentCoroutineContext().cancel(CancellationException("cancel at durable pending publication"))
                    yield()
                }
            })
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(old, current, "The owner must repair PENDING before any reconciliation")
        assertEquals(old, Json.decodeFromString<ActiveNight>(durable))
        assertEquals(0, writes)
    }

    @Test fun aDifferentPendingRequestIsNotOverwrittenByCancelledCleanup() = runTest {
        val old = start()
        val change = change(old)
        val newer = old.copy(confidence = Confidence.PENDING,
            pending = change.request.copy(requestId = "newer-request"))
        var current = old
        val operation = launch {
            runner.apply(change, { current }, publish = { current = it }, beforeWrite = {
                current = newer
                throw CancellationException("another request owns the snapshot")
            })
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(newer, current)
        assertEquals(0, writes)
    }

    @Test fun cancellationAsTheConfirmedSnapshotCommitsNeverDowngradesIt() = runTest {
        link.connect()
        val old = start()
        val change = change(old)
        var current = old
        var durable = Json.encodeToString(old)
        val operation = launch {
            runner.apply(change, { current }, publish = {
                current = it
                durable = Json.encodeToString(it)
                if (it == change.intended) {
                    currentCoroutineContext().cancel(CancellationException("cancel at durable confirmation"))
                    yield()
                }
            })
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(change.intended, current)
        assertEquals(change.intended, Json.decodeFromString<ActiveNight>(durable))
        assertEquals(change.plan.programSteps, link.slots[2]!!.steps)
    }

    @Test fun cancellationAfterAPossibleWritePublishesUnknownBeforeReturning() = runTest {
        link.connect()
        val old = start()
        val change = change(old)
        var current = old
        val broken = object : BedJetLink by link {
            override suspend fun send(command: ByteArray) {
                link.send(command)
                throw CancellationException("lost write completion")
            }
        }
        val operation = launch {
            NightRunner(BedJetController(broken) { now }) { now }.apply(change, { current }, publish = { current = it })
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(Confidence.UNKNOWN, current.confidence)
        assertEquals(change.request.requestId, current.pending!!.requestId)
    }

    @Test fun cancelledCleanupHasABoundedNonCancellableGrace() = runTest {
        link.connect()
        val old = start()
        val change = change(old)
        var current = old
        val repairGate = CompletableDeferred<Unit>()
        var repairEntered = false
        val broken = object : BedJetLink by link {
            override suspend fun send(command: ByteArray) {
                link.send(command)
                throw CancellationException("interrupted write")
            }
        }
        val operation = launch {
            NightRunner(BedJetController(broken) { now }) { now }.apply(change, { current }, publish = {
                if (it.confidence == Confidence.UNKNOWN) {
                    repairEntered = true
                    repairGate.await()
                }
                current = it
            })
        }
        runCurrent()
        assertTrue(repairEntered)
        advanceTimeBy(8_001)
        runCurrent()
        val endedWithinGrace = operation.isCompleted
        repairGate.complete(Unit)
        operation.join()
        assertTrue(endedWithinGrace, "A blocked writer cannot keep cancelled transaction ownership forever")
    }
}

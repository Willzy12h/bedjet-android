package io.github.willzy12h.nocturne.night

import android.Manifest
import android.app.NotificationManager
import android.os.Looper
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.nullable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgrammeObservationRecoveryTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun aRetainedOffCannotEarnConfirmedCompletionWhenTheTrackingClockExpires() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        retainOffWhileUnknownSaveFails(f)
        val night = f.diskNight()
        val commands = f.sent.size
        try {
            f.at(ZonedDateTime.parse(night.endsAt))
            assertTrue("Local expiry remains available while the UNKNOWN write is unavailable", f.graph.actions.expire(night.identity))
            assertNull(f.graph.repository.activeNight.value)
            val record = f.graph.repository.history.value.single { it.id == night.recordId }
            assertTrue(record.events.any { it.kind == EventKind.ENDED })
            assertEquals(commands, f.sent.size)
            assertNull("Known interruption cannot become confirmed whole-night completion merely because the clock expired",
                record.confirmedCompletionAt)
            assertEquals(0L, record.healthExportVersion)
        } finally { f.beforeFileWrite = null }
    }

    @Test fun aRetainedOffIsNotAConfirmedCurrentProgrammeForServiceTimeoutFeedback() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        retainOffWhileUnknownSaveFails(f)
        try {
            assertNull("The service must not assure current confirmation from a file awaiting its UNKNOWN write",
                f.graph.actions.currentConfirmedProgramme())
        } finally { f.beforeFileWrite = null }
    }

    @Test fun aRetainedOffCannotBeDescribedAsUnchangedAfterAnAdmittedRetimeTimeout() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        retainOffWhileUnknownSaveFails(f)
        val night = f.diskNight()
        try {
            assertNull("A current known interruption cannot receive the unchanged-programme timeout assurance",
                f.graph.actions.quietRetimeTimeout(night.identity, f.target, admitted = true))
        } finally { f.beforeFileWrite = null }
    }

    @Test fun aKnownOffWithdrawsAnAlreadyPostedProgrammeWakeNoticeEvenWhenItsSaveFails() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        shadowOf(f.app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(f.app)
        assertEquals(OutcomeKind.CONFIRMED, f.start().kind)
        val night = f.diskNight()
        f.at(ZonedDateTime.parse(night.wakeUpStart))
        f.graph.actions.wakeUp(night.identity)
        val notices = shadowOf(f.app.getSystemService(NotificationManager::class.java))
        assertNotNull("This exercises withdrawal of an existing programme wake notice", notices.getNotification(2))
        failUnknownSave(f)
        try {
            val commands = f.sent.size
            f.remoteOff()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Confidence.UNKNOWN, f.graph.actions.status.value.confidence)
            assertEquals(Confidence.CONFIRMED, f.diskNight().confidence)
            assertEquals(commands, f.sent.size)
            assertNull("The old durable confirmation cannot keep a programme wake action visible after known Off",
                notices.getNotification(2))
        } finally { f.beforeFileWrite = null }
    }

    /** Fault only UNKNOWN publication: History, end journal and clearing the active night stay writable. */
    private fun failUnknownSave(f: ProgrammeOwnerFixture) {
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.toString(Charsets.UTF_8))?.confidence == Confidence.UNKNOWN)
                throw IOException("The UNKNOWN publication is unavailable")
        }
    }

    private suspend fun retainOffWhileUnknownSaveFails(f: ProgrammeOwnerFixture) {
        var observed = false
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                val night = BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.toString(Charsets.UTF_8))
                if (night?.confidence == Confidence.CONFIRMED && !observed) {
                    observed = true
                    runBlocking { withTimeout(2_000) { f.remoteOff() } }
                } else if (night?.confidence == Confidence.UNKNOWN) throw IOException("The UNKNOWN publication is unavailable")
            }
        }
        assertEquals(OutcomeKind.UNKNOWN, f.start().kind)
        assertTrue(observed)
        // Serialize after any bounded fallback writer before checking its retained process state.
        f.graph.repository.updateRuntime { it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Confidence.CONFIRMED, f.diskNight().confidence)
        assertEquals(Confidence.UNKNOWN, f.graph.actions.status.value.confidence)
        assertTrue(f.graph.actions.hasProgrammeUncertainty(f.graph.repository.activeNight.value))
    }

    @Test fun aKnownOffStillInvalidatesConfirmationAfterTheLinkDisconnects() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        var observed = false
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.CONFIRMED) {
                f.beforeFileWrite = null
                runBlocking { withTimeout(2_000) { f.remoteOff(); f.device.disconnect() } }
                observed = true
            }
        }
        val outcome = f.start()
        assertTrue(observed)
        assertEquals("Disconnection cannot erase an already-received current-target Off", Confidence.UNKNOWN, f.diskNight().confidence)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(1, f.starts())
    }

    @Test fun aFailedDeferredOffSaveStaysUnknownAndRecoversWithoutAnotherDeviceCommand() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        shadowOf(f.app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(f.app)
        f.graph.repository.updateSettings { it.copy(quietNightNotification = true) }
        val notices = shadowOf(f.app.getSystemService(NotificationManager::class.java))
        val failures = CopyOnWriteArrayList<Throwable>()
        // Observe exceptions at the application's coroutine boundary without letting a deliberate disk fault
        // kill the test runner. The graph keeps its real lifetime/job and dispatcher.
        ReflectionHelpers.setField(f.graph, "scope", CoroutineScope(f.graph.scope.coroutineContext +
            CoroutineExceptionHandler { _, error -> failures += error }))
        var elapsed = 0L
        f.graph.actions.elapsedMs = { elapsed }
        val attempts = AtomicInteger()
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.UNKNOWN) {
                attempts.incrementAndGet()
                throw IOException("The local unknown-state write is unavailable")
            }
        }
        val observed = CompletableDeferred<Unit>()
        var owned = false
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            f.graph.actions.busy.collect { busy ->
                if (busy.runningLabel != null) owned = true
                else if (owned && !observed.isCompleted) {
                    assertNotNull("The confirmed programme really posted its quiet notification", notices.getNotification(6))
                    f.remoteOff()
                    // Exercise the bounded handoff, without spending a real 98 seconds in a unit test.
                    elapsed = NightActions.OPERATION_MS + 8_000
                    observed.complete(Unit)
                }
            }
        }
        try {
            f.start()
            withTimeout(2_000) { observed.await() }
            withTimeout(4_000) {
                while (attempts.get() == 0) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            }
            // Finish the failed writer and dispatch its caller's completion/exception before checking it.
            f.graph.repository.updateRuntime { it }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A deferred observation save must not escape as an uncaught application exception: $failures", failures.isEmpty())
            assertEquals("The failed write is not a durable success", Confidence.CONFIRMED, f.diskNight().confidence)
            assertEquals("The UI must retain the known uncertainty while that write is unavailable", Confidence.UNKNOWN,
                f.graph.actions.status.value.confidence)
            f.graph.actions.refreshQuietNotification()
            assertNull("The old confirmed file must not repost a quiet-running notification", notices.getNotification(6))

            val beforeRewrite = f.sent.size
            val rewrite = f.graph.actions.execute(f.graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true))
            assertEquals(OutcomeKind.UNKNOWN, rewrite.kind)
            assertEquals("Uncertainty blocks a programme-preserving rewrite before device writes", beforeRewrite, f.sent.size)
            f.at(ZonedDateTime.parse(f.diskNight().wakeUpStart))
            f.graph.actions.wakeUp(f.diskNight().identity)
            assertNull("The retained contradiction must prevent a confirmed wake-up notification", notices.getNotification(2))
            assertNull(f.graph.repository.runtime.value.wakeShown)

            f.beforeFileWrite = null
            val commands = f.sent.size
            f.graph.actions.observeStatus(f.link.latestSourcedStatus()!!, f.target)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Confidence.UNKNOWN, f.diskNight().confidence)
            assertEquals("Reconciliation is local; it must not restart or otherwise change the BedJet", commands, f.sent.size)
            assertTrue(failures.isEmpty())
        } finally { f.beforeFileWrite = null; watcher.cancelAndJoin() }
    }

    @Test fun deferredOffCannotBeReplayedOverANewerNightRevision() = nextRevisionAfterDeferredOff(checkAdmission = false)
    @Test fun retainedUncertaintyPrecedesTheNextOwnerWhenCleanupGraceIsSpent() = nextRevisionAfterDeferredOff(checkAdmission = true)

    private fun nextRevisionAfterDeferredOff(checkAdmission: Boolean) = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        var elapsed = 0L
        f.graph.actions.elapsedMs = { elapsed }
        val observed = CompletableDeferred<Unit>()
        var owned = false
        var replacement: ActiveNight? = null
        var nextOwner: Job? = null
        var uncertainAtAdmission: Boolean? = null
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            f.graph.actions.busy.collect { busy ->
                if (busy.runningLabel != null) owned = true
                else if (owned && !observed.isCompleted) {
                    f.remoteOff()
                    val current = f.graph.repository.activeNight.value!!
                    replacement = current.copy(revision = current.revision + 1,
                        applied = current.applied.copy(revision = current.revision + 1))
                    // Queue the next owner before the old one unlocks. Its publication represents a later
                    // confirmed revision; the old Off receipt cannot supply evidence about that revision.
                    nextOwner = launch(start = CoroutineStart.UNDISPATCHED) {
                        ReflectionHelpers.getField<Mutex>(f.graph.actions, "lock").withLock {
                            // Inspect immediately at mutex admission, before connection or storage can suspend.
                            uncertainAtAdmission = ReflectionHelpers.callInstanceMethod<Boolean>(f.graph.actions,
                                "hasUnreconciledProgramme", ReflectionHelpers.ClassParameter.from(ActiveNight::class.java, current))
                            f.graph.repository.setActiveNight(replacement)
                        }
                    }
                    elapsed = NightActions.OPERATION_MS + 8_000
                    observed.complete(Unit)
                }
            }
        }
        try {
            f.start()
            withTimeout(4_000) { observed.await(); nextOwner!!.join() }
            shadowOf(Looper.getMainLooper()).idle()
            f.graph.repository.updateRuntime { it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(replacement, f.graph.repository.activeNight.value)
            assertEquals(replacement, f.diskNight())
            assertEquals(Confidence.CONFIRMED, f.diskNight().confidence)
            assertEquals(1, f.starts())
            if (checkAdmission) assertEquals("The next owner must see the known uncertainty before it can act", true, uncertainAtAdmission)
        } finally { watcher.cancelAndJoin() }
    }

    @Test fun anOlderPostConfirmationReportCannotUndoANewerReconciledReport() = reorderedReports(deliverOldAtRelease = true)
    @Test fun theNewestQueuedReceiptWinsWhenReportsArriveInOrder() = reorderedReports(deliverOldAtRelease = false)

    private fun reorderedReports(deliverOldAtRelease: Boolean) = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        var oldOff: SourcedStatus? = null
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.CONFIRMED) {
                f.beforeFileWrite = null
                runBlocking { withTimeout(2_000) {
                    f.device.send(Codec.setMode(Mode.STANDBY)!!)
                    oldOff = f.link.latestSourcedStatus()!!
                    f.device.send(Codec.startProgram(2))
                    val latest = f.link.latestSourcedStatus()!!
                    assertTrue(oldOff!!.receiptOrder < latest.receiptOrder)
                    if (!deliverOldAtRelease) f.observe(oldOff!!)
                    f.observe(latest)
                } }
            }
        }
        var owned = false
        val delivered = CompletableDeferred<Unit>()
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            f.graph.actions.busy.collect { busy ->
                if (busy.runningLabel != null) owned = true
                else if (owned && !delivered.isCompleted) {
                    if (deliverOldAtRelease) f.observe(oldOff!!)
                    delivered.complete(Unit)
                }
            }
        }
        try {
            val outcome = f.start()
            withTimeout(2_000) { delivered.await() }
            assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
            assertEquals("Delayed delivery cannot make an older receipt more authoritative than the newer report already reconciled",
                Confidence.CONFIRMED, f.diskNight().confidence)
            assertTrue(f.graph.repository.history.value.flatMap { it.events }.none { it.kind == EventKind.REMOTE_CHANGE })
            assertEquals(1, f.starts())
        } finally { watcher.cancelAndJoin() }
    }

    @Test fun aProgressTailCanStillEnrichTheMostRecentlyReconciledHead() = progressTail(cachedProgress = false)
    @Test fun aProgressTailCanReplaceCachedProgressOnTheSameReceipt() = progressTail(cachedProgress = true)

    private fun progressTail(cachedProgress: Boolean) = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        var head: SourcedStatus? = null
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.CONFIRMED) {
                f.beforeFileWrite = null
                f.at(f.started.plusSeconds(1))
                val fresh = f.link.latestSourcedStatus()!!
                head = fresh.copy(status = fresh.status.copy(extra = if (cachedProgress) fresh.status.extra else null))
                runBlocking { withTimeout(2_000) { f.observe(head!!) } }
            }
        }
        assertEquals(OutcomeKind.CONFIRMED, f.start().kind)
        assertEquals(Confidence.CONFIRMED, f.diskNight().confidence)
        val full = head!!.copy(status = head!!.status.copy(extra = f.link.latestSourcedStatus()!!.status.extra!!.copy(programStep = 0)))
        assertEquals(head!!.receiptOrder, full.receiptOrder)
        f.observe(full)
        assertEquals("A fresh head with unknown progress must still admit its same-receipt progress tail", Confidence.UNKNOWN,
            f.diskNight().confidence)
        assertEquals(1, f.starts())
    }

    @Test fun aFailedUnlockedOffSaveDoesNotTerminateTheStatusCollector() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        assertEquals(OutcomeKind.CONFIRMED, f.start().kind)
        val attempts = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.UNKNOWN) {
                attempts.incrementAndGet()
                throw IOException("The ordinary observer cannot save UNKNOWN yet")
            }
        }
        // The graph-lived collector uses the same original-report entry as App.startRuntime. Other platform
        // jobs are not needed to exercise this receipt/persistence boundary.
        val collector = f.graph.scope.launch(Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures += error },
            start = CoroutineStart.UNDISPATCHED) {
            f.link.sourcedStatus.collect { report -> if (report != null) f.graph.actions.observeStatus(report, f.target) }
        }
        try {
            val commands = f.sent.size
            f.device.send(Codec.setMode(Mode.STANDBY)!!)
            withTimeout(4_000) { while (attempts.get() == 0) delay(10) }
            f.graph.repository.updateRuntime { it }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A local save failure must not escape from the live status collector: $failures", failures.isEmpty())
            assertTrue("Later device reports still need a live collector", collector.isActive)
            assertEquals(Confidence.UNKNOWN, f.graph.actions.status.value.confidence)
            assertEquals(Confidence.CONFIRMED, f.diskNight().confidence)

            f.beforeFileWrite = null
            f.at(f.graph.now().plusSeconds(1))
            // A new harmless unit report drives retry of the retained Off; there is no new app command.
            f.device.send(Codec.setLed(false))
            withTimeout(4_000) {
                while (f.graph.repository.activeNight.value?.confidence != Confidence.UNKNOWN) delay(10)
            }
            assertEquals(Confidence.UNKNOWN, f.diskNight().confidence)
            assertEquals(commands, f.sent.size)
            assertTrue(collector.isActive)
            assertTrue(failures.isEmpty())
        } finally { f.beforeFileWrite = null; collector.cancelAndJoin() }
    }
}

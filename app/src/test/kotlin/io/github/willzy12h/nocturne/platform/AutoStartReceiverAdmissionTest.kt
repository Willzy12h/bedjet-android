package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.core.store.LoggedNotice
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue

/** An alarm can arrive while an import owns admission; refusing hardware still needs a truthful result. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartReceiverAdmissionTest : PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication() as App
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private var starts = 0
    @Volatile private var failNextFile: String? = null
    private var writeFailure: Throwable = IOException("test local write unavailable")
    @Volatile private var failedWrites = 0
    private val uncaught = ConcurrentLinkedQueue<Throwable>()
    private suspend fun setup(): Graph {
        val demo = DemoBedJet()
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                if (command[0] == 0x01.toByte() && command[1].toInt() and 0xff in 0x80..0x85) starts++
                demo.send(command)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite(); send(command)
            }
        }
        val graph = fresh { Graph(app, link) { scope, now -> Repository(app, scope, writeFile = { file, bytes ->
            if (file.name == failNextFile) {
                failNextFile = null
                failedWrites++
                throw writeFailure
            }
            Repository.writeDurably(file, bytes)
        }, now = now) } }
        // Keep the same supervised Job while observing exceptions that would otherwise escape the app scope.
        ReflectionHelpers.setField(graph, "scope", CoroutineScope(graph.scope.coroutineContext +
            CoroutineExceptionHandler { _, error -> uncaught += error }))
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T23:00:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = device,
            platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true,
                profileId = graph.repository.profiles.value.first().id, deviceId = device, fixedMinute = 23 * 60))) }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PlatformNotifications.channel(app)
        return graph
    }
    private fun ticket(graph: Graph) = AutoStartSchedule.due(graph)!!.ticket()
    private fun outcomes(graph: Graph) = graph.repository.notices.value.filter { it.title == app.getString(R.string.auto_start_outcome) }
    private fun shown() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(PlatformNotifications.OUTCOME_ID)
    private fun pendingJob() = app.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID)
    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("The real receiver must complete its bounded broadcast delivery", check())
    }
    private fun deliver(ticket: AutoStartTicket) {
        val receiver = AutoStartReceiver()
        val action = "test.nocturne.AUTO_START_ADMISSION"
        app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            app.sendBroadcast(ticket.putInto(Intent(action).setPackage(app.packageName)))
            until { shadowOf(receiver).wentAsync() }
            val pending = shadowOf(receiver).originalPendingResult
            assertNotNull(pending)
            until { Shadow.extract<ShadowBroadcastPendingResult>(pending).future.isDone }
            shadowOf(Looper.getMainLooper()).idle()
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun aValidDueAlarmRefusedByImportReportsOneSkippedResultWithoutAJobOrStart() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        assertNotNull(AutoStartSchedule.due(graph, ticket))
        val importing = graph.repository.operationAdmission.tryImport()!!
        try { deliver(ticket) } finally { importing.close() }
        assertEquals("Admission refusal must not silently discard this scheduled attempt", 1, outcomes(graph).size)
        val notice = outcomes(graph).single()
        assertEquals(NoticeKind.PROBLEM, notice.kind)
        assertTrue(notice.text, notice.text.contains("skipped", ignoreCase = true))
        assertTrue(notice.text, notice.text.contains("import", ignoreCase = true))
        assertEquals(notice.title, shown()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(notice.text, shown()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertNull(pendingJob())
        assertEquals(0, starts)
    }

    @Test fun repeatedDeliveryCannotDuplicateTheImportSkipOrReplayHardwareAfterImportFinishes() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        val importing = graph.repository.operationAdmission.tryImport()!!
        try {
            deliver(ticket)
            assertEquals("The first refusal needs its terminal result", 1, outcomes(graph).size)
            val initial = outcomes(graph); val notification = shown()
            deliver(ticket)
            assertEquals(initial, outcomes(graph))
            assertSame(notification, shown())
        } finally { importing.close() }
        val initial = outcomes(graph); val notification = shown()
        deliver(ticket)
        assertEquals("A delivered night is not an automatic retry after import", initial, outcomes(graph))
        assertSame(notification, shown())
        assertNull(pendingJob())
        assertEquals(0, starts)
    }

    @Test fun anExistingUnknownAttemptResultSurvivesRepeatedDeliveryDuringAndAfterImport() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        graph.repository.claimAutomation("charger:${ticket.key}")
        val notice = LoggedNotice(graph.now().toString(), NoticeKind.PROBLEM, app.getString(R.string.auto_start_outcome),
            "BedJet state is unknown after the accepted START.")
        graph.repository.addNotice(notice)
        PlatformNotifications.outcome(app, notice.title, notice.text)
        val notification = shown()
        val importing = graph.repository.operationAdmission.tryImport()!!
        try { deliver(ticket); deliver(ticket) } finally { importing.close() }
        deliver(ticket)
        assertEquals(listOf(notice), outcomes(graph))
        assertSame("A replay must not replace UNKNOWN with a skipped/import notice", notification, shown())
        assertNull("A previously claimed attempt must not enqueue a redundant hardware job", pendingJob())
        assertEquals(0, starts)
    }

    @Test fun tamperedOrExpiredTicketsDuringImportDoNotInventAnAttemptOutcome() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        val importing = graph.repository.operationAdmission.tryImport()!!
        try {
            deliver(ticket.copy(fingerprint = "0".repeat(64)))
            graph.clock = Clock.fixed(Instant.parse("2026-10-26T23:16:00Z"), ZoneOffset.UTC)
            deliver(ticket)
        } finally { importing.close() }
        assertTrue(outcomes(graph).isEmpty())
        assertNull(shown())
        assertTrue(graph.repository.automation.value.claims.isEmpty())
        assertNull(pendingJob())
        assertEquals(0, starts)
    }

    @Test fun aDuplicateOfAPendingJobDoesNotPublishAnImportFailure() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        assertTrue(ChargerJobs.enqueue(graph, ticket))
        val scheduled = pendingJob()!!
        val importing = graph.repository.operationAdmission.tryImport()!!
        try { deliver(ticket) } finally { importing.close() }
        deliver(ticket)
        assertEquals(scheduled, pendingJob())
        assertTrue(outcomes(graph).isEmpty())
        assertNull(shown())
        assertEquals(0, starts)
    }

    @Test fun failureToSaveTheSkipClaimDoesNotEscapeTheReceiverOrSendHardware() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        failNextFile = "automation.json"
        val importing = graph.repository.operationAdmission.tryImport()!!
        try { deliver(ticket) } finally { importing.close() }
        assertEquals("The receiver must encounter the injected claim write failure", 1, failedWrites)
        assertTrue("Storage failure must not escape the receiver into the app scope: $uncaught", uncaught.isEmpty())
        val message = shown()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("A failed local log cannot hide the truthful notification: $message", message.contains("skipped", ignoreCase = true))
        assertTrue(message, message.contains("import", ignoreCase = true))
        assertFalse(message, message.contains("confirmed", ignoreCase = true))
        assertTrue(graph.repository.automation.value.claims.isEmpty())
        assertTrue(outcomes(graph).isEmpty())
        assertNull(pendingJob())
        assertEquals(0, starts)
    }

    @Test fun failureToSaveTheNoticeKeepsTheDurableSkipAndDoesNotEscapeTheReceiver() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        failNextFile = "notices.json"
        val importing = graph.repository.operationAdmission.tryImport()!!
        try { deliver(ticket) } finally { importing.close() }
        assertEquals("The receiver must encounter the injected notice write failure", 1, failedWrites)
        assertTrue("Storage failure must not escape the receiver into the app scope: $uncaught", uncaught.isEmpty())
        assertTrue("The saved attempt claim must remain available for replay suppression",
            "auto-skip:${ticket.key}" in graph.repository.automation.value.claims)
        val notification = shown()
        val message = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(message, message.contains("skipped", ignoreCase = true))
        assertTrue(message, message.contains("import", ignoreCase = true))
        assertTrue(outcomes(graph).isEmpty())
        deliver(ticket)
        assertSame("A durable skip preserves its notification even when local notice storage failed", notification, shown())
        assertTrue(outcomes(graph).isEmpty())
        assertNull(pendingJob())
        assertEquals(0, starts)
    }

    @Test fun cancellationWhileSavingTheRefusalRemainsCancellationAndCannotQueueHardware() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        val interruption = CancellationException("test receiver cancellation")
        writeFailure = interruption
        failNextFile = "automation.json"
        val importing = graph.repository.operationAdmission.tryImport()!!
        try {
            try { ChargerJobs.deliver(graph, ticket); fail("Cancellation must propagate to the bounded receiver owner") }
            catch (error: CancellationException) {
                // Coroutine stack-trace recovery can copy a cancellation while retaining the original cause.
                assertSame(interruption, generateSequence<Throwable>(error) { it.cause }.last())
            }
        } finally { importing.close() }
        assertEquals(1, failedWrites)
        assertTrue(outcomes(graph).isEmpty())
        assertNull(shown())
        assertNull(pendingJob())
        assertEquals(0, starts)
    }

    @Test fun androidSchedulerRefusalReportsOneFailedResultAndNeverReplaysThatNight() = runBlocking {
        val graph = setup(); val ticket = ticket(graph)
        assertNotNull(AutoStartSchedule.due(graph, ticket))
        val scheduler = shadowOf(app.getSystemService(JobScheduler::class.java))
        scheduler.failOnJob(ChargerJobs.ID)
        try {
            deliver(ticket)
            assertEquals("Android's RESULT_FAILURE must not silently discard a valid due attempt", 1, outcomes(graph).size)
            val initial = outcomes(graph); val notice = initial.single(); val notification = shown()
            assertEquals(NoticeKind.PROBLEM, notice.kind)
            assertTrue(notice.text, notice.text.contains("failed", ignoreCase = true))
            assertTrue(notice.text, notice.text.contains("Android"))
            assertFalse(notice.text, notice.text.contains("confirmed", ignoreCase = true))
            assertEquals(notice.text, notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertNull(pendingJob())
            assertEquals(0, starts)
            // Let Android accept subsequent jobs: replay suppression must come from Nocturne's durable claim.
            ReflectionHelpers.getField<MutableSet<Int>>(scheduler, "jobsToFail").clear()
            deliver(ticket)
            assertEquals(initial, outcomes(graph))
            assertSame(notification, shown())
            assertNull("The failed night is terminal even after native scheduling becomes available", pendingJob())
            assertEquals(0, starts)
        } finally { ReflectionHelpers.getField<MutableSet<Int>>(scheduler, "jobsToFail").clear() }
    }
}

package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Real broadcasts must translate a cancelled durable delivery into the user-facing timeout resource. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiverTimeoutMessageTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    @Volatile private var timeoutNextRuntimeWrite = false
    @Volatile private var timedOutWrites = 0
    private var writes = 0

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) { scope, now -> Repository(app, scope, writeFile = { file, bytes ->
            if (file.name == "runtime.json" && timeoutNextRuntimeWrite) {
                timeoutNextRuntimeWrite = false
                timedOutWrites++
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
            Repository.writeDurably(file, bytes)
        }, now = now) } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 1
        graph.repository.updateSettings { it.copy(demoMode = true) }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Expected receiver delivery to finish", check())
    }

    private fun receiveAndAssertTimeout(action: String) {
        val night = graph.repository.activeNight.value!!
        val beforeWrites = writes
        val receiver = NightAlarmReceiver()
        app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        try {
            timeoutNextRuntimeWrite = true
            app.sendBroadcast(Intent(action).putExtra(NightAlarms.EXTRA_NIGHT, night.recordId)
                .putExtra(NightAlarms.EXTRA_REVISION, night.revision))
            until { manager.getNotification(3) != null }
            assertEquals("Exercise cancellation in a real local-delivery write", 1, timedOutWrites)
            val shown = manager.getNotification(3).extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            assertEquals("Receiver notices must never expose coroutine timeout internals",
                app.getString(R.string.runtime_operation_timeout), shown)
            until { graph.repository.notices.value.any { it.text == shown } }
            assertEquals(1, graph.repository.notices.value.count { it.text == shown })
            assertFalse(graph.repository.notices.value.any { it.text.contains("Timed out waiting for") })
            assertEquals("Local retry and failure handling cannot send hardware commands", beforeWrites, writes)
            assertEquals(night.identity, graph.repository.activeNight.value?.identity)
            assertTrue("The prearmed local retry remains available after storage timeout",
                shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
                    shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_LOCAL_RETRY
                })
        } finally { timeoutNextRuntimeWrite = false; app.unregisterReceiver(receiver) }
    }

    @Test fun wakeReceiverUsesReadableTimeoutNotice() {
        val night = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).toInstant(), ZoneOffset.UTC)
        receiveAndAssertTimeout(NightAlarms.ACTION_WAKE_UP)
    }

    @Test fun endReceiverUsesReadableTimeoutNotice() {
        val night = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.endsAt).toInstant(), ZoneOffset.UTC)
        receiveAndAssertTimeout(NightAlarms.ACTION_NIGHT_END)
    }

    @Test fun localRetryReceiverUsesReadableTimeoutNotice() = runBlocking {
        val night = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).toInstant(), ZoneOffset.UTC)
        graph.repository.updateRuntime { it.copy(localDeliveries = listOf(
            LocalDelivery("pending-wake", NightAlarms.ACTION_WAKE_UP, night.identity, graph.now().toString()))) }
        receiveAndAssertTimeout(NightAlarms.ACTION_LOCAL_RETRY)
        assertEquals("An undelivered wake stays available to the next retry", "pending-wake",
            graph.repository.runtime.value.localDeliveries.single().id)
    }
}

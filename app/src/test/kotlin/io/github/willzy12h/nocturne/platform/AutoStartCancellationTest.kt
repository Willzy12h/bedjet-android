package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.Preparation
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartCancellationTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private var beforeWrite: ((File, ByteArray) -> Unit)? = null
    private var starts = 0
    private suspend fun setup(wrap: (DemoBedJet) -> BedJetLink = { it }): Graph {
        lateinit var graph: Graph
        val demo = DemoBedJet(now = { graph.now() })
        val delegate = wrap(demo)
        val link = object : BedJetLink by delegate {
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                if (command[0] == 0x01.toByte() && command[1].toInt() and 0xff in 0x80..0x85) starts++
                delegate.sendGuarded(command, beforeWrite)
            }
        }
        graph = fresh { Graph(context, link) { scope, now ->
            Repository(context, scope, writeFile = { file, bytes ->
                beforeWrite?.invoke(file, bytes)
                Repository.writeDurably(file, bytes)
            }, now = now)
        } }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = device,
            platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true,
                profileId = graph.repository.profiles.value.first().id, deviceId = device, fixedMinute = 22 * 60 + 30))) }
        graph.actions.confirmMs = 1
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PlatformNotifications.channel(context)
        return graph
    }
    private fun runner(graph: Graph, grace: suspend () -> Unit = {}) =
        ChargerRun(graph, { true }, { _, _ -> true }, grace, { true })
    private fun outcomes(graph: Graph) = graph.repository.notices.value.filter { it.title == context.getString(R.string.auto_start_outcome) }
    private suspend fun timeout(): TimeoutCancellationException = try {
        withTimeout(1) { awaitCancellation() }
    } catch (e: TimeoutCancellationException) { e }

    @Test fun ordinaryCancellationAfterAcceptedStartRetainsOneUnknownOutcomeAndCannotReplay() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val graph = setup { demo -> object : BedJetLink by demo {
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite(); demo.send(command)
                if (command[0] == 0x01.toByte() && command[1].toInt() and 0xff in 0x80..0x85) {
                    accepted.complete(Unit)
                    awaitCancellation()
                }
            }
        } }
        val job = launch { runner(graph).execute() }
        withTimeout(10_000) { accepted.await() }
        job.cancelAndJoin()
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertEquals("A stopped JobService child must retain exactly one truthful terminal outcome", 1, outcomes(graph).size)
        val notice = outcomes(graph).single()
        assertEquals(NoticeKind.PROBLEM, notice.kind)
        assertTrue(notice.text, notice.text.contains("unknown", ignoreCase = true))
        assertEquals(1, starts)
        runner(graph).execute()
        assertEquals("The durable attempt claim prevents another START after cancellation", 1, starts)
    }

    @Test fun timeoutAfterExactConfirmedPublicationReportsConfirmedWithLocalBookkeepingCaveat() = runBlocking {
        val graph = setup()
        val interruption = timeout()
        var published: ActiveNight? = null
        beforeWrite = { file, _ ->
            if (file.name == "history.json" && graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED) {
                published = graph.repository.activeNight.value
                beforeWrite = null
                throw interruption
            }
        }
        try {
            runner(graph).execute()
            fail("The local settlement timeout must propagate")
        } catch (_: TimeoutCancellationException) { } finally { beforeWrite = null }
        assertNotNull("The timeout must occur after this exact run was durably CONFIRMED", published)
        assertEquals(published, graph.repository.activeNight.value)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
        assertEquals(1, starts)
        assertEquals(1, outcomes(graph).size)
        val notice = outcomes(graph).single()
        assertEquals("A local bookkeeping timeout cannot undo the receipt-confirmed hardware outcome", NoticeKind.INFO, notice.kind)
        assertTrue(notice.text, notice.text.contains("confirmed", ignoreCase = true))
        assertTrue("The post-confirmation interruption needs a truthful local caveat: ${notice.text}",
            notice.text.contains("local", ignoreCase = true))
    }

    @Test fun aDifferentConfirmedRecordCannotBeAttributedToAnInterruptedAutomaticAttempt() = runBlocking {
        val interruption = timeout()
        lateinit var graph: Graph
        var unrelated: ActiveNight? = null
        graph = setup { demo -> object : BedJetLink by demo {
            override suspend fun prepareForStart() {
                demo.prepareForStart()
                val profile = graph.repository.profiles.value.first()
                val ready = graph.runner().prepareStart(profile, null, graph.repository.settings.value.workingSlot,
                    device, null, null) as Preparation.Ready
                unrelated = ready.change.intended
                graph.repository.setActiveNight(unrelated)
                throw interruption
            }
        } }
        try { runner(graph).execute(); fail("Final preparation must be interrupted") } catch (_: TimeoutCancellationException) { }
        assertEquals(unrelated, graph.repository.activeNight.value)
        assertEquals(0, starts)
        val notice = outcomes(graph).single()
        assertEquals(NoticeKind.PROBLEM, notice.kind)
        assertFalse("Another record's confirmation is not this attempt's receipt", notice.text.contains("started and confirmed", ignoreCase = true))
    }

    @Test fun aSystemReplayCannotReplaceAnUnknownTerminalResultWithASkippedNotice() = runBlocking {
        val graph = setup { demo -> object : BedJetLink by demo {
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite(); demo.send(command)
                if (command[0] == 0x01.toByte() && command[1].toInt() and 0xff in 0x80..0x85)
                    withTimeout(1) { awaitCancellation() }
            }
        } }
        try { runner(graph).execute(); fail("The accepted START must time out") } catch (_: TimeoutCancellationException) { }
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        val initial = outcomes(graph)
        assertEquals(1, initial.size)
        assertTrue(initial.single().text.contains("unknown", ignoreCase = true))
        runner(graph).execute()
        assertEquals("The durable attempt claim preserves this attempt's terminal result", initial, outcomes(graph))
        assertEquals(1, starts)
    }

    @Test fun cancellationDuringChargingGraceDoesNotInventAHardwareAttempt() = runBlocking {
        val graph = setup()
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(onlyIfCharging = true))) }
        val waiting = CompletableDeferred<Unit>()
        val job = launch { runner(graph) { waiting.complete(Unit); awaitCancellation() }.execute() }
        withTimeout(10_000) { waiting.await() }
        job.cancelAndJoin()
        assertNull(graph.repository.activeNight.value)
        assertEquals(0, starts)
        assertTrue(outcomes(graph).isEmpty())
        assertTrue(graph.repository.automation.value.claims.none { it.startsWith("charger:") })
    }
}

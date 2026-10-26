package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.Preparation
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.ui.AppViewModel
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmWindowExplanationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val now = ZonedDateTime.parse("2026-10-26T22:30:00Z")
    @Test fun fiveMinuteAlarmSaysTooSoonAndKeepsTheNormalMinimum() {
        val explanation = AlarmExplanation.resolve(Settings(), Presets.springAutumn, now,
            AlarmSource.NextAlarm(now.plusMinutes(5), "com.sec.android.app.clockpackage", true))
        val text = explanation.description(RuntimeEnvironment.getApplication())
        assertEquals(AlarmReason.OUTSIDE_WINDOW, explanation.reason)
        assertTrue(text, text.contains("too soon"))
        assertTrue(text, text.contains("1 hour"))
    }
    @Test fun tooLateExplainsTheNewEighteenHourMaximum() {
        val text = AlarmExplanation.resolve(Settings(), Presets.springAutumn, now,
            AlarmSource.NextAlarm(now.plusHours(18).plusMinutes(1), null, true))
            .description(RuntimeEnvironment.getApplication())
        assertTrue(text, text.contains("18 hours"))
    }

    @Test fun recheckPublishesFreshAndroidResultBeforeWaitingForTheBedJetOwner() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = graphs.fresh { Graph(app, DemoBedJet { now }) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(now.toInstant(), now.zone)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val models = ViewModelStore()
        val model = AppViewModel(app)
        models.put("recheck", model)
        model.recheckAlarm()
        val previous = model.alarmCheck.value
        val night = (graph.runner().prepareStart(Presets.springAutumn, now.plusHours(8), 2,
            DeviceId.DEMO, null, null) as Preparation.Ready).change.intended
        graph.repository.setActiveNight(night)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.Default) { graph.actions.exclusive { held.complete(Unit); release.await() } }
        try {
            until { held.isCompleted }
            val fire = PendingIntent.getBroadcast(app, 614, Intent("test.blocked.recheck"), PendingIntent.FLAG_IMMUTABLE)
            shadowOf(fire).setCreatorPackage("com.sec.android.app.clockpackage")
            app.getSystemService(AlarmManager::class.java).setAlarmClock(
                AlarmManager.AlarmClockInfo(now.plusMinutes(5).toInstant().toEpochMilli(), fire), fire)
            model.recheckAlarm()
            until { graph.actions.busy.value.queuedCount == 1 }
            assertEquals("com.sec.android.app.clockpackage", model.nextAlarm.value?.creator)
            val result = model.alarmCheck.value.orEmpty()
            assertNotEquals("Last check must update while the hardware owner is still occupied", previous, result)
            assertTrue(result, result.contains("Samsung Clock"))
            assertTrue(result, result.contains("too soon"))
            assertFalse("The earlier owner remains occupied during the assertion", release.isCompleted)
        } finally {
            models.clear()
            release.complete(Unit)
            owner.cancelAndJoin()
        }
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("The controlled owner did not reach its expected state", check())
    }
}

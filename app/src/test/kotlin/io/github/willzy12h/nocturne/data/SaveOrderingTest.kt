package io.github.willzy12h.nocturne.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.ui.AppViewModel
import io.github.willzy12h.nocturne.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZonedDateTime

/** Saves happen off the main thread; what follows a save must see the saved value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SaveOrderingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = compose.activity.graph
    private var previousClock: java.time.Clock? = null

    @org.junit.Before
    fun fixedNightClock() {
        previousClock = graph.clock
        graph.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T22:30:00Z"), java.time.ZoneOffset.UTC)
    }

    @org.junit.After
    fun restoreClock() { previousClock?.let { graph.clock = it } }


    @Before
    fun demoMode() {
        compose.runOnUiThread {
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true) }
                graph.repository.setActiveNight(null)
            }
        }
    }

    private fun idleUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        condition()
    }

    @Test
    fun updateTonightPutsTheEditedProfileOnTheBedJet() {
        val profile = graph.repository.profiles.value.first()
        compose.runOnUiThread { runBlocking { graph.actions.perform(Command.START_NIGHT, profile.id) } }
        val night = graph.repository.activeNight.value!!
        val demo = graph.link as DemoBedJet
        assertEquals(profile.name, demo.slots[night.slot]!!.name)

        compose.onNodeWithText("Profiles").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(profile.id)
        compose.onNodeWithTag("profile-open-${profile.id}").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(profile.name)).performTextReplacement("Edited night")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Update tonight").performClick()

        idleUntil { graph.repository.history.value.single().events.size > 1 }
        assertEquals("Edited night", graph.repository.profile(profile.id)!!.name)
        assertEquals("Edited night", demo.slots[night.slot]!!.name)
    }

    @Test
    fun trustingAnAlarmAppUpdatesTheNextAlarmAtOnce() {
        val context = compose.activity.applicationContext
        // Created by this app, which isn't one of the allowed clock apps.
        val show = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        val at = graph.now().plusHours(6).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        val model = AppViewModel(compose.activity.application)
        model.refreshAlarm()
        val creator = model.nextAlarm.value!!.creator!!
        assertFalse(model.nextAlarm.value!!.counts)

        compose.runOnUiThread { model.useAlarmsFrom(creator) }

        idleUntil { creator in graph.repository.settings.value.alarmPackages }
        // Once saved, the next alarm shown follows the saved setting without another refresh.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(model.nextAlarm.value!!.counts)
    }
}

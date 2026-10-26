package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/** Tests the actual Tonight callbacks; MainActivity's Settings scroll destination is an integration check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AlarmExplanationActionTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    private val timezone = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try { base.evaluate() } finally { TimeZone.setDefault(original) }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(timezone).around(ui.rules)
    private var sourceRequests = 0
    private val profileRequests = mutableListOf<Profile>()

    private fun show(unknown: Boolean = false, profileOnly: Boolean = false, savedWake: Boolean = false) {
        val graph = ui.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
        ui.compose.runOnIdle { runBlocking {
            val profile = graph.repository.tonightsProfile()!!.let {
                it.copy(wakeSource = it.wakeSource.copy(useAlarm = !profileOnly, defaultWakeMinute = 7 * 60))
            }
            graph.repository.saveProfile(profile)
            graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()).copy(acceptUnknownAlarmSource = false) }
            if (savedWake) {
                val wake = graph.now().plusDays(1).withHour(7).withMinute(0)
                val plan = PlanCompiler.compile(profile, graph.now(), wake)
                graph.repository.setActiveNight(ActiveNight("saved", profile.id, graph.now().toString(), wake.toString(),
                    true, 2, plan.wakeUpStart.toString(), plan.end.toString(), deviceId = "demo", profile = profile,
                    confidence = Confidence.CONFIRMED))
            }
            val pending = PendingIntent.getBroadcast(ui.compose.activity, 82, Intent("test.alarm.action"), PendingIntent.FLAG_IMMUTABLE)
            shadowOf(pending).setCreatorPackage("com.sec.android.app.clockpackage")
            ui.compose.activity.getSystemService(AlarmManager::class.java).setAlarmClock(
                AlarmManager.AlarmClockInfo(Instant.parse("2026-10-23T06:30:00Z").toEpochMilli(), pending.takeUnless { unknown }), pending)
            ui.model.refreshAlarm()
        } }
        val model = ui.model
        ui.compose.runOnUiThread {
            ui.compose.activity.setContent {
                NocturneTheme {
                    TonightScreen(model, Modifier, onEdit = { profileRequests += it },
                        onOpenAlarmSettings = { sourceRequests++ })
                }
            }
        }
        ui.compose.waitForIdle()
    }

    @Test fun unknownSourceOpensAlarmSettingsWithoutEditingAProfile() {
        show(unknown = true)
        ui.compose.onNodeWithText("Alarm 06:30 ignored: source unknown — tap to change").performScrollTo().performClick()
        assertEquals(1, sourceRequests)
        assertEquals(emptyList<Profile>(), profileRequests)
    }

    @Test fun profileAlarmOptOutOpensThatProfilesWakeSettings() {
        show(profileOnly = true)
        ui.compose.onNodeWithText("Alarm 06:30 ignored: alarms off for this profile — tap to change").performScrollTo().performClick()
        assertEquals(0, sourceRequests)
        assertEquals(listOf(ui.model.tonightsProfile()), profileRequests)
    }

    @Test fun changedAlarmOnASavedWakeOpensSourcesWithoutImplyingAProfileEditAppliesTonight() {
        show(savedWake = true)
        ui.compose.onNodeWithText("Saved alarm wake 07:00 · Next alarm 06:30 from Samsung Clock not applied").performScrollTo().performClick()
        assertEquals(1, sourceRequests)
        assertEquals(emptyList<Profile>(), profileRequests)
        assertEquals("07:00", Format.clock(ui.graph.repository.activeNight.value!!.wakeAt))
    }
}

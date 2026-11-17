package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.UnnamedAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun show(unknown: Boolean = false, profileOnly: Boolean = false, savedWake: Boolean = false, noAlarm: Boolean = false) {
        val graph = ui.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
        ui.compose.runOnIdle { runBlocking {
            val profile = graph.repository.tonightsProfile()!!.let {
                it.copy(wakeSource = it.wakeSource.copy(useAlarm = !profileOnly, defaultWakeMinute = 7 * 60))
            }
            graph.repository.saveProfile(profile)
            graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()) }
            if (savedWake) {
                val wake = graph.now().plusDays(1).withHour(7).withMinute(0)
                val plan = PlanCompiler.compile(profile, graph.now(), wake)
                graph.repository.setActiveNight(ActiveNight("saved", profile.id, graph.now().toString(), wake.toString(),
                    true, 2, plan.wakeUpStart.toString(), plan.end.toString(), deviceId = "demo", profile = profile,
                    confidence = Confidence.CONFIRMED))
            }
            if (noAlarm) { ui.model.refreshAlarm(); return@runBlocking }
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

    @Test fun unknownSourceExplainsTheMissingAlarmAndStillOffersTheAlarmAppChoice() {
        show(unknown = true)
        ui.compose.onNodeWithText("Alarm 06:30 from an unnamed app — tap to use it tonight").performScrollTo().assertIsDisplayed().performClick()
        ui.compose.onNodeWithTag("missing-alarm-dialog").assertExists()
        ui.compose.onNodeWithTag("missing-alarm-tonight").assertExists()
        ui.compose.onNodeWithTag("missing-alarm-sources").performScrollTo().performClick()
        ui.compose.onNodeWithTag("missing-alarm-dialog").assertDoesNotExist()
        ui.compose.onNodeWithTag("alarm-apps-dialog").assertExists()
        assertEquals(0, sourceRequests)
        assertEquals(emptyList<Profile>(), profileRequests)
    }

    @Test fun noAlarmAsksWhetherTheAlarmIsInAnotherApp() {
        show(noAlarm = true)
        ui.compose.onNodeWithText("No alarm found — profile wake 07:00 · Alarm in another app?").assertIsDisplayed().performClick()
        ui.compose.onNodeWithTag("missing-alarm-dialog").assertExists()
        ui.compose.onNodeWithText("Saved on your BedJet, so it still wakes you if your phone closes Nocturne.", substring = true)
            .assertExists()
        ui.compose.onNodeWithTag("missing-alarm-sources").assertDoesNotExist()
        val profile = ui.model.tonightsProfile()!!
        ui.compose.onNodeWithTag("missing-alarm-fixed").performScrollTo().performClick()
        assertEquals(listOf(profile), profileRequests)
        ui.compose.onNodeWithTag("missing-alarm-dialog").assertDoesNotExist()
    }

    @Test fun setTonightsWakeFromTheMissingAlarmDialogOpensTheTimePicker() {
        show(noAlarm = true)
        ui.compose.onNodeWithText("No alarm found — profile wake 07:00 · Alarm in another app?").performClick()
        ui.compose.onNodeWithTag("missing-alarm-tonight").performScrollTo().performClick()
        ui.compose.onNodeWithTag("missing-alarm-dialog").assertDoesNotExist()
        ui.compose.waitForIdle()
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog() is android.app.TimePickerDialog)
        assertEquals(emptyList<Profile>(), profileRequests)
    }

    @Test fun askEachNightUsesTheUnnamedAlarmForTonightOnlyAfterATap() {
        show(unknown = true)
        // Not used by itself: tonight still ends at the profile's wake time until the person taps.
        assertEquals("07:00", Format.clock(ui.model.startPreview(ui.model.tonightsProfile()!!).wake.time))
        ui.compose.onNodeWithText("Alarm 06:30 from an unnamed app — tap to use it tonight").performScrollTo().performClick()
        ui.compose.onNodeWithTag("missing-alarm-use-unnamed").performScrollTo().assertIsDisplayed().performClick()
        ui.compose.onNodeWithTag("missing-alarm-dialog").assertDoesNotExist()
        ui.waitUntil { ui.graph.repository.settings.value.tonightOverride != null }
        assertEquals("06:30", Format.clock(ui.graph.repository.settings.value.tonightOverride!!.wakeAt))
        assertEquals(UnnamedAlarms.ASK, ui.graph.repository.settings.value.unnamedAlarms)
        assertEquals(emptyList<Profile>(), profileRequests)
    }

    @Test fun ignoringUnnamedAlarmsOffersNoOneTapUse() {
        ui.compose.runOnIdle { runBlocking { ui.graph.repository.updateSettings { it.copy(unnamedAlarms = UnnamedAlarms.IGNORE) } } }
        show(unknown = true)
        ui.compose.onNodeWithText("Alarm 06:30 ignored: source unknown — tap to change").performScrollTo().performClick()
        ui.compose.onNodeWithTag("missing-alarm-dialog").assertExists()
        ui.compose.onNodeWithTag("missing-alarm-use-unnamed").assertDoesNotExist()
    }

    @Test fun alwaysUsingUnnamedAlarmsIsConfirmedFirst() {
        show(unknown = true)
        ui.compose.onNodeWithText("Alarm 06:30 from an unnamed app — tap to use it tonight").performScrollTo().performClick()
        ui.compose.onNodeWithTag("missing-alarm-sources").performScrollTo().performClick()
        ui.compose.onNodeWithTag("unnamed-alarms-ask").performScrollTo().assertIsDisplayed()
        ui.compose.onNodeWithTag("unnamed-alarms-always").performScrollTo().performClick()
        ui.compose.onNodeWithTag("unnamed-alarms-confirm").assertExists()
        assertEquals(UnnamedAlarms.ASK, ui.graph.repository.settings.value.unnamedAlarms)
        ui.compose.onNodeWithTag("unnamed-alarms-confirm-yes").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.unnamedAlarms == UnnamedAlarms.ALWAYS }
        ui.waitUntil { ui.model.nextAlarm.value?.counts == true }
        assertEquals("06:30", Format.clock(ui.model.startPreview(ui.model.tonightsProfile()!!).wake.time))
    }

    @Test fun profileAlarmOptOutOpensThatProfilesWakeSettings() {
        show(profileOnly = true)
        ui.compose.onNodeWithText("Alarm 06:30 ignored: alarms off for this profile — tap to change").assertIsDisplayed().performClick()
        assertEquals(0, sourceRequests)
        assertEquals(listOf(ui.model.tonightsProfile()), profileRequests)
    }

    @Test fun changedAlarmOnASavedWakeOpensTheAlarmAppChoiceWithoutImplyingAProfileEditAppliesTonight() {
        show(savedWake = true)
        ui.compose.onNodeWithText("Saved alarm wake 07:00 · Next alarm 06:30 from Samsung Clock not applied").assertIsDisplayed().performClick()
        ui.compose.onNodeWithTag("alarm-apps-dialog").assertExists()
        assertEquals(0, sourceRequests)
        assertEquals(emptyList<Profile>(), profileRequests)
        assertEquals("07:00", Format.clock(ui.graph.repository.activeNight.value!!.wakeAt))
    }

    @Test fun theAlarmAppChoiceOffersAFixedWakeTimeForTonightsProfile() {
        show(unknown = true)
        ui.compose.onNodeWithText("Alarm 06:30 from an unnamed app — tap to use it tonight").performClick()
        ui.compose.onNodeWithTag("missing-alarm-sources").performScrollTo().performClick()
        val profile = ui.model.tonightsProfile()!!
        ui.compose.onNodeWithText("Use a fixed wake time for ${profile.name}").performScrollTo().performClick()
        assertEquals(listOf(profile), profileRequests)
        ui.compose.onNodeWithTag("alarm-apps-dialog").assertDoesNotExist()
    }
}

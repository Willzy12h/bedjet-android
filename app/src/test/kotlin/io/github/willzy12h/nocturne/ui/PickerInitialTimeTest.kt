package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TimePicker
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.WakeSource
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** R1: inspect and accept the actual native pickers opened by the two production screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PickerInitialTimeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val profile = Presets.summerCool.copy(id = "picker-profile", name = "Picker profile",
        bedtimeMinute = 23 * 60 + 5, wakeSource = WakeSource(useAlarm = false, defaultWakeMinute = 6 * 60 + 10))
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            val graph = app.graph
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T23:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.setActiveNight(null)
                graph.repository.saveProfile(profile)
                graph.repository.updateSettings {
                    it.copy(demoMode = true, themeMode = ThemeMode.DARK, simpleView = false, tonightOverride = null)
                        .chooseTonight(profile.id, graph.now())
                }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(GraphLifecycle()).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun save(block: suspend Repository.() -> Unit) {
        val repository = graph.repository
        compose.runOnUiThread { runBlocking { repository.block() } }
        compose.waitForIdle()
    }
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); condition()
    }
    private fun picker(): Pair<TimePickerDialog, TimePicker> {
        val dialog = ShadowDialog.getLatestDialog() as TimePickerDialog
        assertTrue("The production time picker must be open", dialog.isShowing)
        fun find(view: View): TimePicker? = when (view) {
            is TimePicker -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return dialog to checkNotNull(find(dialog.window!!.decorView))
    }
    private fun assertTime(picker: TimePicker, hour: Int, minute: Int) {
        assertEquals("Picker hour must start at the existing wake or field value", hour, picker.hour)
        assertEquals("Picker minute must start at the existing wake or field value", minute, picker.minute)
    }
    private fun accept(dialog: TimePickerDialog) {
        compose.runOnUiThread { dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        // Native dialog callbacks write Compose state outside its test clock. Deliver the normal snapshot
        // notifications and both frame queues before waiting for the editor and its draft callback to settle.
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }
    @After fun closePicker() {
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { dialog ->
            compose.runOnUiThread { dialog.dismiss() }
        }
    }

    @Test fun existingTonightOverrideOpensAt0630AndUnchangedAcceptanceKeeps0630() {
        val now = graph.now()
        val wake = ZonedDateTime.parse("2026-10-23T06:30:00Z")
        save { updateSettings { it.setWakeOverride(wake, now.minusMinutes(1)) } }
        compose.onNodeWithText(s(R.string.tonight_change_wake, "06:30")).performScrollTo().performClick()

        val (dialog, picker) = picker()
        assertTime(picker, 6, 30)
        accept(dialog)

        waitUntil { graph.repository.settings.value.tonightOverride?.setAt == now.toString() }
        assertEquals(wake, ZonedDateTime.parse(graph.repository.settings.value.tonightOverride!!.wakeAt))
        compose.onNodeWithText(s(R.string.tonight_change_wake, "06:30")).assertExists()
    }

    @Test fun noOverrideOpensAtTheSelectedProfilesPlannedWake() {
        val other = profile.copy(id = "other-picker-profile", name = "Other picker profile",
            wakeSource = profile.wakeSource.copy(defaultWakeMinute = 8 * 60 + 20))
        save { saveProfile(other) }
        compose.onNodeWithText(other.name).performScrollTo().performClick()
        waitUntil { graph.repository.settings.value.tonightChoice?.profileId == other.id }
        compose.onNodeWithText(s(R.string.tonight_set_wake)).performScrollTo().performClick()

        val (dialog, picker) = picker()
        assertTime(picker, 8, 20)
        accept(dialog)

        waitUntil { graph.repository.settings.value.tonightOverride != null }
        assertEquals(ZonedDateTime.parse("2026-10-23T08:20:00Z"),
            ZonedDateTime.parse(graph.repository.settings.value.tonightOverride!!.wakeAt))
    }

    @Test fun runningNightPickerUsesItsPlannedWakeAfterTheProfileIsEditedForLater() {
        compose.onNodeWithText(s(R.string.tonight_start_now)).performScrollTo().performClick()
        waitUntil { graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED && graph.actions.busy.value.runningLabel == null }
        assertEquals("06:10", Format.clock(graph.repository.activeNight.value!!.wakeAt))
        save { saveProfile(profile.copy(wakeSource = profile.wakeSource.copy(defaultWakeMinute = 8 * 60 + 20))) }
        compose.onNodeWithText(s(R.string.tonight_set_wake)).performScrollTo().performClick()

        assertTime(picker().second, 6, 10)
    }

    @Test fun withoutAnyProfileOrOverridePickerFallsBackToTheInjectedClock() {
        val ids = graph.repository.profiles.value.map { it.id }
        save { ids.forEach { deleteProfile(it) } }
        compose.onNodeWithText(s(R.string.tonight_set_wake)).performScrollTo().performClick()

        assertTime(picker().second, 23, 30)
    }

    @Test fun profileBedtimePickerUsesTheCurrentFieldAndUnchangedAcceptancePreservesIt() {
        compose.onNodeWithText(s(R.string.action_edit)).performScrollTo().performClick()
        compose.onNode(hasText("23:05") and hasClickAction()).performScrollTo().performClick()

        val (dialog, picker) = picker()
        assertTime(picker, 23, 5)
        accept(dialog)
        compose.onNode(hasText("23:05") and hasClickAction()).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Name", substring = true))
            .performTextReplacement("Saved unchanged bedtime")
        compose.onNodeWithText(s(R.string.editor_save)).performClick()
        waitUntil { graph.repository.profile(profile.id)?.name == "Saved unchanged bedtime" }
        assertEquals(23 * 60 + 5, graph.repository.profile(profile.id)!!.bedtimeMinute)
    }

    @Test fun profileWakePickerUsesTheEditedFieldWhenReopenedAndPreservesItOnSave() {
        compose.onNodeWithText(s(R.string.action_edit)).performScrollTo().performClick()
        compose.onNode(hasTextExactly("06:10") and hasClickAction()).performScrollTo().performClick()
        val (firstDialog, firstPicker) = picker()
        assertTime(firstPicker, 6, 10)
        compose.runOnUiThread { firstPicker.hour = 5; firstPicker.minute = 45 }
        accept(firstDialog)

        compose.onNode(hasTextExactly("05:45") and hasClickAction()).performScrollTo().performClick()
        val (reopenedDialog, reopenedPicker) = picker()
        assertTime(reopenedPicker, 5, 45)
        accept(reopenedDialog)
        compose.onNodeWithText(s(R.string.editor_save)).performClick()
        waitUntil { graph.repository.profile(profile.id)?.wakeSource?.defaultWakeMinute == 5 * 60 + 45 }
        assertEquals(5 * 60 + 45, graph.repository.profile(profile.id)!!.wakeSource.defaultWakeMinute)
    }
}

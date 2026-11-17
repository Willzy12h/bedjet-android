package io.github.willzy12h.nocturne.ui

import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Phone setup explains the battery setting before Android's own prompt, which gives no reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BatteryExplainTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun s(id: Int) = compose.activity.getString(id)
    private val started get() = shadowOf(compose.activity.application)

    private fun openBatteryFix() {
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.openSettingsPage("permissions")
        compose.onNodeWithTag("setup-check-fix-battery").performScrollTo().performClick()
        compose.onNodeWithTag("battery-explain").assertExists()
        compose.onNodeWithText(s(R.string.battery_explain_why)).assertExists()
        compose.onNodeWithText(s(R.string.battery_explain_next)).assertExists()
    }

    @Test fun continueOpensAndroidsBatteryPromptForNocturne() {
        openBatteryFix()
        assertNull("Nothing opens before Continue", started.nextStartedActivity)
        compose.onNodeWithTag("battery-explain-continue").performClick()
        val intent = started.nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${compose.activity.packageName}", intent.dataString)
        compose.onNodeWithTag("battery-explain").assertDoesNotExist()
    }

    @Test fun notNowOpensNothing() {
        openBatteryFix()
        compose.onNodeWithTag("battery-explain-later").performClick()
        compose.onNodeWithTag("battery-explain").assertDoesNotExist()
        assertNull(started.nextStartedActivity)
    }

    @Test fun aMakerWithItsOwnControlsIsPointedToItsTips() {
        val maker = android.os.Build.MANUFACTURER
        ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", "Xiaomi")
        try {
            openBatteryFix()
            compose.onNodeWithText(s(R.string.battery_explain_maker)).assertExists()
            compose.onNode(hasText(s(R.string.setup_maker_xiaomi_title)) and hasAnyAncestor(hasTestTag("battery-explain")))
                .performClick()
            compose.onNode(hasText(s(R.string.setup_maker_xiaomi_help)) and hasAnyAncestor(hasTestTag("battery-explain")))
                .assertExists()
        } finally {
            ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", maker)
        }
    }

    @Test fun otherPhonesSeeNoMakerTips() {
        openBatteryFix()
        assertTrue(compose.onAllNodesWithText(s(R.string.battery_explain_maker)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theSetupChecklistExplainsFirstToo() {
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.openSettingsPage("permissions")
        compose.onNodeWithTag("open-setup-checklist").performScrollTo().performClick()
        compose.onNode(hasText(s(R.string.uat_setup_fix)) and hasAnyAncestor(hasTestTag("setup-battery")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("battery-explain").assertExists()
        assertNull("Nothing opens before Continue", started.nextStartedActivity)
        compose.onNodeWithTag("battery-explain-continue").performClick()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started.nextStartedActivity.action)
    }
}

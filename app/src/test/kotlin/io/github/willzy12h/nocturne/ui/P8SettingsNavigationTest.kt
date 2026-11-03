package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P8SettingsNavigationTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private val rows = listOf(
        "bedjet" to "BedJet", "night" to "Night and wake-up", "appearance" to "Appearance",
        "permissions" to "Permissions", "backups" to "Profiles and backups", "notifications" to "Notifications",
        "health" to "Health Connect", "about" to "Help and about",
    )
    private fun settings() = ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
    private fun row(id: String) = ui.compose.onNodeWithTag("settings-page-$id").performScrollTo()

    @Test fun rootShowsEightNamedChoicesAndEachOpensARealPage() {
        settings()
        val titles = mapOf(
            "bedjet" to R.string.settings_nav_bedjet,
            "night" to R.string.settings_nav_night,
            "appearance" to R.string.settings_nav_appearance,
            "permissions" to R.string.settings_nav_permissions,
            "backups" to R.string.settings_nav_backups,
            "notifications" to R.string.settings_nav_notifications,
            "health" to R.string.settings_nav_health,
            "about" to R.string.settings_nav_about,
        )
        for ((id, label) in rows) {
            row(id).assertTextContains(label, substring = true).assertHasClickAction().performClick()
            ui.compose.onNode(hasText(ui.s(titles.getValue(id))) and isHeading()).assertIsDisplayed()
            ui.compose.onNodeWithTag("settings-back").performClick()
            row(id).assertExists()
        }
    }

    @Test fun selectedPageSurvivesActivityRecreationAndBackReturnsToRoot() {
        settings()
        row("appearance").performClick()
        ui.compose.onNodeWithTag("night-palette-FOREST").performScrollTo().assertExists()
        ui.compose.activityRule.scenario.recreate()
        ui.compose.onNodeWithTag("night-palette-FOREST").performScrollTo().assertExists()
        ui.compose.onNodeWithTag("settings-back").performClick()
        row("appearance").assertExists()
    }

    @Test fun aboutLicencesOpensAndClosesItsDialog() {
        settings()
        row("about").performClick()
        ui.compose.onNode(hasText("Licences") and hasClickAction()).performScrollTo().performClick()
        ui.compose.onNode(hasText("Licences") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.compose.onNode(hasText("Done") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        ui.compose.onAllNodes(isDialog()).assertCountEquals(0)
        ui.compose.onNodeWithText(ui.s(R.string.settings_source_code)).assertExists()
    }

    @Test fun scheduledThemeExplainsChosenPalettesWithoutOldFixedNames() {
        settings()
        row("appearance").performClick()
        ui.compose.onNodeWithText(ui.s(R.string.theme_scheduled)).performScrollTo().performClick()
        val help = ui.s(R.string.settings_scheduled_help)
        org.junit.Assert.assertFalse(help.contains("Daylight", ignoreCase = true))
        org.junit.Assert.assertFalse(help.contains("Midnight Ink", ignoreCase = true))
        org.junit.Assert.assertTrue(help.contains("night palette", ignoreCase = true))
        org.junit.Assert.assertTrue(help.contains("day palette", ignoreCase = true))
        org.junit.Assert.assertTrue(help.contains("equal", ignoreCase = true))
        ui.compose.onNodeWithText(help).assertExists()
        ui.compose.onNodeWithText("Day from").performScrollTo().assertExists()
    }

    private fun deepLink(backups: Int, alarms: Int) {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                SettingsScreen(ui.model, Modifier, backupsRequest = backups, alarmsRequest = alarms)
            }
        } }
        ui.compose.waitForIdle()
    }

    @Test fun backupsRequestOpensBackupControls() {
        deepLink(backups = 1, alarms = 0)
        ui.compose.onNodeWithText(ui.s(R.string.settings_snapshot_now)).assertIsDisplayed()
    }

    @Test fun alarmsRequestOpensAlarmControls() {
        deepLink(backups = 0, alarms = 1)
        ui.compose.onNode(hasText(ui.s(R.string.settings_nav_night)) and isHeading()).assertIsDisplayed()
    }
}

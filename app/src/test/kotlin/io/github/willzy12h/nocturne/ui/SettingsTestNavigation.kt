package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.*

internal fun ComposeTestRule.openSettingsPage(id: String) {
    onNodeWithTag("settings-page-$id").performScrollTo().performClick()
}

internal fun ComposeTestRule.backSettingsPage() {
    onNodeWithTag("settings-back").performClick()
}

/** Remote is a main tab; Tonight's status line also opens it. */
internal fun ComposeTestRule.openRemote() = navTab("Remote")

/**
 * A main tab by its label (text or, in compact navigation, content description). "History" and "Sleep"
 * are the Sleep page nested in Settings.
 */
internal fun ComposeTestRule.navTab(label: String) {
    if (label == "History" || label == "Sleep") {
        navTab("Settings")
        if (onAllNodesWithTag("settings-page-sleep").fetchSemanticsNodes().isEmpty()) backSettingsPage()
        openSettingsPage("sleep")
        waitForIdle()
        // "History" means the saved nights, which sit below the Sleep page's charts.
        if (label == "History") { onNodeWithTag("sleep-list").performScrollToKey("history"); waitForIdle() }
        return
    }
    onNode((hasText(label) or hasContentDescription(label)) and hasClickAction() and hasAnyAncestor(hasTestTag("main-navigation")))
        .performClick()
    waitForIdle()
}

/** Tonight's Change chooser retains the existing profile-edit route. */
internal fun ComposeTestRule.openTonightProfileEditor() {
    onNodeWithTag("tonight-change").performScrollTo().performClick()
    onNode(hasText("Edit") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    onNodeWithTag("editor-save").assertExists()
}

/** The detailed curve/timeline is deliberately behind the compact night strip. */
internal fun ComposeTestRule.openTonightDetail() {
    onNodeWithTag("tonight-detail-toggle")
        .performScrollTo().performClick()
}

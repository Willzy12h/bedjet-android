package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.*

internal fun ComposeTestRule.openSettingsPage(id: String) {
    onNodeWithTag("settings-page-$id").performScrollTo().performClick()
}

internal fun ComposeTestRule.backSettingsPage() {
    onNodeWithTag("settings-back").performClick()
}

/** Remote is a Tonight sub-page in the approved design: open it from Tonight's status line. */
internal fun ComposeTestRule.openRemote() {
    if (onAllNodesWithTag("remote-back").fetchSemanticsNodes().isNotEmpty()) return
    navTab("Tonight")
    onNodeWithTag("open-remote", useUnmergedTree = true).performScrollTo().performClick()
    waitForIdle()
}

/** A main tab by its label (text or, in compact navigation, content description); leaves Remote first. */
internal fun ComposeTestRule.navTab(label: String) {
    if (label == "Remote") return openRemote()
    if (onAllNodesWithTag("remote-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("remote-back").performClick()
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

package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag

internal fun ComposeContentTestRule.openSettingsPage(id: String) {
    onNodeWithTag("settings-page-$id").performScrollTo().performClick()
}

internal fun ComposeContentTestRule.backSettingsPage() {
    onNodeWithTag("settings-back").performClick()
}

/** Remote is a Tonight sub-page in the approved design: open it from Tonight's status line. */
internal fun ComposeContentTestRule.openRemote() {
    if (onAllNodesWithTag("remote-back").fetchSemanticsNodes().isNotEmpty()) return
    navTab("Tonight")
    onNodeWithTag("open-remote", useUnmergedTree = true).performScrollTo().performClick()
    waitForIdle()
}

/** A main tab by its label (text or, in compact navigation, content description); leaves Remote first. */
internal fun ComposeContentTestRule.navTab(label: String) {
    if (label == "Remote") return openRemote()
    if (onAllNodesWithTag("remote-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("remote-back").performClick()
    onNode((hasText(label) or hasContentDescription(label)) and hasClickAction() and hasAnyAncestor(hasTestTag("main-navigation")))
        .performClick()
    waitForIdle()
}

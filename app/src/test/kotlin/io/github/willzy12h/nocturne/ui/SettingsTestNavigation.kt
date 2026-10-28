package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

internal fun ComposeContentTestRule.openSettingsPage(id: String) {
    onNodeWithTag("settings-page-$id").performScrollTo().performClick()
}

internal fun ComposeContentTestRule.backSettingsPage() {
    onNodeWithTag("settings-back").performClick()
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.night.SlotWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The unreadable-data banner, the lost-originals confirmation and the slot-write acknowledgement (P5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryNoticesTest {
    @get:Rule val compose = createComposeRule()
    private fun s(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun banner(state: UnreadableBannerState, calls: MutableList<String>, restore: Boolean = true) = compose.setContent {
        NocturneTheme(io.github.willzy12h.nocturne.core.store.ThemeMode.DARK) {
            UnreadableDataBanner(state, onDismiss = { calls += "dismiss" }, onRestore = if (restore) ({ calls += "restore" }) else null,
                onAcceptLoss = { calls += "accept" })
        }
    }

    @Test fun unreadableFilesOfferViewRestoreAndDismiss() {
        val calls = mutableListOf<String>()
        banner(UnreadableBannerState(listOf("profiles.json"), restoreUnfinished = false, originalsBlocked = false), calls)
        compose.onNodeWithText(s(R.string.unreadable_banner)).assertExists()
        compose.onAllNodes(hasText("profiles.json", substring = true)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.unreadable_view)).performClick()
        compose.onNode(hasText("profiles.json", substring = true)).assertExists()
        compose.onNodeWithText(s(R.string.unreadable_restore)).performClick()
        compose.onNodeWithText(s(R.string.unreadable_dismiss)).performClick()
        assertEquals(listOf("restore", "dismiss"), calls)
    }

    @Test fun aStuckRestoreSaysSoAndCannotBeDismissed() {
        val calls = mutableListOf<String>()
        banner(UnreadableBannerState(listOf("restore-pending.json"), restoreUnfinished = true, originalsBlocked = false), calls)
        compose.onNodeWithText(s(R.string.restore_unfinished_title)).assertExists()
        compose.onAllNodesWithText(s(R.string.unreadable_banner)).assertCountEquals(0)
        compose.onAllNodes(hasText("Copies", substring = true)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.unreadable_dismiss)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.unreadable_view)).performClick()
        compose.onNodeWithText(s(R.string.restore_unfinished_details)).assertExists()
    }

    @Test fun aStuckRestoreBesideOtherFilesKeepsBothMessages() {
        val state = UnreadableBannerState(listOf("restore-pending.json", "history.json"), restoreUnfinished = true, originalsBlocked = false)
        assertEquals(listOf("history.json"), state.setAside)
        assertTrue(state.dismissible)
        banner(state, mutableListOf())
        compose.onNodeWithText(s(R.string.restore_unfinished_title)).assertExists()
        compose.onNodeWithText(s(R.string.unreadable_banner)).assertExists()
    }

    @Test fun aDamagedJournalSetAsideIsAnOrdinaryUnreadableFile() {
        val state = UnreadableBannerState(listOf("restore-pending.json"), restoreUnfinished = false, originalsBlocked = false)
        assertTrue(state.dismissible)
        assertEquals(listOf("restore-pending.json"), state.setAside)
    }

    @Test fun acceptingLostOriginalsAsksFirstAndSaysFutureOriginalsAreUncertain() {
        val calls = mutableListOf<String>()
        banner(UnreadableBannerState(emptyList(), restoreUnfinished = false, originalsBlocked = true), calls, restore = false)
        compose.onNodeWithText(s(R.string.originals_blocked_text)).assertExists()
        compose.onAllNodesWithText(s(R.string.unreadable_dismiss)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.originals_accept_button)).performClick()
        assertTrue(calls.isEmpty())
        compose.onNode(hasText("marked as uncertain", substring = true)).assertExists()
        compose.onNodeWithText(s(R.string.originals_accept_cancel)).performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText(s(R.string.originals_accept_button)).performClick()
        compose.onNodeWithText(s(R.string.originals_accept_confirm)).performClick()
        assertEquals(listOf("accept"), calls)
    }

    @Test fun nothingToReportShowsNothing() {
        val state = UnreadableBannerState(emptyList(), restoreUnfinished = false, originalsBlocked = false)
        assertFalse(state.visible)
    }

    @Test fun anUnconfirmedSlotWriteCanBeMarkedAsChecked() {
        var checked = 0
        compose.setContent {
            NocturneTheme(io.github.willzy12h.nocturne.core.store.ThemeMode.DARK) {
                SlotWriteNotice(SlotWrite("demo", 1, null, Confidence.UNKNOWN, "Keep on slot 2")) { checked++ }
            }
        }
        compose.onNode(hasText("Keep on slot 2", substring = true)).assertExists()
        compose.onNodeWithText(s(R.string.slot_write_checked)).performClick()
        assertEquals(1, checked)
    }
}

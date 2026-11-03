package io.github.willzy12h.nocturne.widget

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the widget is awake, asleep, connecting or not in range, and what wakes it. */
class WidgetLinkRulesTest {
    private val minute = 60_000L
    private val awake = WidgetLinkFacts(everConnected = true)

    private fun state(facts: WidgetLinkFacts, now: Long = 1_000 * minute) = WidgetLinkRules.state(facts, now, hasDevice = true, demo = false)

    @Test fun neverConnectedIsAsleepAndDoesNotReadInBackground() {
        assertEquals(WidgetLinkState.ASLEEP, state(WidgetLinkFacts()))
        assertFalse(WidgetLinkRules.readsInBackground(WidgetLinkFacts()))
    }

    @Test fun demoAndNoBedJetAreNeverGrey() {
        assertEquals(WidgetLinkState.AWAKE, WidgetLinkRules.state(WidgetLinkFacts(), 0, hasDevice = true, demo = true))
        assertEquals(WidgetLinkState.AWAKE, WidgetLinkRules.state(WidgetLinkFacts(), 0, hasDevice = false, demo = false))
    }

    @Test fun aReadWakesItAndClearsMissesAndAFailedTap() {
        val read = WidgetLinkRules.read(WidgetLinkFacts(sleeping = true, misses = 4, failedAtMs = 5, connectingUntilMs = 9), 77)
        assertEquals(WidgetLinkState.AWAKE, state(read))
        assertEquals(77, read.lastReadMs)
        assertTrue(WidgetLinkRules.readsInBackground(read))
    }

    @Test fun oneMissGreysItButKeepsTrying() {
        val missed = WidgetLinkRules.missed(awake, 10 * minute)
        assertEquals(WidgetLinkState.ASLEEP, state(missed))
        assertTrue(WidgetLinkRules.readsInBackground(missed))
    }

    /** The approved timeline: misses at 08:15, 08:45, 09:45 and 11:45, then asleep with no more reads. */
    @Test fun sleepsAfterTheFourthMissInARow() {
        var facts = awake
        val times = listOf(0L, 30, 90, 210).map { (8 * 60 + 15 + it) * minute }
        times.forEachIndexed { index, at ->
            assertTrue("Try ${index + 1} is due", WidgetReadBackoff.due(at, facts.lastMissMs, facts.misses))
            facts = WidgetLinkRules.missed(facts, at)
            assertEquals(index == 3, facts.sleeping)
        }
        assertFalse(WidgetLinkRules.readsInBackground(facts))
        assertEquals(WidgetLinkRules.SLEEP_AFTER_MISSES, facts.misses)
    }

    @Test fun openingTheAppWakesBackgroundReads() {
        val asleep = awake.copy(sleeping = true, misses = 4, lastMissMs = 3)
        val opened = WidgetLinkRules.opened(asleep)
        assertTrue(WidgetLinkRules.readsInBackground(opened))
        assertEquals(0, opened.misses)
    }

    @Test fun aReconnectTapShowsConnectingUntilItsDeadline() {
        val now = 500 * minute
        val connecting = WidgetLinkFacts(connectingUntilMs = now + WidgetLinkRules.CONNECTING_MS)
        assertEquals(WidgetLinkState.CONNECTING, state(connecting, now))
        // A process that died mid-try does not leave it connecting.
        assertEquals(WidgetLinkState.ASLEEP, state(connecting, now + WidgetLinkRules.CONNECTING_MS))
        // A clock set back does not leave it connecting for ever.
        assertEquals(WidgetLinkState.ASLEEP, state(connecting, now - 10 * minute))
    }

    @Test fun aFailedTapSaysNotInRange() =
        assertEquals(WidgetLinkState.MISSING, state(WidgetLinkFacts(everConnected = true, sleeping = true, failedAtMs = 5)))

    @Test fun presenceReadsOnlyWhileGreyAndNotTooOften() {
        val now = 900 * minute
        fun reads(facts: WidgetLinkFacts) = WidgetLinkRules.presenceReads(facts, now, hasDevice = true, demo = false)
        assertFalse("An awake widget ignores the BedJet advertising after our own reads", reads(awake))
        assertTrue(reads(awake.copy(sleeping = true)))
        assertTrue(reads(awake.copy(misses = 1)))
        assertFalse(reads(awake.copy(sleeping = true, connectingUntilMs = now + 1_000)))
        assertFalse(reads(awake.copy(sleeping = true, presenceReadMs = now - 9 * minute)))
        assertTrue(reads(awake.copy(sleeping = true, presenceReadMs = now - WidgetLinkRules.PRESENCE_GAP_MS)))
        assertFalse(WidgetLinkRules.presenceReads(awake.copy(sleeping = true), now, hasDevice = true, demo = true))
    }

    @Test fun aForceStopIsFoundOnceOnTheNextStart() {
        val stop = ApplicationExitInfo.REASON_USER_REQUESTED
        val exits = listOf(ApplicationExitInfo.REASON_LOW_MEMORY to 300L, stop to 200L, stop to 100L)
        assertEquals(200L, WidgetLinkRules.forceStopSince(exits, handledMs = 0))
        assertNull("Already handled", WidgetLinkRules.forceStopSince(exits, handledMs = 200))
        assertNull(WidgetLinkRules.forceStopSince(listOf(ApplicationExitInfo.REASON_LOW_MEMORY to 300L), 0))
    }
}

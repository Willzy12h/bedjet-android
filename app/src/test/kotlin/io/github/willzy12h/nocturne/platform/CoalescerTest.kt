package io.github.willzy12h.nocturne.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescerTest {
    @Test fun aBurstOfFiveRunsTwiceAndTheLastRunSeesTheLastChange() = runTest {
        val coalescer = Coalescer()
        val gate = CompletableDeferred<Unit>()
        var state = 0
        val seen = mutableListOf<Int>()
        val first = async { coalescer.run { seen += state; gate.await() } }
        runCurrent()
        val rest = (1..4).map { n -> state = n; async { coalescer.run { seen += state } } }
        runCurrent()
        gate.complete(Unit)
        val ran = (listOf(first) + rest).awaitAll()
        assertEquals("One running and one waiting", 2, ran.count { it })
        assertEquals(listOf(0, 4), seen)
    }

    @Test fun aLaterBroadcastAfterARunStartsGetsItsOwnRun() = runTest {
        val coalescer = Coalescer()
        var runs = 0
        assertTrue(coalescer.run { runs++ })
        assertTrue(coalescer.run { runs++ })
        assertEquals(2, runs)
    }

    @Test fun onlyTheStarterSeesAFailureAndTheNextBurstStillRuns() = runTest {
        val coalescer = Coalescer()
        val gate = CompletableDeferred<Unit>()
        val busy = launch { coalescer.run { gate.await() } }
        runCurrent()
        val starter = async { runCatching { coalescer.run { error("repair failed") } } }
        val joiner = async { coalescer.run { } }
        runCurrent()
        gate.complete(Unit)
        busy.join()
        assertTrue(starter.await().isFailure)
        assertFalse("Joined quietly", joiner.await())
        assertTrue(coalescer.run { })
    }

    @Test fun aJoinerOfACancelledRunRunsItself() = runTest {
        val coalescer = Coalescer()
        val gate = CompletableDeferred<Unit>()
        launch { coalescer.run { gate.await() } }
        runCurrent()
        var runs = 0
        val starter = launch { coalescer.run { runs++ } }
        runCurrent()
        val joiner = async { coalescer.run { runs++ } }
        runCurrent()
        starter.cancel()
        runCurrent()
        gate.complete(Unit)
        assertTrue("The joiner ran, since the run it joined never did", joiner.await())
        assertEquals(1, runs)
    }
}

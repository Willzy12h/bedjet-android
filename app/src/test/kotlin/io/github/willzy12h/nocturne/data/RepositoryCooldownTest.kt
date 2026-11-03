package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryCooldownTest {
    @get:Rule val temp = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = temp.root
    } }
    private fun repo(write: (java.io.File, ByteArray) -> Unit = Repository::writeDurably): Repository {
        val job = Job().also { jobs += it }
        return Repository(context, CoroutineScope(job + Dispatchers.Unconfined), Dispatchers.Unconfined, write)
    }
    @After fun cleanup() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test fun selectedProfilesAndAnswerPersistTogether() = runBlocking {
        val r = repo()
        val selected = r.profiles.value.first()
        val others = r.profiles.value.drop(1)
        assertTrue(r.answerCooldownOffer(mapOf(selected.id to selected.afterWake)))
        assertEquals(listOf(Phase(Mode.COOL, 44, 30, 10)), r.profile(selected.id)!!.afterWake)
        assertEquals(others, r.profiles.value.drop(1))
        jobs.last().cancelAndJoin()
        val restarted = repo()
        assertTrue(restarted.settings.value.cooldownOfferAnswered)
        assertEquals(r.profiles.value, restarted.profiles.value)
    }

    @Test fun declineAndStaleSelection() = runBlocking {
        val r = repo()
        val p = r.profiles.value.first()
        r.saveProfile(p.copy(afterWake = listOf(Phase(Mode.HEAT, 60, 40, 10))))
        assertFalse(r.answerCooldownOffer(mapOf(p.id to p.afterWake)))
        assertFalse(r.settings.value.cooldownOfferAnswered)
        val before = r.profiles.value
        assertTrue(r.answerCooldownOffer(emptyMap()))
        assertTrue(r.settings.value.cooldownOfferAnswered)
        assertEquals(before, r.profiles.value)
    }

    @Test fun failedWriteLeavesOfferAndProfilesUnchanged() = runBlocking {
        val r = repo { file, bytes ->
            if (file.name == "settings.json") throw IOException("Disk full")
            Repository.writeDurably(file, bytes)
        }
        val before = r.profiles.value
        try { r.answerCooldownOffer(mapOf(before.first().id to before.first().afterWake)); fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals(before, r.profiles.value)
        assertFalse(r.settings.value.cooldownOfferAnswered)
    }
}

package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryCooldownSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = temp.root
    } }
    private fun repo() = Repository(context, scope, Dispatchers.Unconfined)
    @After fun cleanup() = runBlocking { job.cancelAndJoin() }

    @Test fun oldSessionCannotAcceptOrDeclineAfterDemoRoundTrip() = runBlocking {
        val r = repo()
        val before = r.profiles.value
        val revision = r.sessionRevision.value
        val selected = before.first()
        r.enterDemoSession()
        r.exitDemoSession()
        assertFalse(r.answerCooldownOffer(mapOf(selected.id to selected.afterWake), revision))
        assertFalse(r.answerCooldownOffer(emptyMap(), revision))
        assertFalse(r.settings.value.cooldownOfferAnswered)
        assertEquals(before, r.profiles.value)
    }

    @Test fun restoreInvalidatesHeldOfferEvenWhenAfterWakeIsUnchanged() = runBlocking {
        val r = repo()
        val selected = r.profiles.value.first()
        val revision = r.sessionRevision.value
        val imported = r.bundle().let { bundle ->
            bundle.copy(profiles = bundle.profiles.map {
                if (it.id == selected.id) it.copy(name = "Imported profile") else it
            })
        }
        r.restore(imported)
        val restored = r.profiles.value
        assertEquals(selected.afterWake, r.profile(selected.id)!!.afterWake)
        assertEquals("Imported profile", r.profile(selected.id)!!.name)
        assertFalse(r.answerCooldownOffer(mapOf(selected.id to selected.afterWake), revision))
        assertFalse(r.answerCooldownOffer(emptyMap(), revision))
        assertFalse(r.settings.value.cooldownOfferAnswered)
        assertEquals(restored, r.profiles.value)
    }

    @Test fun declineDoesNotReplaceUnreadableProfilesWithDefaults() = runBlocking {
        val original = "{broken profiles"
        File(temp.root, "profiles.json").writeText(original)
        val r = repo()
        assertTrue(r.unreadable.value.contains("profiles.json"))
        val defaults = r.profiles.value
        assertTrue(r.answerCooldownOffer(emptyMap(), r.sessionRevision.value))
        assertEquals(defaults, r.profiles.value)
        assertFalse(File(temp.root, "profiles.json").exists())
        assertTrue(temp.root.listFiles().orEmpty().any {
            it.name.startsWith("profiles.json.corrupt-") && it.readText() == original
        })
    }
}

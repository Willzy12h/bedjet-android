package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import io.github.willzy12h.nocturne.core.store.BackupCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime

/** A replacement Repository invalidates every pending Share before it opens a provider document. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileShareRequestTest {
    @get:Rule val temp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private var openedOutputs = 0
    private val uri = Uri.parse("content://test/shared-profile.json")
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
        }
    }

    private fun repository(): Repository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return Repository(context, scope, writer = Dispatchers.Unconfined,
            openOutput = { openedOutputs++; ByteArrayOutputStream() },
            now = { ZonedDateTime.parse("2026-10-29T22:30Z") })
    }

    private fun savedRequest(repo: Repository): ProfileShareRequest {
        val encoded = BackupCodec.json.encodeToString(ProfileShareRequest.serializer(),
            repo.profileShareRequest(repo.profiles.value.first()))
        return BackupCodec.json.decodeFromString(ProfileShareRequest.serializer(), encoded)
    }

    private suspend fun assertRejectedBeforeOpeningOutput(repo: Repository, request: ProfileShareRequest) {
        val before = openedOutputs
        val failure = runCatching { repo.exportProfileTo(uri, request) }.exceptionOrNull()
        assertEquals("An invalidated Share must not open or truncate the selected document", before, openedOutputs)
        assertTrue("An invalidated Share must report that the request needs to be retried", failure is IOException)
    }

    @After fun finish() = scopes.forEach { it.cancel() }

    @Test fun newRepositoryRejectsRevisionZeroRequestBeforeOpeningDocument() = runBlocking {
        val first = repository()
        val request = savedRequest(first)
        assertEquals(0L, request.sessionRevision)
        scopes.forEach { it.cancel() }

        val restarted = repository()

        assertEquals(request.sessionRevision, restarted.sessionRevision.value)
        assertRejectedBeforeOpeningOutput(restarted, request)
    }

    @Test fun newRepositoryRejectsRequestAfterEarlierDemoRoundTrip() = runBlocking {
        val first = repository()
        first.enterDemoSession()
        first.exitDemoSession()
        val request = savedRequest(first)
        assertEquals(2L, request.sessionRevision)
        scopes.forEach { it.cancel() }

        val restarted = repository()

        assertEquals(0L, restarted.sessionRevision.value)
        assertRejectedBeforeOpeningOutput(restarted, request)
    }

    @Test fun matchingSessionCountersCannotReviveAnEarlierRepositorysRequest() = runBlocking {
        val first = repository()
        first.enterDemoSession()
        first.exitDemoSession()
        val request = savedRequest(first)
        scopes.forEach { it.cancel() }

        val restarted = repository()
        restarted.enterDemoSession()
        restarted.exitDemoSession()

        assertEquals(2L, restarted.sessionRevision.value)
        assertEquals(request.sessionRevision, restarted.sessionRevision.value)
        assertRejectedBeforeOpeningOutput(restarted, request)
    }

    @Test fun legacySavedRequestDecodesButCannotOpenADocument() = runBlocking {
        val repo = repository()
        val selected = repo.profileShareRequest(repo.profiles.value.first())
        // Saved requests from the previous version contain only these two fields.
        val oldJson = JsonObject(mapOf(
            "encodedProfile" to JsonPrimitive(selected.encodedProfile),
            "sessionRevision" to JsonPrimitive(selected.sessionRevision),
        )).toString()

        val restored = BackupCodec.json.decodeFromString(ProfileShareRequest.serializer(), oldJson)

        assertRejectedBeforeOpeningOutput(repo, restored)
    }
}

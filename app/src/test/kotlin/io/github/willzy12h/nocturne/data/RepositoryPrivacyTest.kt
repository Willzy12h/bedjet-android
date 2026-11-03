package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryPrivacyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
    }
    @After fun finish() { scope.cancel() }

    private fun assertNoPayloadLogged(file: String, malformed: String, privateText: String) {
        File(temporary.root, file).writeText(malformed)
        ShadowLog.clear()
        val repo = Repository(context, scope, writer = Dispatchers.Unconfined)
        assertTrue("The corrupt file still needs to be reported", file in repo.unreadable.value)
        val logs = ShadowLog.getLogs().filter { it.tag == "Repository" }
        assertTrue("Keep a useful storage failure category in logs", logs.isNotEmpty())
        assertFalse("Storage logs must not include JSON payloads or deserialisation causes", logs.any {
            it.msg.orEmpty().contains(privateText) || it.throwable?.stackTraceToString().orEmpty().contains(privateText)
        })
    }

    @Test fun corruptSettingsDoNotLogTheSavedBedJetAddressFromTheJsonException() {
        val address = "ble:DE:AD:BE:EF:00:01"
        assertNoPayloadLogged("settings.json", "{\"targetDevice\":\"$address\",\"themeMode\":", address)
    }

    @Test fun corruptJournalDoesNotLogAnyRawBackupFieldsThroughItsNestedCause() {
        val privateText = "PRIVATE_NIGHT_PROFILE_SENTINEL"
        assertNoPayloadLogged("restore-pending.json", "{\"writes\":[{\"name\":\"$privateText\"", privateText)
    }
}

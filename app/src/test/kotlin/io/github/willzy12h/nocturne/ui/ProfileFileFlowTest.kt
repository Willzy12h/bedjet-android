package io.github.willzy12h.nocturne.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.data.ImportArea
import io.github.willzy12h.nocturne.data.ImportDisposition
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** Profiles' file actions must preserve personal data and the exact selection across document-provider delays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ProfileFileFlowTest {
    private val graphs = GraphLifecycle()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)
    private val app get() = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private var controller: ActivityController<MainActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val model get() = ViewModelProvider(activity)[AppViewModel::class.java]
    private var input = ""
    private val output = AtomicReference<ByteArray?>()
    private lateinit var original: Profile
    private lateinit var before: BackupBundle
    private val uri = Uri.parse("content://test/profile.json")

    @Before fun setup() {
        graph = graphs.fresh { Graph(app, repositoryFactory = { scope, now ->
            Repository(app, scope, writer = Dispatchers.Main.immediate, now = now,
                openInput = { input.byteInputStream() }, openOutput = {
                    object : ByteArrayOutputStream() { override fun close() { output.set(toByteArray()); super.close() } }
                })
        }).also { it.clock = Clock.fixed(Instant.parse("2026-10-26T21:00:00Z"), ZoneOffset.UTC) } }
        ReflectionHelpers.setField(app, "graph", graph)
        runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = false, fahrenheit = true, themeMode = ThemeMode.DARK) }
            original = graph.repository.profiles.value.first()
            graph.repository.addRecord(NightRecord("keep-history", original.id, original.name,
                graph.now().minusHours(8).toString(), graph.now().minusHours(1).toString(), false, deviceId = "ble:AA:BB:CC:DD:EE:FF"))
            graph.repository.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, null)
        }
        before = graph.repository.bundle()
        launch()
        compose.navTab("Profiles")
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); controller = null }
    private fun launch(state: Bundle? = null) {
        val next = Robolectric.buildActivity(MainActivity::class.java)
        controller = (if (state == null) next.setup() else next.setup(state)).windowFocusChanged(true)
        compose.waitForIdle()
    }
    private fun restore() {
        compose.waitForIdle()
        val saved = Bundle()
        controller!!.pause().saveInstanceState(saved).stop().destroy()
        controller = null
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writeBundle(saved); parcel.marshall() } finally { parcel.recycle() }
        val reader = Parcel.obtain()
        val restored = try {
            reader.unmarshall(bytes, 0, bytes.size); reader.setDataPosition(0)
            requireNotNull(reader.readBundle(MainActivity::class.java.classLoader))
        } finally { reader.recycle() }
        launch(restored)
    }
    private fun until(check: () -> Boolean) = compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); check() }
    private fun requestCode(action: String): Int {
        val launch = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(action, launch.intent.action)
        return launch.requestCode
    }
    private fun deliver(code: Int) {
        compose.runOnUiThread { assertTrue(activity.activityResultRegistry.dispatchResult(code, Activity.RESULT_OK, Intent().setData(uri))) }
    }
    private fun share(): Int {
        compose.onNodeWithTag("profile-more-${original.id}").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.parity_share_profile)).performClick()
        return requestCode(Intent.ACTION_CREATE_DOCUMENT)
    }
    private fun import(profile: Profile) {
        input = BackupCodec.encode(BackupBundle(createdAt = graph.now().toString(), profiles = listOf(profile), settings = Settings()))
        compose.onNodeWithTag("profiles-import").performClick()
        deliver(requestCode(Intent.ACTION_OPEN_DOCUMENT))
        until { model.importPreview.value != null }
    }
    private fun apply() {
        compose.onNodeWithText(app.getString(R.string.import_apply)).performClick()
        until { !model.importApplying.value && model.importPreview.value == null }
    }
    private fun assertPersonalDataPreserved() {
        assertEquals(before.settings, graph.repository.settings.value)
        assertEquals(before.history, graph.repository.history.value)
        assertEquals(before.slotBackups, graph.repository.slotBackups.value)
        assertTrue(graph.repository.snapshotList.value.any { it.manual })
    }

    @Test fun importingASharedProfileAddsItWithoutRemovingProfilesOrOtherPersonalData() {
        val incoming = original.copy(id = "shared-new", name = "Shared night")
        import(incoming)
        val review = requireNotNull(model.importPreview.value)
        assertEquals("Profile import must never propose deleting unrelated profiles", 0,
            review.sections.single { it.area == ImportArea.PROFILES }.count(ImportDisposition.REMOVED))
        apply()
        assertEquals(before.profiles + incoming, graph.repository.profiles.value)
        assertPersonalDataPreserved()
    }
    @Test fun importingAnExistingIdReviewsAndReplacesOnlyThatProfile() {
        val incoming = original.copy(name = "Reviewed replacement")
        import(incoming)
        assertEquals(1, requireNotNull(model.importPreview.value).sections.single { it.area == ImportArea.PROFILES }.count(ImportDisposition.REPLACED))
        apply()
        assertEquals(before.profiles.map { if (it.id == original.id) incoming else it }, graph.repository.profiles.value)
        assertPersonalDataPreserved()
    }
    @Test fun shareExportsTheSelectedSnapshotEvenIfTheProfileChangesWhileThePickerIsOpen() {
        val code = share()
        runBlocking { graph.repository.saveProfile(original.copy(name = "Later edit")) }
        val done = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        deliver(code); until { done.isCompleted }
        assertEquals(listOf(original), BackupCodec.decode(requireNotNull(output.get()).toString(Charsets.UTF_8)).profiles)
    }
    @Test fun pendingShareSurvivesParcelledActivityRecreation() {
        val code = share()
        restore()
        val done = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        deliver(code); until { done.isCompleted }
        assertEquals(listOf(original), BackupCodec.decode(requireNotNull(output.get()).toString(Charsets.UTF_8)).profiles)
    }
    @Test fun pendingShareAfterEarlierDemoUseSurvivesParcelledActivityRecreation() {
        runBlocking { graph.repository.enterDemoSession(); graph.repository.exitDemoSession() }
        compose.waitForIdle()
        val code = share()
        restore()
        val done = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        deliver(code); until { done.isCompleted }
        assertEquals(listOf(original), BackupCodec.decode(requireNotNull(output.get()).toString(Charsets.UTF_8)).profiles)
    }
    @Test fun pendingShareCannotReadFromAnotherSessionAfterAConflatedDemoRoundTrip() {
        val code = share()
        runBlocking { graph.repository.enterDemoSession(); graph.repository.exitDemoSession() }
        val done = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        deliver(code); until { done.isCompleted }
        assertNull("A stale document result must not export another session's data", output.get())
    }
}

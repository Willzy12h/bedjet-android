package io.github.willzy12h.nocturne.widget

import android.content.Context
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class WidgetPolicyTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun dynamicStartDoesNotFreezeTonightsProfile() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val first = widgetActions(context).first()
        assertNull("Dynamic Start resolves at tap, not configuration", first.profileId)
        assertEquals("Start tonight", first.label)
        val repo = context.graph.repository
        val second = repo.profiles.value[1]
        repo.updateSettings { it.chooseTonight(second.id, context.graph.now()) }
        assertEquals(second.id, repo.tonightsProfile()?.id)
        assertNull(first.profileId)
    }
}

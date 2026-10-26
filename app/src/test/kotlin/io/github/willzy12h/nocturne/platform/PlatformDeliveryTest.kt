package io.github.willzy12h.nocturne.platform
import android.content.Context
import android.service.quicksettings.Tile
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.widget.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class PlatformDeliveryTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    @Test fun claimsSuppressDuplicateReminderAndChargerAcrossRestart() = runBlocking {
        val repo=context.graph.repository
        assertTrue(repo.claimAutomation("reminder:2026-10-22"))
        assertFalse(repo.claimAutomation("reminder:2026-10-22"))
        val restarted=fresh { Graph(context) }.repository
        assertFalse(restarted.claimAutomation("reminder:2026-10-22"))
        assertTrue(restarted.claimAutomation("charger:2026-10-22"))
        assertFalse(restarted.claimAutomation("charger:2026-10-22"))
    }
    @Test fun existingNightWidgetCapturesIdentityButOffResolvesByTarget() = runBlocking {
        val graph=context.graph
        graph.clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-22T22:30:00Z"),java.time.ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode=true) }
        graph.actions.perform(Command.START_NIGHT)
        val shown=graph.repository.activeNight.value!!.identity
        val request=widgetRequest(context, WidgetAction("Off",Command.OFF))
        assertTrue(request.requireIdentity)
        assertEquals(shown,request.expected)
        graph.actions.perform(Command.EXTEND_WARM)
        assertEquals(io.github.willzy12h.nocturne.night.OutcomeKind.CONFIRMED,graph.actions.execute(request).kind)
        assertNull(graph.repository.activeNight.value)
        assertEquals(io.github.willzy12h.nocturne.core.protocol.Mode.STANDBY,graph.link.status.value?.mode)
    }
    @Test fun deletedFixedProfileNeverBecomesDynamicStart() = runBlocking {
        val repo=context.graph.repository
        val id=repo.profiles.value.first().id
        repo.deleteProfile(id)
        assertEquals("Profile deleted — reconfigure",configuredWidgetAction(context,Command.START_NIGHT,"Start old",id).label)
    }
    @Test fun unknownTileRemainsNavigableAndOpensActualTonight() {
        assertEquals(Tile.STATE_INACTIVE,NightTileService.state(null,false))
        val intent=NightTileService.openIntent(context)
        assertTrue(intent.getBooleanExtra("openTonight",false))
        assertEquals("io.github.willzy12h.nocturne.ui.MainActivity",intent.component!!.className)
    }
}

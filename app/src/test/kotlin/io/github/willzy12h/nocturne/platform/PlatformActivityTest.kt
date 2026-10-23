package io.github.willzy12h.nocturne.platform

import android.content.Context
import android.content.DialogInterface
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class PlatformActivityTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun operationTimeoutIsShownAtOrigin() = runBlocking {
        val app=RuntimeEnvironment.getApplication() as io.github.willzy12h.nocturne.App
        var connecting=false
        val demo=io.github.willzy12h.nocturne.core.link.DemoBedJet()
        val link=object:io.github.willzy12h.nocturne.core.link.BedJetLink by demo {
            override suspend fun connect() {connecting=true;kotlinx.coroutines.awaitCancellation()}
        }
        val graph=fresh { io.github.willzy12h.nocturne.Graph(app,link) }
        io.github.willzy12h.nocturne.App::class.java.getDeclaredField("graph").apply {isAccessible=true}.set(app,graph)
        graph.clock=Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"),ZoneOffset.UTC)
        graph.repository.updateSettings {it.copy(demoMode=true)}
        val controller=Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(app,graph.actions.capture(Command.START_NIGHT,requireIdentity=true))).setup()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val looper=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        var deadline=System.nanoTime()+5_000_000_000L
        while(!connecting && System.nanoTime()<deadline){looper.idle();Thread.yield()}
        assertTrue(connecting)
        looper.idleFor(java.time.Duration.ofSeconds(91))
        deadline=System.nanoTime()+5_000_000_000L
        while(org.robolectric.Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString() != "The operation timed out. Check the BedJet status before trying again." && System.nanoTime()<deadline){looper.idle();Thread.yield()}
        assertEquals("The operation timed out. Check the BedJet status before trying again.",org.robolectric.Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString())
        controller.pause().stop().destroy()
        Unit
    }
    @Test fun offDoesNotAddAReplacementConfirmation() = runBlocking {
        val context:Context=RuntimeEnvironment.getApplication();val graph=context.graph
        graph.repository.updateSettings {it.copy(demoMode=true)}
        val controller=Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(context,graph.actions.capture(Command.OFF,requireIdentity=true))).setup()
        assertNotEquals("Continue",ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE)?.text?.toString())
        controller.pause().stop().destroy()
        Unit
    }
    @Test fun widgetPreviewKeepsOriginVisibleUntilOutcome() = runBlocking {
        val context:Context=RuntimeEnvironment.getApplication();val graph=context.graph
        graph.clock=Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"),ZoneOffset.UTC)
        graph.repository.updateSettings {it.copy(demoMode=true)}
        val intent=PlatformActionActivity.intent(context,graph.actions.capture(Command.START_NIGHT,requireIdentity=true))
        val controller=Robolectric.buildActivity(PlatformActionActivity::class.java,intent).setup()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("Keep origin open to show the typed command outcome",controller.get().isFinishing)
        controller.pause().stop().destroy()
        Unit
    }
}

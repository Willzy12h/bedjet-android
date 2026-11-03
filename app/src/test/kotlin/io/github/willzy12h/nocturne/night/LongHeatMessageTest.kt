package io.github.willzy12h.nocturne.night

import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.plan.PlanException
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.ui.AppViewModel
import io.github.willzy12h.nocturne.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LongHeatMessageTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context = RuntimeEnvironment.getApplication()

    @Test fun programmeDurationErrorUsesTheUserFacingModeName() {
        val coreError = BedJetError.ProgramTooLong()
        assertTrue(coreError.message!!.contains("Gentle heat"))
        assertTrue(coreError.planMessage(context)!!.contains("Long heat"))
    }

    @Test fun compilerTemperatureRefusalUsesTheUserFacingModeName() {
        val coreMessage = "Gentle heat cannot honour this profile's temperature limits"
        assertEquals("Long heat cannot honour this profile's temperature limits", PlanException(coreMessage).planMessage(context))
    }

    @Test fun editorPlanPreviewPresentsTheCompilerRefusalAndPreservesItsCause() {
        val profile = Presets.springAutumn.copy(
            start = emptyList(), wake = emptyList(), afterWake = emptyList(),
            middle = Presets.springAutumn.middle.copy(mode = Mode.EXTENDED_HEAT),
            limits = ProfileLimits(minTemp = 68, maxTemp = 86),
        )
        val store = ViewModelStore()
        try {
            val model = AppViewModel(context as App)
            store.put("long-heat-preview", model)
            val failure = model.previewPlan(profile).exceptionOrNull()!!
            assertEquals("Long heat cannot honour this profile's temperature limits", failure.message)
            assertTrue(failure.cause is PlanException)
            assertEquals("Gentle heat cannot honour this profile's temperature limits", failure.cause!!.message)
        } finally {
            store.clear()
        }
    }

    @Test fun arbitraryUserTextIsNotRewritten() {
        val message = "Profile Gentle heat is unavailable"
        assertEquals(message, IllegalStateException(message).planMessage(context))
    }
}

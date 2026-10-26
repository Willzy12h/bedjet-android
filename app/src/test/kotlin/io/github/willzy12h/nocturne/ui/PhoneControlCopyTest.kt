package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.boostDescription
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneControlCopyTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun everyConfiguredBoostNamesTheTurboModeItActuallyStarts() {
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            val steps = PlanCompiler.phaseSteps(Phase(Mode.TURBO, 86, 100, minutes), ProfileLimits(), TurboChain.TURBO_THEN_HEAT)
            assertEquals(Mode.TURBO, steps.first().mode)
            assertEquals(10, steps.first().durationMinutes)
            assertEquals(minutes, steps.sumOf { it.durationMinutes })
            assertTrue(steps.drop(1).all { it.mode == Mode.HEAT })
            assertEquals("Turbo ${minutes}m", context.getString(R.string.remote_boost, minutes))
            assertEquals(if (minutes == 10) "Turbo heat for 10 minutes"
                else "Turbo heat for 10 minutes, then Heat for ${minutes - 10} minutes", boostDescription(context, minutes))
        }
    }

    @Test fun recoveryAndNotificationLabelsSayResumeNight() {
        for (id in listOf(R.string.recovery_resume_programme, R.string.recovery_resume_previous,
            R.string.runtime_command_resume, R.string.runtime_resume_title)) {
            assertEquals("Resume night", context.getString(id))
        }
    }

    @Test fun endingAndItsConfirmationSayStopNight() {
        for (id in listOf(R.string.tonight_end_night, R.string.runtime_command_end_night,
            R.string.runtime_end, R.string.guard_confirm)) {
            assertEquals("Stop night", context.getString(id))
        }
    }
}

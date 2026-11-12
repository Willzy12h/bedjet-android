package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.store.AlarmApps
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.ClockApps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The other-apps list says which apps have really set an alarm, and that the rest may keep theirs private. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AlarmAppsChoicesTest {
    // The application starts its own graph; finish it before Robolectric removes its storage.
    private val graphs = GraphLifecycle()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(graphs).around(compose)
    private val clock = ClockApps.App("com.google.android.deskclock", "Clock")
    private val noise = ClockApps.App("com.tmsoft.whitenoise.pro", "White Noise", clock = false, seen = true)
    private val asking = ClockApps.App("com.example.sleep", "Sleep app", clock = false)

    @Test fun seenAndPermissionOnlyAppsAreToldApartAndCanBeTicked() {
        var chosen: Pair<AlarmApps, Set<String>>? = null
        val settings = ClockApps.choose(Settings(), AlarmApps.CLOCK_APPS, listOf(clock))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AlarmAppsChoices(settings, listOf(clock), next = null, others = listOf(noise, asking)) { c, s -> chosen = c to s }
        } } }
        compose.onNodeWithTag("alarm-apps-other-${noise.packageName}").performScrollTo()
            .assertTextContains("Has set an alarm on this phone", substring = true).assertIsOff()
        compose.onNodeWithTag("alarm-apps-other-${asking.packageName}").performScrollTo()
            .assertTextContains("only use its alarms if they show on your lock screen", substring = true)
        compose.onNodeWithTag("alarm-apps-only-${clock.packageName}").performScrollTo().assertIsOn()
        compose.onNodeWithTag("alarm-apps-other-${noise.packageName}").performClick()
        assertEquals(AlarmApps.CLOCK_APPS to setOf(noise.packageName), chosen)
    }
}

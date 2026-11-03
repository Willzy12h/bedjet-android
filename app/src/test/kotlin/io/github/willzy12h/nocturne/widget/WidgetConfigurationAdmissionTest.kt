package io.github.willzy12h.nocturne.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetConfigurationAdmissionTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun anUnregisteredWidgetIdCannotOpenConfigurationThatWillCrashOnSelection() {
        val app = RuntimeEnvironment.getApplication() as App
        val activity = Robolectric.buildActivity(SingleActionConfigActivity::class.java,
            Intent(app, SingleActionConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 81234)).create()
        try {
            assertTrue("Only a widget hosted for this provider can be configured", activity.get().isFinishing)
        } finally { activity.destroy() }
    }

    @Test fun aHostedSingleActionWidgetCanStillBeConfigured() {
        val app = RuntimeEnvironment.getApplication() as App
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(17, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, SingleActionReceiver::class.java)
        })
        val activity = Robolectric.buildActivity(SingleActionConfigActivity::class.java,
            Intent(app, SingleActionConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 17)).create()
        try { assertFalse(activity.get().isFinishing) } finally { activity.destroy() }
    }

    @Test fun anotherProvidersWidgetCannotBeConfiguredAsASingleAction() {
        val app = RuntimeEnvironment.getApplication() as App
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(17, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, QuickControlsReceiver::class.java)
        })
        val activity = Robolectric.buildActivity(SingleActionConfigActivity::class.java,
            Intent(app, SingleActionConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 17)).create()
        try { assertTrue(activity.get().isFinishing) } finally { activity.destroy() }
    }
}

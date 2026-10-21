package io.github.willzy12h.nocturne.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import io.github.willzy12h.nocturne.ui.NocturneTheme
import kotlinx.coroutines.launch

/** Picks what a single-button widget does. */
class SingleActionConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val actions = widgetActions(this)
        setContent {
            NocturneTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("What should this button do?", style = MaterialTheme.typography.titleLarge)
                        actions.forEach { action ->
                            FilledTonalButton(onClick = { choose(widgetId, action) }, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, action: WidgetAction) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@SingleActionConfigActivity).getGlanceIdBy(widgetId)
            updateAppWidgetState(this@SingleActionConfigActivity, glanceId) { prefs ->
                prefs[SingleActionWidget.KEY_COMMAND] = action.command.name
                prefs[SingleActionWidget.KEY_LABEL] = action.label
                if (action.profileId != null) prefs[SingleActionWidget.KEY_PROFILE] = action.profileId else prefs.remove(SingleActionWidget.KEY_PROFILE)
            }
            SingleActionWidget().update(this@SingleActionConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

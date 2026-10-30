package io.github.willzy12h.nocturne.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.ui.NocturneTheme
import io.github.willzy12h.nocturne.ui.ThemeDuePrompt
import io.github.willzy12h.nocturne.ui.rememberThemePresentation
import kotlinx.coroutines.launch

/** Ticks and orders the presets Controls shows. Off is not listed: it is always on the widget. */
class QuickControlsConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId <= 0 || AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider !=
            ComponentName(this, QuickControlsReceiver::class.java)) { finish(); return }
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@QuickControlsConfigActivity).getGlanceIdBy(widgetId)
            val saved = getAppWidgetState(this@QuickControlsConfigActivity, PreferencesGlanceStateDefinition, glanceId)[QuickControlsWidget.KEY_PRESETS]
            show(widgetId, controlKeys(this@QuickControlsConfigActivity, saved))
        }
    }

    private fun show(widgetId: Int, chosen: List<String>) {
        // Chosen presets first, in their order, then the rest of the choices unticked.
        val everything = widgetPresetChoices(this).map { it.key }.filter { it != PRESET_OFF }
        setContent {
            val settings by graph.repository.settings.collectAsState()
            val theme = rememberThemePresentation(settings, graph::now)
            var order by remember { mutableStateOf(chosen + everything.filter { it !in chosen }) }
            var ticked by remember { mutableStateOf(chosen.toSet()) }
            NocturneTheme(settings, theme.at, paletteOverride = theme.palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.widget_controls_config), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.widget_controls_config_help), style = MaterialTheme.typography.bodyMedium)
                        order.forEachIndexed { index, key ->
                            val label = widgetPreset(this@QuickControlsConfigActivity, key)?.label ?: return@forEachIndexed
                            Row(Modifier.fillMaxWidth().testTag("controls-choice-$key"), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(key in ticked, { ticked = if (it) ticked + key else ticked - key })
                                Text(label, Modifier.weight(1f))
                                IconButton({ order = order.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0) {
                                    Icon(painterResource(R.drawable.ic_nc_chevron_up), stringResource(R.string.widget_move_up, label))
                                }
                                IconButton({ order = order.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < order.lastIndex) {
                                    Icon(painterResource(R.drawable.ic_nc_chevron_down), stringResource(R.string.widget_move_down, label))
                                }
                            }
                        }
                        FilledTonalButton({ save(widgetId, order.filter { it in ticked }) }, Modifier.fillMaxWidth().testTag("controls-save")) {
                            Text(stringResource(R.string.widget_controls_save))
                        }
                    }
                }
                ThemeDuePrompt(theme)
            }
        }
    }

    private fun save(widgetId: Int, keys: List<String>) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@QuickControlsConfigActivity).getGlanceIdBy(widgetId)
            updateAppWidgetState(this@QuickControlsConfigActivity, glanceId) { it[QuickControlsWidget.KEY_PRESETS] = keys.joinToString(",") }
            QuickControlsWidget().update(this@QuickControlsConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

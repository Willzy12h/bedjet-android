package io.github.willzy12h.nocturne.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.ui.MainActivity
import io.github.willzy12h.nocturne.ui.NocturneTheme
import io.github.willzy12h.nocturne.ui.ThemeDuePrompt
import io.github.willzy12h.nocturne.ui.heldForReorder
import io.github.willzy12h.nocturne.ui.rememberThemePresentation
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Opens Nocturne's quick preset editor, where the temperatures, times and fan of the presets are set. */
internal fun editPresetsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_EDIT_PRESETS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** The "Edit presets in Nocturne" button and the line saying the presets themselves are set in the app. */
@Composable
internal fun EditPresetsInApp(context: Context) {
    Text(stringResource(R.string.widget_presets_in_app), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton({ context.startActivity(editPresetsIntent(context)) }, Modifier.fillMaxWidth().testTag("edit-presets")) {
        Text(stringResource(R.string.widget_edit_presets))
    }
}

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
            val state = getAppWidgetState(this@QuickControlsConfigActivity, PreferencesGlanceStateDefinition, glanceId)
            show(widgetId, controlKeys(this@QuickControlsConfigActivity, state[QuickControlsWidget.KEY_PRESETS]),
                state[QuickControlsWidget.KEY_LAYOUT] == QuickControlsWidget.LAYOUT_SIX)
        }
    }

    private fun show(widgetId: Int, chosen: List<String>, sixSaved: Boolean) {
        // Chosen presets first, in their order, then the rest of the choices unticked.
        val everything = widgetPresetChoices(this).map { it.key }.filter { it != PRESET_OFF }
        setContent {
            val settings by graph.repository.settings.collectAsState()
            val theme = rememberThemePresentation(settings, graph::now)
            var order by remember { mutableStateOf(chosen + everything.filter { it !in chosen }) }
            var ticked by remember { mutableStateOf(chosen.toSet()) }
            var six by remember { mutableStateOf(sixSaved) }
            // The row being carried and how far, so the others slide out of its way as it passes them.
            var held by remember { mutableStateOf<Int?>(null) }
            var carried by remember { mutableFloatStateOf(0f) }
            val room = with(LocalDensity.current) { (56.dp + 8.dp).toPx() }
            fun landing(from: Int, distance: Float) = (from + (distance / room).roundToInt()).coerceIn(0, order.lastIndex)
            fun move(from: Int, to: Int) { if (from != to) order = order.toMutableList().apply { add(to, removeAt(from)) } }
            val target = held?.let { landing(it, carried) }
            NocturneTheme(settings, theme.at, paletteOverride = theme.palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.widget_controls_config), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.widget_controls_config_drag), style = MaterialTheme.typography.bodyMedium)
                        order.forEachIndexed { index, presetKey ->
                            val label = widgetPreset(this@QuickControlsConfigActivity, presetKey)?.label ?: return@forEachIndexed
                            key(presetKey) {
                                val from = held
                                val shift by animateFloatAsState(when {
                                    from == null || target == null || index == from -> 0f
                                    index in (from + 1)..target -> -room
                                    index in target until from -> room
                                    else -> 0f
                                }, if (from != null) tween(150) else snap(), label = "controls-shift")
                                val up = stringResource(R.string.widget_move_up, label)
                                val down = stringResource(R.string.widget_move_down, label)
                                Row(Modifier.fillMaxWidth().height(56.dp).testTag("controls-choice-$presetKey")
                                    .graphicsLayer { if (held != index) translationY = shift }
                                    .heldForReorder(held == index) { carried }
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                    .pointerInput(presetKey, index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { held = index; carried = 0f },
                                            onDrag = { change, amount -> change.consume(); carried += amount.y },
                                            onDragEnd = { move(index, landing(index, carried)); held = null; carried = 0f },
                                            onDragCancel = { held = null; carried = 0f })
                                    }
                                    .semantics {
                                        customActions = listOfNotNull(
                                            CustomAccessibilityAction(up) { move(index, index - 1); true }.takeIf { index > 0 },
                                            CustomAccessibilityAction(down) { move(index, index + 1); true }.takeIf { index < order.lastIndex })
                                    },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(presetKey in ticked, { ticked = if (it) ticked + presetKey else ticked - presetKey })
                                    Text(label, Modifier.weight(1f))
                                    Icon(painterResource(R.drawable.ic_nc_grip), stringResource(R.string.widget_drag_handle, label),
                                        Modifier.padding(end = 16.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(stringResource(R.string.widget_layout_title), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.widget_layout_help), style = MaterialTheme.typography.bodyMedium)
                        listOf(false to R.string.widget_layout_night, true to R.string.widget_layout_six).forEach { (option, text) ->
                            Row(Modifier.fillMaxWidth().height(56.dp).testTag(if (option) "layout-six" else "layout-night")
                                .selectable(six == option, role = Role.RadioButton) { six = option },
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(six == option, null)
                                Text(stringResource(text), Modifier.padding(start = 12.dp))
                            }
                        }
                        EditPresetsInApp(this@QuickControlsConfigActivity)
                        FilledTonalButton({ save(widgetId, order.filter { it in ticked }, six) }, Modifier.fillMaxWidth().testTag("controls-save")) {
                            Text(stringResource(R.string.widget_controls_save))
                        }
                    }
                }
                ThemeDuePrompt(theme)
            }
        }
    }

    private fun save(widgetId: Int, keys: List<String>, six: Boolean) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@QuickControlsConfigActivity).getGlanceIdBy(widgetId)
            updateAppWidgetState(this@QuickControlsConfigActivity, glanceId) {
                it[QuickControlsWidget.KEY_PRESETS] = keys.joinToString(",")
                it[QuickControlsWidget.KEY_LAYOUT] = if (six) QuickControlsWidget.LAYOUT_SIX else QuickControlsWidget.LAYOUT_NIGHT
            }
            QuickControlsWidget().update(this@QuickControlsConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

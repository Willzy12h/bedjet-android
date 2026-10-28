package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Settings
import kotlin.math.abs

/** Reorders actual sections, with a compact view for dragging sections whose content spans several screens. */
@Composable
internal fun TonightSections(settings: Settings, onOrder: (List<String>) -> Unit,
    onCollapse: (String) -> Unit, afterSection: @Composable (String) -> Unit = {},
    content: @Composable (String) -> Unit) {
    val order = settings.normalisedTonightOrder()
    val currentOrder by rememberUpdatedState(order)
    val saveOrder by rememberUpdatedState(onOrder)
    var arranging by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragStart by remember { mutableFloatStateOf(0f) }
    val bounds = remember { mutableMapOf<String, Rect>() }
    val upAction = stringResource(R.string.ux_move_up_action)
    val downAction = stringResource(R.string.ux_move_down_action)
    fun move(id: String, destination: Int) {
        val from = currentOrder.indexOf(id)
        if (from < 0 || destination !in currentOrder.indices || from == destination) return
        saveOrder(currentOrder.toMutableList().apply { removeAt(from); add(destination, id) })
    }
    TextButton(onClick = { arranging = !arranging }) {
        Text(stringResource(if (arranging) R.string.ux_done_reordering else R.string.ux_reorder_sections))
    }
    if (arranging) Text(stringResource(R.string.ux_reorder_hint), style = MaterialTheme.typography.bodySmall)
    order.forEachIndexed { index, id -> key(id) {
        val title = stringResource(when (id) {
            "wake" -> R.string.ux_section_wake
            "connection" -> R.string.ux_section_connection
            else -> R.string.ux_section_night
        })
        val savedCollapsed = id in settings.collapsedTonightSections
        val collapsed = arranging || savedCollapsed
        val action = stringResource(if (collapsed) R.string.gpt12_expand_section else R.string.gpt12_collapse_section, title)
        val state = stringResource(if (collapsed) R.string.ux_collapsed else R.string.ux_expanded)
        Column(Modifier.fillMaxWidth().testTag("tonight-section-$id").zIndex(if (dragging == id) 1f else 0f),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .onGloballyPositioned { if (dragging != id) bounds[id] = it.boundsInRoot() }
                .graphicsLayer { translationY = if (dragging == id) dragDistance else 0f }
                .semantics {
                    heading()
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(upAction) { move(id, index - 1); true })
                        if (index < order.lastIndex) add(CustomAccessibilityAction(downAction) { move(id, index + 1); true })
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { arranging = true }, modifier = Modifier.testTag("drag-$id")
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = id; dragDistance = 0f; dragStart = bounds[id]?.center?.y ?: 0f },
                            onDragCancel = { dragging = null; dragDistance = 0f },
                            onDragEnd = {
                                val destination = currentOrder.indices.minByOrNull { candidate ->
                                    abs((bounds[currentOrder[candidate]]?.center?.y ?: Float.MAX_VALUE) - (dragStart + dragDistance))
                                }
                                if (destination != null) move(id, destination)
                                dragging = null; dragDistance = 0f
                            },
                        ) { change, delta -> change.consume(); dragDistance += delta.y }
                    }) {
                    Icon(Icons.Default.DragHandle, stringResource(R.string.ux_drag_section, title))
                }
                TextButton(onClick = {
                    if (arranging) {
                        arranging = false
                        if (savedCollapsed) onCollapse(id)
                    } else onCollapse(id)
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    heading()
                    contentDescription = action
                    stateDescription = state
                }) {
                    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f))
                    Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null)
                }
                IconButton(onClick = { move(id, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Default.ArrowUpward, stringResource(R.string.ux_move_up, title))
                }
                IconButton(onClick = { move(id, index + 1) }, enabled = index < order.lastIndex) {
                    Icon(Icons.Default.ArrowDownward, stringResource(R.string.ux_move_down, title))
                }
            }
            if (!collapsed) content(id)
        }
        afterSection(id)
    } }
}

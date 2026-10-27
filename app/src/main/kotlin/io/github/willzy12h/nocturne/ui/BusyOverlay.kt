package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R

/** Drawn over Tonight's heading, never as a child of its scrolling controls. Idle emits no layout. */
@Composable
fun BusyIndicator(model: AppViewModel) {
    val local by model.busy.collectAsState()
    val owner by model.commandBusy.collectAsState()
    if (!local && owner.runningLabel == null && owner.queuedCount == 0) return
    val label = owner.runningLabel?.let { stringResource(R.string.busy_running, it) }
        ?: stringResource(R.string.busy_waiting)
    val queued = owner.queuedCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.busy_queued, it, it) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("busy-overlay"),
        shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rememberMotionAllowed()) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { .5f }, modifier = Modifier.fillMaxWidth())
            Text(listOfNotNull(label, queued).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

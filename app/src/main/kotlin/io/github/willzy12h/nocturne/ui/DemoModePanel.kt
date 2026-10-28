package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R

/** Demo is a separate, disposable session. The main scaffold keeps its exit visible across tabs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoModePanel(model: AppViewModel, showTryDemo: Boolean = true, showBanner: Boolean = true) {
    val settings by model.settings.collectAsState()
    if (settings.demoMode) {
        if (showBanner) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.gpt12_demo_banner), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(vertical = 8.dp))
                TextButton(onClick = model::exitDemo, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gpt12_exit_demo))
                }
            }
        }
    } else if (showTryDemo) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_use_demo_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::useDemo, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.setup_use_demo))
            }
        }
    }
}

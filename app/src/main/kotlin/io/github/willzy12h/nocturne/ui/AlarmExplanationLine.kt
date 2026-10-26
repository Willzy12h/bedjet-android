package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.AlarmSettingsTarget
import io.github.willzy12h.nocturne.night.description

/** A small, wrapping explanation with a full-width touch target, including in the simple timeline. */
@Composable
fun AlarmExplanationLine(explanation: AlarmExplanation, onClick: () -> Unit) {
    val action = stringResource(when (explanation.target) {
        AlarmSettingsTarget.ALARM_SOURCES -> R.string.alarm_source_open_sources
        AlarmSettingsTarget.PROFILE_WAKE -> R.string.alarm_source_open_profile
        AlarmSettingsTarget.TONIGHT_WAKE -> R.string.alarm_source_open_tonight
    })
    Text(explanation.description(LocalContext.current),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick).padding(vertical = 8.dp))
}

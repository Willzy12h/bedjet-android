package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode

/** The name stays a distinct text node; the secondary limit comes from the protocol model in the chosen unit. */
@Composable
fun ModeLabel(mode: Mode, fahrenheit: Boolean = LocalTemperatureFahrenheit.current) {
    Column {
        Text(modeName(mode))
        if (mode.usesFan) Text(stringResource(R.string.ux_mode_max, tempText(mode.temperatureRange.last, fahrenheit)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

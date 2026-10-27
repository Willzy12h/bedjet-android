package io.github.willzy12h.nocturne.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState

/** These switches show the unit's last receipt; requesting a change never moves them optimistically. */
@Composable
internal fun DeviceControls(model: AppViewModel) {
    val status by model.status.collectAsState()
    val state by model.linkState.collectAsState()
    val hasLiveStatus by model.hasLiveStatus.collectAsState()
    val canControl = state == LinkState.READY && hasLiveStatus
    val extra = status?.extra ?: return
    val lightsOn = stringResource(R.string.remote_lights_on)
    val lightsOff = stringResource(R.string.remote_lights_off)
    val beepsOn = stringResource(R.string.remote_beeps_on)
    val beepsOff = stringResource(R.string.remote_beeps_off)
    BusyIndicator(model)
    SwitchRow(stringResource(R.string.remote_lights), null, extra.ledEnabled, enabled = canControl) { on ->
        model.remote(if (on) lightsOn else lightsOff, confirms = { it.extra?.ledEnabled == on }) { it.setLed(on) }
    }
    SwitchRow(stringResource(R.string.remote_beeps), null, !extra.beepsMuted, enabled = canControl) { on ->
        model.remote(if (on) beepsOn else beepsOff, confirms = { it.extra?.beepsMuted == !on }) { it.setMuted(!on) }
    }
}

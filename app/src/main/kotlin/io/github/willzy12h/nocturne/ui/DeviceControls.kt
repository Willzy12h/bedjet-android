package io.github.willzy12h.nocturne.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState
import kotlinx.coroutines.launch

/** These switches show the unit's last receipt; requesting a change never moves them optimistically. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DeviceControls(model: AppViewModel, scroll: ScrollState, viewportHeight: Int) {
    val status by model.status.collectAsState()
    val state by model.linkState.collectAsState()
    val hasLiveStatus by model.hasLiveStatus.collectAsState()
    val canControl = state == LinkState.READY && hasLiveStatus
    val extra = status?.extra ?: return
    val lightsOn = stringResource(R.string.remote_lights_on)
    val lightsOff = stringResource(R.string.remote_lights_off)
    val beepsOn = stringResource(R.string.remote_beeps_on)
    val beepsOff = stringResource(R.string.remote_beeps_off)
    val lightsRequester = remember { BringIntoViewRequester() }
    val beepsRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<BringIntoViewRequester?>(null) }
    var scrollAtTap by remember { mutableStateOf(0) }
    var heightAtTap by remember { mutableStateOf(0) }
    LaunchedEffect(viewportHeight, scroll.value, pending) {
        val requester = pending ?: return@LaunchedEffect
        if (scroll.value != scrollAtTap) pending = null
        else if (viewportHeight < heightAtTap) {
            pending = null
            scope.launch { requester.bringIntoView() }
        }
    }
    fun watch(requester: BringIntoViewRequester): Modifier = Modifier.bringIntoViewRequester(requester)
    fun interacted(requester: BringIntoViewRequester) {
        scrollAtTap = scroll.value
        heightAtTap = viewportHeight
        pending = requester
    }
    SwitchRow(stringResource(R.string.remote_lights), null, extra.ledEnabled, enabled = canControl, modifier = watch(lightsRequester)) { on ->
        interacted(lightsRequester)
        model.remote(if (on) lightsOn else lightsOff, confirms = { it.extra?.ledEnabled == on }) { it.setLed(on) }
    }
    SwitchRow(stringResource(R.string.remote_beeps), null, !extra.beepsMuted, enabled = canControl, modifier = watch(beepsRequester)) { on ->
        interacted(beepsRequester)
        model.remote(if (on) beepsOn else beepsOff, confirms = { it.extra?.beepsMuted == !on }) { it.setMuted(!on) }
    }
    // Feedback must not move the switches above the scroll viewport when a request finishes.
    BusyIndicator(model)
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R

/** Asks before something replaces the night that's running, so a stray tap doesn't cancel tonight's wake-up. */
fun interface NightGuard {
    /** Runs [action] straight away when no night is running; otherwise asks first. [what] completes "This will …". */
    fun confirm(what: String, action: () -> Unit)
}

@Composable
fun rememberNightGuard(model: AppViewModel): NightGuard {
    val night by model.activeNight.collectAsState()
    val profiles by model.profiles.collectAsState()
    var pending by remember { mutableStateOf<Triple<String, io.github.willzy12h.nocturne.core.night.ActiveNight, () -> Unit>?>(null) }

    pending?.let { (what, running, action) ->
        val name = profiles.firstOrNull { it.id == running?.profileId }?.name ?: stringResource(R.string.tonights_night)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.guard_title)) },
            text = {
                Text(stringResource(R.string.guard_running_waking, name, Format.clock(running.wakeAt), what))
            },
            confirmButton = { TextButton(onClick = { pending = null; model.withConfirmedNight(running.identity, action) }) { Text(stringResource(R.string.guard_confirm)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.guard_keep)) } },
        )
    }
    return NightGuard { what, action -> if (night == null) action() else pending = Triple(what, night!!, action) }
}

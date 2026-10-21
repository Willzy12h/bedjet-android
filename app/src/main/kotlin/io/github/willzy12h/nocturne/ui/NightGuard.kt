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

/** Asks before something replaces the night that's running, so a stray tap doesn't cancel tonight's wake-up. */
fun interface NightGuard {
    /** Runs [action] straight away when no night is running; otherwise asks first. [what] completes "This will …". */
    fun confirm(what: String, action: () -> Unit)
}

@Composable
fun rememberNightGuard(model: AppViewModel): NightGuard {
    val night by model.activeNight.collectAsState()
    val profiles by model.profiles.collectAsState()
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    pending?.let { (what, action) ->
        val running = night
        val name = profiles.firstOrNull { it.id == running?.profileId }?.name ?: "Tonight's night"
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Stop tonight's night?") },
            text = {
                Text(
                    "$name is running" + (running?.let { ", waking you at ${Format.clock(it.wakeAt)}" } ?: "") +
                        ". This will $what, and the night won't continue.",
                )
            },
            confirmButton = { TextButton(onClick = { pending = null; action() }) { Text("Stop night and continue") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep tonight") } },
        )
    }
    return NightGuard { what, action -> if (night == null) action() else pending = what to action }
}

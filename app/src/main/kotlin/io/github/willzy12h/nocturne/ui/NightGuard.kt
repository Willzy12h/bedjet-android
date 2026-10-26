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
    /** Resume returns control to a programme, so its confirmation must not say the night will stop. */
    fun resume(what: String, action: () -> Unit) = confirm(what, action)
}

private data class PendingNightChoice(val what: String, val running: io.github.willzy12h.nocturne.core.night.ActiveNight,
    val action: () -> Unit, val resume: Boolean = false)

@Composable
fun rememberNightGuard(model: AppViewModel): NightGuard {
    val night by model.activeNight.collectAsState()
    val profiles by model.profiles.collectAsState()
    var pending by remember { mutableStateOf<PendingNightChoice?>(null) }

    pending?.let { (what, running, action, resume) ->
        val name = profiles.firstOrNull { it.id == running.profileId }?.name ?: stringResource(R.string.tonights_night)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(if (resume) R.string.guard_resume_title else R.string.guard_title)) },
            text = {
                Text(if (resume) stringResource(R.string.guard_resume_body)
                    else stringResource(R.string.guard_running_waking, name, Format.clock(running.wakeAt), what))
            },
            confirmButton = { TextButton(onClick = { pending = null; model.withConfirmedNight(running.identity, action) }) {
                Text(stringResource(if (resume) R.string.recovery_resume_programme else R.string.guard_confirm)) } },
            dismissButton = { TextButton(onClick = { pending = null }) {
                Text(stringResource(if (resume) R.string.feature_cancel else R.string.guard_keep)) } },
        )
    }
    return object : NightGuard {
        override fun confirm(what: String, action: () -> Unit) {
            if (night == null) action() else pending = PendingNightChoice(what, night!!, action)
        }
        override fun resume(what: String, action: () -> Unit) {
            if (night == null) action() else pending = PendingNightChoice(what, night!!, action, resume = true)
        }
    }
}

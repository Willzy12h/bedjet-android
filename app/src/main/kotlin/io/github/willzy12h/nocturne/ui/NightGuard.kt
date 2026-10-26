package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import kotlin.math.roundToInt

/** Asks before something replaces the night that's running, so a stray tap doesn't cancel tonight's wake-up. */
class NightGuard internal constructor(private val show: (String, TemporaryChoice?, Boolean, () -> Unit) -> Unit) {
    /** Runs [action] straight away when no night is running; otherwise asks first. [what] completes "This will …". */
    fun confirm(what: String, action: () -> Unit) = show(what, null, false, action)
    /** Resume returns control to a programme; its confirmation does not say the night will stop. */
    fun resume(what: String, action: () -> Unit) = show(what, null, true, action)
    internal fun confirm(what: String, temporary: TemporaryChoice, action: () -> Unit) = show(what, temporary, false, action)
}

private data class GuardedAction(val what: String, val night: ActiveNight, val temporary: TemporaryChoice?,
    val end: () -> Unit, val resume: Boolean = false)

@Composable
fun rememberNightGuard(model: AppViewModel): NightGuard {
    val night by model.activeNight.collectAsState()
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<GuardedAction?>(null) }

    pending?.let { captured ->
        val (what, running, temporary, action, resume) = captured
        val name = running.profile?.name ?: profiles.firstOrNull { it.id == running.profileId }?.name ?: stringResource(R.string.tonights_night)
        var minutes by remember(captured) { mutableIntStateOf(temporary?.preset?.minutes ?: 1) }
        val request = temporary?.forMinutes(minutes)
        val preview = remember(request, night, model.now()) { request?.let(model.graph.actions::previewTemporary) }
        var useTemporary by remember(captured) { mutableStateOf(preview?.plan != null) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(when { resume -> R.string.guard_resume_title; temporary == null -> R.string.guard_title; else -> R.string.temporary_title })) },
            text = {
                if (temporary == null) Text(if (resume) stringResource(R.string.guard_resume_body)
                    else stringResource(R.string.guard_running_waking, name, what))
                else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(useTemporary, { useTemporary = true }, label = { Text(stringResource(R.string.temporary_choice)) }, modifier = Modifier.fillMaxWidth())
                    FilterChip(!useTemporary, { useTemporary = false }, label = { Text(stringResource(R.string.temporary_end_choice)) }, modifier = Modifier.fillMaxWidth())
                    if (useTemporary) {
                        Text(stringResource(R.string.temporary_running, name))
                        temporaryDetails(context, request!!, preview!!, settings.fahrenheit).forEach { Text(it) }
                        Text(stringResource(R.string.temporary_duration, durationText(minutes)))
                        Slider(minutes.toFloat().coerceIn(1f, temporary.maximumMinutes.toFloat()),
                            { minutes = it.roundToInt() }, valueRange = 1f..temporary.maximumMinutes.toFloat(),
                            steps = temporary.maximumMinutes - 2,
                            modifier = Modifier.sliderSemantics(stringResource(R.string.slider_minutes), spokenMinutes(minutes)))
                        temporaryReturn(context, preview)?.let { Text(it) }
                        Text(stringResource(R.string.temporary_schedule))
                    } else {
                        Text(stringResource(R.string.guard_running_waking, name, what))
                        requestedEndDetail(context, temporary, settings.fahrenheit)?.let { Text(it) }
                    }
                    preview?.refusal?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = {
                pending = null
                if (useTemporary && request != null) model.runCaptured(request)
                else model.withConfirmedNight(running.identity, action)
            }, enabled = !useTemporary || preview?.plan != null) {
                Text(stringResource(when { resume -> R.string.recovery_resume_programme; useTemporary -> R.string.temporary_confirm; else -> R.string.guard_confirm }))
            } },
            dismissButton = { TextButton(onClick = { pending = null }) {
                Text(stringResource(if (resume) R.string.feature_cancel else R.string.guard_keep)) } },
        )
    }
    return NightGuard { what, temporary, resume, action ->
        val running = model.activeNight.value
        if (running == null) action() else pending = GuardedAction(what, running, temporary, action, resume)
    }
}

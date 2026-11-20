package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.NapProgramme
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.AlarmSource
import java.time.ZonedDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickNapControls(model: AppViewModel, settings: Settings, profile: Profile?, alarm: AlarmSource.NextAlarm?,
    guard: NightGuard, busy: Boolean, nightWakeOffer: Boolean, closeNightOffer: () -> Unit, setNightWake: () -> Unit) {
    var options by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val label = stringResource(R.string.nap_guard)
    val tokens = paletteTokens
    val context = LocalContext.current
    val now = model.now()
    val preview = remember(settings.napPreset, profile, now.toEpochSecond() / 60) {
        runCatching { NapProgramme.plan(settings.napPreset, now, selectedProfile = profile) }
    }
    val plan = preview.getOrNull()
    val duration = plan?.let { java.time.Duration.between(it.start, it.wake).toMinutes().toInt() }
    val tail = plan?.steps.orEmpty().filter { it.block == Block.AFTER_WAKE }
    val startLabel = if (duration == null) stringResource(R.string.nap_title)
        else stringResource(R.string.nap_duration_label, duration)
    fun start(wake: ZonedDateTime? = null, chosenAlarm: AlarmSource.NextAlarm? = null) {
        val invalid = runCatching { NapProgramme.plan(settings.napPreset, model.now(), wake, profile) }.exceptionOrNull()
        if (invalid != null) {
            problem = invalid.message
            return
        }
        val captured = model.captureNap(profile, wake, chosenAlarm)
        options = false
        closeNightOffer()
        guard.confirm(label) { model.runNap(captured) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NocturneOutlinedButton(onClick = { start() }, enabled = !busy && plan != null, modifier = Modifier.testTag("quick-nap-start")) {
                Text(startLabel)
            }
            NocturneTextButton(onClick = { options = true }, enabled = !busy, modifier = Modifier.testTag("quick-nap-options")) {
                Text(stringResource(R.string.nap_options))
            }
        }

        if (tail.isNotEmpty()) {
            val names = tail.map { it.step.mode }.distinct()
            val mode = if (names.size == 1) modeName(names.single()) else stringResource(R.string.nap_post_wake)
            Note(stringResource(R.string.nap_then_off, mode, durationText(tail.sumOf { it.minutes })))
        }
        if (plan == null) preview.exceptionOrNull()?.message?.let { Note(it) }
    }

    if (nightWakeOffer) AlertDialog(onDismissRequest = closeNightOffer,
        title = { Text(stringResource(R.string.nap_night_range_title)) },
        text = { Text(stringResource(R.string.nap_night_range_body)) },
        confirmButton = { NocturneTextButton(onClick = { closeNightOffer(); setNightWake() }, modifier = Modifier.testTag("nap-range-regular")) {
            Text(stringResource(R.string.tonight_choose_wake_and_start))
        } },
        dismissButton = { NocturneTextButton(onClick = { start() }, modifier = Modifier.testTag("nap-range-start"), enabled = plan != null) { Text(startLabel) } })

    if (options) AlertDialog(onDismissRequest = { options = false },
        title = { Text(stringResource(R.string.nap_options)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nap_options_detail))
                if (plan != null && duration != null) {
                    Text(stringResource(R.string.nap_clock_preview, Format.dayClock(plan.wake), Format.dayClock(plan.end)))
                    NocturneOutlinedButton(onClick = { start() }) {
                        Text(stringResource(R.string.nap_for_duration, durationText(duration)))
                    }
                }
                NocturneOutlinedButton(onClick = {
                    val initial = plan?.wake ?: model.now().plusMinutes(settings.napPreset.durationMinutes.toLong())
                    val themed = android.view.ContextThemeWrapper(context, if (tokens.dark)
                        android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
                    TimePickerDialog(themed, { _, hour, minute ->
                        start(WakeResolver.nextOccurrence(hour * 60 + minute, model.now()))
                    }, initial.hour, initial.minute, Format.twentyFourHour).apply { show(); styleNativeDialog(this, tokens) }
                }) { Text(stringResource(R.string.nap_choose_time)) }
                val accepted = alarm?.takeIf { it.counts && it.time.isAfter(model.now()) }
                val alarmProblem = accepted?.let { candidate ->
                    runCatching { NapProgramme.plan(settings.napPreset, model.now(), candidate.time, profile) }.exceptionOrNull()?.message
                }
                if (alarmProblem != null && accepted != null) Text(stringResource(R.string.nap_alarm_reason, Format.dayClock(accepted.time), alarmProblem))
                else if (accepted != null) NocturneOutlinedButton(onClick = { start(accepted.time, accepted) }) {
                    Text(stringResource(R.string.nap_accept_alarm, Format.dayClock(accepted.time)))
                } else Text(stringResource(R.string.nap_alarm_unavailable))
                NocturneTextButton(onClick = { options = false; editing = true }) { Text(stringResource(R.string.nap_edit)) }
            }
        }, confirmButton = { NocturneTextButton(onClick = { options = false }) { Text(stringResource(R.string.feature_cancel)) } })
    problem?.let { detail ->
        AlertDialog(onDismissRequest = { problem = null }, title = { Text(stringResource(R.string.nap_time_unavailable)) },
            text = { Text(detail) }, confirmButton = { NocturneTextButton(onClick = { problem = null }) {
                Text(stringResource(android.R.string.ok))
            } })
    }
    if (editing) NapPresetEditor(settings.napPreset, profile, settings.fahrenheit, model.now(),
        onSave = { preset -> model.updateSettings { it.copy(napPreset = preset) }; editing = false },
        onDismiss = { editing = false })
}

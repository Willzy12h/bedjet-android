package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import androidx.annotation.StringRes
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Preview
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Season
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.night.refusalText
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

private data class PinRequest(val profile: Profile, val target: String?, val workingSlot: Int,
    val previewedAt: ZonedDateTime, val preview: Preview)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit) {
    val context = LocalContext.current
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    model.nextAlarm.collectAsState().value
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var pinning by remember { mutableStateOf<PinRequest?>(null) }
    var pinSlot by remember { mutableStateOf(0) }
    val activeNight by model.activeNight.collectAsState()

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
            items(profiles, key = { it.id }) { profile ->
                var more by remember(profile.id) { mutableStateOf(false) }
                val copyName = stringResource(R.string.profiles_copy_name, profile.name)
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (settings.resolveTonightChoice(model.now())?.profileId == profile.id) Text(stringResource(R.string.profiles_tonight), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                        }
                        val wake = model.wakePreview(profile)
                        Text(
                            if (wake.fromAlarm) stringResource(R.string.profiles_wake_alarm, Format.clock(wake.time))
                            else stringResource(R.string.profiles_wake_default, Format.clock(wake.time), Format.clockOfDay(profile.wakeSource.defaultWakeMinute)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(describe(profile), style = MaterialTheme.typography.bodySmall)
                        model.previewPlan(profile).getOrNull()?.let {
                            NightTimeline(it, wake.origin, settings.fahrenheit,
                                modifier = Modifier.testTag("profile-timeline-${profile.id}"))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { model.updateSettings { it.chooseTonight(profile.id, model.now()) } }) { Text(stringResource(R.string.profiles_use_tonight)) }
                            TextButton(onClick = { onEdit(profile) }) { Text(stringResource(R.string.action_edit)) }
                            TextButton(onClick = { more = true }) { Text(stringResource(R.string.recovery_more)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.pin_menu)) }, onClick = {
                                    more = false
                                    val at = model.now()
                                    pinning = PinRequest(profile, settings.deviceId(), settings.workingSlot, at,
                                        NightPreview.forStartNow(profile, at, null, settings = settings))
                                    pinSlot = (0..2).first { it != settings.workingSlot }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.profiles_copy)) }, onClick = {
                                    more = false
                                    model.saveProfile(profile.copy(id = UUID.randomUUID().toString(), name = copyName))
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.profiles_delete)) }, onClick = { more = false; deleting = profile })
                            }
                        }
                    }
                }
            }
            item { Box(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text(stringResource(R.string.profiles_new)) }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.profiles_start_from)) },
            text = {
                Column {
                    Presets.all.forEach { preset ->
                        TextButton(onClick = {
                            adding = false
                            onEdit(preset.copy(id = UUID.randomUUID().toString(), name = preset.name))
                        }) { Text("${preset.name} (${stringResource(seasonName(preset.season))})") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.feature_cancel)) } },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.profiles_delete_title, profile.name)) },
            text = { Text(stringResource(R.string.profiles_delete_text)) },
            confirmButton = { TextButton(onClick = { model.deleteProfile(profile.id); deleting = null }) { Text(stringResource(R.string.profiles_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.feature_cancel)) } },
        )
    }
    pinning?.let { request ->
        var previewNow by remember(request) { mutableStateOf(model.now()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(request, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { previewNow = model.now(); delay(5_000) }
            }
        }
        val plan = request.preview.plan
        val program = plan?.let { StoredProgram(request.profile.name, it.steps.map { step ->
            step.step.copy(hours = step.minutes / 60, minutes = step.minutes % 60, endsAtClockTime = false)
        }) }
        val age = Duration.between(request.previewedAt.toInstant(), previewNow.toInstant())
        val expired = age.isNegative || age >= Duration.ofMinutes(1)
        val stale = request.target != settings.deviceId() || request.workingSlot != settings.workingSlot ||
            profiles.firstOrNull { it.id == request.profile.id } != request.profile
        AlertDialog(
            onDismissRequest = { pinning = null },
            title = { Text(stringResource(R.string.pin_title, request.profile.name)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pin_slot_help, request.workingSlot + 1))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..2).filter { it != request.workingSlot }.forEach { slot ->
                            OutlinedButton(onClick = { pinSlot = slot }, enabled = !stale) {
                                Text(stringResource(if (pinSlot == slot) R.string.pin_slot_selected else R.string.pin_slot_option, slot + 1))
                            }
                        }
                    }
                    when {
                        request.target == null -> Text(stringResource(R.string.pin_no_target))
                        stale -> Text(stringResource(R.string.pin_stale))
                        expired -> Text(stringResource(R.string.pin_preview_expired))
                        activeNight != null -> Text(stringResource(R.string.pin_active))
                        plan == null -> Text(request.preview.refusalText(context) ?: stringResource(R.string.pin_no_preview))
                        else -> {
                            Text(stringResource(R.string.pin_preview_window, Format.clock(plan.start), Format.clock(plan.end),
                                pluralStringResource(R.plurals.pin_step_count, plan.steps.size, plan.steps.size)))
                            plan.steps.forEach { step ->
                                Text(if (step.step.mode.usesTemperature) stringResource(R.string.pin_preview_step_temp,
                                    Format.clock(step.startsAt), modeName(step.step.mode), durationText(step.minutes),
                                    tempText(step.step.temperature, settings.fahrenheit), (step.step.fanStep + 1) * 5)
                                else stringResource(R.string.pin_preview_step_no_temp,
                                    Format.clock(step.startsAt), modeName(step.step.mode), durationText(step.minutes),
                                    (step.step.fanStep + 1) * 5), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(stringResource(R.string.pin_duration_help))
                        }
                    }
                }
            },
            confirmButton = { TextButton(
                enabled = request.target != null && !stale && !expired && activeNight == null && program != null,
                onClick = { model.pinProfile(request.profile, pinSlot, request.target!!, request.workingSlot,
                    request.previewedAt, program!!); pinning = null },
            ) { Text(stringResource(R.string.pin_confirm, pinSlot + 1)) } },
            dismissButton = { TextButton(onClick = { pinning = null }) { Text(stringResource(R.string.feature_cancel)) } },
        )
    }
}

@Composable
private fun describe(profile: Profile): String {
    val nothing = stringResource(R.string.profiles_nothing)
    val start = profile.start.map { stringResource(R.string.profiles_phase_short, modeName(it.mode), it.minutes) }.joinToString(" → ").ifEmpty { nothing }
    val middle = stringResource(R.string.profiles_middle_short, modeName(profile.middle.mode), profile.middle.fanStart, profile.middle.fanEnd)
    val wake = profile.wake.map { stringResource(R.string.profiles_phase_short, modeName(it.mode), it.minutes) }.joinToString(" → ").ifEmpty { nothing }
    return stringResource(R.string.profiles_summary, start, middle, wake)
}

@StringRes
fun seasonName(season: Season) = when (season) {
    Season.NONE -> R.string.season_any
    Season.SUMMER -> R.string.season_summer
    Season.WINTER -> R.string.season_winter
    Season.SPRING_AUTUMN -> R.string.season_spring_autumn
}

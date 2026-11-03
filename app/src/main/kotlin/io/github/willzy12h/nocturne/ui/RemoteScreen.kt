package io.github.willzy12h.nocturne.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.manualRuntimeMaximum
import kotlin.math.roundToInt

/** The Remote's modes, in the widget's order; Off is always last and always present. */
private val REMOTE_MODES = listOf(Mode.TURBO, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY, Mode.STANDBY)

/** A mode's own colour, as on the widgets: Ember by the mode's hottest setting, Cool, Dry or Off. */
internal fun PaletteTokens.modeColor(mode: Mode): Color = when (mode) {
    Mode.STANDBY, Mode.WAIT -> off
    else -> stepColor(mode, mode.temperatureRange.last)
}

/**
 * Direct control, like the BedJet's own remote. Changing the mode takes over from a running night.
 *
 * Every section is always laid out, so choosing a mode or sending a command never moves the controls:
 * a control that does not apply is shown disabled with the reason underneath, and progress appears
 * in the status card's reserved line instead of a banner above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(model: AppViewModel, modifier: Modifier) {
    val status by model.status.collectAsState()
    val state by model.linkState.collectAsState()
    val hasLiveStatus by model.hasLiveStatus.collectAsState()
    val settings by model.settings.collectAsState()
    val bluetoothOff by model.bluetoothOff.collectAsState()
    val f = settings.fahrenheit
    val tokens = paletteTokens
    val guard = rememberNightGuard(model)
    val canControl = state == LinkState.READY && hasLiveStatus
    val context = LocalContext.current
    val timerSet = stringResource(R.string.remote_timer_set)
    var customDuration by remember { mutableStateOf<CapturedDuration?>(null) }
    fun runDuration(captured: CapturedDuration) {
        val words = Format.Words { id, args -> context.getString(id, *args) }
        val what = context.getString(R.string.remote_custom_action, Format.duration(captured.request.actionPreset!!.minutes, words))
        guard.confirm(what, captured.temporary()) { model.remoteDuration(timerSet, captured.request) }
    }
    customDuration?.let { captured ->
        CustomRunDuration(captured, onDismiss = { customDuration = null }, onConfirm = {
            customDuration = null
            runDuration(it)
        })
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatusLine(model, runningName = null)
        BluetoothBanner(model)
        Text(stringResource(R.string.remote_title), color = tokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        // No BedJet chosen: the route to choosing one, as on Tonight.
        if (settings.deviceId() == null) {
            SetupFlow(model, compact = true)
            return@Column
        }
        val s = status
        if (s == null) {
            if (!bluetoothOff) Text(stringResource(R.string.remote_connect), color = tokens.ink2, fontSize = 15.sp)
            return@Column
        }
        val motion = rememberMotionAllowed()
        val accent by animateColorAsState(tokens.modeColor(if (s.isRunning) s.mode else Mode.STANDBY),
            if (motion) tween(250) else snap(), label = "remote-accent")

        RemoteStatusCard(model, s, canControl, accent, f)

        // Modes: a fixed three-by-two grid of tinted tiles, the selected one stronger and outlined.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            REMOTE_MODES.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode ->
                        val name = modeName(mode)
                        val what = if (mode == Mode.STANDBY) stringResource(R.string.guard_turn_off) else stringResource(R.string.guard_switch_to, name)
                        RemoteModeTile(mode, selected = s.mode == mode, enabled = canControl, fahrenheit = f,
                            modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (mode == Mode.STANDBY) guard.confirm(what) {
                                model.remote(name, endsNight = true, confirms = { it.mode == mode }) { it.setMode(mode) }
                            } else {
                                val captured = captureRemoteTemporary(model.graph, mode, s)
                                guard.confirm(what, captured) { model.remoteCaptured(name, captured.request, mode) }
                            }
                        }
                    }
                }
            }
        }

        val notRunning = stringResource(R.string.remote_choose_mode_first)
        // Temperature: always shown; disabled with a reason when the running mode has none.
        run {
            val usable = s.isRunning && s.mode.usesTemperature
            var temp by remember(s.targetTemperature) { mutableFloatStateOf(s.targetTemperature.toFloat()) }
            val shown = temp.roundToInt()
            val colour by animateColorAsState(if (usable) tokens.stepColor(s.mode, shown) else tokens.ink3,
                if (motion) tween(150) else snap(), label = "remote-temperature-colour")
            val temperatureSet = stringResource(R.string.remote_temperature_set)
            RemoteSection(stringResource(R.string.slider_temp), if (usable) tempText(shown, f) else "—", colour,
                note = when {
                    !s.isRunning -> notRunning
                    !s.mode.usesTemperature -> stringResource(R.string.remote_temperature_unused, modeName(s.mode))
                    else -> null
                }) {
                ScaledSlider(
                    temp, { temp = it }, range = s.minTemperature.toFloat()..s.maxTemperature.toFloat(),
                    enabled = canControl && usable,
                    modifier = Modifier.sliderSemantics(stringResource(R.string.slider_temp), spokenTemp(shown, f)),
                    // Continuous while dragging, so the thumb glides; the value sent is rounded on release.
                    steps = 0,
                    ticks = listOf(s.minTemperature, (s.minTemperature + s.maxTemperature) / 2, s.maxTemperature)
                        .map { it.toFloat() to tempText(it, f) }, tag = "temperature",
                    onValueChangeFinished = { val t = temp.roundToInt(); temp = t.toFloat()
                        model.remote(temperatureSet, affectsProgramme = true, confirms = { it.targetTemperature == t }) { it.setTemperature(t) } },
                    colors = sliderColors(colour),
                )
            }
        }
        // Fan: the same, in the mode's colour; 5% is the lowest running speed.
        run {
            val usable = s.isRunning && s.mode.usesFan
            var fan by remember(s.fanStep) { mutableFloatStateOf(s.fanPercent.toFloat()) }
            val shown = ((fan / 5).roundToInt() * 5).coerceIn(5, 100)
            val colour by animateColorAsState(if (usable) tokens.modeColor(s.mode) else tokens.ink3,
                if (motion) tween(150) else snap(), label = "remote-fan-colour")
            val fanSet = stringResource(R.string.remote_fan_set)
            RemoteSection(stringResource(R.string.slider_fan), if (usable) stringResource(R.string.remote_percent_tick, shown) else "—", colour,
                note = if (!s.isRunning) notRunning else stringResource(R.string.remote_fan_minimum)) {
                ScaledSlider(
                    fan, { fan = it.coerceAtLeast(5f) }, range = 0f..100f, steps = 0,
                    enabled = canControl && usable,
                    modifier = Modifier.sliderSemantics(stringResource(R.string.slider_fan), spokenPercent(shown)),
                    ticks = listOf(0, 50, 100).map { it.toFloat() to stringResource(R.string.remote_percent_tick, it) }, tag = "fan",
                    // Read the slider's latest value here, not this composition's rounded copy, which is a frame behind.
                    onValueChangeFinished = { val percent = ((fan / 5).roundToInt() * 5).coerceIn(5, 100); val step = percent / 5 - 1
                        fan = percent.toFloat()
                        model.remote(fanSet, affectsProgramme = true, confirms = { it.fanStep == step }, announceConfirmation = false) { it.setFan(step) } },
                    colors = sliderColors(colour),
                )
            }
        }
        // Run for: chips wrap within a reserved height, so a mode with fewer choices keeps the same layout.
        run {
            val maximumMinutes = if (s.isRunning) manualRuntimeMaximum(s) else 0
            val timerChoices = listOf(15, 30, 60, 120, 240, 480).filter { it <= maximumMinutes }
                .ifEmpty { listOf(maximumMinutes).filter { it > 0 } }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.remote_run_for), color = tokens.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                FlowRow(Modifier.fillMaxWidth().heightIn(min = 104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timerChoices.forEach { minutes ->
                        AssistChip({ runDuration(captureDuration(model.graph, s, minutes)) }, enabled = canControl, label = { Text(durationText(minutes)) })
                    }
                    AssistChip({ customDuration = captureDuration(model.graph, s,
                        ((s.remainingSeconds + 59) / 60).coerceIn(1, maximumMinutes)) }, enabled = canControl && maximumMinutes > 0,
                        label = { Text(stringResource(R.string.remote_custom)) })
                }
                if (!s.isRunning) Text(notRunning, color = tokens.ink2, fontSize = 13.sp)
            }
        }
        s.extra?.let { extra ->
            val reminderCleared = stringResource(R.string.remote_reminder_cleared)
            if (extra.notification == 1) {
                FilledTonalButton({ model.remote(reminderCleared, confirms = { it.extra?.notification == 0 }) { it.acknowledge() } }, enabled = canControl) { Text(stringResource(R.string.remote_clear_reminder)) }
            }
        }
    }
}

/**
 * What the BedJet reports, as plain text rather than a card, so it reads as the current state and not as a button.
 * The mode buttons below change it. One line is reserved for progress.
 */
@Composable
private fun RemoteStatusCard(model: AppViewModel, s: io.github.willzy12h.nocturne.core.protocol.DeviceStatus, canControl: Boolean,
    accent: Color, f: Boolean) {
    val tokens = paletteTokens
    val local by model.busy.collectAsState()
    val owner by model.commandBusy.collectAsState()
    val sending = if (local || owner.runningLabel != null || owner.queuedCount > 0) {
        val label = owner.runningLabel?.let { stringResource(R.string.busy_running, it) } ?: stringResource(R.string.busy_waiting)
        val queued = owner.queuedCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.busy_queued, it, it) }
        listOfNotNull(label, queued).joinToString(" · ")
    } else null
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }
        .testTag("remote-status").padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.remote_now_label), color = tokens.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(modeIcon(s.mode)), null, Modifier.size(20.dp), tint = tokens.readableText(accent, tokens.ground, 3f))
            Column(Modifier.weight(1f)) {
                Text(modeName(s.mode), color = tokens.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.remote_air_room, tempText(s.actualTemperature, f), tempText(s.ambientTemperature, f)),
                    color = tokens.ink2, fontSize = 14.sp)
            }
        }
        Text(if (s.isRunning) stringResource(R.string.remote_running, tempText(s.targetTemperature, f), s.fanPercent, durationText(s.remainingSeconds / 60))
            else stringResource(R.string.remote_not_running_choose), color = tokens.ink2, fontSize = 14.sp)
        // The reserved line: progress, then "Last reported", then a night's step; never a banner that pushes controls down.
        val line = sending ?: if (!canControl) stringResource(R.string.remote_last_reported)
            else s.extra?.takeIf { it.programStep > 0 }?.let { stringResource(R.string.remote_program_step, it.programStep) } ?: ""
        Text(line, color = if (sending != null) tokens.readableText(tokens.warn, tokens.ground) else tokens.ink2, fontSize = 13.sp,
            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("remote-progress").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** A widget-style mode tile: icon, name and limit, tinted in the mode's colour. */
@Composable
private fun RemoteModeTile(mode: Mode, selected: Boolean, enabled: Boolean, fahrenheit: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = paletteTokens
    val colour = tokens.modeColor(mode)
    val shape = RoundedCornerShape(16.dp)
    val alpha = if (enabled) 1f else .45f
    Column(modifier.heightIn(min = 76.dp).clip(shape)
        .background(tokens.tint(colour, if (selected) TintLevel.ON else TintLevel.TILE).compositeOver(tokens.surface))
        .border(if (selected) 2.dp else 1.dp, if (selected) colour else tokens.outline, shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .testTag("remote-mode-${mode.name}").padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(modeIcon(mode)), null, Modifier.size(20.dp),
            tint = tokens.readableText(colour, tokens.surface, 3f).copy(alpha = alpha))
        CompositionLocalProvider(LocalContentColor provides tokens.ink.copy(alpha = alpha)) {
            ModeLabel(mode, fahrenheit)
        }
    }
}

/** A titled control with its value large on the right in the control's colour, and an optional note. */
@Composable
private fun RemoteSection(title: String, value: String, colour: Color, note: String?, content: @Composable () -> Unit) {
    val tokens = paletteTokens
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(tokens.surface)
        .then(tokens.edge?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = tokens.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(value, color = tokens.readableText(colour, tokens.surface, 3f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        content()
        if (note != null) Text(note, color = tokens.ink2, fontSize = 13.sp)
    }
}

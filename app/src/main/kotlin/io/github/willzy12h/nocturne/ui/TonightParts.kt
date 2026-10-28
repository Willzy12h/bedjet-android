package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.boostDescription
import java.time.Duration
import java.time.ZonedDateTime

/** The design's boost stripe (`--boost`); no palette token carries it. */
private val BoostColor = Color(0xFFFF6FA0)

/** The design's `.lbl`: 10.5, bold, uppercase, ink3. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) = Text(text.uppercase(), modifier,
    color = paletteTokens.ink3, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)

/** A text link in the accent colour (600, 11.5), with a 48 dp touch target. */
@Composable
internal fun AccentLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null,
    enabled: Boolean = true, fontSize: TextUnit = 11.5.sp) {
    Row(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp)
        .alpha(if (enabled) 1f else .4f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { Icon(painterResource(it), null, Modifier.size(12.dp), tint = paletteTokens.accent) }
        Text(text, color = paletteTokens.accent, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
    }
}

/** The design's status line: a dot and one short sentence, with Remote on the right. */
@Composable
internal fun StatusLine(model: AppViewModel, runningName: String?, onRemote: () -> Unit) {
    val settings by model.settings.collectAsState()
    val state by model.linkState.collectAsState()
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val bluetoothOff by model.bluetoothOff.collectAsState()
    val released by model.graph.sessions.released.collectAsState()
    val selectedName by model.selectedBedJetName.collectAsState()
    val owner by model.commandBusy.collectAsState()
    val tokens = paletteTokens
    val connection = headerConnection(settings.demoMode, settings.targetDevice != null, released,
        bluetoothOff, state, live && status != null)
    val name = when {
        settings.demoMode -> stringResource(R.string.status_demo_device)
        settings.targetDevice == null -> stringResource(R.string.status_no_device)
        else -> selectedName ?: DeviceId.address(settings.targetDevice!!) ?: stringResource(R.string.settings_bedjet)
    }
    val sending = owner.runningLabel != null
    val text = when {
        sending -> stringResource(R.string.parity_status_sending)
        connection == HeaderConnection.DEMO -> stringResource(R.string.parity_status_demo)
        connection == HeaderConnection.BLUETOOTH_OFF -> stringResource(R.string.status_bluetooth_off)
        runningName != null && connection == HeaderConnection.CONNECTED -> stringResource(R.string.parity_status_running, runningName)
        else -> stringResource(R.string.parity_status_line, name, stringResource(when (connection) {
            HeaderConnection.CONNECTED -> R.string.parity_status_connected
            HeaderConnection.CONNECTING -> R.string.parity_status_connecting
            else -> R.string.parity_status_offline
        }))
    }
    val dot = when {
        sending -> tokens.warn
        else -> connectionSymbolColour(connection, tokens.dark)
    }
    val description = stringResource(R.string.uat_connection_description, name, stringResource(connection.text))
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("status-header"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(dot, 8, Modifier.testTag("status-symbol-${connection.name}"))
        Text(text, Modifier.weight(1f).testTag("status-connection").semantics { contentDescription = "$description. $text" },
            color = tokens.ink2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        if (settings.demoMode) AccentLink(stringResource(R.string.gpt12_exit_demo), model::exitDemo)
        AccentLink(stringResource(R.string.parity_remote_link), onRemote, Modifier.testTag("open-remote"))
    }
}

private class BarSegment(val minutes: Long, val colours: List<Color>)

private fun barSegments(plan: Plan, tokens: PaletteTokens): List<BarSegment> {
    val segments = mutableListOf<BarSegment>()
    var middle = mutableListOf<Color>()
    var middleMinutes = 0L
    fun flush() {
        if (middle.isNotEmpty()) segments += BarSegment(middleMinutes, middle.toList())
        middle = mutableListOf(); middleMinutes = 0
    }
    plan.steps.forEach { step ->
        val colour = tokens.stepColor(step.step.mode, step.step.temperature)
        if (step.block == Block.MIDDLE) { middle += colour; middleMinutes += step.minutes }
        else { flush(); segments += BarSegment(step.minutes.toLong(), listOf(colour)) }
    }
    flush()
    return segments.filter { it.minutes > 0 }
}

/** The design's segmented night: 2 dp gaps, round outer ends, a gradient across the Middle ramp, and a Now marker. */
@Composable
internal fun NightBar(plan: Plan, now: ZonedDateTime?, modifier: Modifier = Modifier, height: Dp = 30.dp) {
    val tokens = paletteTokens
    val segments = barSegments(plan, tokens)
    val total = segments.sumOf { it.minutes }.coerceAtLeast(1)
    val span = Duration.between(plan.start, plan.end).toMinutes().coerceAtLeast(1)
    val nowFraction = now?.let { (Duration.between(plan.start, it).toMinutes().toFloat() / span).coerceIn(0f, 1f) }
    Canvas(modifier.fillMaxWidth().height(height).testTag("night-bar")) {
        val gap = 2.dp.toPx()
        val outer = CornerRadius(minOf(10.dp.toPx(), size.height / 2))
        val inner = CornerRadius(minOf(3.dp.toPx(), size.height / 2))
        val available = size.width - gap * (segments.size - 1).coerceAtLeast(0)
        var x = 0f
        segments.forEachIndexed { i, segment ->
            val w = available * segment.minutes / total
            val left = if (i == 0) outer else inner
            val right = if (i == segments.lastIndex) outer else inner
            val path = Path().apply {
                addRoundRect(RoundRect(x, 0f, x + w, size.height, topLeftCornerRadius = left, topRightCornerRadius = right,
                    bottomRightCornerRadius = right, bottomLeftCornerRadius = left))
            }
            val brush = if (segment.colours.size > 1) Brush.horizontalGradient(segment.colours, startX = x, endX = x + w)
                else SolidColor(segment.colours.first())
            drawPath(path, brush)
            x += w + gap
        }
        nowFraction?.let {
            val at = (size.width * it).coerceIn(1.5.dp.toPx(), size.width - 1.5.dp.toPx())
            drawLine(Color.White, Offset(at, -2.dp.toPx()), Offset(at, size.height + 2.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** "Cool 20° → 24°": the Middle's mode and its first and last temperatures. */
@Composable
internal fun planSummary(plan: Plan, fahrenheit: Boolean): String? {
    val steps = plan.steps.filter { it.block == Block.MIDDLE }.ifEmpty { plan.steps }
    val first = steps.firstOrNull() ?: return null
    val name = modeName(first.step.mode)
    if (!first.step.mode.usesTemperature) return name
    val from = tempText(first.step.temperature, fahrenheit)
    val to = tempText(steps.last().step.temperature, fahrenheit)
    return if (from == to) stringResource(R.string.parity_summary_single, name, from)
        else stringResource(R.string.parity_summary_range, name, from, to)
}

internal fun modeIcon(mode: Mode): Int = when (mode) {
    Mode.TURBO -> R.drawable.ic_nc_turbo
    Mode.HEAT, Mode.EXTENDED_HEAT -> R.drawable.ic_nc_heat
    Mode.COOL -> R.drawable.ic_nc_cool
    Mode.DRY -> R.drawable.ic_nc_dry
    else -> R.drawable.ic_nc_off
}

/** The preset the person tapped, shown as "Heat… / Sending" until the BedJet has taken the command. */
internal data class PresetSending(val key: String, val title: String, val color: Color)

/** Holds [PresetSending] and clears it once the command queue has drained (or after 15 s if it never started). */
@Composable
internal fun rememberPresetSending(model: AppViewModel): MutableState<PresetSending?> {
    val state = remember { mutableStateOf<PresetSending?>(null) }
    val owner by model.commandBusy.collectAsState()
    val busy = owner.runningLabel != null || owner.queuedCount > 0
    var seenBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy, state.value) {
        if (state.value == null) seenBusy = false
        else if (busy) seenBusy = true
        else if (seenBusy) { state.value = null; seenBusy = false }
    }
    LaunchedEffect(state.value) { if (state.value != null) { delay(15_000); state.value = null } }
    return state
}

/** The design's `.tile .pd`: a small amber dot that pulses while a command is on its way. */
@Composable
private fun SendingDot(tag: String) {
    val alpha = if (rememberMotionAllowed()) rememberInfiniteTransition(label = "pd").animateFloat(1f, .35f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pd").value else 1f
    Box(Modifier.testTag(tag).alpha(alpha)) { Dot(paletteTokens.warn, 7) }
}

/** One `.tile`: stripe, icon and name on one line, the detail below. */
@Composable
private fun PresetTile(stripe: Color, icon: Int, title: String, detail: String?, active: Boolean, enabled: Boolean,
    description: String, tag: String, modifier: Modifier, sending: Boolean = false, onClick: () -> Unit) {
    StripeTile(stripe = stripe, title = if (sending) "$title…" else title,
        subtitle = if (sending) stringResource(R.string.parity_sending) else detail ?: " ",
        level = if (active || sending) TintLevel.ON else TintLevel.TILE, selectedBorder = if (active) stripe else null,
        titleFontWeight = FontWeight.Bold,
        icon = { Icon(painterResource(icon), null, Modifier.size(16.dp), tint = stripe) },
        trailing = {
            if (sending) SendingDot(tag.replace("quick-mode-", "quick-sending-"))
            else if (active) Box(Modifier.testTag(tag.replace("quick-mode-", "quick-active-"))) { Dot(stripe, 7) }
        },
        modifier = modifier.heightIn(min = 56.dp).alpha(if (enabled) 1f else .5f).testTag(tag)
            .semantics { contentDescription = description }.clickable(role = Role.Button, onClick = onClick))
}

/** The design's QUICK PRESETS: three columns of Turbo, Heat, Cool, Dry, Boost and Off. */
@Composable
internal fun QuickPresets(model: AppViewModel, guard: NightGuard, sending: MutableState<PresetSending?>, onEdit: () -> Unit) {
    val context = LocalContext.current
    val settings by model.settings.collectAsState()
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val link by model.linkState.collectAsState()
    val tokens = paletteTokens
    val canControl = live && link == io.github.willzy12h.nocturne.core.link.LinkState.READY
    val evidence = quickEvidence(model)
    Column(Modifier.testTag("quick-controls"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.parity_quick_presets), Modifier.weight(1f))
            AccentLink(stringResource(R.string.action_edit), onEdit, icon = R.drawable.ic_nc_edit)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Mode.TURBO, Mode.HEAT, Mode.COOL, Mode.DRY, null, Mode.STANDBY).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { QuickPresetTile(model, guard, it, evidence, canControl, sending, Modifier.weight(1f)) }
                }
            }
        }
        if (QuickDefaults.modes.any { !QuickDefaults.valid(QuickDefaults.resolve(settings, it)) })
            Text(stringResource(R.string.uat_quick_invalid), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface)
    }
}

/** One preset; null is Boost. */
@Composable
private fun QuickPresetTile(model: AppViewModel, guard: NightGuard, mode: Mode?, evidence: DeviceStatus?, canControl: Boolean,
    sending: MutableState<PresetSending?>, modifier: Modifier) {
    val context = LocalContext.current
    val settings by model.settings.collectAsState()
    val tokens = paletteTokens
    when (mode) {
        null -> {
            val startBoost = stringResource(R.string.guard_start_boost)
            PresetTile(BoostColor, R.drawable.ic_nc_ramp, stringResource(R.string.shortcut_boost),
                stringResource(R.string.boost_label_turbo_then_heat_compact), false, canControl,
                boostDescription(context, settings.boostMinutes), "quick-mode-BOOST", modifier,
                sending.value?.key == "BOOST") {
                val captured = model.quickAction(Command.BOOST)
                val title = context.getString(R.string.shortcut_boost)
                guard.confirm(startBoost, captured.temporary) {
                    sending.value = PresetSending("BOOST", title, BoostColor); model.runCaptured(captured.end)
                }
            }
        }
        Mode.STANDBY -> {
            val label = stringResource(Format.modeNameRes(Mode.STANDBY))
            PresetTile(tokens.off, R.drawable.ic_nc_off, label, null, evidence?.mode == Mode.STANDBY, canControl,
                label, "quick-mode-${Mode.STANDBY.name}", modifier, sending.value?.key == Mode.STANDBY.name) {
                guard.confirm(context.getString(R.string.guard_turn_off)) {
                    sending.value = PresetSending(Mode.STANDBY.name, label, tokens.off); model.run(Command.OFF)
                }
            }
        }
        else -> {
            val preset = QuickDefaults.resolve(settings, mode)
            val valid = QuickDefaults.valid(preset)
            val label = stringResource(Format.modeNameRes(mode))
            val detail = preset.takeIf { valid }?.let {
                val duration = durationText(it.minutes)
                if (it.mode.usesTemperature) stringResource(R.string.quick_tile_detail, tempText(it.temperature, settings.fahrenheit), duration)
                else duration
            }
            PresetTile(tokens.stepColor(mode, preset.temperature), modeIcon(mode), label, detail, quickMatches(evidence, preset),
                canControl, if (valid) presetDetail(context, preset, settings.fahrenheit) else stringResource(R.string.uat_quick_invalid),
                "quick-mode-${mode.name}", modifier, sending.value?.key == mode.name) {
                val captured = captureModeQuick(model, preset)
                val color = tokens.stepColor(mode, preset.temperature)
                guard.confirm(context.getString(R.string.guard_switch_to, label), captured.temporary) {
                    sending.value = PresetSending(mode.name, label, color); model.runCaptured(captured.end)
                }
            }
        }
    }
}

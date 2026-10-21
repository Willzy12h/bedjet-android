package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/**
 * Tonight, in three layouts over the same parts: Midnight Ink (dark) leads with a big wake time; Daylight (light)
 * puts start and wake in two stat tiles and labels the curve; simple view swaps the curve for a list of steps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit) {
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    val night by model.activeNight.collectAsState()
    val alarm by model.nextAlarm.collectAsState()
    val busy by model.busy.collectAsState()
    val release by model.release.collectAsState()
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = ZonedDateTime.now(); model.refreshAlarm() } }
    val dark = nocturneColors.dark
    val simple = settings.simpleView

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tonight", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        ConnectionLine(model)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        SetupChecks()
        release?.let { latest ->
            val uri = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = model::dismissRelease,
                title = { Text("Nocturne ${latest.tag} is available") },
                text = { Text(latest.notes.ifBlank { "A new version is ready to download." }) },
                confirmButton = { TextButton(onClick = { uri.openUri(latest.apkUrl ?: latest.pageUrl); model.dismissRelease() }) { Text("Download") } },
                dismissButton = { TextButton(onClick = model::dismissRelease) { Text("Later") } },
            )
        }

        val running = night
        if (running != null) {
            val plan = remember(running, profiles) { model.activePlan() }
            val profile = profiles.firstOrNull { it.id == running.profileId }
            val startedAt = runCatching { ZonedDateTime.parse(running.startedAt) }.getOrNull()
            Text(profile?.name ?: "Night running", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                simple -> Unit // The timeline below ends with the wake time.
                dark -> WakeHeadline("Waking you at", Format.clock(running.wakeAt), running.wakeFromAlarm)
                else -> StatTiles(startedAt?.let(Format::clock) ?: "--:--", "Started", Format.clock(running.wakeAt), running.wakeFromAlarm, waking = true)
            }
            plan?.stepAt(now)?.second?.let { step ->
                Text(
                    "Now: ${blockName(step.block)} · ${Format.modeName(step.step.mode)} · fan ${(step.step.fanStep + 1) * 5}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // Mid-night the controls come before the plan: they're what you reach for, and up here the
            // "night started" snackbar at the bottom of the screen doesn't cover them.
            BigButton("End night", onClick = { model.run(Command.END_NIGHT) })
            OutlinedButton(onClick = { model.run(Command.RETIME) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Re-check alarm") }
            plan?.let { PlanCard(it, settings, running.wakeFromAlarm, now = now) }
        } else {
            var chosen by remember(settings.selectedProfileId, profiles) { mutableStateOf(model.tonightsProfile()) }
            val profile = chosen
            val wake = profile?.let { model.wakePreview(it, now) }
            if (wake != null && !simple) {
                if (dark) WakeHeadline("Wakes you at", Format.clock(wake.time), wake.fromAlarm)
                else StatTiles(Format.clock(now), "Starts", Format.clock(wake.time), wake.fromAlarm, waking = false)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { p ->
                    FilterChip(
                        selected = profile?.id == p.id,
                        onClick = { chosen = p; model.updateSettings { it.copy(selectedProfileId = p.id) } },
                        label = { Text(p.name) },
                    )
                }
                profile?.let { TextButton(onClick = { onEdit(it) }) { Text("Edit") } }
            }
            profile?.let {
                alarm?.let { next ->
                    if (!next.counts) Note("Your next alarm at ${Format.clock(next.time)} is from ${next.creator}, which isn't a clock app. Allow it in Settings.")
                } ?: if (it.wakeSource.useAlarm) Note("No alarm set, so the profile's wake time is used.") else Unit
                model.previewPlan(it, startNow = true).fold(
                    onSuccess = { plan -> PlanCard(plan, settings, wake?.fromAlarm ?: false, now = null) },
                    onFailure = { e -> Text(e.message.orEmpty(), color = MaterialTheme.colorScheme.error) },
                )
                BigButton("Start night now", enabled = !busy, onClick = { model.run(Command.START_NIGHT, it.id) })
            }
        }

        QuickButtons(model, settings)
    }
}

/** Midnight Ink's lead: the wake time, large. */
@Composable
private fun WakeHeadline(label: String, time: String, fromAlarm: Boolean) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        Text(if (fromAlarm) "From your alarm" else "Profile's wake time", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Daylight's lead: start and wake side by side. */
@Composable
private fun StatTiles(startTime: String, startLabel: String, wakeTime: String, fromAlarm: Boolean, waking: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(startLabel, startTime, null, Modifier.weight(1f))
        StatTile(if (waking) "Waking you at" else "Wakes you at", wakeTime, if (fromAlarm) "Your alarm" else "Profile's wake time", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, note: String?, modifier: Modifier) {
    Card(modifier.semantics(mergeDescendants = true) {}, colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The night itself: the bar curve, or in simple view the step list. */
@Composable
private fun PlanCard(plan: Plan, settings: Settings, fromAlarm: Boolean, now: ZonedDateTime?) {
    Card(Modifier.fillMaxWidth(), colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            if (settings.simpleView) {
                NightTimeline(plan, fromAlarm, settings.fahrenheit, now = now)
            } else {
                Text("The night", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                NightCurve(plan, now = now, legend = !nocturneColors.dark)
            }
        }
    }
}

/** The one primary action on the screen: full width, 56 dp, 16 dp corners. */
@Composable
private fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** Four one-tap actions in a row, each marked with the colour of what it does. */
@Composable
private fun QuickButtons(model: AppViewModel, settings: Settings) {
    val colors = nocturneColors
    Text("Quick", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickButton("Boost", "${settings.boostMinutes} min", colors.turbo, Modifier.weight(1f)) { model.run(Command.BOOST) }
        QuickButton("Warm", null, colors.heat, Modifier.weight(1f)) { model.run(Command.WARM) }
        QuickButton("Cool", null, colors.cool, Modifier.weight(1f)) { model.run(Command.COOL) }
        QuickButton("Off", null, colors.idle, Modifier.weight(1f)) { model.run(Command.OFF) }
    }
}

@Composable
private fun QuickButton(label: String, detail: String?, dot: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
        border = cardBorder(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Dot(dot, 10)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun surfaceCard() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
private fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun ConnectionLine(model: AppViewModel) {
    val state by model.linkState.collectAsState()
    val status by model.status.collectAsState()
    val settings by model.settings.collectAsState()
    val colors = nocturneColors
    val text = when (state) {
        LinkState.READY -> status?.let { "${Format.modeName(it.mode)} · room ${Format.temp(it.ambientTemperature, settings.fahrenheit)} · air ${Format.temp(it.actualTemperature, settings.fahrenheit)}" } ?: "Connected"
        LinkState.CONNECTING -> "Connecting to your BedJet…"
        LinkState.SEARCHING -> "Not connected"
        LinkState.BLUETOOTH_OFF -> "Bluetooth is off"
        LinkState.UNAUTHORIZED -> "Allow Nearby devices to reach your BedJet"
    }
    // The dot repeats the text's meaning in colour; the label still says it in words.
    val dot = when (state) {
        LinkState.READY -> colors.connected
        LinkState.CONNECTING -> colors.heat
        else -> colors.idle
    }
    AssistChip(
        onClick = model::reconnect,
        label = { Text((if (settings.demoMode) "Demo · " else "") + text) },
        leadingIcon = { Dot(dot, 8) },
    )
}

fun blockName(block: Block) = when (block) {
    Block.START -> "Start"
    Block.MIDDLE -> "Middle"
    Block.WAKE -> "Wake-up"
    Block.AFTER_WAKE -> "After wake"
}

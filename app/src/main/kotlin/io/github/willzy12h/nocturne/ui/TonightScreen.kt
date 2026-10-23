package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
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
    val nightStatus by model.nightStatus.collectAsState()
    var now by remember { mutableStateOf(model.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = model.now(); model.refreshAlarm() } }
    val guard = rememberNightGuard(model)
    val context = LocalContext.current
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
        if (settings.deviceId() == null) SetupFlow(model, compact = true)
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
            val confirmed = running.confidence == Confidence.CONFIRMED && nightStatus.confidence == Confidence.CONFIRMED
            Text(profile?.name ?: "Tonight's night", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!confirmed || running.trackingManual) {
                val state = when (nightStatus.confidence ?: running.confidence) {
                    Confidence.PENDING -> "Change pending: the BedJet has not confirmed it."
                    Confidence.FAILED -> "Change failed. Check the BedJet before trying again."
                    Confidence.UNKNOWN -> "BedJet state unknown. Check the unit before another programme."
                    Confidence.CONFIRMED -> "Tracking manually. Nocturne has not resumed the programme."
                }
                Note(state + (nightStatus.reason?.let { " $it" } ?: ""))
            }
            when {
                simple -> Unit // The timeline below ends with the wake time.
                dark -> WakeHeadline(if (confirmed) "Waking you at" else "Planned wake", Format.clock(running.wakeAt), running.wakeOrigin)
                else -> StatTiles(startedAt?.let(Format::clock) ?: "--:--", if (confirmed) "Started" else "Planned start", Format.clock(running.wakeAt), running.wakeOrigin, waking = confirmed, planned = !confirmed)
            }
            if (confirmed) plan?.stepAt(now)?.second?.let { step ->
                Text(
                    "Now: ${blockName(step.block)} · ${Format.modeName(step.step.mode)} · fan ${(step.step.fanStep + 1) * 5}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // Mid-night the controls come before the plan: they're what you reach for, and up here the
            // "night started" snackbar at the bottom of the screen doesn't cover them.
            if (confirmed) BigButton("End night", onClick = { guard.confirm("turn the BedJet off") { model.run(Command.END_NIGHT) } })
            plan?.let { PlanCard(it, settings, running.wakeOrigin, now = now) }
        } else {
            val choiceId = settings.resolveTonightChoice(now)?.profileId
            val scheduleWindow = if (now.hour < 12) now.toLocalDate().minusDays(1) else now.toLocalDate()
            var chosen by remember(choiceId, scheduleWindow, profiles) { mutableStateOf(settings.resolveProfile(profiles, now)) }
            val profile = chosen
            val preview = profile?.let { model.startPreview(it, now) }
            val wake = preview?.wake
            if (wake != null && !simple) {
                if (dark) WakeHeadline("Wakes you at", Format.clock(wake.time), wake.origin)
                else StatTiles(Format.clock(now), "Starts", Format.clock(wake.time), wake.origin, waking = false)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { p ->
                    FilterChip(
                        selected = profile?.id == p.id,
                        onClick = { chosen = p; model.updateSettings { it.chooseTonight(p.id, model.now()) } },
                        label = { Text(p.name) },
                    )
                }
                profile?.let { TextButton(onClick = { onEdit(it) }) { Text("Edit") } }
            }
            profile?.let {
                alarm?.let { next ->
                    if (!next.counts) Note("Your next alarm at ${Format.clock(next.time)} is from ${next.creator}, which isn't a clock app. Allow it in Settings.")
                } ?: if (it.wakeSource.useAlarm) Note("No alarm set, so the profile's wake time is used.") else Unit
                preview?.plan?.let { plan -> PlanCard(plan, settings, wake?.origin ?: WakeOrigin.PROFILE_DEFAULT, now = null) }
                    ?: Text(preview?.refusalReason.orEmpty(), color = MaterialTheme.colorScheme.error)
                BigButton("Start night now", enabled = !busy && preview?.plan != null, onClick = { model.run(Command.START_NIGHT, it.id) })
            }
        }

        val wakeOverride = settings.resolveWakeOverride(now)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val current = model.now()
                TimePickerDialog(context, { _, hour, minute ->
                    val wake = WakeResolver.nextOccurrence(hour * 60 + minute, model.now())
                    model.setTonightWake(wake)
                }, current.hour, current.minute, true).show()
            }) { Text(if (wakeOverride == null) "Set tonight-only wake" else "Change tonight-only wake · ${Format.clock(wakeOverride.wakeAt)}") }
            if (wakeOverride != null) TextButton(onClick = { model.setTonightWake(null) }) { Text("Clear wake override") }
            if (settings.resolveTonightChoice(now) != null) TextButton(onClick = { model.updateSettings { it.followSchedule() } }) {
                Text("Tonight only · Follow schedule")
            }
        }
        NightRecoveryControls(model, night, guard, now)
        QuickButtons(model, settings, guard)
    }
}

/** Midnight Ink's lead: the wake time, large. */
@Composable
private fun WakeHeadline(label: String, time: String, origin: WakeOrigin) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        Text(when (origin) {
            WakeOrigin.ALARM -> stringResource(R.string.wake_origin_alarm_headline)
            WakeOrigin.OVERRIDE -> stringResource(R.string.wake_origin_override)
            WakeOrigin.PROFILE_DEFAULT -> stringResource(R.string.wake_origin_profile)
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Daylight's lead: start and wake side by side. */
@Composable
private fun StatTiles(startTime: String, startLabel: String, wakeTime: String, origin: WakeOrigin, waking: Boolean, planned: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(startLabel, startTime, null, Modifier.weight(1f))
        StatTile(if (planned) "Planned wake" else if (waking) "Waking you at" else "Wakes you at", wakeTime, when (origin) {
            WakeOrigin.ALARM -> stringResource(R.string.wake_origin_alarm)
            WakeOrigin.OVERRIDE -> stringResource(R.string.wake_origin_override)
            WakeOrigin.PROFILE_DEFAULT -> stringResource(R.string.wake_origin_profile)
        }, Modifier.weight(1f))
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
private fun PlanCard(plan: Plan, settings: Settings, origin: WakeOrigin, now: ZonedDateTime?) {
    Card(Modifier.fillMaxWidth(), colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            if (settings.simpleView) {
                NightTimeline(plan, origin, settings.fahrenheit, now = now)
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickButtons(model: AppViewModel, settings: Settings, guard: NightGuard) {
    val colors = nocturneColors
    Text("Quick", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        QuickButton("Boost", "${settings.boostMinutes} min", colors.turbo, Modifier.weight(1f)) { guard.confirm("start Boost") { model.run(Command.BOOST, replacementConfirmed = true) } }
        QuickButton("Warm", null, colors.heat, Modifier.weight(1f)) { guard.confirm("start warming") { model.run(Command.WARM, replacementConfirmed = true) } }
        QuickButton("Cool", null, colors.cool, Modifier.weight(1f)) { guard.confirm("start cooling") { model.run(Command.COOL, replacementConfirmed = true) } }
        QuickButton("Off", null, colors.idle, Modifier.weight(1f)) { guard.confirm("turn the BedJet off") { model.run(Command.OFF) } }
    }
}

@Composable
private fun QuickButton(label: String, detail: String?, dot: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
        border = cardBorder(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Dot(dot, 10)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

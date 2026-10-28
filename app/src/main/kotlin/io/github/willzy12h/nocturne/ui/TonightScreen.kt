package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.willzy12h.nocturne.core.protocol.Mode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import io.github.willzy12h.nocturne.ui.NocturneButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.boostDescription
import io.github.willzy12h.nocturne.night.boostLabel
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.ChangeKind
import java.time.format.DateTimeFormatter
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.AlarmSettingsTarget
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** Fixed wake and explanation above saved Quick controls and reorderable Tonight sections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit, onOpenBackups: () -> Unit = {},
    onOpenAlarmSettings: () -> Unit = {}, onEditPhase: (Profile, EditorPhaseSelection) -> Unit = { p, _ -> onEdit(p) }) {
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    val running by model.activeNight.collectAsState()
    val alarm by model.nextAlarm.collectAsState()
    val busy by model.busy.collectAsState()
    val nightStatus by model.nightStatus.collectAsState()
    val runtime by model.runtime.collectAsState()
    var now by remember { mutableStateOf(model.now()) }
    val foreground = rememberScreenInteractive()
    LaunchedEffect(model, foreground) {
        if (foreground) {
            now = model.now(); model.refreshAlarm()
            while (true) { delay(30_000); now = model.now(); model.refreshAlarm() }
        }
    }
    val guard = rememberNightGuard(model)
    val context = LocalContext.current
    val choiceId = settings.resolveTonightChoice(now)?.profileId
    val scheduleWindow = if (now.hour < 12) now.toLocalDate().minusDays(1) else now.toLocalDate()
    var chosen by remember(choiceId, scheduleWindow, profiles) { mutableStateOf(settings.resolveProfile(profiles, now)) }
    val night = running
    val profile = if (night == null) chosen else profiles.firstOrNull { it.id == night.profileId }
    val preview = if (night == null) profile?.let { model.startPreview(it, now) } else null
    val plan = if (night == null) preview?.plan else remember(night, profiles) { model.activePlan() }
    val plannedWake = night?.wakeAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: preview?.wake?.time
    val wakeOverride = settings.resolveWakeOverride(now)
    val confirmed = night != null && night.confidence == Confidence.CONFIRMED && nightStatus.confidence == Confidence.CONFIRMED
    val overridable = night == null || (confirmed && !night.trackingManual && !night.isTestRun)
    val pickWake: () -> Unit = {
        if (overridable) {
            val current = model.now()
            val initial = settings.resolveWakeOverride(current)?.wakeAt?.let(ZonedDateTime::parse) ?: plannedWake ?: current
            val expectedNight = night?.identity
            TimePickerDialog(context, { _, hour, minute ->
                model.setTonightWake(WakeResolver.nextOccurrence(hour * 60 + minute, model.now()), expectedNight)
            }, initial.hour, initial.minute, true).show()
        }
    }
    val explanation = AlarmExplanation.resolve(settings, profile, now, alarm, night, runtime, nightStatus, previewWake = preview?.wake)
    val openExplanation = {
        when (explanation.target) {
            AlarmSettingsTarget.ALARM_SOURCES -> onOpenAlarmSettings()
            AlarmSettingsTarget.PROFILE_WAKE -> profile?.let(onEdit) ?: onOpenAlarmSettings()
            AlarmSettingsTarget.TONIGHT_WAKE -> if (overridable) pickWake() else onOpenAlarmSettings()
        }
    }
    val manualLabel = if (night?.trackingManual == true)
        stringResource(if (confirmed) R.string.tonight_manual_until else R.string.planned_end) else null
    val headline = tonightWakeHeadline(settings, profile, now, alarm, night)
    val wakeTime = headline.at?.let(Format::clock) ?: stringResource(R.string.ux_unknown_time)
    val editPhase: (Block, ZonedDateTime) -> Unit = phaseEdit@{ block, at ->
        val selected = profile ?: return@phaseEdit
        val displayedPlan = plan ?: return@phaseEdit
        // A changed profile, manual run, or trimmed Start cannot safely inherit a source index.
        val trimmedStart = night != null && block == Block.START &&
            (night.revision != 0 || runCatching { displayedPlan.start.isAfter(ZonedDateTime.parse(night.startedAt)) }.getOrDefault(true))
        val selection = if (night?.trackingManual == true || trimmedStart ||
            (night?.profile != null && night.profile != selected)) null
            else editorPhaseSelection(selected, displayedPlan, block, at)
        if (selection == null) onEdit(selected) else onEditPhase(selected, selection)
    }

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
            Text(stringResource(R.string.tonight_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(.4f).padding(top = 4.dp).semantics { heading() })
            Column(Modifier.weight(.6f).testTag("tonight-fixed-wake").semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.End) {
                Text(stringResource(headline.label), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(wakeTime, fontSize = if (LocalConfiguration.current.fontScale >= 1.5f) 24.sp else 32.sp,
                    fontWeight = FontWeight.Light, textAlign = TextAlign.End)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("tonight-content").padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val largeText = LocalConfiguration.current.fontScale >= 1.5f
            if (!largeText) HomeReading(model, settings)
            SetupChecklistCard(model)
            // Long fallback explanations must remain readable without consuming the controls' viewport.
            headline.reason?.let {
                Text(stringResource(it), modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AlarmExplanationLine(explanation, openExplanation)
            if (largeText) HomeReading(model, settings)
            QuickControls(model, settings, guard)
            TonightSections(settings,
                onOrder = { order -> model.updateSettings { it.copy(tonightSectionOrder = order) } },
                onCollapse = { id -> model.updateSettings { it.copy(collapsedTonightSections =
                    if (id in it.collapsedTonightSections) it.collapsedTonightSections - id else it.collapsedTonightSections + id) } },
            ) { section ->
                when (section) {
                    "connection" -> {
                        val unreadable by model.unreadable.collectAsState()
                        val restoreUnfinished by model.restoreUnfinished.collectAsState()
                        val originalsBlocked by model.originalsBlocked.collectAsState()
                        val banner = UnreadableBannerState(unreadable, restoreUnfinished, originalsBlocked)
                        UnreadableDataBanner(banner, onDismiss = model::dismissUnreadable,
                            onRestore = onOpenBackups.takeIf { banner.offersRestore }, onAcceptLoss = model::acceptLostOriginals)
                        runtime.unresolvedSlotWrite?.takeIf { DeviceId.same(it.deviceId, settings.deviceId()) }?.let {
                            SlotWriteNotice(it, onChecked = model::acknowledgeSlotWrite)
                        }
                        SetupChecks()
                        BedJetPicker(model, compact = true)
                    }
                    "wake" -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (overridable) OutlinedButton(onClick = pickWake) {
                                Text(if (wakeOverride == null) stringResource(R.string.tonight_set_wake)
                                    else stringResource(R.string.tonight_change_wake, Format.clock(wakeOverride.wakeAt)))
                            }
                            if (wakeOverride != null && overridable) TextButton(onClick = { model.setTonightWake(null) }) {
                                Text(stringResource(R.string.tonight_clear_wake))
                            }
                            if (settings.resolveTonightChoice(now) != null) TextButton(onClick = { model.updateSettings { it.followSchedule() } }) {
                                Text(stringResource(R.string.tonight_follow_schedule))
                            }
                        }
                        wakeOverride?.let { override ->
                            runCatching { ZonedDateTime.parse(override.expiresAt) }.getOrNull()?.let { lapses ->
                                Note(stringResource(R.string.override_expiry, Format.clock(lapses), lapses.format(DateTimeFormatter.ofPattern("EEE d MMM"))))
                            }
                        }
                        NightRecoveryControls(model, night, guard, now)
                    }
                    else -> if (night != null) {
                        Text(profile?.name ?: night.profile?.name ?: stringResource(R.string.tonights_night), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!confirmed || night.trackingManual) {
                            val state = stringResource(when (nightStatus.confidence ?: night.confidence) {
                                Confidence.PENDING -> R.string.tonight_state_pending
                                Confidence.FAILED -> R.string.tonight_state_failed
                                Confidence.UNKNOWN -> R.string.tonight_state_unknown
                                Confidence.CONFIRMED -> R.string.tonight_state_manual
                            })
                            Note(state + (nightStatus.reason?.let { " $it" } ?: ""))
                        }
                        if (nightStatus.confidence == Confidence.PENDING) {
                            val requested = night.pending?.takeUnless { it.kind == ChangeKind.TEMPORARY }?.requestedWake
                                ?: runtime.retime?.requestedWakeAt ?: runtime.retime?.alarmAt
                            val changing = requested != null || runtime.retime != null || runtime.alarmInbox != null ||
                                night.pending?.kind == ChangeKind.RETIME || night.pending?.kind == ChangeKind.REAPPLY
                            if (changing) Note(requested?.let { stringResource(R.string.wake_requested, Format.clock(it)) }
                                ?: stringResource(R.string.wake_requested_unknown))
                        }
                        if (night.profile != null && profile != null && profile != night.profile && night.pending?.kind != ChangeKind.REAPPLY)
                            Note(stringResource(R.string.tonight_edits_next_night, profile.name))
                        if (confirmed) {
                            plan?.stepAt(now)?.second?.let { step ->
                                Text(stringResource(R.string.tonight_now, blockName(step.block), modeName(step.step.mode), (step.step.fanStep + 1) * 5),
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        night.temporary?.takeIf { confirmed && !night.trackingManual && now.isBefore(ZonedDateTime.parse(it.until)) }?.let {
                            Note(stringResource(R.string.temporary_planned, Format.clock(it.until)))
                        }
                        val turnOff = stringResource(R.string.guard_turn_off)
                        BigButton(stringResource(R.string.tonight_end_night), onClick = { guard.confirm(turnOff) { model.run(Command.END_NIGHT) } })
                        val end = manualLabel?.let { label -> runCatching { PlanEnd(label, ZonedDateTime.parse(night.endsAt)) }.getOrNull() }
                        plan?.let { PlanCard(it, settings, night.wakeOrigin, now = now.takeIf { confirmed }, end = end, middle = night.profile?.middle,
                            onEditPhase = if (profile == null) null else editPhase) }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            profiles.forEach { p ->
                                FilterChip(selected = profile?.id == p.id, onClick = {
                                    chosen = p; model.updateSettings { it.chooseTonight(p.id, model.now()) }
                                }, label = { Text(p.name) })
                            }
                            profile?.let { TextButton(onClick = { onEdit(it) }) { Text(stringResource(R.string.action_edit)) } }
                        }
                        profile?.let { selected ->
                            plan?.let { PlanCard(it, settings, preview?.wake?.origin ?: WakeOrigin.PROFILE_DEFAULT, now = null, middle = profile?.middle, onEditPhase = editPhase) }
                                ?: Text(preview?.refusalReason.orEmpty(), color = MaterialTheme.colorScheme.onSurface)
                            BigButton(stringResource(R.string.tonight_start_now), enabled = !busy && plan != null,
                                onClick = { model.run(Command.START_NIGHT, selected.id) })
                        }
                    }
                }
            }
        }
    }

    BusyIndicator(model)
    }
}

/** The large reading and its context are based only on the current BedJet receipt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeReading(model: AppViewModel, settings: Settings) {
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val link by model.linkState.collectAsState()
    val reading = status.takeIf { live && link == LinkState.READY }
    if (reading == null) {
        Text(stringResource(R.string.home_reading_unavailable),
            modifier = Modifier.testTag("home-reading-context"), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    } else {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tempText(reading.actualTemperature, settings.fahrenheit),
                modifier = Modifier.testTag("home-temperature"), fontSize = 48.sp, lineHeight = 56.sp,
                fontWeight = FontWeight.Light)
            Column {
                Text(stringResource(R.string.home_air_from_bedjet), modifier = Modifier.testTag("home-reading-context"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(if (reading.mode == Mode.STANDBY) stringResource(R.string.home_idle) else modeName(reading.mode),
                    style = MaterialTheme.typography.bodyMedium)
                if (reading.mode.usesFan) Text(stringResource(R.string.timeline_fan, reading.fanPercent),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Where a wake label sits: Midnight Ink's headline, Daylight's tile, or simple view's final timeline row. */
enum class WakeLabelStyle { HEADLINE, TILE, TIMELINE }

/**
 * Labels where tonight's wake time comes from in all four looks, so an override is never called
 * the profile's time or the alarm's.
 */
@Composable
fun wakeOriginLabel(origin: WakeOrigin, style: WakeLabelStyle): String {
    val configuration = LocalConfiguration.current
    val compactTimeline = style == WakeLabelStyle.TIMELINE &&
        configuration.screenWidthDp <= 320 && configuration.fontScale >= 2f
    return stringResource(when (origin) {
        WakeOrigin.ALARM -> if (style == WakeLabelStyle.HEADLINE) R.string.wake_origin_alarm_headline else R.string.wake_origin_alarm
        WakeOrigin.OVERRIDE -> if (compactTimeline) R.string.wake_origin_override_compact else R.string.wake_origin_override
        WakeOrigin.PROFILE_DEFAULT -> if (style == WakeLabelStyle.TIMELINE) R.string.wake_time else R.string.wake_origin_profile
        WakeOrigin.AUTO_START -> R.string.wake_origin_auto_start
    })
}

/** The night itself: the bar curve, or in simple view the step list. */
@Composable
private fun PlanCard(plan: Plan, settings: Settings, origin: WakeOrigin, now: ZonedDateTime?, end: PlanEnd? = null,
    middle: io.github.willzy12h.nocturne.core.plan.Middle? = null, onEditPhase: ((Block, ZonedDateTime) -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            if (settings.simpleView) {
                NightTimeline(plan, origin, settings.fahrenheit, now = now, end = end, middle = middle,
                    onEditPhase = onEditPhase?.let { edit -> { entry -> edit(entry.block, entry.startsAt) } })
            } else {
                Text(stringResource(R.string.tonight_the_night), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                NightCurve(plan, now = now, legend = !nocturneColors.dark, end = end, phaseInteraction = true,
                    onEditPhase = onEditPhase?.let { edit -> { step -> edit(step.block, step.startsAt) } })
                if (end == null) Text(wakeOriginLabel(origin, WakeLabelStyle.TILE),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** The one primary action on the screen: full width, at least 56 dp, 16 dp corners. */
@Composable
private fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) }
}

@Composable
private fun QuickControls(model: AppViewModel, settings: Settings, guard: NightGuard) {
    val collapsed = settings.quickControlsCollapsed
    val state = stringResource(if (collapsed) R.string.ux_collapsed else R.string.ux_expanded)
    val title = stringResource(R.string.tonight_quick)
    val action = stringResource(if (collapsed) R.string.gpt12_expand_section else R.string.gpt12_collapse_section, title)
    Column(Modifier.testTag("quick-controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { model.updateSettings { it.copy(quickControlsCollapsed = !it.quickControlsCollapsed) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                heading(); stateDescription = state; contentDescription = action
            },
            contentPadding = PaddingValues(0.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null)
        }
        if (!collapsed) ModeQuickButtons(model, settings, guard)
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
    val hasLiveStatus by model.hasLiveStatus.collectAsState()
    val settings by model.settings.collectAsState()
    val colors = nocturneColors
    val text = when (state) {
        LinkState.READY -> status?.takeIf { hasLiveStatus }?.let { stringResource(R.string.connection_status, modeName(it.mode), tempText(it.ambientTemperature, settings.fahrenheit),
            tempText(it.actualTemperature, settings.fahrenheit)) } ?: stringResource(R.string.connection_ready)
        LinkState.CONNECTING -> stringResource(R.string.connection_connecting)
        LinkState.SEARCHING -> stringResource(R.string.connection_searching)
        LinkState.BLUETOOTH_OFF -> stringResource(R.string.connection_bluetooth_off)
        LinkState.UNAUTHORIZED -> stringResource(R.string.connection_unauthorized)
    }
    // The dot repeats the text's meaning in colour; the label still says it in words.
    val dot = when (state) {
        LinkState.READY -> colors.connected
        LinkState.CONNECTING -> colors.heat
        else -> colors.idle
    }
    AssistChip(
        onClick = model::reconnect,
        label = { Text(if (settings.demoMode) stringResource(R.string.connection_demo, text) else text) },
        leadingIcon = { Dot(dot, 8) },
    )
}

@Composable
fun blockName(block: Block): String = stringResource(when (block) {
    Block.TEMPORARY -> R.string.temporary_block
    Block.START -> R.string.block_start
    Block.MIDDLE -> R.string.block_middle
    Block.WAKE -> R.string.block_wake
    Block.AFTER_WAKE -> R.string.block_after_wake
})

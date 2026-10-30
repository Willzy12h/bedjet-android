package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.key
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import io.github.willzy12h.nocturne.ui.NocturneButton as Button
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.data.ProfileShareRequest
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    var pinSlot by remember { mutableIntStateOf(0) }
    val activeNight by model.activeNight.collectAsState()
    val tokens = paletteTokens
    val largeText = LocalConfiguration.current.fontScale >= 1.5f
    // UX-02: long-press a card and drag it. The order shown while dragging is local; it is saved once, on release.
    val listState = rememberLazyListState()
    val reorder = remember { ProfileReorder() }
    val spacingPx = with(LocalDensity.current) { 12.dp.toPx() }
    val latestProfiles by rememberUpdatedState(profiles)
    val shown = reorder.order?.let { ids ->
        ids.mapNotNull { id -> profiles.firstOrNull { it.id == id } } + profiles.filterNot { it.id in ids }
    } ?: profiles
    // Hand back to the saved list once it carries the dropped order, so nothing jumps back in between.
    LaunchedEffect(profiles, reorder.dragging) {
        if (reorder.dragging == null && reorder.order?.let { it == profiles.map { p -> p.id } } != false) reorder.order = null
    }
    LaunchedEffect(reorder.dragging) {
        // Near the top or bottom edge the list scrolls under the held card, so a long list can still be reordered.
        while (reorder.dragging != null) {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == reorder.dragging }
            if (item != null) {
                val top = item.offset + reorder.offset
                val bottom = top + item.size
                val edge = info.viewportSize.height * .12f
                val step = when {
                    top < info.viewportStartOffset + edge -> -12f
                    bottom > info.viewportEndOffset - edge -> 12f
                    else -> 0f
                }
                if (step != 0f) {
                    val moved = listState.scrollBy(step)
                    reorder.offset += moved
                    reorder.swapIfPast(listState, spacingPx)
                }
            }
            delay(16)
        }
    }

    val importPreview by model.importPreview.collectAsState()
    val importApplying by model.importApplying.collectAsState()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importProfiles) }
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    val sharer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val request = sharing; sharing = null
        if (uri != null && request != null) model.shareProfile(uri, BackupCodec.json.decodeFromString<ProfileShareRequest>(request))
    }

    Column(modifier.fillMaxSize()) {
        // The design's title row: "Profiles", then Import and the primary "+ New" pill.
        FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.profiles_title), (if (largeText) Modifier.fillMaxWidth() else Modifier.weight(1f)).align(Alignment.CenterVertically),
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = tokens.ink)
            Pill(stringResource(R.string.parity_import), tokens.surface2, tokens.ink, R.drawable.ic_nc_import,
                enabled = !settings.demoMode && !importApplying, tag = "profiles-import") {
                importer.launch(arrayOf("application/json", "*/*"))
            }
            Pill(stringResource(R.string.parity_new), tokens.ink, tokens.ground, tag = "profiles-new",
                description = stringResource(R.string.profiles_new)) { adding = true }
        }
        if (profiles.size > 1) Text(stringResource(R.string.profiles_reorder_hint),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), color = tokens.ink2, fontSize = 13.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { profile ->
                var more by remember(profile.id) { mutableStateOf(false) }
                val copyName = stringResource(R.string.profiles_copy_name, profile.name)
                // The profile Tonight shows: the running night's, else tonight's choice or the default for the day.
                val selected = (activeNight?.profileId ?: settings.resolveProfile(profiles, model.now())?.id) == profile.id
                val stripe = tokens.stepColor(profile.middle.mode, profile.middle.tempStart)
                val shape = RoundedCornerShape(18.dp)
                val dragged = reorder.dragging == profile.id
                val position = shown.indexOf(profile)
                val moveUp = stringResource(R.string.profiles_move_up)
                val moveDown = stringResource(R.string.profiles_move_down)
                Box(Modifier.fillMaxWidth().testTag("profile-card-${profile.id}")
                    // The held card draws above its neighbours and follows the finger; rows of any height swap cleanly.
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragged) reorder.offset else 0f
                        if (dragged) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12.dp.toPx() }
                    }
                    .pointerInput(profile.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { reorder.start(profile.id, latestProfiles.map { it.id }) },
                            onDrag = { change, amount ->
                                change.consume()
                                reorder.offset += amount.y
                                reorder.swapIfPast(listState, spacingPx)
                            },
                            onDragEnd = { reorder.finish()?.let(model::reorderProfiles) },
                            onDragCancel = { reorder.finish()?.let(model::reorderProfiles) },
                        )
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            if (position > 0) CustomAccessibilityAction(moveUp) { model.moveProfile(profile.id, -1); true } else null,
                            if (position < shown.lastIndex) CustomAccessibilityAction(moveDown) { model.moveProfile(profile.id, 1); true } else null,
                        )
                    }) {
                Column(Modifier.fillMaxWidth().testTag("profile-open-${profile.id}").clip(shape).background(tokens.surface)
                    .sideStripe(stripe, tokens, width = 5.dp, level = TintLevel.PROFILE)
                    .then(when {
                        selected -> Modifier.border(1.5.dp, tokens.accent, shape)
                        tokens.edge != null -> Modifier.border(1.dp, tokens.edge, shape)
                        else -> Modifier
                    })
                    .clickable(role = Role.Button) { onEdit(profile) }
                    .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val actions: @Composable () -> Unit = {
                        if (selected) Pill(stringResource(R.string.profiles_tonight), tokens.ink, tokens.ground,
                            tag = "profile-tonight-${profile.id}", compact = true, onClick = null)
                        else Pill(stringResource(R.string.parity_start), tokens.surface2, tokens.ink2,
                            tag = "profile-use-${profile.id}", compact = true,
                            description = stringResource(R.string.profiles_use_tonight)) {
                            model.updateSettings { it.chooseTonight(profile.id, model.now()) }
                        }
                        Box {
                            val moreLabel = stringResource(R.string.recovery_more)
                            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { more = true }
                                .semantics { contentDescription = moreLabel }.testTag("profile-more-${profile.id}"),
                                contentAlignment = Alignment.Center) {
                                Text("⋯", color = tokens.ink2, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false },
                                modifier = Modifier.testTag("profile-menu"), shape = RoundedCornerShape(16.dp),
                                containerColor = tokens.menu, border = BorderStroke(1.dp, tokens.outline)) {
                                MenuRow(stringResource(R.string.action_edit), R.drawable.ic_nc_edit, tokens.ink) { more = false; onEdit(profile) }
                                if (position > 0) MenuRow(moveUp, R.drawable.ic_nc_chevron_up, tokens.ink) {
                                    more = false; model.moveProfile(profile.id, -1)
                                }
                                if (position < shown.lastIndex) MenuRow(moveDown, R.drawable.ic_nc_chevron_down, tokens.ink) {
                                    more = false; model.moveProfile(profile.id, 1)
                                }
                                MenuRow(stringResource(R.string.parity_duplicate), R.drawable.ic_nc_copy, tokens.ink) {
                                    more = false
                                    model.saveProfile(profile.copy(id = UUID.randomUUID().toString(), name = copyName))
                                }
                                if (!settings.demoMode) MenuRow(stringResource(R.string.parity_share_profile), R.drawable.ic_nc_share, tokens.ink) {
                                    more = false; sharing = BackupCodec.json.encodeToString(model.profileShareRequest(profile))
                                    sharer.launch(profile.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "profile" } + ".json")
                                }
                                MenuRow(stringResource(R.string.pin_menu), R.drawable.ic_nc_box, tokens.ink) {
                                    more = false
                                    val at = model.now()
                                    pinning = PinRequest(profile, settings.deviceId(), settings.workingSlot, at,
                                        NightPreview.forStartNow(profile, at, null, settings = settings))
                                    pinSlot = (0..2).first { it != settings.workingSlot }
                                }
                                MenuRow(stringResource(R.string.profiles_delete), R.drawable.ic_nc_trash,
                                    if (tokens.dark) Color(0xFFFFB3A8) else tokens.bad) { more = false; deleting = profile }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().testTag("profile-header-${profile.id}")) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painterResource(R.drawable.ic_nc_fav), null, Modifier.size(20.dp), tint = tokens.accent)
                            Text(profile.name, Modifier.weight(1f), color = tokens.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (!largeText) actions()
                        }
                        if (largeText) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically) { actions() }
                    }
                    val wake = model.wakePreview(profile)
                    val description = describe(profile)
                    val wakeDescription = stringResource(R.string.wake_time) + " " + Format.clock(wake.time)
                    model.previewPlan(profile).getOrNull()?.let {
                        NightBar(it, null, Modifier.testTag("profile-timeline-${profile.id}")
                            .semantics { contentDescription = "$description. $wakeDescription" }, height = 22.dp)
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val times = stringResource(R.string.parity_profile_times, Format.clockOfDay(profile.bedtimeMinute), Format.clock(wake.time))
                        val wakeText = if (wake.fromAlarm) stringResource(R.string.profiles_wake_alarm, Format.clock(wake.time))
                            else stringResource(R.string.profiles_wake_default, Format.clock(wake.time), Format.clockOfDay(profile.wakeSource.defaultWakeMinute))
                        Text(times, Modifier.weight(1f).align(Alignment.CenterVertically).semantics { contentDescription = "$times. $wakeText" },
                            color = tokens.ink2, fontSize = 15.sp)
                        WeekdayDots(profile.days)
                    }
                }
                }
            }
            if (!settings.cooldownOfferAnswered) item { CooldownOfferCard(model, profiles, settings.fahrenheit) }
            item { Box(Modifier.padding(8.dp)) }
        }
    }

    importPreview?.let { preview ->
        key(preview) {
            ImportPreviewDialog(preview, importApplying, onApply = { model.confirmImport(preview) }, onCancel = model::cancelImport)
        }
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.profiles_start_from)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Presets.all.forEach { preset ->
                        StripeTile(
                            stripe = tokens.stepColor(preset.middle.mode, preset.middle.tempStart),
                            title = preset.name,
                            subtitle = stringResource(seasonName(preset.season)),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                adding = false
                                // A preset's own after-wake steps are kept; the Cooldown only fills a preset that has none.
                                onEdit(preset.copy(id = UUID.randomUUID().toString(), name = preset.name,
                                    afterWake = preset.afterWake.ifEmpty { listOf(io.github.willzy12h.nocturne.core.plan.Phase(io.github.willzy12h.nocturne.core.protocol.Mode.COOL, 44, 30, 10)) }))
                            },
                        )
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

/**
 * The drag in progress: which card is held, how far it has moved from its laid-out place, and the order so far.
 * A swap happens when the held card's centre passes a neighbour's centre, using the real heights of both rows.
 */
internal class ProfileReorder {
    var dragging by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
    var order by mutableStateOf<List<String>?>(null)
    private var startOrder: List<String>? = null

    fun start(id: String, ids: List<String>) { dragging = id; offset = 0f; order = ids; startOrder = ids }

    /** Ends the drag; returns the new order when it changed, for saving. */
    fun finish(): List<String>? {
        val result = order?.takeIf { it != startOrder }
        dragging = null; offset = 0f; startOrder = null
        if (result == null) order = null
        return result
    }

    fun swapIfPast(state: androidx.compose.foundation.lazy.LazyListState, spacing: Float) {
        val id = dragging ?: return
        val ids = order ?: return
        val visible = state.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        val at = ids.indexOf(id)
        val centre = current.offset + current.size / 2f + offset
        if (offset > 0 && at < ids.lastIndex) {
            val next = visible.firstOrNull { it.key == ids[at + 1] } ?: return
            if (centre > next.offset + next.size / 2f) {
                order = ids.toMutableList().apply { add(at + 1, removeAt(at)) }
                offset -= next.size + spacing
            }
        } else if (offset < 0 && at > 0) {
            val previous = visible.firstOrNull { it.key == ids[at - 1] } ?: return
            if (centre < previous.offset + previous.size / 2f) {
                order = ids.toMutableList().apply { add(at - 1, removeAt(at)) }
                offset += previous.size + spacing
            }
        }
    }
}

/** A rounded pill button: the design's Import, "+ New", Tonight and Start. */
@Composable
private fun Pill(text: String, container: Color, content: Color, icon: Int? = null, enabled: Boolean = true,
    tag: String, compact: Boolean = false, description: String? = null, onClick: (() -> Unit)?) {
    Box(Modifier.heightIn(min = 48.dp)
        .then(onClick?.let { Modifier.clickable(enabled = enabled, role = Role.Button, onClick = it) } ?: Modifier)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .testTag(tag), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = if (compact) 36.dp else 48.dp).clip(RoundedCornerShape(50)).background(container)
            .alpha(if (enabled) 1f else .5f).padding(horizontal = if (compact) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(painterResource(it), null, Modifier.size(18.dp), tint = content) }
            Text(text, color = content, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: Int, color: Color, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color) },
        modifier = Modifier.padding(horizontal = 6.dp), onClick = onClick)

/** M T W T F S S: the days this profile is the default for, filled in the accent. */
@Composable
private fun WeekdayDots(days: Set<Int>) {
    val tokens = paletteTokens
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        java.time.DayOfWeek.entries.forEach { day ->
            val on = day.value in days
            val dayName = day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            val dayDescription = if (on) dayName else stringResource(R.string.parity_day_off, dayName)
            Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clip(CircleShape)
                .background(if (on) tokens.accent.copy(alpha = .38f) else tokens.surface2)
                .semantics { contentDescription = dayDescription },
                contentAlignment = Alignment.Center) {
                Text(day.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    color = if (on) tokens.ink else tokens.ink2, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun describe(profile: Profile): String {
    val nothing = stringResource(R.string.profiles_nothing)
    val start = profile.start.map { stringResource(R.string.profiles_phase_short, modeName(it.mode), it.minutes) }.joinToString(" → ").ifEmpty { nothing }
    val quiet = profile.middle.quietFan
    val middle = if (profile.middle.fanShape == io.github.willzy12h.nocturne.core.plan.FanShape.QUIET_MIDDLE && quiet != null)
        stringResource(R.string.profiles_quiet_middle_short, modeName(profile.middle.mode), quiet.settle, quiet.asleep, quiet.rise)
    else stringResource(R.string.profiles_middle_short, modeName(profile.middle.mode), profile.middle.fanStart, profile.middle.fanEnd)
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

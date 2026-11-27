package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.TonightChoice
import io.github.willzy12h.nocturne.core.store.WeeklySchedule
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

// Day values are 1–7. Zero is the default-profile picker and null means no picker is open.
private const val DEFAULT_PICKER = 0

@Composable
internal fun WeeklyScheduleScreen(model: AppViewModel, modifier: Modifier, scroll: ScrollState, backLabel: String,
    picker: Int?, selectedId: String?, onPicker: (Int?, String?) -> Unit,
    onBack: () -> Unit, onEdit: (Profile) -> Unit) {
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val schedule = settings.effectiveWeeklySchedule(profiles)
    val fallback = profiles.firstOrNull { it.id == schedule.fallbackProfileId }
    val tokens = paletteTokens
    val now = model.now()
    Column(modifier.fillMaxSize().verticalScroll(scroll).testTag("weekly-schedule")
        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = FeedbackClearance)) {
        AccentLink(backLabel, onBack, modifier = Modifier.testTag("weekly-back"), fontSize = 14.sp)
        Text(stringResource(R.string.weekly_schedule), Modifier.semantics { heading() },
            color = tokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.weekly_intro), Modifier.padding(top = 4.dp, bottom = 10.dp),
            color = tokens.ink2, fontSize = 14.sp)
        settings.resolveTonightChoice(now)?.let { choice ->
            profiles.firstOrNull { it.id == choice.profileId }?.let { profile ->
                Text(stringResource(R.string.weekly_tonight_override, profile.name, tonightChoiceLabel(choice, now)),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tokens.sheet)
                        .padding(8.dp).testTag("weekly-tonight-override"), color = tokens.ink2, fontSize = 13.sp)
            }
        }
        val empty = stringResource(R.string.weekly_no_default)
        DayOfWeek.entries.forEach { day ->
            val name = weekdayName(day)
            val profile = profiles.firstOrNull { it.id == schedule.profileIdFor(day) }
            val summary = profile?.name ?: fallback?.let { stringResource(R.string.weekly_default_named, it.name) } ?: empty
            val expanded = picker == day.value
            WeeklyRow(if (day == now.dayOfWeek) stringResource(R.string.weekly_today, name) else name, summary,
                expanded, Modifier.testTag("weekly-day-${day.value}")) {
                if (expanded) onPicker(null, null) else onPicker(day.value, profile?.id)
            }
            if (expanded) WeeklyProfilePicker(
                stringResource(R.string.weekly_every_day, name), profiles, selectedId,
                missingChoice = fallback?.let { stringResource(R.string.weekly_use_default_named, it.name) }
                    ?: stringResource(R.string.weekly_use_default),
                saveLabel = stringResource(R.string.weekly_save_day, name), onChoice = { onPicker(day.value, it) },
                onSave = { model.setWeeklyProfile(day, selectedId); onPicker(null, null) }, onEdit = onEdit,
            )
            HorizontalDivider(color = tokens.hairline)
        }
        val expanded = picker == DEFAULT_PICKER
        WeeklyRow(stringResource(R.string.weekly_default_title), fallback?.name ?: empty,
            expanded, Modifier.testTag("weekly-fallback")) {
            if (expanded) onPicker(null, null) else onPicker(DEFAULT_PICKER, fallback?.id)
        }
        if (expanded) WeeklyProfilePicker(stringResource(R.string.weekly_default_title), profiles, selectedId,
            missingChoice = empty, saveLabel = stringResource(R.string.weekly_save_default),
            onChoice = { onPicker(DEFAULT_PICKER, it) },
            onSave = { model.setWeeklyFallback(selectedId); onPicker(null, null) }, onEdit = onEdit)
        Text(stringResource(R.string.weekly_default_explanation), color = tokens.ink2, fontSize = 12.sp)
    }
}

@Composable
private fun WeeklyRow(title: String, summary: String, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = paletteTokens
    val state = stringResource(if (expanded) R.string.weekly_expanded else R.string.weekly_collapsed)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .then(if (expanded) Modifier.background(tokens.surface) else Modifier)
        .clickable(role = Role.Button, onClick = onClick).semantics { stateDescription = state }
        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), color = tokens.ink, fontWeight = FontWeight.SemiBold)
        Text(summary, Modifier.weight(1f), textAlign = TextAlign.End, color = tokens.ink2, fontSize = 13.sp)
        Icon(painterResource(R.drawable.ic_nc_chevron_down), null,
            Modifier.size(16.dp).rotate(if (expanded) 0f else -90f), tint = tokens.ink2)
    }
}

@Composable
private fun WeeklyProfilePicker(title: String, profiles: List<Profile>, selectedId: String?, missingChoice: String,
    saveLabel: String, onChoice: (String?) -> Unit, onSave: () -> Unit, onEdit: (Profile) -> Unit) {
    val tokens = paletteTokens
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.sheet).padding(8.dp)) {
        Text(title, Modifier.padding(horizontal = 6.dp, vertical = 4.dp).semantics { heading() },
            color = tokens.ink, fontWeight = FontWeight.SemiBold)
        Column(Modifier.selectableGroup()) {
            profiles.forEach { profile ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    WeeklyChoice(profile.name, selectedId == profile.id, Modifier.weight(1f).testTag("weekly-pick-${profile.id}")) {
                        onChoice(profile.id)
                    }
                    val editLabel = stringResource(R.string.weekly_edit_profile, profile.name)
                    AccentLink(stringResource(R.string.action_edit), { onEdit(profile) },
                        Modifier.testTag("weekly-edit-${profile.id}").semantics { contentDescription = editLabel })
                }
            }
            WeeklyChoice(missingChoice, selectedId == null, Modifier.fillMaxWidth().testTag("weekly-pick-default")) { onChoice(null) }
        }
        NocturneButton(onClick = onSave, enabled = selectedId == null || profiles.any { it.id == selectedId },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("weekly-save-day")) {
            Text(saveLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WeeklyChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = paletteTokens
    Row(modifier.clip(RoundedCornerShape(8.dp)).then(if (selected) Modifier.background(tokens.surface2) else Modifier)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        RadioButton(selected = selected, onClick = null, colors = nocturneRadioColors())
        Text(label, color = tokens.ink, fontSize = 14.sp)
    }
}

internal fun weekdayName(day: DayOfWeek, style: TextStyle = TextStyle.FULL): String =
    day.getDisplayName(style, Locale.getDefault())

@Composable
internal fun tonightChoiceLabel(choice: TonightChoice, now: ZonedDateTime): String {
    val expires = ZonedDateTime.parse(choice.expiresAt).withZoneSameInstant(now.zone)
    val expiry = when {
        expires.hour != 12 || expires.minute != 0 -> Format.dayClock(expires)
        expires.toLocalDate() == now.toLocalDate() -> stringResource(R.string.tonight_choice_noon_today)
        else -> stringResource(R.string.tonight_choice_noon_tomorrow)
    }
    return stringResource(R.string.tonight_choice_expiry, expiry)
}

@Composable
internal fun weeklyProfileUsage(schedule: WeeklySchedule, id: String): String {
    val days = DayOfWeek.entries.filter { schedule.profileIdFor(it) == id }
        .joinToString(", ") { weekdayName(it, TextStyle.SHORT) }
    return when {
        schedule.fallbackProfileId == id && days.isNotEmpty() -> stringResource(R.string.weekly_usage_with_default, days)
        schedule.fallbackProfileId == id -> stringResource(R.string.weekly_default_title)
        days.isNotEmpty() -> days
        else -> stringResource(R.string.weekly_no_days)
    }
}

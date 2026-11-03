package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Mode

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CooldownOfferCard(model: AppViewModel, profiles: List<Profile>, fahrenheit: Boolean) {
    val revision by model.cooldownOfferSessionRevision.collectAsState()
    val saving by model.cooldownOfferSaving.collectAsState()
    var choices by remember(revision) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val tokens = paletteTokens
    StripeTile(stripe = tokens.stepColor(Mode.COOL, 44), title = stringResource(R.string.cooldown_offer_title), subtitle = null,
        modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cooldown_offer_future_only), style = MaterialTheme.typography.bodySmall)
        profiles.forEach { profile ->
            val checked = choices[profile.id] ?: profile.afterWake.isEmpty()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cooldown-choice-${profile.id}")
                .toggleable(value = checked, enabled = !saving, role = Role.Checkbox,
                    onValueChange = { choices = choices + (profile.id to it) }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = !saving, colors = nocturneCheckboxColors())
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(profile.name)
                    val replacement = profile.afterWake.map { phase ->
                        when {
                            phase.mode.usesTemperature -> stringResource(R.string.cooldown_offer_phase_temp,
                                modeName(phase.mode), durationText(phase.minutes), tempText(phase.temperature, fahrenheit), phase.fanPercent)
                            phase.mode.usesFan -> stringResource(R.string.cooldown_offer_phase_fan,
                                modeName(phase.mode), durationText(phase.minutes), phase.fanPercent)
                            else -> stringResource(R.string.cooldown_offer_phase_simple, modeName(phase.mode), durationText(phase.minutes))
                        }
                    }.joinToString(" → ")
                    Text(if (replacement.isEmpty()) stringResource(R.string.cooldown_offer_empty)
                        else stringResource(R.string.cooldown_offer_replace, replacement), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NocturneButton(enabled = !saving, onClick = {
                model.answerCooldownOffer(profiles.filter { choices[it.id] ?: it.afterWake.isEmpty() }
                    .associate { it.id to it.afterWake }, revision)
            }) { Text(stringResource(R.string.cooldown_offer_add)) }
            NocturneTextButton(enabled = !saving, onClick = { model.answerCooldownOffer(emptyMap(), revision) }) {
                Text(stringResource(R.string.cooldown_offer_decline))
            }
        }
    }
}

package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.platform.AutoStartSchedule
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Composable
internal fun TonightScheduledStart(model: AppViewModel, now: ZonedDateTime) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val alarm by model.nextAlarm.collectAsStateWithLifecycle()
    val automation by model.graph.repository.automation.collectAsStateWithLifecycle()
    if (settings.demoMode || !AutoStartSchedule.settings(model.graph).enabled) return
    val plan = remember(settings, profiles, alarm, automation, now.truncatedTo(ChronoUnit.MINUTES)) {
        AutoStartSchedule.nextEditable(model.graph)
    } ?: return
    val skipped = plan.key in automation.skippedStarts
    Column {
        Text(stringResource(if (skipped) R.string.tonight_start_skipped else R.string.tonight_start_scheduled,
            plan.profile.name, Format.savedAt(plan.startAt.toString())))
        TextButton(onClick = { model.setScheduledStartSkipped(plan.ticket(), !skipped) }) {
            Text(stringResource(if (skipped) R.string.tonight_undo_skip else R.string.tonight_skip_start))
        }
    }
}

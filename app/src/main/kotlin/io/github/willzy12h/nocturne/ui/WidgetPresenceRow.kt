package io.github.willzy12h.nocturne.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.edit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.widget.WidgetPresence
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Whether setup still offers the one-time "Allow": Android 16+, a BedJet chosen, not allowed and not skipped. */
internal fun presenceSetupPending(context: Context): Boolean =
    WidgetPresence.offered(context) && !context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SKIPPED, false)

private const val PRESENCE_PREFS = "widget_presence"
private const val KEY_SKIPPED = "skipped"

/**
 * Android 16 and later: one "Allow" so Android tells Nocturne when the BedJet is in range and an asleep widget wakes
 * by itself. In setup it can be skipped; Settings always shows it, with its state once allowed.
 */
@Composable
internal fun WidgetPresenceRow(inSetup: Boolean, onChanged: () -> Unit = {}) {
    if (!WidgetPresence.supported) return
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<WidgetPresence.Result?>(null) }
    // Back from Android's prompt: take the BedJet back even if a cancelled prompt never reported.
    LifecycleResumeEffect(Unit) { WidgetPresence.endSearchPause(context); refresh++; onPauseOrDispose { } }
    val associated = remember(refresh) { WidgetPresence.associated(context) }
    val offered = remember(refresh) { WidgetPresence.offered(context) }
    if (!associated && !offered) return
    val allow = {
        result = null
        val activity = context.activity()
        if (activity == null) result = WidgetPresence.Result.DECLINED
        else WidgetPresence.associate(activity) { result = it; refresh++; onChanged() }
    }
    StripeTile(
        stripe = paletteTokens.accent,
        title = stringResource(R.string.presence_title),
        subtitle = stringResource(if (associated) R.string.presence_on else R.string.presence_body),
        modifier = Modifier.fillMaxWidth().testTag("widget-presence"),
        trailing = if (associated) null else { {
            OutlinedButton(onClick = allow, modifier = Modifier.heightIn(min = 48.dp).testTag("widget-presence-allow")) {
                Text(stringResource(R.string.presence_allow))
            }
        } },
    ) {
        Column {
            if (!associated) Text(stringResource(R.string.presence_pause_note), color = paletteTokens.ink2, fontSize = 14.sp)
            when (result) {
                WidgetPresence.Result.DECLINED -> R.string.presence_failed
                WidgetPresence.Result.BUSY -> R.string.presence_busy
                else -> null
            }?.let { Text(stringResource(it), color = paletteTokens.ink2, fontSize = 14.sp, modifier = Modifier.testTag("widget-presence-result")) }
            if (inSetup && !associated) TextButton(onClick = {
                context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_SKIPPED, true) }
                refresh++
                onChanged()
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.uat_setup_skip)) }
        }
    }
}

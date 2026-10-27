package io.github.willzy12h.nocturne.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Settings
import java.time.Instant
import java.time.ZonedDateTime

/** Local screen consent is not a device setting and must not travel in a backup. */
private fun themePreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences("appearance-consent", Context.MODE_PRIVATE)

private fun Settings.themeSelection() = "$themeMode:$scheduledDarkMinute:$scheduledLightMinute"

/** Native command confirmations stay calm while the main screen still owes a light-theme choice. */
internal fun nativeThemeIsDark(context: Context, settings: Settings, at: ZonedDateTime, systemDark: Boolean): Boolean {
    if (settings.isDarkTheme(at, systemDark)) return true
    val memory = themePreferences(context)
    return memory.getBoolean("lastDark", false) ||
        (memory.getString("holdSelection", null) == settings.themeSelection() && memory.getLong("holdUntil", 0) > at.toInstant().toEpochMilli())
}

/** A confirmed choice may publish asynchronously; retain its precise selection until that publication arrives. */
internal fun approveLightSelection(context: Context, settings: Settings, at: ZonedDateTime) {
    themePreferences(context).edit {
        putString("approvedSelection", settings.themeSelection())
        putLong("approvedUntil", at.toInstant().plusSeconds(60).toEpochMilli())
        remove("holdUntil"); remove("holdSelection")
    }
}

internal data class ThemePresentation(
    val at: ZonedDateTime,
    val dark: Boolean,
    val lightDue: Boolean,
    val switchNow: () -> Unit,
    val stayDark: () -> Unit,
)

/** No light frame is rendered before consent, including the first frame after a process restart. */
@Composable
internal fun rememberThemePresentation(settings: Settings, now: () -> ZonedDateTime): ThemePresentation {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { themePreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Reading the revision ties external confirmation writes to this composition without keeping an Activity alive.
    val memory = remember(preferences, revision) { preferences.all }
    val holdUntil = (memory["holdUntil"] as? Long)?.let(Instant::ofEpochMilli)
    val at = rememberThemeNow(settings, now, holdUntil)
    val requestedDark = settings.isDarkTheme(at, isSystemInDarkTheme())
    val selection = settings.themeSelection()
    val lastDark = memory["lastDark"] as? Boolean
    val held = memory["holdSelection"] == selection && holdUntil?.isAfter(at.toInstant()) == true
    val approved = memory["approvedSelection"] == selection &&
        (memory["approvedUntil"] as? Long ?: 0L) > at.toInstant().toEpochMilli()
    val lightDue = !requestedDark && !held && !approved && lastDark == true
    val dark = requestedDark || held || lightDue
    SideEffect {
        val expiredHold = holdUntil != null && (!held && (memory["holdSelection"] != selection || !holdUntil.isAfter(at.toInstant())))
        val expiredApproval = memory.containsKey("approvedUntil") &&
            ((memory["approvedUntil"] as? Long ?: 0L) <= at.toInstant().toEpochMilli())
        if (lastDark != dark || expiredHold || expiredApproval || (approved && !requestedDark)) {
            preferences.edit {
                putBoolean("lastDark", dark)
                if (expiredHold) { remove("holdUntil"); remove("holdSelection") }
                if (expiredApproval || (approved && !requestedDark)) { remove("approvedSelection"); remove("approvedUntil") }
            }
        }
    }
    return ThemePresentation(at, dark, lightDue,
        switchNow = {
            preferences.edit {
                putBoolean("lastDark", false)
                remove("holdUntil"); remove("holdSelection")
                remove("approvedSelection"); remove("approvedUntil")
            }
        },
        stayDark = {
            preferences.edit {
                putBoolean("lastDark", true)
                putString("holdSelection", selection)
                putLong("holdUntil", nextDarkPeriod(settings, at).toInstant().toEpochMilli())
            }
        },
    )
}

private fun nextDarkPeriod(settings: Settings, at: ZonedDateTime): ZonedDateTime {
    val minute = settings.scheduledDarkMinute
    return (0L..1L).flatMap { day ->
        val boundary = at.toLocalDate().plusDays(day).atTime(minute / 60, minute % 60).atZone(at.zone)
        listOf(boundary.withEarlierOffsetAtOverlap(), boundary.withLaterOffsetAtOverlap())
    }.filter { it.isAfter(at) }.minBy { it.toInstant() }
}

@Composable
internal fun ThemeDuePrompt(theme: ThemePresentation) {
    if (theme.lightDue) AlertDialog(
        onDismissRequest = theme.stayDark,
        title = { Text(stringResource(R.string.theme_light_due)) },
        text = { Text(stringResource(R.string.theme_light_due_help)) },
        confirmButton = { TextButton(onClick = theme.switchNow) { Text(stringResource(R.string.theme_switch_now)) } },
        dismissButton = { TextButton(onClick = theme.stayDark) { Text(stringResource(R.string.theme_stay_dark)) } },
    )
}
